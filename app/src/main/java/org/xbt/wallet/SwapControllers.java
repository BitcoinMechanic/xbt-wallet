package org.xbt.wallet;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Controller discovery and scoped bidirectional swap requests. Never submits a wallet payment. */
final class SwapControllers {
    static final int LIMIT = 65536;
    static final String INFO = "/v1/provider/info", DIRECTORY = "/v1/providers";
    static final class Provider {
        final String name, identity, network;
        final List<String> endpoints;
        Provider(JSONObject json, String expectedNetwork) throws Exception {
            if (!"xbt-swap-provider-v1".equals(json.getString("format"))) throw new Exception("Unsupported controller discovery protocol.");
            name = json.getString("name"); identity = json.getString("provider"); network = json.getString("environment");
            if (name.trim().isEmpty() || name.length() > 80 || name.matches("(?s).*[\\p{Cntrl}].*")
                || !identity.matches("(02|03)[0-9a-f]{64}")) throw new Exception("Controller returned invalid details.");
            if (!network.equals(expectedNetwork)) throw new Exception("Controller is on a different network.");
            JSONArray urls = json.getJSONArray("endpoints");
            if (urls.length() < 1 || urls.length() > 4) throw new Exception("Controller returned invalid addresses.");
            endpoints = new ArrayList<>();
            for (int i = 0; i < urls.length(); i++) {
                String url = ControllerAddress.origin(urls.getString(i));
                if (!endpoints.contains(url)) endpoints.add(url);
            }
            JSONArray pairs = json.getJSONArray("pairs");
            boolean pair = false;
            for (int i=0; i<pairs.length(); i++) if ("XBT/BTC".equals(pairs.getString(i))) pair = true;
            if (!pair) throw new Exception("Controller does not advertise XBT/BTC swaps.");
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("format", "xbt-swap-provider-v1").put("name", name).put("provider", identity)
                .put("environment", network).put("endpoints", new JSONArray(endpoints)).put("pairs", new JSONArray().put("XBT/BTC"));
        }
    }
    static Provider inspect(String address, String network, String expectedIdentity, boolean useTor) throws Exception {
        String origin = ControllerAddress.origin(address);
        Provider p = new Provider(get(origin, INFO, useTor), network);
        if (!p.endpoints.contains(origin)) throw new Exception("Controller does not advertise this address.");
        if (expectedIdentity != null && !expectedIdentity.isEmpty() && !expectedIdentity.equals(p.identity))
            throw new Exception("Controller identity changed. The saved controller was not replaced.");
        return p;
    }
    static List<Provider> search(String address, String network, boolean useTor) throws Exception {
        JSONObject json = get(ControllerAddress.origin(address), DIRECTORY, useTor);
        if (!"xbt-swap-directory-v1".equals(json.getString("format"))) throw new Exception("Unsupported controller directory.");
        JSONArray rows = json.getJSONArray("providers");
        if (rows.length() > 100) throw new Exception("Controller directory is too large.");
        List<Provider> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!network.equals(row.optString("environment"))) continue;
            Provider p = new Provider(row, network);
            if (seen.add(p.identity)) result.add(p);
        }
        return result;
    }
    static JSONObject receive(String origin, String token, JSONObject request, boolean useTor) throws Exception {
        return walletRequest(origin, token, request, useTor, "/v1/receive");
    }
    static JSONObject send(String origin, String token, JSONObject request, boolean useTor) throws Exception {
        return walletRequest(origin, token, request, useTor, "/v1/send");
    }
    private static JSONObject walletRequest(String origin, String token, JSONObject request, boolean useTor, String path) throws Exception {
        if (!token.matches("[a-f0-9]{64}")) throw new Exception("Configure this controller’s Wallet Swap Access key first.");
        JSONObject response = request(ControllerAddress.origin(origin), path, useTor, request, token);
        return walletResponse(response);
    }
    static JSONObject walletResponse(JSONObject response) throws Exception {
        if (response.has("error")) {
            String code = response.getString("error");
            String message = switch (code) {
                case "recipient_invoice_timing" -> "The recipient invoice is expired, too close to expiry, or has a future creation time. Create a fresh invoice; if this persists, check the node clocks. No new swap was created.";
                case "recipient_invoice_expiring" -> "The recipient invoice expires too soon. A new swap needs a 10-minute payment window plus 3 minutes of safety margin. Create a fresh invoice. No new swap was created.";
                case "contract_expiry_invalid" -> "The controller rejected inconsistent invoice or quote timestamps. No new swap was created.";
                case "service_swap_resolving" -> "The swap is already resolving. Follow its status until cleanup completes.";
                case "service_payment_already_started" -> "The outgoing payment may already have started. This swap cannot be cancelled; check its status until settlement or recovery completes.";
                case "service_cancel_not_ready" -> "The controller is still preparing the swap. Check status, then cancel once setup finishes.";
                case "service_swap_already_finished" -> "This swap has already finished. Refresh its status.";
                case "service_approved_swap_cannot_cancel", "wallet_request_invalid" -> "The controller refused this operation. If you requested cancellation, update the controller to a version that supports cancelling issued swap invoices, then check status.";
                case "grant_closed" -> "The node grant expired, was paused, or was replaced. Wait for the controller to refresh its node pairing, then check this request.";
                case "channel_requires_recovery", "previous_enrollment_requires_recovery" -> "The coordinator must resolve an earlier channel or swap before accepting this request.";
                case "route_outside_approved_channels", "approved_channel_changed" -> "The route uses a channel outside the current node grant. The controller operator must refresh its approved channels.";
                case "wallet_send_not_found" -> "The controller has not recorded this outgoing swap. Retry the quote using the same recipient invoice.";
                case "wallet_quote_changed" -> "The controller did not accept the exact reviewed quote. No payment was submitted.";
                case "wallet_receive_not_found" -> "The controller has not recorded this request. Use Retry request to resume with the same invoice.";
                case "service_previous_swap_active" -> "This controller is still handling another swap in this direction. Wait for it to finish.";
                case "market_unavailable", "market_data_invalid" -> "The controller could not obtain valid, fresh Neoxa pricing. No new quote was approved.";
                case "recipient_invoice_limits" -> "The node rejected the recipient invoice limits. Updated node builds accept positive recipient invoices up to 10,000,000 XBT sats or 1,000,000 BTC sats; controller pricing and grant limits may be lower. Older builds may still enforce the 1,000,000-sat XBT limit. Older builds also use this code for an invoice timelock above 40 blocks. If the amount is within range, check the node version and decoded invoice; this code alone does not identify the failed limit.";
                case "market_amount_limit" -> "This swap exceeds the controller’s quote limits (up to 10,000,000 XBT sats and 100,000 BTC sats, including applicable costs). Use a smaller invoice.";
                case "market_depth_unavailable" -> "Neoxa’s order book has insufficient depth within the controller’s price limits for this amount.";
                case "service_review_superseded" -> "The controller’s node pairing changed since this quote was created. Cancel the unapproved quote and request a fresh invoice.";
                case "service_pairing_required" -> "The controller has not paired its Lightning nodes.";
                case "service_history_full" -> "The controller’s swap history is full. Its operator must resolve this.";
                case "incoming_amount_below_safe_minimum" -> "The incoming swap amount is below the channel’s safe payment minimum. Use a larger recipient invoice.";
                case "recipient_route_unknown" -> "The recipient is unknown to the routing graph, and this invoice contains no private-channel route hints. Ask the recipient for an invoice advertising its receiving channel and any private upstream hops.";
                case "route_hint_entry_unknown" -> "The invoice contains route hints, but their entry nodes are unknown to the routing graph. A private upstream hop may be missing. Ask the recipient for a complete route hint from a publicly reachable node.";
                case "route_hint_path_unavailable" -> "The invoice contains route hints, but the coordinator could not find a route to any advertised entry node. Check the upstream path and liquidity; this does not establish that a fee limit was exceeded.";
                case "route_hint_fee_limit" -> "Every advertised route hint alone requires more forwarding fees than the saved routing-fee cap. Check the reported cap and invoice hint fees.";
                case "route_hint_delay_limit" -> "Every advertised route hint exceeds the saved timelock limit before reaching its entry node. Check the invoice timelocks and reported limit.";
                case "route_hint_hop_limit" -> "Every advertised route hint leaves no room for a path to its entry node within the saved hop limit.";
                case "bounded_route_unavailable" -> "No usable route was found. This result alone does not identify a fee limit, missing private hop or liquidity problem. Check the route-hint diagnostics below.";
                default -> "Controller refused the swap request.";
            };
            String details = code.matches("[a-z_]{1,80}") ? "\nCode: " + code : "";
            JSONObject diagnostic = response.optJSONObject("diagnostic");
            if (diagnostic != null) for (String field : new String[]{"node", "role", "operation", "stage", "http_status", "rpc_code", "reference", "amount_msat", "minimum_msat", "maximum_msat", "route_hint_count", "max_fee_msat", "max_delay_blocks", "max_hops", "route_hint_entry_unknown", "route_hint_path_unavailable", "route_hint_fee_limit", "route_hint_delay_limit", "route_hint_hop_limit"}) {
                String value = diagnostic.optString(field, "");
                if (value.matches("[a-zA-Z0-9_.-]{1,80}")) details += "\n" + field + ": " + value;
            }
            throw new Exception(message + details);
        }
        return response.getJSONObject("result");
    }
    private static JSONObject get(String origin, String path, boolean useTor) throws Exception {
        return request(origin, path, useTor, null, "");
    }
    private static JSONObject request(String origin, String path, boolean useTor, JSONObject payload, String token) throws Exception {
        URI uri = URI.create(origin + path);
        boolean onion = ControllerAddress.onion(origin);
        if (onion && !useTor) throw new Exception("Tor is required for this controller.");
        byte[] body;
        if ("http".equals(uri.getScheme())) {
            body = onionRequest(uri, TorTransport.socksProxy(), payload, token);
        } else {
            HttpURLConnection connection = (HttpURLConnection) (useTor ? uri.toURL().openConnection(TorTransport.httpProxy()) : uri.toURL().openConnection());
            connection.setConnectTimeout(useTor ? 30000 : 10000); connection.setReadTimeout(useTor ? 30000 : 10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "XBTWallet/0.1");
            if (payload != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            try {
                if (connection.getResponseCode()==401) throw new Exception("Controller access denied. Check Wallet Swap Access and the saved key.");
                if (connection.getResponseCode() != 200) throw new Exception("Controller unavailable (HTTP " + connection.getResponseCode() + ").");
                String type = connection.getContentType();
                if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new Exception("Controller did not return JSON.");
                try (InputStream input = connection.getInputStream()) { body = bounded(input, LIMIT); }
            } finally { connection.disconnect(); }
        }
        try { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
        catch (JSONException e) { throw new Exception("Controller returned invalid JSON."); }
    }
    static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (result.size() + count > limit) throw new IOException("Controller response is too large.");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
    // HTTP onion traffic goes only through SOCKS with unresolved DNS. Android's
    // cleartext policy stays disabled; no direct socket or TLS bypass is used.
    static byte[] onionRequest(URI uri, Proxy proxy) throws Exception { return onionRequest(uri, proxy, null, ""); }
    static byte[] onionRequest(URI uri, Proxy proxy, JSONObject payload, String token) throws Exception {
        if (!ControllerAddress.onion(ControllerAddress.origin("http://" + uri.getRawAuthority())) || proxy.type() != Proxy.Type.SOCKS)
            throw new Exception("Onion transport requires Tor.");
        String stage = "connect";
        try (Socket socket = new Socket(proxy)) {
            socket.connect(InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort() < 0 ? 80 : uri.getPort()), 30000);
            socket.setSoTimeout(30000);
            byte[] bytes = payload == null ? new byte[0] : payload.toString().getBytes(StandardCharsets.UTF_8);
            if (payload != null && !token.matches("[a-f0-9]{64}")) throw new Exception("Invalid receive access key.");
            String request = (payload == null ? "GET " : "POST ") + uri.getRawPath() + " HTTP/1.1\r\nHost: " + uri.getRawAuthority()
                + "\r\nAccept: application/json\r\nUser-Agent: XBTWallet/0.1\r\nConnection: close\r\n"
                + (payload == null ? "" : "Authorization: Bearer " + token + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n") + "\r\n";
            stage = "write_request";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
            stage = "read_response";
            byte[] all = bounded(socket.getInputStream(), LIMIT + 8192);
            String raw = new String(all, StandardCharsets.ISO_8859_1); int split = raw.indexOf("\r\n\r\n");
            if (split < 0 || split > 8192) throw new Exception("Invalid controller HTTP response.");
            String[] headers = raw.substring(0, split).split("\r\n");
            if (headers[0].contains(" 401 ")) throw new Exception("Controller access denied. Check Wallet Swap Access and the saved key.");
            if (!headers[0].matches("HTTP/1\\.[01] 200(?: .*)?")) throw new Exception("Controller unavailable through Tor.");
            Map<String, String> fields = new HashMap<>();
            for (int i=1; i<headers.length; i++) {
                int colon = headers[i].indexOf(':'); if (colon < 1) throw new Exception("Invalid response headers.");
                String key = headers[i].substring(0, colon).toLowerCase(Locale.ROOT);
                if (fields.put(key, headers[i].substring(colon+1).trim()) != null) throw new Exception("Duplicate response headers.");
            }
            if (!fields.getOrDefault("content-type", "").split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new Exception("Controller did not return JSON.");
            byte[] body = Arrays.copyOfRange(all, split+4, all.length);
            String transfer = fields.get("transfer-encoding");
            if (transfer != null) {
                if (!transfer.equalsIgnoreCase("chunked") || fields.containsKey("content-length")) throw new Exception("Invalid response framing.");
                ByteArrayInputStream input = new ByteArrayInputStream(body); ByteArrayOutputStream output = new ByteArrayOutputStream();
                while (true) {
                    StringBuilder line = new StringBuilder(); int b;
                    while ((b=input.read()) != -1 && b != '\n') { if (line.length() > 80) throw new Exception("Invalid chunk."); line.append((char)b); }
                    if (b == -1) throw new Exception("Incomplete controller response.");
                    int size = Integer.parseInt(line.toString().trim().split(";",2)[0],16);
                    if (size == 0) break;
                    if (size < 0 || size > LIMIT-output.size()) throw new Exception("Controller response is too large.");
                    byte[] chunk = new byte[size]; if (input.read(chunk) != size || input.read() != '\r' || input.read() != '\n') throw new Exception("Incomplete controller response.");
                    output.write(chunk);
                }
                body = output.toByteArray();
            } else if (fields.containsKey("content-length") && Long.parseLong(fields.get("content-length")) != body.length) throw new Exception("Incomplete controller response.");
            if (body.length > LIMIT) throw new Exception("Controller response is too large.");
            return body;
        } catch (SocketTimeoutException e) {
            throw new IOException((stage.equals("connect")
                ? "Tor connection to the controller timed out before this request was sent. Check status before retrying the saved request."
                : "The controller did not finish replying over Tor. It may have accepted the request. Check status before retrying; do not repeat a payment.")
                + "\nCode: controller_timeout\nStage: " + stage, e);
        } catch (SocketException e) {
            throw new IOException((stage.equals("connect")
                ? "Could not connect to the controller through Tor. This request was not sent. Check the controller connection and retry the saved request."
                : "The Tor connection failed while exchanging the request. The controller may have accepted it. Check status before retrying; do not repeat a payment.")
                + "\nCode: controller_connection_failed\nStage: " + stage, e);
        }
    }
}
