import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var wallet: WalletModel
    @State private var tab = "All"
    @State private var rows: [HistoryRow] = []
    @State private var edit: HistoryRow?
    @State private var label = ""
    var body: some View {
        Page(title: "Payment history") {
            Picker("Type", selection: $tab) { ForEach(["All", "Lightning", "On Chain"], id: \.self) { Text($0) } }.pickerStyle(.segmented)
            ForEach(rows.filter { tab == "All" || $0.kind == tab }) { row in
                Button { edit = row; label = UserDefaults.standard.string(forKey: "label." + row.id) ?? row.label } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack { Text(wallet.display(row.amount)); Spacer(); Text(row.status).font(.caption) }
                        Text(UserDefaults.standard.string(forKey: "label." + row.id) ?? row.label).font(.subheadline)
                        Text(row.date, style: .date).font(.caption).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(14).background(Palette.field, in: RoundedRectangle(cornerRadius: 12))
                }.foregroundStyle(.white)
            }
            if rows.isEmpty { Text("No payments yet").foregroundStyle(.secondary) }
            if let store = wallet.store {
                ForEach(store.attempts.filter { $0.state == "pending" }) { attempt in
                    VStack(alignment: .leading) {
                        Text("Unresolved \(attempt.kind) request").foregroundStyle(.orange)
                        Text(attempt.reference).font(.caption).textSelection(.enabled)
                        Text("Check the node’s history before submitting it again.").font(.caption)
                    }
                }
            }
        }.task { await load() }
        .alert("Payment label", isPresented: Binding(get: { edit != nil }, set: { if !$0 { edit = nil } })) {
            TextField("Optional", text: $label)
            Button("Save") { if let edit { UserDefaults.standard.set(label, forKey: "label." + edit.id) }; edit = nil }
            Button("Cancel", role: .cancel) { edit = nil }
        }
    }
    private func load() async {
        do {
            var result: [HistoryRow] = []
            for tx in try await wallet.rpc.call("getTransactions").array("transactions") {
                result.append(HistoryRow(id: tx.text("tx_hash"), kind: "On Chain", amount: tx.int("amount"), status: tx.int("num_confirmations") > 0 ? "Confirmed" : "Unconfirmed", label: tx.text("label"), date: Date(timeIntervalSince1970: Double(tx.int("time_stamp")))))
            }
            for payment in try await wallet.rpc.call("listPayments", ["include_incomplete": true, "max_payments": "200", "reversed": true]).array("payments") {
                result.append(HistoryRow(id: payment.text("payment_hash"), kind: "Lightning", amount: -payment.int("value_sat"), status: payment.text("status"), label: "Sent", date: Date(timeIntervalSince1970: Double(payment.int("creation_time_ns")) / 1e9)))
            }
            for invoice in try await wallet.rpc.call("listInvoices", ["num_max_invoices": "200", "reversed": true]).array("invoices") where invoice.bool("settled") {
                let hash = (Data(base64Encoded: invoice.text("r_hash")) ?? Data()).map { String(format: "%02x", $0) }.joined()
                result.append(HistoryRow(id: hash, kind: "Lightning", amount: invoice.int("amt_paid_sat"), status: "Received", label: invoice.text("memo"), date: Date(timeIntervalSince1970: Double(invoice.int("settle_date")))))
            }
            rows = result.sorted { $0.date > $1.date }
        } catch { wallet.error = error.localizedDescription }
    }
}
struct HistoryRow: Identifiable { let id: String; let kind: String; let amount: Int64; let status: String; let label: String; let date: Date }

struct FundsView: View {
    @EnvironmentObject var wallet: WalletModel
    @State private var outputs: [Reply] = []
    @State private var selected: String?
    @State private var label = ""
    var body: some View {
        Page(title: "On chain funds") {
            ForEach(outputs, id: \.outputID) { output in
                Button {
                    selected = output.outputID
                    label = UserDefaults.standard.string(forKey: "label." + output.outputID) ?? ""
                } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(wallet.display(output.int("amount_sat")))
                        Text(UserDefaults.standard.string(forKey: "label." + output.outputID) ?? "Tap to label").font(.subheadline).foregroundStyle(.secondary)
                        Text(output.outputID).font(.caption).textSelection(.enabled)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }.task {
            do { outputs = try await wallet.rpc.call("listUnspent", ["min_confs": 0, "max_confs": 2147483647]).array("utxos") }
            catch { wallet.error = error.localizedDescription }
        }.alert("Label output", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } })) {
            TextField("Optional", text: $label)
            Button("Save") { if let selected { UserDefaults.standard.set(label, forKey: "label." + selected) }; selected = nil }
            Button("Cancel", role: .cancel) { selected = nil }
        }
    }
}
struct OpenChannelView: View {
    @EnvironmentObject var wallet: WalletModel
    @State private var peer = ""
    @State private var amount = ""
    @State private var rate = "1"
    @State private var publicChannel = false
    @State private var confirm = false
    @State private var result = ""
    var body: some View {
        Page(title: "Open channel") {
            Field(title: "Peer", placeholder: "public key@host:port", text: $peer)
            AmountField(text: $amount, placeholder: "Channel capacity")
            Field(title: "Network fee (sats/vByte)", placeholder: "1", text: $rate, keyboard: .numberPad)
            Toggle("Public channel", isOn: $publicChannel)
            Text("The network fee is paid in addition to the channel capacity. The node also enforces its channel reserve requirements.").font(.footnote).foregroundStyle(.secondary)
            Button("Review channel") { confirm = true }.buttonStyle(WalletButton()).disabled(wallet.busy || !result.isEmpty)
            if !result.isEmpty { Text(result).textSelection(.enabled) }
        }.confirmationDialog("Open this XBT channel with the entered capacity and fee rate?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Open channel") {
                wallet.perform {
                    let pieces = peer.split(separator: "@", omittingEmptySubsequences: false)
                    guard pieces.count == 2, let key = Data(hex: String(pieces[0])), key.count == 33,
                          let fee = UInt64(rate), fee > 0, let store = wallet.store else { throw WalletError("Enter a compressed public key@host:port and positive fee rate.") }
                    guard !pieces[1].lowercased().contains(".onion") else { throw WalletError("Tor transport has not yet been ported to iPhone. Use a clearnet or VPN peer address.") }
                    let sats = try Amounts.parse(amount, unit: wallet.unit, usdRate: wallet.currentRate)
                    let peers = try await wallet.rpc.call("listPeers").array("peers")
                    if !peers.contains(where: { $0.text("pub_key") == String(pieces[0]) }) {
                        _ = try await wallet.rpc.call("connectPeer", ["addr": ["pubkey": String(pieces[0]), "host": String(pieces[1])], "perm": true], timeout: 60)
                    }
                    let id = try store.begin("open-channel", reference: peer + ":" + String(sats))
                    let response = try await wallet.rpc.call("openChannelSync", ["node_pubkey": key.base64EncodedString(), "local_funding_amount": String(sats), "sat_per_vbyte": String(fee), "private": !publicChannel, "min_confs": 1], timeout: 90)
                    try store.finish(id, state: "succeeded", result: response.text("funding_txid_str"))
                    result = "Channel funding submitted. Its status will appear on Home."
                }
            }
        }
    }
}
extension Data {
    init?(hex: String) {
        guard hex.count % 2 == 0, hex.allSatisfy({ $0.isHexDigit }) else { return nil }
        self.init()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            append(byte); index = next
        }
    }
}
struct AdvancedView: View {
    @EnvironmentObject var wallet: WalletModel
    @State private var name = ""
    var body: some View {
        Page(title: "Advanced") {
            NavigationLink("Back up seed") { SeedView() }
            Field(title: "Wallet name", placeholder: "Optional", text: $name).onChange(of: name) { name = String($0.prefix(32)) }
            Button("Save name") { wallet.name = name; UserDefaults.standard.set(name, forKey: "walletName") }.buttonStyle(WalletButton())
            Text("iPhone preview · XBT mainnet\nThis build uses direct network connections. Tor, Remote Control, multiple wallets and automatic swaps are not yet available on iOS.")
                .font(.footnote).foregroundStyle(.secondary)
            Text("Price: Neoxa XBT/USDC, treating USDC as USD. Rates older than 15 minutes are not used.").font(.footnote).foregroundStyle(.secondary)
        }.onAppear { name = wallet.name }
    }
}
struct SeedView: View {
    @EnvironmentObject var wallet: WalletModel
    @Environment(\.dismiss) private var dismiss
    @State private var words: [String] = []
    @State private var reveal = false
    var body: some View {
        Page(title: "Back up seed") {
            if !reveal {
                Text("Warning! Anyone who knows this seed has access to your funds. Recovery from these words alone does not restore your Lightning channels. Channel recovery also needs the channel backup and peer cooperation.").foregroundStyle(Palette.rope)
                Button("Show recovery words") {
                    do { words = try wallet.seed(); reveal = true }
                    catch { wallet.error = error.localizedDescription }
                }.buttonStyle(WalletButton())
                Button("Later") { dismiss() }
            } else {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 15) {
                    ForEach(Array(words.enumerated()), id: \.offset) { item in Text("\(item.offset + 1). \(item.element)").font(.body.monospaced()) }
                }.privacySensitive()
                Text("Keep a copy offline. Do not share it or store a screenshot in a cloud photo library.").font(.footnote).foregroundStyle(.secondary)
                Button("Done") { words = []; reveal = false; dismiss() }.buttonStyle(WalletButton())
            }
        }.onDisappear { words = []; reveal = false }
    }
}
