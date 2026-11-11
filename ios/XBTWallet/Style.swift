import SwiftUI

enum Palette {
    static let background = Color(red: 16/255, green: 21/255, blue: 23/255)
    static let field = Color(red: 25/255, green: 33/255, blue: 36/255)
    static let green = Color(red: 101/255, green: 216/255, blue: 154/255)
    static let rope = Color(red: 232/255, green: 210/255, blue: 167/255)
    static let gradient = LinearGradient(colors: [Color(red: 0.71, green: 0.61, blue: 0.45), rope, Color(red: 0.96, green: 0.90, blue: 0.78), rope], startPoint: .leading, endPoint: .trailing)
}
struct WalletButton: ButtonStyle {
    var destructive = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
            .foregroundStyle(destructive ? Color.white : Palette.background)
            .background(destructive ? Color(red: 0.66, green: 0.20, blue: 0.25) : Palette.green)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
struct RevealTitle: View {
    let text: String
    @State private var shown = false
    var body: some View {
        Text(text).font(.system(size: 29, weight: .semibold, design: .rounded))
            .foregroundStyle(Palette.gradient).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.vertical, 20)
            .mask(GeometryReader { geo in
                Rectangle().frame(width: shown ? geo.size.width : 0).frame(maxWidth: .infinity)
            })
            .onAppear { withAnimation(.easeOut(duration: 0.5)) { shown = true } }
    }
}
struct Field: View {
    let title: String
    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !title.isEmpty { Text(title).font(.subheadline).foregroundStyle(Palette.rope) }
            TextField("", text: $text, prompt: Text(placeholder).italic().foregroundColor(.secondary))
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(keyboard)
                .padding(14).background(Palette.field).clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }
}
struct AmountField: View {
    @EnvironmentObject var wallet: WalletModel
    @Binding var text: String
    var placeholder = "Optional"
    var disabled = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Amount").font(.subheadline).foregroundStyle(Palette.rope)
            HStack {
                TextField("", text: $text, prompt: Text(placeholder).italic().foregroundColor(.secondary))
                    .keyboardType(.decimalPad).disabled(disabled).padding(14)
                    .background(Palette.field).clipShape(RoundedRectangle(cornerRadius: 12))
                Button(wallet.unit.rawValue) {
                    let sats = try? Amounts.parse(text, unit: wallet.unit, usdRate: wallet.currentRate)
                    wallet.cycleUnit()
                    if let sats { text = Amounts.entry(sats, unit: wallet.unit, usdRate: wallet.currentRate) }
                }.foregroundStyle(Palette.rope).frame(minWidth: 52)
            }
            if let sats = try? Amounts.parse(text, unit: wallet.unit, usdRate: wallet.currentRate) {
                Text(Amounts.format(sats, unit: wallet.unit == .usd ? (sats > 999_999 ? .xbt : .sats) : .usd, usdRate: wallet.currentRate))
                    .font(.footnote).italic().foregroundStyle(.secondary)
            }
        }
    }
}
struct Page<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        ScrollView {
            VStack(spacing: 20) { RevealTitle(text: title); content() }
                .padding(.horizontal, 22).padding(.bottom, 30)
        }.scrollDismissesKeyboard(.interactively).background(Palette.background)
    }
}
