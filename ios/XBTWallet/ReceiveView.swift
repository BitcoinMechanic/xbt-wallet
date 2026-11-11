import SwiftUI
import CoreImage.CIFilterBuiltins

struct QRImage: View {
    let text: String
    var image: UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cg = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 8, y: 8)), from: output.extent.applying(CGAffineTransform(scaleX: 8, y: 8))) else { return nil }
        return UIImage(cgImage: cg)
    }
    var body: some View {
        if let image {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                .padding(16).background(.white).clipShape(RoundedRectangle(cornerRadius: 12))
                .frame(maxWidth: 300).accessibilityLabel("Payment QR code")
        }
    }
}
struct ReceiveView: View {
    @EnvironmentObject var wallet: WalletModel
    @Environment(\.dismiss) private var dismiss
    let lightning: Bool
    @State private var amount = ""
    @State private var memo = ""
    @State private var label = ""
    @State private var address = ""
    @State private var invoice = ""
    @State private var hash = ""
    @State private var watch: Task<Void, Never>?
    var payload: String {
        if lightning { return invoice }
        guard !address.isEmpty else { return "" }
        if let sats = try? Amounts.parse(amount, unit: wallet.unit, usdRate: wallet.currentRate) {
            return "bitcoin:\(address)?amount=\(Amounts.entry(sats, unit: .xbt, usdRate: nil))"
        }
        return address
    }
    var body: some View {
        Page(title: lightning ? "Receive Lightning" : "Receive XBT") {
            AmountField(text: $amount, placeholder: lightning ? "Optional" : "(optional)")
            if lightning {
                Field(title: "Description", placeholder: "Optional – sender can see this", text: $memo)
                Field(title: "Label", placeholder: "Optional – for your own records", text: $label)
                Button("Create invoice") { createInvoice() }.buttonStyle(WalletButton()).disabled(wallet.busy || !invoice.isEmpty)
            }
            if !payload.isEmpty {
                QRImage(text: payload)
                Button { UIPasteboard.general.string = payload } label: {
                    Text(lightning ? "Copy invoice" : address).font(.footnote).textSelection(.enabled)
                }
                ShareLink(item: payload) { Label("Share", systemImage: "square.and.arrow.up") }
            }
            if !lightning {
                Field(title: "Label", placeholder: "(optional)", text: $label)
                    .onChange(of: label) { UserDefaults.standard.set($0, forKey: "addressLabel." + address) }
                if address.isEmpty { ProgressView() }
            }
        }
        .task {
            guard !lightning, address.isEmpty else { return }
            do {
                address = try await wallet.rpc.call("newAddress", ["type": "WITNESS_PUBKEY_HASH"]).text("address")
                guard !address.isEmpty else { throw WalletError("The node returned an empty receiving address.") }
                startWatching()
            } catch { wallet.error = error.localizedDescription }
        }
        .onDisappear { watch?.cancel() }
    }
    private func createInvoice() {
        wallet.perform {
            let sats = amount.isEmpty ? 0 : try Amounts.parse(amount, unit: wallet.unit, usdRate: wallet.currentRate)
            let response = try await wallet.rpc.call("addInvoice", ["value": String(sats), "memo": memo, "expiry": "3600"])
            invoice = response.text("payment_request"); hash = response.text("r_hash")
            guard !invoice.isEmpty, !hash.isEmpty else { throw WalletError("The node returned an incomplete invoice response.") }
            if let bytes = Data(base64Encoded: hash) {
                UserDefaults.standard.set(label, forKey: "label." + bytes.map { String(format: "%02x", $0) }.joined())
            }
            startWatching()
        }
    }
    private func startWatching() {
        watch?.cancel()
        watch = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if Task.isCancelled { return }
                do {
                    if lightning {
                        let response = try await wallet.rpc.call("lookupInvoice", ["r_hash": hash])
                        if response.bool("settled") { dismiss(); return }
                    } else {
                        let response = try await wallet.rpc.call("getTransactions")
                        for tx in response.array("transactions") {
                            if tx.array("output_details").contains(where: { $0.text("address") == address && $0.bool("is_our_address") }) {
                                if !label.isEmpty { UserDefaults.standard.set(label, forKey: "label." + tx.text("tx_hash")) }
                                dismiss(); return
                            }
                        }
                    }
                } catch { /* Foreground refresh retries only read operations. */ }
            }
        }
    }
}
