import SwiftUI

struct HomeView: View {
    @EnvironmentObject var wallet: WalletModel
    @State private var expanded: String?
    @State private var channelsOpen = true
    @State private var scanner = false
    @State private var scanned: ScanResult?
    @State private var price = false
    var body: some View {
        ScrollView {
            VStack(spacing: 26) {
                Button { withAnimation(.easeInOut(duration: 0.5)) { price.toggle() } } label: {
                    ZStack {
                        if price {
                            Text(wallet.currentRate.map { "$\(NSDecimalNumber(decimal: $0).stringValue)\n/XBT" } ?? "Price unavailable")
                                .font(.title2).multilineTextAlignment(.center).foregroundStyle(Palette.gradient)
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                        } else {
                            Image("xbt_logo").resizable().scaledToFit().transition(.move(edge: .leading).combined(with: .opacity))
                        }
                    }.frame(height: 100).clipped()
                }.buttonStyle(.plain).accessibilityLabel("XBT logo, show market price")
                if !wallet.name.isEmpty { Text(wallet.name).foregroundStyle(.secondary) }
                VStack(spacing: 18) {
                    HStack {
                        Button("On Chain") { toggle("chain") }.font(.title3.weight(.semibold))
                        Spacer()
                        Button(wallet.display(wallet.balance.int("confirmed_balance"))) { wallet.cycleUnit() }.foregroundStyle(.white)
                    }
                    if wallet.balance.int("unconfirmed_balance") > 0 {
                        Text("Unconfirmed: \(wallet.display(wallet.balance.int("unconfirmed_balance")))").font(.footnote).foregroundStyle(.secondary)
                    }
                    if expanded == "chain" {
                        HStack {
                            NavigationLink("Send") { SendOnChainView() }
                            NavigationLink("Receive") { ReceiveView(lightning: false) }
                        }.buttonStyle(WalletButton()).transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
                VStack(spacing: 18) {
                    HStack {
                        Button("Lightning") { toggle("lightning") }.font(.title3.weight(.semibold))
                        Spacer()
                        Button { wallet.cycleUnit() } label: {
                            VStack(alignment: .trailing, spacing: 3) {
                                Text("Spendable  \(wallet.display(wallet.local))").foregroundStyle(.white)
                                Text("Receivable  \(wallet.display(wallet.remote))").foregroundStyle(Palette.rope)
                            }.font(.subheadline)
                        }
                    }
                    if expanded == "lightning" {
                        HStack {
                            NavigationLink("Send") { PayLightningView() }
                            NavigationLink("Receive") { ReceiveView(lightning: true) }
                        }.buttonStyle(WalletButton()).transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
                Button { scanner = true } label: { Label("Scan", systemImage: "qrcode.viewfinder") }
                    .buttonStyle(WalletButton()).frame(maxWidth: 190).padding(.vertical, 25)
                VStack(spacing: 18) {
                    Button { withAnimation(.easeInOut) { channelsOpen.toggle() } } label: {
                        HStack { Text("Channels (\(wallet.channels.count + wallet.pending.count))"); Spacer(); Image(systemName: channelsOpen ? "chevron.down" : "chevron.right") }
                    }.foregroundStyle(Palette.rope)
                    if channelsOpen {
                        ForEach(wallet.channels, id: \.channelID) { channel in ChannelRow(channel: channel) }
                        ForEach(wallet.pending, id: \.pendingID) { item in
                            VStack(alignment: .leading) {
                                HStack { Text("Confirming channel"); Spacer(); Circle().fill(.orange).frame(width: 9, height: 9) }
                                Text("Confirmations needed: \(item.int("confirmations"))/\(item.int("confirmation_height") > 0 ? "block \(item.int("confirmation_height"))" : "pending")").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        NavigationLink("Open channel") { OpenChannelView() }.font(.subheadline)
                    }
                }
                Text("XBT mainnet · \(wallet.status)").font(.caption).foregroundStyle(.secondary)
            }.padding(24)
        }
        .background(Palette.background)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    NavigationLink("Payment history") { HistoryView() }
                    NavigationLink("On chain funds") { FundsView() }
                    NavigationLink("Channels") { ChannelsView() }
                    NavigationLink("Advanced") { AdvancedView() }
                } label: { Image(systemName: "ellipsis").font(.title2).padding(5) }
            }
        }
        .sheet(isPresented: $scanner) { QRScanner { scanned = $0; scanner = false } }
        .navigationDestination(isPresented: Binding(get: { scanned != nil }, set: { if !$0 { scanned = nil } })) {
            if let result = scanned {
                if let invoice = result.invoice { PayLightningView(initialInvoice: invoice) }
                else { SendOnChainView(initialAddress: result.address ?? "", initialSats: result.sats) }
            }
        }
    }
    private func toggle(_ name: String) { withAnimation(.easeInOut(duration: 0.25)) { expanded = expanded == name ? nil : name } }
}

extension Reply {
    var channelID: String { text("channel_point") }
    var pendingID: String { object("channel").text("channel_point") }
}

struct ChannelRow: View {
    @EnvironmentObject var wallet: WalletModel
    let channel: Reply
    @State private var expanded = false
    @State private var pulse = false
    @State private var labeling = false
    @State private var label = ""
    var savedLabel: String { UserDefaults.standard.string(forKey: "label." + channel.channelID) ?? "Channel" }
    var body: some View {
        VStack(spacing: 10) {
            Button { withAnimation(.easeInOut) { expanded.toggle() } } label: {
                VStack(spacing: 10) {
                    HStack {
                        Text(savedLabel).foregroundStyle(.white); Spacer()
                        Circle().fill(channel.bool("active") ? Palette.green : Color.red)
                            .frame(width: 10, height: 10).opacity(pulse ? 1 : 0.35)
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Palette.rope)
                            Capsule().fill(Palette.green).frame(width: geo.size.width * CGFloat(channel.int("local_balance")) / CGFloat(max(1, channel.int("capacity"))))
                        }
                    }.frame(height: 9)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
                .onLongPressGesture { label = savedLabel == "Channel" ? "" : savedLabel; labeling = true }
            if expanded {
                HStack(alignment: .top) {
                    VStack(alignment: .leading) { Text("Local: \(wallet.display(channel.int("local_balance")))"); Text("Status:") }
                    Spacer()
                    VStack(alignment: .trailing) { Text("Remote: \(wallet.display(channel.int("remote_balance")))"); Text(channel.bool("active") ? "Connected" : "Disconnected") }
                }.font(.caption)
                NavigationLink("Channel details") { ChannelDetail(channel: channel) }.font(.subheadline)
            }
        }.onAppear { withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { pulse = true } }
            .alert("Label channel", isPresented: $labeling) {
                TextField("Optional", text: $label)
                Button("Save") { UserDefaults.standard.set(label.isEmpty ? nil : label, forKey: "label." + channel.channelID) }
                Button("Cancel", role: .cancel) {}
            }
    }
}
struct ChannelsView: View {
    @EnvironmentObject var wallet: WalletModel
    var body: some View {
        Page(title: "Channels") {
            Text("\(wallet.channels.count) open · \(wallet.pending.count) pending").foregroundStyle(.secondary)
            ForEach(wallet.channels, id: \.channelID) { ChannelRow(channel: $0) }
            NavigationLink("Open channel") { OpenChannelView() }.buttonStyle(WalletButton())
        }
    }
}
struct ChannelDetail: View {
    @EnvironmentObject var wallet: WalletModel
    let channel: Reply
    @State private var closing = false
    @State private var result = ""
    var body: some View {
        Page(title: "Channel details") {
            Text("Local: \(wallet.display(channel.int("local_balance")))")
            Text("Remote: \(wallet.display(channel.int("remote_balance")))")
            Text("Peer").foregroundStyle(Palette.rope)
            Text(channel.text("remote_pubkey")).font(.footnote).textSelection(.enabled)
            Text("Funding output").foregroundStyle(Palette.rope)
            Button(channel.channelID) { UIPasteboard.general.string = channel.channelID }.font(.footnote)
            if result.isEmpty {
                Button("Close channel") { closing = true }.buttonStyle(WalletButton(destructive: true)).disabled(wallet.busy)
            } else { Text(result).textSelection(.enabled) }
        }.confirmationDialog("Close this channel cooperatively? Its balance will return on chain after confirmation.", isPresented: $closing, titleVisibility: .visible) {
            Button("Close channel", role: .destructive) {
                wallet.perform {
                    let pieces = channel.channelID.split(separator: ":")
                    guard pieces.count == 2, let index = UInt32(pieces[1]), let store = wallet.store else { throw WalletError("Invalid funding output.") }
                    let id = try store.begin("close-channel", reference: channel.channelID)
                    for try await event in wallet.rpc.stream("closeChannel", ["channel_point": ["funding_txid_str": String(pieces[0]), "output_index": index], "force": false]) {
                        if event.fields["close_pending"] != nil || event.fields["chan_close"] != nil {
                            try store.finish(id, state: "succeeded", result: "Cooperative close submitted")
                            result = "Closing transaction submitted. Check payment history for confirmation."
                            return
                        }
                    }
                    throw WalletError("The close request ended without confirmation. Check channel status before retrying.")
                }
            }
        }
    }
}
