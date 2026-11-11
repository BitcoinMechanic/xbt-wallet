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
    public volatile String status = "Starting your XBT node…";
    public volatile boolean rpcReady;
    private boolean started;
    private final SyncProgress sync = new SyncProgress();
    private long syncLogOffset;
    private final TorTransport tor = new TorTransport();
    public final class LocalBinder extends Binder { NodeService service() { return NodeService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public void onCreate() {
        super.onCreate();
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
            SharedPreferences prefs = getSharedPreferences("wallet", 0);
            // Network cannot be changed once secrets are created. Separate directory adds a second boundary.
            String network = prefs.getString("network", "regtest");
            String configuredPeer = prefs.getString("syncPeer", network.equals("mainnet") ? "" : "10.0.2.2:19444").trim();
            String peer = configuredPeer.isEmpty() && network.equals("mainnet") ? "" : Policy.endpoint(configuredPeer);
            if (!network.equals("regtest") && !network.equals("mainnet")) throw new Exception("Unsupported network");
            File directory = new File(getFilesDir(), "node-" + network);
            if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create node directory");
            String args = "--lnddir=" + directory.getAbsolutePath() + " --bitcoin.active --bitcoin." + network
                    + " --bitcoin.node=neutrino" + (peer.isEmpty() ? "" : " --neutrino.connect=" + peer)
                    + " --nolisten --norest --rpclisten=127.0.0.1:0 --tlsdisableautofill";
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
        String network = getSharedPreferences("wallet", 0).getString("network", "regtest");
        File log = new File(getFilesDir(), "node-" + network + "/logs/bitcoin/" + network + "/lnd.log");
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
        return Stateservice.GetStateResponse.parseFrom(rpc("getState", Stateservice.GetStateRequest.getDefaultInstance())).getState();
    }
    public void unlock() throws Exception {
        String secret = new Vault(this).get("password");
        if (secret == null) throw new Exception("Wallet password is unavailable on this device.");
        rpc("unlockWallet", Walletunlocker.UnlockWalletRequest.newBuilder()
                .setWalletPassword(ByteString.copyFromUtf8(secret)).build());
    }
    public void createWallet() throws Exception {
        if (walletState() != Stateservice.WalletState.NON_EXISTING) throw new Exception("A wallet already exists.");
        Vault vault = new Vault(this);
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
    @Override public void onDestroy() {
        // Android may kill a service without this callback. LND's database remains the source of truth.
        io.execute(() -> { try { rpc("stopDaemon", lnrpc.LightningOuterClass.StopRequest.getDefaultInstance()); } catch (Exception ignored) {} finally { tor.close(this); } });
        io.shutdown(); super.onDestroy();
    }
}
