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
    private CountDownLatch connected = new CountDownLatch(1);
    private android.net.LocalSocket recoverySocket;
    private java.io.BufferedReader recoveryInput;
    private java.io.Writer recoveryOutput;
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
            connected = new CountDownLatch(1);
            try (FileOutputStream file = new FileOutputStream(TorService.getTorrc(context))) {
                file.write(("ClientOnly 1\nAvoidDiskWrites 1\nSocksPort 127.0.0.1:auto IsolateSOCKSAuth\nHTTPTunnelPort 127.0.0.1:auto\n").getBytes(StandardCharsets.UTF_8));
            }
            bound = context.bindService(new Intent(context, TorService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new Exception("Could not start Tor.");
        }
        if (!connected.await(15, TimeUnit.SECONDS)) throw new Exception("Tor service did not start. Restart the app to retry.");
        long began = android.os.SystemClock.elapsedRealtime();
        long deadline = began + 180000;
        String controlError = null;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            TorService tor = service;
            if (tor == null) throw new Exception("Tor stopped. Restart the app to retry.");
            // tor-android 0.4.9.14 connects once on the socket CREATE event,
            // which can precede listen(). Recover that failed control connection
            // without restarting Tor, discarding state, or bypassing its proxy.
            if (tor.getTorControlConnection() == null && recoverySocket == null
                    && android.os.SystemClock.elapsedRealtime() - began >= 10000) {
                status.accept("Connecting to Tor · retrying local control connection…");
                try { connectRecoveryControl(context); }
                catch (java.io.IOException e) { controlError = e.getMessage(); }
            }
            if (tor.getTorControlConnection() != null || recoverySocket != null) {
                String phase = info(tor, "status/bootstrap-phase");
                if (phase != null) {
                    java.util.regex.Matcher progress = java.util.regex.Pattern.compile("PROGRESS=(\\d+)").matcher(phase);
                    if (progress.find()) status.accept("Connecting to Tor · " + progress.group(1) + "%");
                }
                if ("1".equals(info(tor, "status/circuit-established"))) {
                    int socks = listenerPort(info(tor, "net/listeners/socks"));
                    int http = listenerPort(info(tor, "net/listeners/httptunnel"));
                    if (socks <= 0 || http <= 0) throw new java.io.IOException("Tor proxy listeners are unavailable");
                    httpPort = http; socksPort = socks;
                    status.accept("Tor connected"); return socks;
                }
            }
            Thread.sleep(500);
        }
        throw new Exception("Tor has not connected after 3 minutes. Check your connection and retry; no direct connection was used." + (controlError == null ? "" : " Local control: " + controlError));
    }
    private void connectRecoveryControl(Context context) throws java.io.IOException {
        if (recoverySocket != null) return;
        android.net.LocalSocket socket = new android.net.LocalSocket();
        try {
            socket.connect(new android.net.LocalSocketAddress(new java.io.File(
                    context.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket").getAbsolutePath(),
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(5000);
            recoveryInput = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            recoveryOutput = new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            recoveryCommand("AUTHENTICATE", null);
            recoverySocket = socket;
        } catch (java.io.IOException e) { socket.close(); throw e; }
    }
    private String recoveryCommand(String command, String key) throws java.io.IOException {
        recoveryOutput.write(command + "\r\n"); recoveryOutput.flush();
        String value = null;
        for (int i = 0; i < 32; i++) {
            String line = recoveryInput.readLine();
            if (line == null || !line.startsWith("250") || line.length() < 4)
                throw new java.io.IOException("Tor local control rejected " + command.split(" ")[0]);
            if (key != null && line.substring(4).startsWith(key + "=")) value = line.substring(5 + key.length());
            if (line.charAt(3) == ' ') return value;
            if (line.charAt(3) != '-') throw new java.io.IOException("Unexpected Tor control response");
        }
        throw new java.io.IOException("Tor control response exceeded limit");
    }
    private String info(TorService tor, String key) throws java.io.IOException {
        return recoverySocket == null ? tor.getInfo(key) : recoveryCommand("GETINFO " + key, key);
    }
    static int listenerPort(String value) throws java.io.IOException {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\"127\\.0\\.0\\.1:(\\d+)\"").matcher(value == null ? "" : value);
        if (!match.find()) throw new java.io.IOException("Tor did not return a loopback proxy listener");
        int port = Integer.parseInt(match.group(1));
        if (port < 1 || port > 65535) throw new java.io.IOException("Invalid Tor proxy port");
        return port;
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
        if (recoverySocket != null) {
            try { recoveryCommand("SIGNAL SHUTDOWN", null); } catch (java.io.IOException ignored) { }
            try { recoverySocket.close(); } catch (java.io.IOException ignored) { }
            recoveryInput = null; recoveryOutput = null; recoverySocket = null;
        }
        if (bound) { context.unbindService(connection); bound = false; }
        service = null;
    }
}
