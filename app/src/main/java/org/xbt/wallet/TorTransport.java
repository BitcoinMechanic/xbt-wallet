package org.xbt.wallet;

import android.content.*;
import android.os.IBinder;
import org.torproject.jni.TorService;
import java.io.FileOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** App-owned Tor client. No external proxy and no direct fallback. */
final class TorTransport {
    private volatile TorService service;
    private boolean bound;
    private static volatile int httpPort;
    private static volatile int socksPort;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TorService.LocalBinder) binder).getService(); connected.countDown();
        }
        public void onServiceDisconnected(ComponentName name) { service = null; httpPort = 0; socksPort = 0; }
    };
    synchronized int start(Context context, Consumer<String> status) throws Exception {
        context = context.getApplicationContext();
        status.accept("Connecting to Tor…");
        if (!bound) {
            try (FileOutputStream file = new FileOutputStream(TorService.getTorrc(context))) {
                file.write(("ClientOnly 1\nAvoidDiskWrites 1\nSocksPort 127.0.0.1:auto IsolateSOCKSAuth\nHTTPTunnelPort 127.0.0.1:auto\n").getBytes(StandardCharsets.UTF_8));
            }
            bound = context.bindService(new Intent(context, TorService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new Exception("Could not start Tor.");
        }
        if (!connected.await(15, TimeUnit.SECONDS)) throw new Exception("Tor service did not start. Restart the app to retry.");
        long deadline = android.os.SystemClock.elapsedRealtime() + 180000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            TorService tor = service;
            if (tor == null) throw new Exception("Tor stopped. Restart the app to retry.");
            if (tor.getTorControlConnection() != null) {
                String phase = tor.getInfo("status/bootstrap-phase");
                if (phase != null) {
                    java.util.regex.Matcher progress = java.util.regex.Pattern.compile("PROGRESS=(\\d+)").matcher(phase);
                    if (progress.find()) status.accept("Connecting to Tor · " + progress.group(1) + "%");
                }
                if ("1".equals(tor.getInfo("status/circuit-established")) && tor.getSocksPort() > 0) {
                    httpPort = tor.getHttpTunnelPort(); socksPort = tor.getSocksPort();
                    status.accept("Tor connected"); return tor.getSocksPort();
                }
            }
            Thread.sleep(500);
        }
        throw new Exception("Tor has not connected after 3 minutes. Check your connection and retry; no direct connection was used.");
    }
    static Proxy socksProxy() throws Exception {
        if (socksPort <= 0) throw new Exception("Tor is not ready");
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
    }
    static Proxy httpProxy() throws Exception {
        int port = httpPort;
        if (port <= 0) throw new Exception("Tor is not ready");
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }
    synchronized void close(Context context) {
        context = context.getApplicationContext();
        httpPort = 0; socksPort = 0;
        if (bound) { context.unbindService(connection); bound = false; }
    }
}
