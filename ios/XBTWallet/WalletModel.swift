import Foundation
import SwiftUI

@MainActor final class WalletModel: ObservableObject {
    enum Phase { case welcome, starting, create, syncing, ready, failed }
    @Published var phase: Phase = .welcome
    @Published var status = ""
    @Published var error: String?
    @Published var busy = false
    @Published var info = Reply()
    @Published var balance = Reply()
    @Published var channels: [Reply] = []
    @Published var pending: [Reply] = []
    @Published var unit: AmountUnit = AmountUnit(rawValue: UserDefaults.standard.string(forKey: "amountUnit") ?? "sats") ?? .sats
    @Published var usdRate: Decimal?
    @Published var rateDate: Date?
    @Published var name = UserDefaults.standard.string(forKey: "walletName") ?? ""
    let rpc = NativeRPC()
    private(set) var store: WalletStore?
    private var started = false
    private var starting = false
    private var polling: Task<Void, Never>?
    private var lastRates = Date.distantPast
    var offlinePreview: Bool { ProcessInfo.processInfo.arguments.contains("--ui-testing") }
    var currentRate: Decimal? {
        guard let date = rateDate, Date().timeIntervalSince(date) < 900 else { return nil }
        return usdRate
    }
    var local: Int64 { channels.filter { $0.bool("active") }.reduce(0) { $0 + max(0, $1.int("local_balance") - $1.object("local_constraints").int("chan_reserve_sat")) } }
    var remote: Int64 { channels.filter { $0.bool("active") }.reduce(0) { $0 + max(0, $1.int("remote_balance") - $1.object("remote_constraints").int("chan_reserve_sat")) } }
    func display(_ sats: Int64) -> String { Amounts.format(sats, unit: unit, usdRate: currentRate) }
    func cycleUnit() {
        let units = AmountUnit.allCases
        unit = units[(units.firstIndex(of: unit)! + 1) % units.count]
        UserDefaults.standard.set(unit.rawValue, forKey: "amountUnit")
    }
    func perform(_ action: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await action() } catch { self.error = error.localizedDescription }
        }
    }
    func launch() async {
        guard !starting else { return }
        if offlinePreview { return }
        starting = true
        defer { starting = false }
        do {
            if store == nil { store = try WalletStore() }
            guard let store else { throw WalletError("Wallet storage is unavailable.") }
            if !started {
                // A new installation starts with onboarding. Existing credentials resume directly.
                if phase == .welcome, try Secrets.read(store.secretPrefix + "password") == nil { return }
                phase = .starting; status = "Starting the XBT node"
                try await rpc.start(directory: store.directory.appendingPathComponent("node"))
                started = true
            }
            try await updateState()
            if polling == nil {
                polling = Task { [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        guard let self else { return }
                        if self.busy || self.phase == .welcome { continue }
                        do { try await self.updateState() }
                        catch { self.status = error.localizedDescription }
                    }
                }
            }
        } catch { self.error = error.localizedDescription; phase = .failed }
    }
    func beginSetup() async {
        if offlinePreview { phase = .create; return }
        phase = .starting
        await launch()
    }
    private func updateState() async throws {
        let reply = try await rpc.call("getState", timeout: 10)
        let state = reply.text("state")
        switch state {
        case "NON_EXISTING": phase = .create
        case "LOCKED":
            guard let store, let password = try Secrets.read(store.secretPrefix + "password") else {
                throw WalletError("Wallet password is missing from this device’s Keychain. Do not delete the app; restore using your saved seed on a separate installation.")
            }
            status = "Unlocking"
            _ = try await rpc.call("unlockWallet", ["wallet_password": password.base64EncodedString()], timeout: 60)
        case "UNLOCKED", "RPC_ACTIVE", "SERVER_ACTIVE":
            do {
                info = try await rpc.call("getInfo", timeout: 10)
                let features = info.object("features").fields
                guard features["512"] != nil || features["513"] != nil else {
                    throw WalletError("The node did not advertise XBT support. Payments are disabled.")
                }
                status = "Block \(info.int("block_height").formatted()) · \(info.bool("synced_to_chain") ? "Synchronized" : "Downloading and verifying chain data")"
                if info.bool("synced_to_chain") {
                    phase = .ready
                    try await refresh()
                } else { phase = .syncing }
            } catch {
                if phase != .ready { phase = .syncing }
                throw error
            }
        default: status = "Wallet state: \(state)"
        }
    }
    func create(name: String) async throws {
        guard let store else { throw WalletError("Wallet storage is unavailable.") }
        guard name.count <= 32 else { throw WalletError("Use at most 32 characters for the wallet name.") }
        guard try await rpc.call("getState").text("state") == "NON_EXISTING" else {
            throw WalletError("A wallet already exists. Restart to unlock it.")
        }
        var seed = try Secrets.read(store.secretPrefix + "seed")
        if seed == nil {
            let response = try await rpc.call("genSeed")
            guard let words = response.fields["cipher_seed_mnemonic"] as? [String], words.count == 24 else {
                throw WalletError("The native engine did not return a valid 24-word aezeed.")
            }
            seed = Data(words.joined(separator: " ").utf8)
            try Secrets.write(store.secretPrefix + "seed", seed!)
        }
        let password = try Secrets.read(store.secretPrefix + "password") ?? Secrets.randomPassword()
        try Secrets.write(store.secretPrefix + "password", password)
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        UserDefaults.standard.set(self.name, forKey: "walletName")
        let words = String(decoding: seed!, as: UTF8.self).split(separator: " ").map(String.init)
        _ = try await rpc.call("initWallet", ["wallet_password": password.base64EncodedString(), "cipher_seed_mnemonic": words], timeout: 90)
        phase = .syncing; status = "Wallet created · synchronizing"
    }
    func seed() throws -> [String] {
        guard let store, let data = try Secrets.read(store.secretPrefix + "seed") else { throw WalletError("No recovery seed is available.") }
        return String(decoding: data, as: UTF8.self).split(separator: " ").map(String.init)
    }
    func refresh() async throws {
        balance = try await rpc.call("walletBalance")
        channels = try await rpc.call("listChannels").array("channels")
        pending = try await rpc.call("pendingChannels").array("pending_open_channels")
        if Date().timeIntervalSince(lastRates) > 60 {
            lastRates = Date()
            Task { await refreshRate() }
        }
    }
    func refreshRate() async {
        do {
            let url = URL(string: "https://neoxa.exchange/api/exchange/ticker/BTCB2_USDC")!
            var request = URLRequest(url: url); request.timeoutInterval = 15
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, data.count < 262144 else { return }
            let reply = try Reply(data: data)
            guard reply.bool("success"), reply.text("pair") == "BTCB2_USDC" else { return }
            let ticker = reply.object("ticker")
            let date = Date(timeIntervalSince1970: Double(ticker.int("computedAt")) / 1000)
            guard Date().timeIntervalSince(date) < 900, date.timeIntervalSinceNow < 300,
                  let rate = Decimal(string: ticker.text("lastPrice")), rate > 0 else { return }
            usdRate = rate; rateDate = date
        } catch { /* A missing rate never substitutes BTC's price or blocks XBT amounts. */ }
    }
    func pay(invoice: String, amount: Int64?, label: String) async throws -> Reply {
        guard phase == .ready, let store else { throw WalletError("Wait for wallet synchronization.") }
        let decoded = try await checkedInvoice(invoice)
        let sats = decoded.int("num_satoshis") > 0 ? decoded.int("num_satoshis") : (amount ?? 0)
        guard sats > 0 else { throw WalletError("Enter an amount for this invoice.") }
        let hash = decoded.text("payment_hash")
        let id = try store.begin("lightning", reference: hash)
        if !label.isEmpty { UserDefaults.standard.set(label, forKey: "label." + hash) }
        var params: [String: Any] = ["payment_request": invoice, "timeout_seconds": 60,
            "fee_limit_sat": String(max(1, sats / 100)), "no_inflight_updates": false]
        if decoded.int("num_msat") == 0 { params["amt"] = String(sats) }
        do {
            for try await result in try rpc.stream("sendPayment", params) {
                if result.text("status") == "SUCCEEDED" {
                    try store.finish(id, state: "succeeded", result: result.text("payment_hash"))
                    return result
                }
                if result.text("status") == "FAILED" {
                    try store.finish(id, state: "failed", result: result.text("failure_reason"))
                    throw WalletError("Payment failed: \(result.text("failure_reason")). Check routing, liquidity and the displayed fee limit.")
                }
            }
            throw WalletError("Payment stream ended without a final result. Check history before retrying.")
        } catch { throw WalletError("\(error.localizedDescription)\nPayment reference: \(hash)") }
    }
    func checkedInvoice(_ text: String) async throws -> Reply {
        let reply = try await rpc.call("decodePayReq", ["pay_req": text])
        let features = reply.object("features").fields
        guard features["512"] != nil || features["513"] != nil else {
            throw WalletError("This is a BTC invoice. Automatic swaps have not yet been ported to iPhone; it cannot be paid directly from XBT.")
        }
        guard reply.int("num_msat") % 1000 == 0 else {
            throw WalletError("This invoice requires a fractional satoshi amount. This iPhone preview currently supports whole-satoshi payments only.")
        }
        let deadline = reply.int("timestamp") + reply.int("expiry")
        guard deadline > Int64(Date().timeIntervalSince1970) else { throw WalletError("This invoice has expired. Request a fresh invoice.") }
        return reply
    }
    func sendCoins(params: [String: Any], reference: String) async throws -> String {
        guard phase == .ready, let store else { throw WalletError("Wait for wallet synchronization.") }
        let id = try store.begin("on-chain", reference: reference)
        do {
            let reply = try await rpc.call("sendCoins", params, timeout: 90)
            let txid = reply.text("txid")
            guard !txid.isEmpty else { throw WalletError("The node returned no transaction ID. Check history before retrying.") }
            try store.finish(id, state: "succeeded", result: txid)
            return txid
        } catch {
            throw WalletError("\(error.localizedDescription)\nThe request is recorded as unresolved; do not repeat it without checking history.")
        }
    }
}
