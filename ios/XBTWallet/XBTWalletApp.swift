import SwiftUI

@main struct XBTWalletApp: App {
    @StateObject private var wallet = WalletModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(wallet).preferredColorScheme(.dark).tint(Palette.green)
                .overlay {
                    if scenePhase != .active { Palette.background.ignoresSafeArea().overlay(Image("xbt_logo").resizable().scaledToFit().padding(60)) }
                }
                .task { await wallet.launch() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var wallet: WalletModel
    @State private var name = "Neutrino"
    @State private var step = 0
    @State private var showSeed = false
    @State private var mode = false
    var body: some View {
        NavigationStack {
            Group {
                switch wallet.phase {
                case .welcome:
                    Page(title: "Your Lightning Wallet") {
                        Image("xbt_logo").resizable().scaledToFit().padding(.vertical, 30)
                        Text("Your keys. Your XBT.").foregroundStyle(.secondary)
                        Button("Create your wallet") { mode = true }.buttonStyle(WalletButton())
                        if mode {
                            Text("Choose how to connect").foregroundStyle(Palette.rope)
                            Button("Easy mode") { Task { await wallet.beginSetup() } }.buttonStyle(WalletButton())
                            Button("Remote Control") {
                                wallet.error = "Remote Control is not yet available in this first iPhone build. The Android app supports Core Lightning connections."
                            }.buttonStyle(.bordered)
                        }
                    }
                case .create:
                    Page(title: step == 0 ? "Create your wallet" : "Name your wallet") {
                        if step == 0 {
                            Text("Create a new XBT mainnet wallet on this iPhone.").foregroundStyle(.secondary)
                            Button("Continue") { step = 1 }.buttonStyle(WalletButton())
                        } else {
                            Field(title: "Name", placeholder: "Optional", text: $name)
                                .onChange(of: name) { name = String($0.prefix(32)) }
                            Button("Create wallet") { create(name) }.buttonStyle(WalletButton()).disabled(wallet.busy)
                            Button("Not now") { create("") }.disabled(wallet.busy)
                        }
                        Button("Cancel setup") { step = 0; wallet.phase = .welcome }.padding(.top, 35)
                    }
                case .starting, .syncing:
                    Page(title: "Starting...") {
                        ProgressView().tint(Palette.green)
                        Text(wallet.status).multilineTextAlignment(.center).foregroundStyle(.secondary)
                        Text("Keep the app open while the XBT node synchronizes. iOS suspends the node when the app is in the background.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                case .failed:
                    Page(title: "Unable to start") {
                        Text(wallet.error ?? wallet.status).textSelection(.enabled)
                        Button("Try starting again") { Task { await wallet.launch() } }.buttonStyle(WalletButton())
                    }
                case .ready: HomeView()
                }
            }
            .background(Palette.background.ignoresSafeArea())
            .toolbarBackground(Palette.background, for: .navigationBar)
            .sheet(isPresented: $showSeed) { NavigationStack { SeedView() }.environmentObject(wallet) }
            .alert("Wallet", isPresented: Binding(get: { wallet.error != nil }, set: { if !$0 { wallet.error = nil } })) {
                Button("OK") { wallet.error = nil }
            } message: { Text(wallet.error ?? "") }
            .overlay(alignment: .bottom) {
                if wallet.busy { ProgressView().padding().background(Palette.field, in: Capsule()).padding() }
            }
        }
    }
    private func create(_ name: String) {
        wallet.perform { try await wallet.create(name: name); showSeed = true }
    }
}
