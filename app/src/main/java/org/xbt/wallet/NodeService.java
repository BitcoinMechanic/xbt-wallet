package org.xbt.wallet;

import android.app.*;
import android.content.*;
import android.os.*;
import com.google.protobuf.MessageLite;
import com.google.protobuf.ByteString;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Consumer;
import lndmobile.Callback;
import lndmobile.Lndmobile;
import lnrpc.Stateservice;
import lnrpc.Walletunlocker;

/** One embedded runtime per Android process, owned by a visible foreground service. */
public final class NodeService extends Service {
    public final ExecutorService io = Executors.newSingleThreadExecutor();
    public final long startupAt = android.os.SystemClock.elapsedRealtime();
    public volatile String status = "Starting your XBT node…";
    public volatile boolean rpcReady;
    public volatile boolean supportsOnionPeers;
    private boolean started;
    private String profile;
    private boolean stopped;
    private ClnRemote remote;
    private final java.util.concurrent.atomic.AtomicInteger activeStreams = new java.util.concurrent.atomic.AtomicInteger();
    public boolean isRemote() { return WalletProfiles.settings(this, profile).getString("mode", "").equals("remote"); }
    private final SyncProgress sync = new SyncProgress();
    private long syncLogOffset;
    static final TorTransport processTor = new TorTransport();
    private final TorTransport tor = processTor;
    public final class LocalBinder extends Binder { NodeService service() { return NodeService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public void onCreate() {
        super.onCreate();
        profile = WalletProfiles.active(this);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("node", "XBT node", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "node").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("XBT Alpha node is running").setContentText("Tap to view synchronization and channels")
                .setContentIntent(open).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) { started = true; io.execute(this::startNode); }
        return START_NOT_STICKY;
    }
    private void startNode() {
        try {
            SharedPreferences prefs = WalletProfiles.settings(this, profile);
            if (isRemote()) {
                org.json.JSONObject saved = new org.json.JSONObject(new Vault(this, profile).get("remote-config"));
                RemoteConnection config = new RemoteConnection(saved.getString("endpoint"), saved.getString("rune"), saved.optString("certificate"), saved.getString("nodeId"));
                if (config.onion) tor.start(this, message -> status = message);
                status = "Connecting to your remote node…";
                ClnRemote client = new ClnRemote(config, prefs.getString("network", "mainnet"));
                client.verify(); remote = client; supportsOnionPeers = true; rpcReady = true;
                status = "Remote Core Lightning connected"; return;
            }
            // Network cannot be changed once secrets are created. Separate directory adds a second boundary.
            String network = prefs.getString("network", "regtest");
            String configuredPeer = prefs.getString("syncPeer", network.equals("mainnet") ? "" : "10.0.2.2:19444").trim();
            String peer = configuredPeer.isEmpty() && network.equals("mainnet") ? "" : Policy.endpoint(configuredPeer);
            if (!network.equals("regtest") && !network.equals("mainnet")) throw new Exception("Unsupported network");
            File directory = WalletProfiles.directory(this, profile, network);
            if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create node directory");
            String args = "--lnddir=" + directory.getAbsolutePath() + " --bitcoin.active --bitcoin." + network
                    + " --bitcoin.node=neutrino" + (peer.isEmpty() ? "" : " --neutrino.connect=" + peer)
                    + " --nolisten --norest --rpclisten=127.0.0.1:0 --tlsdisableautofill --accept-keysend";
            if (network.equals("regtest")) args += " --bitcoin.blake2b-activation-height=1";

            boolean useTor = prefs.getBoolean("useTor", false);
            if (peer.toLowerCase(java.util.Locale.ROOT).contains(".onion:") && !useTor)
                throw new Exception("Enable Tor to connect to an onion peer.");
            boolean onionPeers = prefs.getBoolean("onionPeers", false);
            if (useTor || onionPeers) {
                int socks = tor.start(this, message -> status = message);
                args += " --tor.active --tor.socks=127.0.0.1:" + socks
                    + " --connectiontimeout=60s --neutrino.broadcasttimeout=60s";
                if (useTor) args += " --tor.streamisolation";
                if (!useTor) args += " --tor.skip-proxy-for-clearnet-targets";
                supportsOnionPeers = true;
            }
            if (network.equals("mainnet")) {
                String feeUrl = prefs.getString("feeUrl", "");
                if (feeUrl.isEmpty()) {
                    feeUrl = useTor ? "http://mempool5nxspkxjk3n5afqh7zswbv4i324z76ltmw3cvfmniw45mnhad.onion/api/v1/fees/precise"
                        : "https://mempool.guide/api/v1/fees/precise";
                    prefs.edit().putString("feeUrl", feeUrl).apply();
                }
                args += " --fee.url=" + Policy.feeUrl(feeUrl, useTor);
            }
            Lndmobile.start(args, new Callback() {
                public void onResponse(byte[] ignored) { rpcReady = true; status = useTor ? "Node started · Tor" : onionPeers ? "Node started · direct connections and Tor onion peers" : "Node started · direct connection"; }
                public void onError(Exception error) { rpcReady = false; status = "Node could not start: " + error.getMessage(); }
            });
        } catch (Exception e) { status = e.getMessage(); }
    }
    public SyncProgress syncProgress() {
        String network = WalletProfiles.settings(this, profile).getString("network", "regtest");
        File log = new File(WalletProfiles.directory(this, profile, network), "logs/bitcoin/" + network + "/lnd.log");
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(log, "r")) {
            if (input.length() < syncLogOffset) syncLogOffset = 0;
            input.seek(syncLogOffset);
            String line;
            while ((line = input.readLine()) != null) sync.accept(line);
            syncLogOffset = input.getFilePointer();
        } catch (java.io.IOException ignored) { /* No progress log yet. */ }
        SyncProgress snapshot = new SyncProgress();
        snapshot.target = sync.target; snapshot.headers = sync.headers; snapshot.filters = sync.filters; snapshot.stage = sync.stage;
        return snapshot;
    }
    public void testTor() throws Exception { tor.start(this, message -> status = message); }
    public void testTorPeer(String endpoint) throws Exception {
        endpoint = Policy.onionEndpoint(endpoint);
        int socks = tor.start(this, message -> status = message);
        int split = endpoint.lastIndexOf(':');
        status = "Connecting to onion peer…";
        try (java.net.Socket socket = new java.net.Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", socks)))) {
            socket.connect(java.net.InetSocketAddress.createUnresolved(endpoint.substring(0, split), Integer.parseInt(endpoint.substring(split + 1))), 60000);
            status = "Onion peer reachable through Tor";
        }
    }
    public byte[] rpc(String method, MessageLite request) throws Exception {
        if (isRemote()) {
            if (remote == null) throw new Exception("Remote node is not connected. Check Remote Control settings.");
            return remote.rpc(method, request);
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Callback callback = new Callback() {
            public void onResponse(byte[] value) { result.complete(value == null ? new byte[0] : value); }
            public void onError(Exception e) { result.completeExceptionally(e); }
        };
        Lndmobile.class.getMethod(method, byte[].class, Callback.class).invoke(null, request.toByteArray(), callback);
        try { return result.get(45, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new Exception(e.getCause().getMessage(), e.getCause()); }
        catch (TimeoutException e) { throw new Exception("Node response timed out. Check status before trying again."); }
    }
    public Stateservice.WalletState walletState() throws Exception {
        if (isRemote()) return rpcReady ? Stateservice.WalletState.SERVER_ACTIVE : Stateservice.WalletState.WAITING_TO_START;
        return Stateservice.GetStateResponse.parseFrom(rpc("getState", Stateservice.GetStateRequest.getDefaultInstance())).getState();
    }
    public void unlock() throws Exception {
        String secret = new Vault(this, profile).get("password");
        if (secret == null) throw new Exception("Wallet password is unavailable on this device.");
        rpc("unlockWallet", Walletunlocker.UnlockWalletRequest.newBuilder()
                .setWalletPassword(ByteString.copyFromUtf8(secret)).build());
    }
    public void createWallet() throws Exception {
        if (walletState() != Stateservice.WalletState.NON_EXISTING) throw new Exception("A wallet already exists.");
        Vault vault = new Vault(this, profile);
        String words = vault.get("seed");
        if (words == null) {
            Walletunlocker.GenSeedResponse seed = Walletunlocker.GenSeedResponse.parseFrom(rpc("genSeed", Walletunlocker.GenSeedRequest.getDefaultInstance()));
            words = String.join(" ", seed.getCipherSeedMnemonicList());
            vault.put("seed", words);
        }
        String password = vault.get("password");
        if (password == null) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            password = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            Arrays.fill(bytes, (byte) 0); vault.put("password", password);
        }
        // Save secrets before initialization so process death never loses a newly funded wallet's seed.
        rpc("initWallet", Walletunlocker.InitWalletRequest.newBuilder().setWalletPassword(ByteString.copyFromUtf8(password))
                .addAllCipherSeedMnemonic(Arrays.asList(words.split(" "))).build());
    }
    public void checkDeletion() throws Exception {
        if (activeStreams.get() > 0) throw new Exception("Wait for the current operation to finish before deleting this wallet.");
        if (isRemote()) return; // Removing local credentials never touches the remote node.
        if (!rpcReady) throw new Exception("Wait for this wallet to start so its funds and channels can be checked.");
        Stateservice.WalletState state = walletState();
        if (state == Stateservice.WalletState.NON_EXISTING && new Vault(this, profile).get("seed") == null) return;
        if (state != Stateservice.WalletState.SERVER_ACTIVE) throw new Exception("Unlock and synchronize this wallet before deleting it.");
        lnrpc.LightningOuterClass.GetInfoResponse info = lnrpc.LightningOuterClass.GetInfoResponse.parseFrom(rpc("getInfo", lnrpc.LightningOuterClass.GetInfoRequest.getDefaultInstance()));
        if (!info.getSyncedToChain()) throw new Exception("Wait for chain synchronization before deleting this wallet, so its balance can be checked.");
        lnrpc.LightningOuterClass.WalletBalanceResponse funds = lnrpc.LightningOuterClass.WalletBalanceResponse.parseFrom(rpc("walletBalance", lnrpc.LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
        lnrpc.LightningOuterClass.ListChannelsResponse channels = lnrpc.LightningOuterClass.ListChannelsResponse.parseFrom(rpc("listChannels", lnrpc.LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
        lnrpc.LightningOuterClass.PendingChannelsResponse pending = lnrpc.LightningOuterClass.PendingChannelsResponse.parseFrom(rpc("pendingChannels", lnrpc.LightningOuterClass.PendingChannelsRequest.getDefaultInstance()));
        WalletDeletion.check(funds, channels, pending);
        lnrpc.LightningOuterClass.ListPaymentsResponse payments = lnrpc.LightningOuterClass.ListPaymentsResponse.parseFrom(rpc("listPayments", lnrpc.LightningOuterClass.ListPaymentsRequest.newBuilder().setIncludeIncomplete(true).build()));
        for (lnrpc.LightningOuterClass.Payment payment : payments.getPaymentsList())
            if (payment.getStatus() == lnrpc.LightningOuterClass.Payment.PaymentStatus.IN_FLIGHT || payment.getStatus() == lnrpc.LightningOuterClass.Payment.PaymentStatus.INITIATED)
                throw new Exception("A Lightning payment is still in progress. Wait for it to finish before deleting this wallet.");
    }
    public void stopForSwitch() throws Exception {
        if (activeStreams.get() > 0) throw new Exception("An operation is still awaiting a node response. Wait for it to finish before switching wallets.");
        if (isRemote()) { rpcReady = false; stopped = true; tor.close(this); return; }
        if (rpcReady && walletState() == Stateservice.WalletState.SERVER_ACTIVE) {
            lnrpc.LightningOuterClass.ListChannelsResponse channels = lnrpc.LightningOuterClass.ListChannelsResponse.parseFrom(rpc("listChannels", lnrpc.LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
            for (lnrpc.LightningOuterClass.Channel channel : channels.getChannelsList()) if (channel.getPendingHtlcsCount() > 0)
                throw new Exception("This wallet has a payment in flight. Wait for settlement before switching wallets.");
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        Lndmobile.stopRuntime(new Callback() {
            public void onResponse(byte[] value) { done.complete(null); }
            public void onError(Exception e) { done.completeExceptionally(e); }
        });
        try { done.get(65, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new Exception("Could not safely stop this wallet: " + e.getCause().getMessage()); }
        catch (TimeoutException e) { throw new Exception("Wallet shutdown is still pending. The selected wallet was not changed."); }
        rpcReady = false; stopped = true; tor.close(this);
    }
    public void sendPayment(byte[] request, lndmobile.RecvStream callback) {
        activeStreams.incrementAndGet();
        if (isRemote()) io.execute(() -> {
            try { callback.onResponse(remote.pay(routerrpc.RouterOuterClass.SendPaymentRequest.parseFrom(request)).toByteArray()); }
            catch (Exception e) { callback.onError(e); } finally { activeStreams.decrementAndGet(); }
        });
        else Lndmobile.routerSendPaymentV2(request, new lndmobile.RecvStream() {
            private boolean done;
            public void onResponse(byte[] data) {
                try { lnrpc.LightningOuterClass.Payment p = lnrpc.LightningOuterClass.Payment.parseFrom(data);
                    if (!done && (p.getStatus() == lnrpc.LightningOuterClass.Payment.PaymentStatus.SUCCEEDED || p.getStatus() == lnrpc.LightningOuterClass.Payment.PaymentStatus.FAILED)) { done = true; activeStreams.decrementAndGet(); }
                    callback.onResponse(data);
                } catch (Exception e) { onError(e); }
            }
            public void onError(Exception e) { if (!done) { done = true; activeStreams.decrementAndGet(); } callback.onError(e); }
        });
    }
    ChannelBackupFile.Export exportChannelBackup() throws Exception {
        if (isRemote()) {
            String id = remote.call("getinfo").getString("id");
            return ChannelBackupFile.clnExport(WalletProfiles.settings(this, profile).getString("network", "mainnet"), id,
                remote.call("staticbackup").getJSONArray("scb"));
        }
        lnrpc.LightningOuterClass.ChanBackupSnapshot snapshot = lnrpc.LightningOuterClass.ChanBackupSnapshot.parseFrom(
            rpc("exportAllChannelBackups", lnrpc.LightningOuterClass.ChanBackupExportRequest.getDefaultInstance()));
        byte[] data = snapshot.getMultiChanBackup().getMultiChanBackup().toByteArray();
        lnrpc.LightningOuterClass.VerifyChanBackupResponse checked = lnrpc.LightningOuterClass.VerifyChanBackupResponse.parseFrom(
            rpc("verifyChanBackup", ChannelBackupFile.lndSnapshot(data)));
        return new ChannelBackupFile.Export(data, checked.getChanPointsCount(), "backup");
    }
    String testChannelBackup(byte[] data) throws Exception {
        if (isRemote()) return ChannelBackupFile.testCln(data,
            WalletProfiles.settings(this, profile).getString("network", "mainnet"), remote.call("getinfo").getString("id"),
            remote.call("staticbackup").getJSONArray("scb"));
        lnrpc.LightningOuterClass.VerifyChanBackupResponse saved = lnrpc.LightningOuterClass.VerifyChanBackupResponse.parseFrom(
            rpc("verifyChanBackup", ChannelBackupFile.lndSnapshot(data)));
        lnrpc.LightningOuterClass.ChanBackupSnapshot current = lnrpc.LightningOuterClass.ChanBackupSnapshot.parseFrom(
            rpc("exportAllChannelBackups", lnrpc.LightningOuterClass.ChanBackupExportRequest.getDefaultInstance()));
        lnrpc.LightningOuterClass.VerifyChanBackupResponse live = lnrpc.LightningOuterClass.VerifyChanBackupResponse.parseFrom(
            rpc("verifyChanBackup", ChannelBackupFile.lndSnapshot(current.getMultiChanBackup().getMultiChanBackup().toByteArray())));
        return "Backup decrypted and passed integrity checks for this wallet.\n"
            + ChannelBackupFile.coverage(saved.getChanPointsList(), live.getChanPointsList())
            + "\nThis does not test peer availability or guarantee recovery. No recovery was attempted and no channels were closed.";
    }
    byte[] signReplacement(ReplacementTransaction.Plan plan) throws Exception {
        if (isRemote()) return remote.signReplacement(plan);
        walletrpc.FeeBumpRpc.FinalizePsbtResponse signed = walletrpc.FeeBumpRpc.FinalizePsbtResponse.parseFrom(
            rpc("walletKitFinalizePsbt", walletrpc.FeeBumpRpc.FinalizePsbtRequest.newBuilder()
                .setFundedPsbt(ByteString.copyFrom(plan.psbt)).build()));
        byte[] raw = signed.getRawFinalTx().toByteArray();
        plan.verifySigned(raw); return raw;
    }
    String publishReplacement(byte[] signed, ReplacementTransaction.Plan plan) throws Exception {
        String expected = ReplacementTransaction.txid(plan.unsigned);
        if (isRemote()) {
            String actual = remote.publishReplacement(signed);
            if (!actual.equals(expected)) throw new Exception("Node returned an unexpected replacement transaction ID. Check history before retrying.");
        } else {
            plan.verifySigned(signed);
            walletrpc.FeeBumpRpc.PublishResponse result = walletrpc.FeeBumpRpc.PublishResponse.parseFrom(
                rpc("walletKitPublishTransaction", walletrpc.FeeBumpRpc.Transaction.newBuilder()
                    .setTxHex(ByteString.copyFrom(signed)).setLabel("RBF replacement of " + plan.source.txid).build()));
            if (!result.getPublishError().isEmpty()) throw new Exception(result.getPublishError());
        }
        return expected;
    }
    public void closeChannel(byte[] request, lndmobile.RecvStream callback) {
        if (isRemote()) {
            activeStreams.incrementAndGet();
            io.execute(() -> { try { callback.onResponse(remote.close(lnrpc.LightningOuterClass.CloseChannelRequest.parseFrom(request)).toByteArray()); }
                catch (Exception e) { callback.onError(e); } finally { activeStreams.decrementAndGet(); } });
        } else Lndmobile.closeChannel(request, callback);
    }
    @Override public void onDestroy() {
        // Android may kill a service without this callback. LND's database remains the source of truth.
        io.execute(() -> { try { if (!stopped && !isRemote()) rpc("stopDaemon", lnrpc.LightningOuterClass.StopRequest.getDefaultInstance()); } catch (Exception ignored) {} finally { tor.close(this); } });
        io.shutdown(); super.onDestroy();
    }
}
