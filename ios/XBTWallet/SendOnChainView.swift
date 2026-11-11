import SwiftUI

struct SendOnChainView: View {
    @EnvironmentObject var wallet: WalletModel
    var initialAddress = ""
    var initialSats: Int64?
    @State private var address = ""
    @State private var amount = ""
    @State private var label = ""
    @State private var rate = "1"
    @State private var outputs: [Reply] = []
    @State private var selected: Set<String> = []
    @State private var entire = false
    @State private var estimatedFee: Int64?
    @State private var estimateError = ""
    @State private var review = false
    @State private var txid = ""
    @State private var scan = false
    @State private var estimateTask: Task<Void, Never>?
    var selectedOutputs: [Reply] { outputs.filter { selected.contains($0.outputID) } }
    var selectedTotal: Int64 { selectedOutputs.reduce(0) { $0 + $1.int("amount_sat") } }
    var sats: Int64 { (try? Amounts.parse(amount, unit: wallet.unit, usdRate: wallet.currentRate)) ?? 0 }
    var inputPoints: [[String: Any]] { selectedOutputs.map { $0.object("outpoint").fields } }
    var estimateKey: String { "\(address)|\(amount)|\(rate)|\(wallet.unit.rawValue)|\(selected.sorted())|\(entire)" }
    var body: some View {
        Page(title: txid.isEmpty ? "Send on chain" : "Transaction sent") {
            if !txid.isEmpty {
                Image(systemName: "checkmark.circle.fill").font(.largeTitle).foregroundStyle(Palette.green)
                Text(txid).font(.footnote).textSelection(.enabled)
            } else {
                Field(title: "Address", placeholder: "XBT address", text: $address)
                HStack {
                    Button("Scan QR") { scan = true }
                    Button("Paste") { if let text = UIPasteboard.general.string { accept(text) } }
                }.buttonStyle(.bordered)
                AmountField(text: $amount, placeholder: "Required", disabled: entire)
                Toggle("Use entire selection", isOn: $entire).disabled(selected.isEmpty)
                    .onChange(of: entire) { if $0 { amount = Amounts.entry(selectedTotal, unit: wallet.unit, usdRate: wallet.currentRate) } }
                Field(title: "Label", placeholder: "Optional", text: $label)
                Field(title: "Network fee (sats/vByte)", placeholder: "1", text: $rate, keyboard: .numberPad)
                Text("On chain funds").foregroundStyle(Palette.rope).frame(maxWidth: .infinity, alignment: .leading)
                if outputs.isEmpty { Text("No spendable outputs").foregroundStyle(.secondary) }
                ForEach(outputs, id: \.outputID) { output in
                    Button {
                        entire = false
                        if !selected.insert(output.outputID).inserted { selected.remove(output.outputID) }
                    } label: {
                        HStack {
                            Image(systemName: selected.contains(output.outputID) ? "checkmark.circle.fill" : "circle")
                            VStack(alignment: .leading) {
                                Text(wallet.display(output.int("amount_sat")))
                                Text(String(output.outputID.prefix(16)) + "…").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                    }.foregroundStyle(.white)
                }
                if let fee = estimatedFee {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Estimated network fee: \(fee.formatted()) sats")
                        Text("Recipient receives: \((entire ? max(0, selectedTotal - fee) : sats).formatted()) sats")
                        Text("Total including fee: \((entire ? selectedTotal : sats + fee).formatted()) sats")
                    }.font(.footnote).foregroundStyle(Palette.rope)
                }
                if !estimateError.isEmpty { Text(estimateError).font(.footnote).foregroundStyle(.secondary) }
                Button("Review transaction") {
                    guard !address.isEmpty, sats > 0, let feeRate = Int64(rate), feeRate > 0 else {
                        wallet.error = "Enter an address, amount and positive fee rate."; return
                    }
                    guard estimatedFee != nil else { wallet.error = "Wait for a valid network fee estimate."; return }
                    review = true
                }.buttonStyle(WalletButton()).disabled(wallet.busy)
            }
        }
        .task {
            address = initialAddress
            if let initialSats { amount = Amounts.entry(initialSats, unit: wallet.unit, usdRate: wallet.currentRate) }
            do { outputs = try await wallet.rpc.call("listUnspent", ["min_confs": 1, "max_confs": 2147483647]).array("utxos") }
            catch { wallet.error = error.localizedDescription }
            scheduleEstimate()
        }
        .onChange(of: estimateKey) { _ in scheduleEstimate() }
        .onDisappear { estimateTask?.cancel() }
        .sheet(isPresented: $scan) { QRScanner { result in
            scan = false
            if let address = result.address { self.address = address; if let sats = result.sats { amount = Amounts.entry(sats, unit: wallet.unit, usdRate: wallet.currentRate) } }
            else { wallet.error = "Scan an on-chain address." }
        } }
        .sheet(isPresented: $review) {
            NavigationStack {
                Page(title: "Review transaction") {
                    Text(address).font(.footnote).textSelection(.enabled)
                    Text("Recipient: \((entire ? max(0, selectedTotal - (estimatedFee ?? 0)) : sats).formatted()) sats")
                    Text("Estimated fee: \((estimatedFee ?? 0).formatted()) sats · \(rate) sats/vByte")
                    Text("XBT mainnet").font(.caption)
                    Button("Send") {
                        review = false
                        wallet.perform {
                            var params: [String: Any] = ["addr": address, "sat_per_vbyte": rate, "label": label, "min_confs": 1, "spend_unconfirmed": false]
                            if entire { params["send_all"] = true } else { params["amount"] = String(sats) }
                            if !selected.isEmpty { params["outpoints"] = inputPoints }
                            let reference = "\(address)|\(entire ? "all" : String(sats))|\(selected.sorted())"
                            txid = try await wallet.sendCoins(params: params, reference: reference)
                            if !label.isEmpty { UserDefaults.standard.set(label, forKey: "label." + txid) }
                        }
                    }.buttonStyle(WalletButton())
                    Button("Cancel") { review = false }
                }
            }
        }
    }
    private func accept(_ text: String) {
        do {
            let result = try ScanResult.parse(text)
            guard let address = result.address else { throw WalletError("Paste an on-chain address.") }
            self.address = address
            if let sats = result.sats { entire = false; amount = Amounts.entry(sats, unit: wallet.unit, usdRate: wallet.currentRate) }
        } catch { wallet.error = error.localizedDescription }
    }
    private func scheduleEstimate() {
        estimateTask?.cancel(); estimatedFee = nil; estimateError = ""
        guard !address.isEmpty, sats > 0, let feeRate = Int64(rate), feeRate > 0, feeRate <= 1_000_000 else { return }
        let key = estimateKey
        estimateTask = Task {
            do {
                try await Task.sleep(nanoseconds: 400_000_000)
                // The core estimator validates addresses and chooses inputs. For sweep previews,
                // leave a conservative fee allowance; SendCoins(send_all) computes the final value.
                let previewAmount = entire ? max(1, selectedTotal - Int64(max(1, selected.count) * 200 + 100) * feeRate) : sats
                var params: [String: Any] = ["AddrToAmount": [address: String(previewAmount)], "target_conf": 6, "min_confs": 1]
                if !selected.isEmpty { params["inputs"] = inputPoints }
                let result = try await wallet.rpc.call("estimateFee", params)
                guard !Task.isCancelled, key == estimateKey else { return }
                let baseRate = result.int("sat_per_vbyte")
                guard baseRate > 0 else { throw WalletError("The node returned an invalid fee rate.") }
                let virtualBytes = (result.int("fee_sat") + baseRate - 1) / baseRate
                estimatedFee = virtualBytes * feeRate
            } catch {
                if !Task.isCancelled, key == estimateKey { estimateError = "Fee estimate: \(error.localizedDescription)" }
            }
        }
    }
}
extension Reply {
    var outputID: String { object("outpoint").text("txid_str") + ":" + object("outpoint").text("output_index") }
}
