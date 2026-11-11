import SwiftUI

struct PayLightningView: View {
    @EnvironmentObject var wallet: WalletModel
    var initialInvoice = ""
    @State private var invoice = ""
    @State private var label = ""
    @State private var amount = ""
    @State private var decoded: Reply?
    @State private var inputOptions = false
    @State private var scanning = false
    @State private var review = false
    @State private var succeeded = false
    var sats: Int64 {
        let fixed = decoded?.int("num_satoshis") ?? 0
        return fixed > 0 ? fixed : ((try? Amounts.parse(amount, unit: wallet.unit, usdRate: wallet.currentRate)) ?? 0)
    }
    var body: some View {
        Page(title: succeeded ? "Payment sent" : "Pay Lightning") {
            if succeeded {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 64)).foregroundStyle(Palette.green)
                Text(wallet.display(sats))
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Invoice").foregroundStyle(Palette.rope).font(.subheadline)
                    Button { inputOptions.toggle() } label: {
                        Group {
                            if invoice.isEmpty { Text("ln...").italic().foregroundStyle(.secondary) }
                            else { Text(invoice).lineLimit(3).foregroundStyle(.white) }
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(14).background(Palette.field, in: RoundedRectangle(cornerRadius: 12))
                    }
                    if inputOptions {
                        HStack {
                            Button("Scan QR") { scanning = true }
                            Button("Paste from Clipboard") {
                                guard let value = UIPasteboard.general.string else { wallet.error = "The clipboard contains no text."; return }
                                accept(value)
                            }
                        }.buttonStyle(WalletButton()).font(.footnote)
                    }
                }
                if let decoded {
                    if decoded.int("num_msat") == 0 { AmountField(text: $amount, placeholder: "Required") }
                    else { Text(wallet.display(decoded.int("num_satoshis"))).font(.title2).foregroundStyle(Palette.rope) }
                    if !decoded.text("description").isEmpty { Text(decoded.text("description")) }
                }
                Field(title: "Label", placeholder: "Optional", text: $label)
                Button("Review invoice") {
                    guard sats > 0 else { wallet.error = "Enter a payment amount."; return }
                    review = true
                }.buttonStyle(WalletButton()).disabled(decoded == nil || wallet.busy)
            }
        }
        .onAppear { if invoice.isEmpty, !initialInvoice.isEmpty { accept(initialInvoice) } }
        .sheet(isPresented: $scanning) { QRScanner { result in
            scanning = false
            if let invoice = result.invoice { accept(invoice) } else { wallet.error = "Scan a Lightning invoice." }
        } }
        .sheet(isPresented: $review) {
            NavigationStack {
                Page(title: "Review invoice") {
                    ForEach(AmountUnit.allCases, id: \.self) { unit in
                        Text(Amounts.format(sats, unit: unit, usdRate: wallet.currentRate)).foregroundStyle(Palette.rope)
                    }
                    Text("Maximum routing fee: \(max(1, sats / 100).formatted()) sats")
                    Text("XBT mainnet").font(.caption)
                    Button("Pay invoice") {
                        review = false
                        wallet.perform {
                            _ = try await wallet.pay(invoice: invoice, amount: sats, label: label)
                            succeeded = true
                            try await wallet.refresh()
                        }
                    }.buttonStyle(WalletButton())
                    Button("Cancel") { review = false }
                }
            }.presentationDetents([.medium, .large])
        }
    }
    private func accept(_ text: String) {
        wallet.perform {
            let result = try ScanResult.parse(text)
            guard let value = result.invoice else { throw WalletError("Paste a Lightning invoice.") }
            let response = try await wallet.checkedInvoice(value)
            invoice = value; decoded = response; inputOptions = false
        }
    }
}
