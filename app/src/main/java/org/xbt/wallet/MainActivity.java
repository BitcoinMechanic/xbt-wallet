package org.xbt.wallet;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import com.google.protobuf.ByteString;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

import lnrpc.LightningOuterClass;
import lnrpc.Stateservice;
import java.util.*;

/** Native alpha: all balances and operations come from the embedded XBT node. */
public final class MainActivity extends Activity {
    private static final float UI_SCALE = 0.75f;
    @Override protected void attachBaseContext(Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.densityDpi = Math.round(config.densityDpi * UI_SCALE);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
    private LinearLayout page;
    private View walletMenuAnchor;
    private PopupMenu walletPopup;
    private NodeService node;
    private boolean bound, busy;
    private String screen = "";
    private int historyTab;
    private final boolean[] homeActionsExpanded = new boolean[2];
    private final LinearLayout[] homeActionsViews = new LinearLayout[2];
    private final android.animation.ValueAnimator[] homeActionsAnimation = new android.animation.ValueAnimator[2];
    private final Button[] balanceAmounts = new Button[2];
    private final long[] balanceSats = new long[2];
    private boolean ratesLoading;
    private long lastRatesAttempt;
    private org.json.JSONObject fiatRates;
    private ByteString receivingInvoiceHash;
    private EditText scanTarget;
    private String receivingAddress;
    private Set<String> receiveBaseline = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private final Runnable poll = new Runnable() {
        public void run() {
            if (node != null && !busy && (screen.equals("Home") || screen.equals("Wallet is starting"))) home();
            if (node != null && !busy && screen.equals("Receive XBT")) checkDeposit();
            if (node != null && !busy && screen.equals("Lightning invoice")) checkLightningReceipt();
            handler.postDelayed(this, 5000);
        }
    };
    @Override protected void onResume() { super.onResume(); handler.postDelayed(poll, 5000); }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    private interface Job { void run() throws Exception; }
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { node = ((NodeService.LocalBinder) binder).service(); home(); }
        public void onServiceDisconnected(ComponentName name) { node = null; }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = getSharedPreferences("wallet", 0);
        if (BuildConfig.MAINNET_TEST && !prefs.contains("network")) {
            prefs.edit().putString("network", "mainnet").putString("mode", "easy").commit();
        }
        if (BuildConfig.MAINNET_TEST && !prefs.getBoolean("dnsDiscoveryConfigured", false)) {
            prefs.edit().remove("syncPeer").putBoolean("useTor", false)
                .putString("feeUrl", "https://mempool.guide/api/v1/fees/precise")
                .putBoolean("dnsDiscoveryConfigured", true).commit();
        }
        if (prefs.getString("mode", "").equals("easy")) start(); else modes();
    }
    private void page(String title) {
        page(title, false);
    }
    private void page(String title, boolean sensitive) {
        // Allow remote UI testing only in debug builds on the valueless test chain.
        boolean mirrorable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && prefs.getString("network", "regtest").equals("regtest") && !sensitive;
        if (mirrorable) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (walletPopup != null) walletPopup.dismiss();
        walletMenuAnchor = null;
        screen = title;
        ScrollView scroll = new ScrollView(this); page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.setBackgroundColor(0xff101517); scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(page); setContentView(scroll);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this); brand.setText("XBT"); brand.setTextSize(25);
        brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); brand.setTextColor(0xffa8efd5);
        brand.setLetterSpacing(0.08f);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1)); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView network = new TextView(this);
        network.setText("ALPHA · " + prefs.getString("network", "regtest").toUpperCase(Locale.ROOT));
        network.setTextSize(10); network.setLetterSpacing(0.08f); network.setTextColor(0xff9aaea6);
        network.setPadding(dp(10), dp(6), dp(10), dp(6)); network.setBackground(surface(0xff1b2925, 20, 0xff2d4038));
        header.addView(network);
        if (title.equals("Home")) {
            Button menu = new Button(this); styleButton(menu, false);
            menu.setText("⋮"); menu.setTextSize(25); menu.setPadding(0, 0, 0, 0);
            menu.setContentDescription("Wallet menu");
            LinearLayout.LayoutParams menuSize = new LinearLayout.LayoutParams(dp(48), dp(48)); menuSize.leftMargin = dp(10);
            header.addView(menu, menuSize); walletMenuAnchor = menu; menu.setOnClickListener(v -> more());
        }
        LinearLayout.LayoutParams headerSize = new LinearLayout.LayoutParams(-1, -2); headerSize.bottomMargin = dp(24);
        page.addView(header, headerSize);
        if (!title.equals("Home")) {
            TextView heading = text(title); heading.setTextSize(28); heading.setTextColor(0xfff3f7f5);
            heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            heading.setPadding(0, 0, 0, dp(20));
        }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private android.graphics.drawable.GradientDrawable surface(int color, int radius, int border) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }
    private void styleButton(Button button, boolean accent) {
        button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{0xff65716c, accent ? 0xff101b16 : 0xffe5eee9}));
        button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(14), dp(12), dp(14), dp(12)); button.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(0xff19201d, 14, 0xff252f2a));
        states.addState(new int[]{}, surface(accent ? 0xffa8efd5 : 0xff1d2723, 14, accent ? 0 : 0xff304037));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x336de8b1), states, null));
    }
    private TextView text(String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(15);
        view.setTextColor(0xffafbeb6); view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(8), 0, dp(12)); page.addView(view); return view;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label);
        boolean accent = label.startsWith("Review") || label.startsWith("Create") || label.equals("Send") || label.equals("Save") || label.equals("Check backup");
        styleButton(b, accent);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(5); params.bottomMargin = dp(5);
        page.addView(b, params); b.setOnClickListener(v -> { if (!busy) action.run(); }); return b;
    }
    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setTextSize(16);
        e.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}}, new int[]{0xff6c7c73,0xffeef5f0}));
        e.setHintTextColor(0xff8d9f94); e.setPadding(dp(14), dp(14), dp(14), dp(14)); e.setMinimumHeight(dp(54));
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, surface(0xff151d18, 12, 0xff202e25));
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff19251e, 12, 0xffa8efd5));
        background.addState(new int[]{}, surface(0xff151e19, 12, 0xff35453b)); e.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(4); params.bottomMargin = dp(10);
        page.addView(e, params); return e;
    }
    private EditText labelField(String value) {
        EditText label = field("Label (optional)", value);
        label.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        return label;
    }
    private String historyLabel(String value) { return value.isEmpty() ? "" : "Label: " + value + "\n"; }
    private String transactionHistoryLabel(LightningOuterClass.Transaction tx) {
        String saved = prefs.getString("tx-label-" + tx.getTxHash(), "");
        if (prefs.contains("tx-label-" + tx.getTxHash())) return saved;
        if (tx.getAmount() > 0) {
            Set<String> labels = new LinkedHashSet<>();
            for (LightningOuterClass.OutputDetail output : tx.getOutputDetailsList()) {
                if (!output.getIsOurAddress()) continue;
                String label = prefs.getString("utxo-label-" + tx.getTxHash() + ":" + output.getOutputIndex(), "");
                if (!label.isEmpty()) labels.add(label);
            }
            if (!labels.isEmpty()) return String.join(" · ", labels);
        }
        return tx.getLabel();
    }
    private void labelReceivedTransactions(LightningOuterClass.TransactionDetails transactions) {
        String address = prefs.getString("receive-label-address", "");
        String label = prefs.getString("receive-label", "");
        if (address.isEmpty() || label.isEmpty()) return;
        Set<String> baseline = prefs.getStringSet("receive-label-baseline", Collections.emptySet());
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) {
            if (baseline.contains(tx.getTxHash()) || tx.getNumConfirmations() < 0 || tx.getAmount() <= 0) continue;
            for (LightningOuterClass.OutputDetail output : tx.getOutputDetailsList()) {
                if (!output.getIsOurAddress() || output.getAmount() <= 0 || !address.equals(output.getAddress())) continue;
                String txKey = "tx-label-" + tx.getTxHash();
                String coinKey = "utxo-label-" + tx.getTxHash() + ":" + output.getOutputIndex();
                if (!prefs.contains(txKey)) edit.putString(txKey, label);
                if (!prefs.contains(coinKey)) edit.putString(coinKey, label);
            }
        }
        edit.apply();
    }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("Could not complete action").setMessage(e.getMessage()).setPositiveButton("OK", null).show(); }
    private void work(Job job) {
        if (busy || node == null) return;
        busy = true;
        node.io.execute(() -> { try { job.run(); } catch (Exception e) { runOnUiThread(() -> error(e)); }
            finally { runOnUiThread(() -> busy = false); } });
    }
    private void modes() {
        page("Your Lightning wallet");
        text("Easy Mode starts an XBT Lightning node on this phone. This alpha uses a test network by default.");
        button("Easy Mode", this::setup);
        Button hard = button("Hard Mode · coming later", () -> {}); hard.setEnabled(false);
    }
    private void setup() {
        page("Connect your XBT node");
        text("Enter an XBT full-node peer serving compact block filters. A Lightning peer is a separate connection. Regtest coins have no value.");
        EditText peer = field("Full-node host:port", prefs.getString("syncPeer", "10.0.2.2:19444"));
        button("Start regtest wallet", () -> {
            try { String endpoint = Policy.endpoint(peer.getText().toString());
                if (!prefs.edit().putString("syncPeer", endpoint).putString("network", "regtest").putString("mode", "easy").commit()) throw new Exception("Could not save setup");
                start();
            } catch (Exception e) { error(e); }
        });
    }
    private void start() {
        page("Starting your node");
        Intent intent = new Intent(this, NodeService.class); startForegroundService(intent);
        bound = bindService(intent, connection, BIND_AUTO_CREATE);
    }
    private void home() {
        if (screen.equals("Home") && node != null && node.rpcReady) { refresh(); return; }
        page("Home");
        if (node == null) { text("Connecting to node…"); return; }
        text(node.status);
        if (!node.rpcReady) { button("Refresh", this::home); button("Node connection", this::nodeConnection); return; }
        button("Refresh wallet", this::refresh);
        refresh();
    }
    private void refresh() {
        final String origin = screen;
        work(() -> {
            Stateservice.WalletState state = node.walletState();
            if (state == Stateservice.WalletState.NON_EXISTING) {
                runOnUiThread(() -> { page("Create your wallet"); text("Your keys stay on this device. Back up the seed to recover on-chain funds; channel recovery also requires channel backups.");
                    button("Create wallet", () -> work(() -> { node.createWallet(); runOnUiThread(() -> { page("Wallet created"); button("Back up seed", this::backup); button("I'll do it later, I promise", this::home); }); })); }); return;
            }
            if (state == Stateservice.WalletState.LOCKED) { node.unlock(); runOnUiThread(this::home); return; }
            if (state != Stateservice.WalletState.SERVER_ACTIVE) {
                SyncProgress progress = node.syncProgress();
                runOnUiThread(() -> {
                    if (!screen.equals(origin)) return;
                    page("Wallet is starting");
                    text(state == Stateservice.WalletState.UNLOCKED ? "Wallet unlocked · synchronizing chain" : "Preparing wallet");
                    if (progress.target > 0) {
                        syncProgressBar("Block headers", progress.headers, progress.target, progress.percent(progress.headers));
                        syncProgressBar("Filter headers", progress.filters, progress.target, progress.percent(progress.filters));
                        text(progress.headers >= progress.target && progress.filters >= progress.target
                            ? "Headers ready · finishing wallet startup…" : "Initial sync is running. Progress updates automatically.");
                    } else {
                        ProgressBar spinner = new ProgressBar(this); page.addView(spinner);
                        text("Connecting to the chain peer and waiting for sync progress…");
                    }
                    button("Refresh", this::home);
                }); return;
            }
            if (!prefs.getString("receive-label", "").isEmpty()) labelReceivedTransactions(
                LightningOuterClass.TransactionDetails.parseFrom(node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance())));
            LightningOuterClass.GetInfoResponse info = LightningOuterClass.GetInfoResponse.parseFrom(node.rpc("getInfo", LightningOuterClass.GetInfoRequest.getDefaultInstance()));
            LightningOuterClass.WalletBalanceResponse balance = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            LightningOuterClass.PendingChannelsResponse pending = LightningOuterClass.PendingChannelsResponse.parseFrom(node.rpc("pendingChannels", LightningOuterClass.PendingChannelsRequest.getDefaultInstance()));
            LightningOuterClass.ListChannelsResponse active = LightningOuterClass.ListChannelsResponse.parseFrom(node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
            // One-way wallet history marker: closures must never reset onboarding.
            if (!prefs.getBoolean("hasEverHadChannel", false)) {
                boolean observedChannel = active.getChannelsCount() > 0 || pending.getPendingOpenChannelsCount() > 0
                        || pending.getPendingClosingChannelsCount() > 0 || pending.getPendingForceClosingChannelsCount() > 0
                        || pending.getWaitingCloseChannelsCount() > 0;
                if (!observedChannel) {
                    LightningOuterClass.ClosedChannelsResponse closed = LightningOuterClass.ClosedChannelsResponse.parseFrom(
                            node.rpc("closedChannels", LightningOuterClass.ClosedChannelsRequest.getDefaultInstance()));
                    observedChannel = closed.getChannelsCount() > 0;
                }
                if (observedChannel && !prefs.edit().putBoolean("hasEverHadChannel", true).commit())
                    throw new Exception("Could not save channel history.");
            }
            runOnUiThread(() -> {
                if (!screen.equals(origin) || walletPopup != null || ((homeActionsAnimation[0] != null && homeActionsAnimation[0].isRunning()) || (homeActionsAnimation[1] != null && homeActionsAnimation[1].isRunning()))) return;
                page("Home");
                long channelFunds = 0;
                for (LightningOuterClass.Channel channel : active.getChannelsList())
                    channelFunds = Math.addExact(channelFunds, channel.getLocalBalance());
                if (!info.getSyncedToChain()) text("Syncing chain · block " + info.getBlockHeight());
                homeBalances(balance.getTotalBalance(), channelFunds);
                boolean hasPublic = false;
                for (LightningOuterClass.Channel c : active.getChannelsList()) hasPublic |= !c.getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.PendingOpenChannel c : pending.getPendingOpenChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.ClosedChannel c : pending.getPendingClosingChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.ForceClosedChannel c : pending.getPendingForceClosingChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.WaitingCloseChannel c : pending.getWaitingCloseChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                if (hasPublic) text("Required on-chain reserve: " + balance.getReservedBalanceAnchorChan() + " sats");
            });
        });
    }
    private void syncProgressBar(String label, long height, long target, int percent) {
        text(label + " · " + percent + "%");
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(percent);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffa8efd5));
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));
        text(String.format(Locale.getDefault(), "%,d / %,d", height, target));
    }
    private void homeBalances(long onChain, long channelFunds) {
        balanceSats[0] = onChain; balanceSats[1] = channelFunds;
        balanceDropdown(0, "On chain", this::sendOnChain, this::deposit);
        balanceDropdown(1, "Lightning funds", this::payment, this::receiveLightning);
        updateBalanceAmounts();
    }
    private void balanceDropdown(int index, String label, Runnable sendAction, Runnable receiveAction) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setClipChildren(true);
        homeActionsViews[index] = actions;
        Button chain = button(label, () -> {
            boolean expand = !homeActionsExpanded[index];
            int other = 1 - index;
            if (expand && homeActionsExpanded[other]) animateHomeActions(other, false);
            animateHomeActions(index, expand);
        });
        chain.setAllCaps(false);
        page.removeView(chain);
        LinearLayout balanceRow = new LinearLayout(this);
        balanceRow.setGravity(Gravity.CENTER_VERTICAL);
        chain.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        balanceRow.addView(chain, new LinearLayout.LayoutParams(0, -2, 1));
        Button amount = new Button(this); styleButton(amount, false);
        amount.setTextColor(0xffa8efd5); amount.setTextSize(17); amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        balanceAmounts[index] = amount;
        amount.setOnClickListener(v -> {
            int count = prefs.getString("fiat-currency", "USD").equals("Off") ? 2 : 3;
            int unit = (prefs.getInt("balance-unit-" + index, 0) + 1) % count;
            prefs.edit().putInt("balance-unit-" + index, unit).apply();
            updateBalanceAmounts();
        });
        LinearLayout.LayoutParams amountSize = new LinearLayout.LayoutParams(0, -2, 1); amountSize.leftMargin = dp(8);
        balanceRow.addView(amount, amountSize);
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2); rowSize.topMargin = dp(8); rowSize.bottomMargin = dp(8);
        page.addView(balanceRow, rowSize);
        actions.setPadding(dp(4), dp(4), dp(4), dp(12));
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        Button send = button("Send", sendAction), receive = button("Receive", receiveAction);
        page.removeView(send); page.removeView(receive);
        send.setAllCaps(false); receive.setAllCaps(false);
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(0, -2, 1); sendSize.rightMargin = dp(4);
        LinearLayout.LayoutParams receiveSize = new LinearLayout.LayoutParams(0, -2, 1); receiveSize.leftMargin = dp(4);
        actions.addView(send, sendSize); actions.addView(receive, receiveSize);
        actions.setVisibility(homeActionsExpanded[index] ? View.VISIBLE : View.GONE);
        actions.setAlpha(homeActionsExpanded[index] ? 1f : 0f);
    }
    private void updateBalanceAmounts() {
        String code = prefs.getString("fiat-currency", "USD");
        java.math.BigDecimal price = null;
        try {
            if (fiatRates == null) fiatRates = new org.json.JSONObject(prefs.getString("fiat-rates", "{}"));
            long age = System.currentTimeMillis() - fiatRates.getLong("timestamp");
            if (age >= -300000 && age < 900000 && fiatRates.has(code)) price = new java.math.BigDecimal(fiatRates.getString(code));
        } catch (Exception ignored) {}
        boolean needsFiat = false;
        for (int i = 0; i < 2; i++) {
            int unit = prefs.getInt("balance-unit-" + i, 0);
            if (code.equals("Off") && unit == 2) unit = 0;
            needsFiat |= unit == 2;
            if (balanceAmounts[i] != null) {
                balanceAmounts[i].setText(BalanceDisplay.format(balanceSats[i], unit, code, price));
                balanceAmounts[i].setContentDescription((i == 0 ? "On-chain balance: " : "Lightning balance: ")
                    + balanceAmounts[i].getText() + ". Tap to change units.");
            }
        }
        if (needsFiat) fetchFiatRates();
    }
    private void fetchFiatRates() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (ratesLoading || (lastRatesAttempt != 0 && now - lastRatesAttempt < 60000)) return;
        ratesLoading = true; lastRatesAttempt = now;
        new Thread(() -> {
            org.json.JSONObject result = null;
            try { result = FiatRates.fetch(prefs.getBoolean("useTor", false)); } catch (Exception ignored) {}
            final org.json.JSONObject fetched = result;
            runOnUiThread(() -> {
                ratesLoading = false;
                if (isDestroyed()) return;
                if (fetched != null) {
                    fiatRates = fetched;
                    prefs.edit().putString("fiat-rates", fetched.toString()).apply();
                }
                if (screen.equals("Home")) updateBalanceAmounts();
            });
        }, "fiat-rates").start();
    }
    private void fiatCurrency() {
        String selected = prefs.getString("fiat-currency", "USD");
        String[] labels = new String[FiatRates.CURRENCIES.length + 1]; int checked = 0;
        labels[0] = "Off";
        for (int i = 0; i < FiatRates.CURRENCIES.length; i++) {
            String code = FiatRates.CURRENCIES[i];
            labels[i + 1] = code + " · " + java.util.Currency.getInstance(code).getDisplayName();
            if (code.equals(selected)) checked = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("Fiat currency")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                android.content.SharedPreferences.Editor edit = prefs.edit();
                edit.putString("fiat-currency", which == 0 ? "Off" : FiatRates.CURRENCIES[which - 1]);
                if (which == 0) for (int i = 0; i < 2; i++) {
                    if (prefs.getInt("balance-unit-" + i, 0) == 2) edit.putInt("balance-unit-" + i, 0);
                }
                edit.apply();
                dialog.dismiss();
            }).setNegativeButton("Cancel", null).show();
    }
    private void animateHomeActions(int index, boolean expanded) {
        LinearLayout actions = homeActionsViews[index];
        if (homeActionsAnimation[index] != null) homeActionsAnimation[index].cancel();
        homeActionsExpanded[index] = expanded;
        int startHeight = actions.getVisibility() == View.GONE ? 0 : actions.getHeight();
        actions.setVisibility(View.VISIBLE);
        actions.measure(View.MeasureSpec.makeMeasureSpec(page.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int endHeight = homeActionsExpanded[index] ? actions.getMeasuredHeight() : 0;
        float startAlpha = actions.getAlpha();
        float endAlpha = homeActionsExpanded[index] ? 1f : 0f;
        homeActionsAnimation[index] = android.animation.ValueAnimator.ofFloat(0f, 1f);
        homeActionsAnimation[index].setDuration(260);
        homeActionsAnimation[index].setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        homeActionsAnimation[index].addUpdateListener(frame -> {
            float progress = (float) frame.getAnimatedValue();
            actions.getLayoutParams().height = Math.round(startHeight + (endHeight - startHeight) * progress);
            actions.setAlpha(startAlpha + (endAlpha - startAlpha) * progress);
            actions.requestLayout();
        });
        homeActionsAnimation[index].addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                actions.setVisibility(homeActionsExpanded[index] ? View.VISIBLE : View.GONE);
                actions.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
                actions.requestLayout();
            }
        });
        homeActionsAnimation[index].start();
    }
    private String outpoint(LightningOuterClass.Utxo coin) {
        return coin.getOutpoint().getTxidStr() + ":" + coin.getOutpoint().getOutputIndex();
    }
    private String coinLabel(LightningOuterClass.Utxo coin) {
        return prefs.getString("utxo-label-" + outpoint(coin), "");
    }
    private void utxos() {
        work(() -> {
            LightningOuterClass.ListUnspentResponse outputs = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
                    LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(0).setMaxConfs(Integer.MAX_VALUE).build()));
            LightningOuterClass.WalletBalanceResponse balance = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            runOnUiThread(() -> {
                page("On chain funds");
                text(balance.getConfirmedBalance() + " sats confirmed\n" + balance.getUnconfirmedBalance() + " sats unconfirmed");
                button("Receive XBT", this::deposit);
                if (outputs.getUtxosCount() == 0) text("No unspent outputs.");
                else text("Tap an output to edit its label.");
                for (LightningOuterClass.Utxo coin : outputs.getUtxosList()) {
                    String label = coinLabel(coin);
                    button((label.isEmpty() ? "Unlabelled output" : label) + "\n" + coin.getAmountSat() + " sats · " + coin.getConfirmations() + " confirmations\n" + outpoint(coin), () -> labelCoin(coin));
                }
                button("Refresh", this::utxos); button("Home", this::home);
            });
        });
    }
    private void labelCoin(LightningOuterClass.Utxo coin) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Label"); input.setText(coinLabel(coin));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new AlertDialog.Builder(this).setTitle("Label UTXO").setMessage(outpoint(coin)).setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d,w) -> {
                String label = input.getText().toString().trim();
                if (!prefs.edit().putString("utxo-label-" + outpoint(coin), label).commit()) { error(new Exception("Could not save label.")); return; }
                utxos();
            }).show();
    }
    private void channelList() {
        work(() -> {
            LightningOuterClass.ListChannelsResponse open = LightningOuterClass.ListChannelsResponse.parseFrom(node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
            LightningOuterClass.PendingChannelsResponse pending = LightningOuterClass.PendingChannelsResponse.parseFrom(node.rpc("pendingChannels", LightningOuterClass.PendingChannelsRequest.getDefaultInstance()));
            LightningOuterClass.GetInfoResponse info = LightningOuterClass.GetInfoResponse.parseFrom(node.rpc("getInfo", LightningOuterClass.GetInfoRequest.getDefaultInstance()));
            LightningOuterClass.WalletBalanceResponse balance = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            runOnUiThread(() -> {
                page("Channels");
                int count = open.getChannelsCount() + pending.getPendingOpenChannelsCount()
                    + pending.getPendingClosingChannelsCount() + pending.getPendingForceClosingChannelsCount()
                    + pending.getWaitingCloseChannelsCount();
                text(count + (count == 1 ? " channel" : " channels"));
                if (open.getChannelsCount() + pending.getPendingOpenChannelsCount() + pending.getPendingClosingChannelsCount()
                    + pending.getPendingForceClosingChannelsCount() + pending.getWaitingCloseChannelsCount() == 0) text("No open or pending channels.");
                for (LightningOuterClass.Channel c : open.getChannelsList()) button(channelLabelPrefix(c.getChannelPoint()) + (c.getActive() ? "Open · online" : "Open · offline") + "\n" + channelBalances(c.getLocalBalance(), c.getRemoteBalance()), () -> channelDetails(c));
                for (LightningOuterClass.PendingChannelsResponse.PendingOpenChannel c : pending.getPendingOpenChannelsList()) button(channelLabelPrefix(c.getChannel().getChannelPoint()) + "Pending opening\n" + channelBalances(c.getChannel().getLocalBalance(), c.getChannel().getRemoteBalance()), () -> pendingChannelDetails(c.getChannel(), "Pending opening · " + c.getConfirmationsUntilActive() + " confirmations until active"));
                for (LightningOuterClass.PendingChannelsResponse.ClosedChannel c : pending.getPendingClosingChannelsList()) button(channelLabelPrefix(c.getChannel().getChannelPoint()) + "Pending closing\n" + channelBalances(c.getChannel().getLocalBalance(), c.getChannel().getRemoteBalance()), () -> pendingChannelDetails(c.getChannel(), "Pending closing"));
                for (LightningOuterClass.PendingChannelsResponse.ForceClosedChannel c : pending.getPendingForceClosingChannelsList()) button(channelLabelPrefix(c.getChannel().getChannelPoint()) + "Pending force close\n" + channelBalances(c.getChannel().getLocalBalance(), c.getChannel().getRemoteBalance()), () -> pendingChannelDetails(c.getChannel(), "Pending force close"));
                for (LightningOuterClass.PendingChannelsResponse.WaitingCloseChannel c : pending.getWaitingCloseChannelsList()) button(channelLabelPrefix(c.getChannel().getChannelPoint()) + "Waiting to close\n" + channelBalances(c.getChannel().getLocalBalance(), c.getChannel().getRemoteBalance()), () -> pendingChannelDetails(c.getChannel(), "Waiting to close"));
                Button add = button(prefs.getBoolean("hasEverHadChannel", false) ? "Open Another Channel" : "Open Channel", this::coins);
                add.setEnabled(info.getSyncedToChain() && balance.getConfirmedBalance() > 0 && !prefs.getBoolean("opening", false));
                if (prefs.getBoolean("opening", false)) text("A submission is recorded. Check its status before submitting again.");
                else if (!add.isEnabled()) text("Opening needs a synchronized wallet and confirmed funds.");
                button("Refresh", this::channelList); button("Home", this::home);
            });
        });
    }
    private String channelBalances(long local, long remote) {
        return "Local: " + local + " sats\nRemote: " + remote + " sats";
    }
    private void pendingChannelDetails(LightningOuterClass.PendingChannelsResponse.PendingChannel channel, String status) {
        page("Pending channel details");
        String label = prefs.getString("channel-label-" + channel.getChannelPoint(), "");
        if (!label.isEmpty()) text(label);
        text(status);
        text(channel.getCapacity() + " sats capacity\n" + channel.getLocalBalance() + " sats local balance");
        text("Peer\n" + channel.getRemoteNodePub());
        text("Funding output\n" + channel.getChannelPoint());
        button(label.isEmpty() ? "Add label" : "Edit label", () -> labelChannel(channel.getChannelPoint(), () -> pendingChannelDetails(channel, status)));
        button("Back to channels", this::channelList);
    }
    private String channelLabelPrefix(String point) {
        String label = prefs.getString("channel-label-" + point, "");
        return label.isEmpty() ? "" : label + "\n";
    }
    private void labelChannel(String point, Runnable back) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint("Channel label");
        input.setText(prefs.getString("channel-label-" + point, ""));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new AlertDialog.Builder(this).setTitle("Label channel").setMessage("Leave blank to remove the label.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", (d,w) -> {
                String label = input.getText().toString().trim();
                android.content.SharedPreferences.Editor edit = prefs.edit();
                if (label.isEmpty()) edit.remove("channel-label-" + point);
                else edit.putString("channel-label-" + point, label);
                if (!edit.commit()) { error(new Exception("Could not save channel label.")); return; }
                back.run();
            }).show();
    }
    private void channelDetails(LightningOuterClass.Channel channel) {
        page("Channel details");
        String label = prefs.getString("channel-label-" + channel.getChannelPoint(), "");
        if (!label.isEmpty()) text(label);
        button(label.isEmpty() ? "Add label" : "Edit label", () -> labelChannel(channel.getChannelPoint(), () -> channelDetails(channel)));
        text((channel.getActive() ? "Online" : "Offline") + " · " + channel.getCapacity() + " sats capacity");
        text(channel.getLocalBalance() + " sats local balance\n" + channel.getRemoteBalance() + " sats remote balance");
        text("Peer\n" + channel.getRemotePubkey());
        text("Funding output\n" + channel.getChannelPoint());
        String key = "channel-close-" + channel.getChannelPoint();
        String closing = prefs.getString(key, "");
        if (!closing.isEmpty()) text(closing);
        text("Closing returns your remaining funds to your on-chain wallet after fees and confirmation. A cooperative close requires the peer to be online.");
        EditText rate = field("Closing fee rate in sats/vbyte", "1");
        Button close = button("Close Channel", () -> {
            try {
                long fee = Policy.amount(rate.getText().toString());
                new AlertDialog.Builder(this).setTitle("Close this channel?")
                    .setMessage("Your local balance is " + channel.getLocalBalance() + " sats before closing fees.\nFee rate: " + fee + " sats/vbyte.\nThis channel will no longer be available for payments. Funds return on-chain after confirmation.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Close Channel", (dialog, which) -> closeChannel(channel, fee)).show();
            } catch (Exception e) { error(e); }
        });
        close.setEnabled(channel.getActive() && closing.isEmpty());
        if (!channel.getActive()) text("Reconnect to the peer before closing cooperatively.");
        button("Back to channels", this::channelList);
    }
    private void closeChannel(LightningOuterClass.Channel channel, long fee) {
        work(() -> {
            final String key = "channel-close-" + channel.getChannelPoint();
            if (prefs.contains(key)) throw new Exception("A close request is already recorded. Check channel status before any further attempt.");
            // Re-read the node immediately before a user-authorized submission.
            LightningOuterClass.ListChannelsResponse current = LightningOuterClass.ListChannelsResponse.parseFrom(node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
            boolean online = false;
            for (LightningOuterClass.Channel c : current.getChannelsList())
                if (c.getChannelPoint().equals(channel.getChannelPoint())) online = c.getActive();
            if (!online) throw new Exception("This channel is no longer open and online. Refresh the channel list.");
            String[] point = channel.getChannelPoint().split(":");
            LightningOuterClass.CloseChannelRequest request = LightningOuterClass.CloseChannelRequest.newBuilder()
                .setChannelPoint(LightningOuterClass.ChannelPoint.newBuilder().setFundingTxidStr(point[0]).setOutputIndex(Integer.parseInt(point[1])))
                .setForce(false).setSatPerVbyte(fee).setMaxFeePerVbyte(fee).build();
            if (!prefs.edit().putString(key, "Close requested. Check Channels for confirmation progress.").commit())
                throw new Exception("Could not save close request.");
            runOnUiThread(() -> { if (!isDestroyed()) channelDetails(channel); });
            lndmobile.Lndmobile.closeChannel(request.toByteArray(), new lndmobile.RecvStream() {
                private volatile boolean terminal;
                public void onResponse(byte[] data) {
                    try {
                        LightningOuterClass.CloseStatusUpdate update = LightningOuterClass.CloseStatusUpdate.parseFrom(data == null ? new byte[0] : data);
                        terminal = update.hasChanClose();
                        String status = terminal ? "Channel closed." : update.hasClosePending()
                            ? "Closing transaction broadcast. Waiting for confirmation." : "Close requested. Waiting for the peer.";
                        prefs.edit().putString(key, status).commit();
                        runOnUiThread(() -> { if (!isDestroyed() && screen.equals("Channel details")) channelDetails(channel); });
                    } catch (Exception e) { onError(e); }
                }
                public void onError(Exception e) {
                    if (terminal) return;
                    prefs.edit().putString(key, "Close status needs checking: " + e.getMessage() + ". Refresh Channels before attempting anything else.").commit();
                    runOnUiThread(() -> { if (!isDestroyed() && screen.equals("Channel details")) channelDetails(channel); });
                }
            });
        });
    }
    private void more() {
        if (walletMenuAnchor == null || walletPopup != null) return;
        PopupMenu popup = new PopupMenu(this, walletMenuAnchor, Gravity.END);
        walletPopup = popup;
        String[] labels = {"Payment history", "Fiat currency: " + prefs.getString("fiat-currency", "USD"),
            "On chain funds", "Channels", "Swap controllers", "Back up seed", "Test recovery", "Advanced"};
        Runnable[] actions = {this::paymentHistory, this::fiatCurrency, this::utxos, this::channelList, this::swapControllers,
            this::backup, this::testRecovery, this::advanced};
        for (int i = 0; i < labels.length; i++) popup.getMenu().add(0, i, i, labels[i]);
        popup.setOnMenuItemClickListener(item -> {
            if (busy) return true;
            popup.dismiss(); actions[item.getItemId()].run(); return true;
        });
        popup.setOnDismissListener(menu -> { if (walletPopup == popup) walletPopup = null; });
        popup.show();
    }
    private org.json.JSONArray savedControllers() throws Exception {
        return new org.json.JSONArray(prefs.getString("swap-controllers", "[]"));
    }
    private boolean controllerTor(String address) {
        return prefs.getBoolean("useTor", false) || ControllerAddress.onion(address);
    }
    private void prepareControllerTransport(String address) throws Exception {
        if (controllerTor(address)) node.testTor();
    }
    private void swapControllers() {
        page("Swap controllers");
        button("Search directory", this::controllerSearch);
        button("Add controller", () -> controllerAdd("", ""));
        try {
            org.json.JSONArray saved = savedControllers();
            if (saved.length() == 0) text("No saved controllers yet.");
            for (int i=0; i<saved.length(); i++) {
                org.json.JSONObject item = saved.getJSONObject(i);
                button(item.optString("label", item.getString("name")) + "\n" + item.getString("address"), () -> controllerDetails(item));
            }
        } catch (Exception e) { error(e); }
        button("Back", this::home);
    }
    private void controllerAdd(String address, String expectedIdentity) {
        page("Add controller");
        text("Controller address"); EditText url = field("https://… or http://….onion", address);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        button("Scan QR", () -> scan(url));
        text("Label"); EditText label = field("Optional — for your own records", "");
        text("Onion addresses connect through Tor automatically.");
        button("Check controller", () -> {
            final String endpoint;
            final String privateLabel = label.getText().toString().trim();
            try {
                endpoint = ControllerAddress.origin(url.getText().toString());
                if (privateLabel.length() > 80) throw new Exception("Use a label up to 80 characters.");
            } catch (Exception e) { error(e); return; }
            work(() -> {
                // Never replace an existing identity based on a directory listing.
                String pin = expectedIdentity;
                org.json.JSONArray existing = savedControllers();
                for (int i=0; i<existing.length(); i++) if (endpoint.equals(existing.getJSONObject(i).getString("address"))) {
                    String savedPin = existing.getJSONObject(i).getString("provider");
                    if (!pin.isEmpty() && !pin.equals(savedPin)) throw new Exception("Directory identity differs from the saved controller.");
                    pin = savedPin;
                }
                prepareControllerTransport(endpoint);
                SwapControllers.Provider info = SwapControllers.inspect(endpoint, prefs.getString("network", "regtest"), pin, controllerTor(endpoint));
                runOnUiThread(() -> {
                    page("Review controller"); text(info.name); text(endpoint); text("XBT → BTC · " + info.network);
                    text("Controller identity\n" + info.identity);
                    text("Connection checked. Saving a controller does not authorize payments. Swap payments are not enabled in this build.");
                    button("Save controller", () -> {
                        try {
                            org.json.JSONArray saved = savedControllers(), updated = new org.json.JSONArray();
                            for (int i=0; i<saved.length(); i++) if (!endpoint.equals(saved.getJSONObject(i).getString("address"))) updated.put(saved.getJSONObject(i));
                            if (updated.length() >= 20) throw new Exception("You can save up to 20 controllers.");
                            org.json.JSONObject value = info.json().put("address", endpoint).put("checked_at", System.currentTimeMillis());
                            if (!privateLabel.isEmpty()) value.put("label", privateLabel);
                            updated.put(value);
                            if (!prefs.edit().putString("swap-controllers", updated.toString()).commit()) throw new Exception("Could not save controller.");
                            swapControllers();
                        } catch (Exception e) { error(e); }
                    });
                    button("Back", () -> controllerAdd(endpoint, expectedIdentity));
                });
            });
        });
        button("Back", this::swapControllers);
    }
    private void controllerDetails(org.json.JSONObject item) {
        page("Controller details");
        String address = item.optString("address"), identity = item.optString("provider");
        text(item.optString("label", item.optString("name"))); text(address);
        text("XBT → BTC · " + item.optString("environment")); text("Saved identity\n" + identity);
        text("Checked when saved: " + java.text.DateFormat.getDateTimeInstance().format(new Date(item.optLong("checked_at"))));
        text("Swap payments are not enabled in this build.");
        button("Check connection", () -> work(() -> {
            prepareControllerTransport(address);
            SwapControllers.inspect(address, prefs.getString("network", "regtest"), identity, controllerTor(address));
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Controller reachable")
                .setMessage("The controller responded with its saved identity.").setPositiveButton("OK", null).show());
        }));
        button("Remove controller", () -> new AlertDialog.Builder(this).setTitle("Remove saved controller?")
            .setMessage("This removes the controller from this wallet’s list.").setNegativeButton("Cancel", null)
            .setPositiveButton("Remove", (d, w) -> {
                try {
                    org.json.JSONArray saved = savedControllers(), updated = new org.json.JSONArray();
                    for (int i=0; i<saved.length(); i++) if (!address.equals(saved.getJSONObject(i).getString("address"))) updated.put(saved.getJSONObject(i));
                    if (!prefs.edit().putString("swap-controllers", updated.toString()).commit()) throw new Exception("Could not remove controller.");
                    swapControllers();
                } catch (Exception e) { error(e); }
            }).show());
        button("Back", this::swapControllers);
    }
    private void controllerSearch() {
        page("Search controllers");
        text("Directory address");
        EditText directory = field("https://… or http://….onion", prefs.getString("swap-directory", ""));
        text("Search a controller directory, or use a controller’s discovery address. No public directory is bundled yet.");
        button("Search", () -> {
            final String address;
            try { address = ControllerAddress.origin(directory.getText().toString()); }
            catch (Exception e) { error(e); return; }
            work(() -> {
                prepareControllerTransport(address);
                List<SwapControllers.Provider> results = SwapControllers.search(address, prefs.getString("network", "regtest"), controllerTor(address));
                prefs.edit().putString("swap-directory", address).apply();
                runOnUiThread(() -> controllerResults(results));
            });
        });
        button("Scan directory QR", () -> scan(directory));
        button("Back", this::swapControllers);
    }
    private void controllerResults(List<SwapControllers.Provider> results) {
        page("Search results");
        EditText query = field("Search name or address", "");
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); page.addView(rows);
        Runnable render = () -> {
            rows.removeAllViews(); String search = query.getText().toString().toLowerCase(Locale.ROOT).trim(); int matches = 0;
            for (SwapControllers.Provider p : results) {
                if (!(p.name + " " + String.join(" ", p.endpoints)).toLowerCase(Locale.ROOT).contains(search)) continue;
                matches++;
                for (String address : p.endpoints) {
                    Button row = new Button(this); styleButton(row, false);
                    row.setText(p.name + "\n" + address);
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.topMargin = dp(6); rows.addView(row, size);
                    row.setOnClickListener(v -> controllerAdd(address, p.identity));
                }
            }
            if (matches == 0) { TextView empty = new TextView(this); empty.setText("No matching controllers."); empty.setTextColor(0xffafbeb6); rows.addView(empty); }
        };
        query.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        render.run();
        button("Back", this::controllerSearch);
    }

    private void advanced() {
        page("Advanced"); text(node == null ? "Node disconnected" : node.status);
        button("Node connection", this::nodeConnection);
        text("Fiat estimates use Neoxa XBT/USDC (BTCB2_USDC), treating 1 USDC as approximately 1 USD. Other currencies use Frankfurter reference rates. Stale or unavailable quotes display —. Regtest balances are test funds.");
        button("Closed-channel history", this::closedChannels); button("Back", this::home);
    }
    private void nodeConnection() {
        page("Node connection");
        text("Network: " + prefs.getString("network", "regtest") + " · compact-filter P2P peer");
        boolean mainnet = prefs.getString("network", "regtest").equals("mainnet");
        EditText peer = field(mainnet ? "Automatic discovery (leave blank)" : "Full-node host:port",
            prefs.getString("syncPeer", mainnet ? "" : "10.0.2.2:19444"));
        CheckBox tor = new CheckBox(this); tor.setText("Use Tor"); tor.setChecked(prefs.getBoolean("useTor", false)); page.addView(tor);
        EditText feeUrl = null;
        if (prefs.getString("network", "regtest").equals("mainnet")) {
            text("XBT fee-estimator URL"); feeUrl = field("https://…", prefs.getString("feeUrl", ""));
        }
        final EditText feeSource = feeUrl;
        text("Tor runs inside this app. Onion peers need a v3 onion address and P2P port. Changes take effect after restarting the app.");
        button("Test Tor connection", () -> work(() -> {
            node.testTor();
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Tor connected")
                .setMessage("Tor established a circuit. The configured node connection is unchanged until you save and restart.")
                .setPositiveButton("OK", null).show());
        }));
        button("Test onion peer", () -> {
            final String endpoint;
            try { endpoint = Policy.onionEndpoint(peer.getText().toString()); } catch (Exception e) { error(e); return; }
            work(() -> {
                node.testTorPeer(endpoint);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Onion peer reachable")
                    .setMessage("Connected to the peer's P2P port through Tor. Chain synchronization is a separate check.")
                    .setPositiveButton("OK", null).show());
            });
        });
        button("Save connection", () -> {
            try {
                String entered = peer.getText().toString().trim();
                String endpoint = mainnet && entered.isEmpty() ? "" : Policy.endpoint(entered);
                if (endpoint.contains(".onion:") && !tor.isChecked()) throw new Exception("Enable Tor for an onion peer.");
                android.content.SharedPreferences.Editor edit = prefs.edit().putString("syncPeer", endpoint).putBoolean("useTor", tor.isChecked());
                if (feeSource != null && !feeSource.getText().toString().trim().isEmpty()) edit.putString("feeUrl", Policy.feeUrl(feeSource.getText().toString(), tor.isChecked()));
                if (!edit.commit()) throw new Exception("Could not save connection.");
                new AlertDialog.Builder(this).setTitle("Connection saved").setMessage("Restart the app to use this connection. Your wallet and channels are preserved.").setPositiveButton("OK", null).show();
            } catch (Exception e) { error(e); }
        });
        button("Back", this::advanced);
    }
    private void closedChannels() {
        work(() -> {
            LightningOuterClass.ClosedChannelsResponse closed = LightningOuterClass.ClosedChannelsResponse.parseFrom(node.rpc("closedChannels", LightningOuterClass.ClosedChannelsRequest.getDefaultInstance()));
            runOnUiThread(() -> {
                page("Closed-channel history");
                if (closed.getChannelsCount() == 0) text("No closed channels.");
                for (LightningOuterClass.ChannelCloseSummary c : closed.getChannelsList()) text(channelLabelPrefix(c.getChannelPoint()) + c.getCapacity() + " sats · " + c.getCloseType().name() + "\n" + c.getChannelPoint());
                button("Back", this::advanced);
            });
        });
    }
    private static final class HistoryEntry {
        final String labelKey, fallbackLabel, details;
        HistoryEntry(String labelKey, String fallbackLabel, String details) {
            this.labelKey = labelKey; this.fallbackLabel = fallbackLabel; this.details = details;
        }
    }
    private void editHistoryLabel(HistoryEntry entry, Runnable refresh) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Label (optional)");
        input.setText(prefs.getString(entry.labelKey, entry.fallbackLabel));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new AlertDialog.Builder(this).setTitle("Edit payment label").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) -> {
                // Persist empty overrides too, so clearing a label cannot resurrect a backend label.
                if (!prefs.edit().putString(entry.labelKey, input.getText().toString().trim()).commit()) {
                    error(new Exception("Could not save label.")); return;
                }
                refresh.run();
            }).show();
    }
    private void paymentHistory() {
        work(() -> {
            LightningOuterClass.TransactionDetails transactions = LightningOuterClass.TransactionDetails.parseFrom(node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance()));
            LightningOuterClass.ListPaymentsResponse payments = LightningOuterClass.ListPaymentsResponse.parseFrom(node.rpc("listPayments", LightningOuterClass.ListPaymentsRequest.newBuilder().setIncludeIncomplete(true).setMaxPayments(100).setReversed(true).build()));
            LightningOuterClass.ListInvoiceResponse invoices = LightningOuterClass.ListInvoiceResponse.parseFrom(node.rpc("listInvoices", LightningOuterClass.ListInvoiceRequest.newBuilder().setNumMaxInvoices(100).setReversed(true).build()));
            labelReceivedTransactions(transactions);
            NavigableMap<Long, List<HistoryEntry>> onChain = new TreeMap<>(Collections.reverseOrder());
            NavigableMap<Long, List<HistoryEntry>> lightning = new TreeMap<>(Collections.reverseOrder());
            for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) {
                String direction = tx.getAmount() > 0 ? "On-chain received" : tx.getAmount() < 0 ? "On-chain sent / channel funding" : "On-chain transaction";
                String status = tx.getNumConfirmations() < 0 ? "Conflicted" : tx.getNumConfirmations() == 0 ? "Unconfirmed" : tx.getNumConfirmations() + " confirmations";
                onChain.computeIfAbsent(tx.getTimeStamp(), key -> new ArrayList<>()).add(new HistoryEntry("tx-label-" + tx.getTxHash(), transactionHistoryLabel(tx), direction + " · " + Math.abs(tx.getAmount()) + " sats\n" + status + "\n" + tx.getTxHash()));
            }
            for (LightningOuterClass.Payment p : payments.getPaymentsList())
                lightning.computeIfAbsent(p.getCreationTimeNs() / 1000000000L, key -> new ArrayList<>()).add(new HistoryEntry("payment-label-" + p.getPaymentHash(), "", "Lightning sent · " + p.getValueSat() + " sats\n" + p.getStatus().name() + "\n" + p.getPaymentHash()));
            for (LightningOuterClass.Invoice invoice : invoices.getInvoicesList()) {
                if (invoice.getState() != LightningOuterClass.Invoice.InvoiceState.SETTLED) continue;
                lightning.computeIfAbsent(invoice.getSettleDate(), key -> new ArrayList<>()).add(new HistoryEntry("invoice-label-" + android.util.Base64.encodeToString(invoice.getRHash().toByteArray(), android.util.Base64.NO_WRAP), "",
                    "Lightning received · " + invoice.getAmtPaidSat() + " sats\nSettled" + (invoice.getMemo().isEmpty() ? "" : "\nDescription: " + invoice.getMemo())));
            }
            boolean limited = payments.getPaymentsCount() == 100 || invoices.getInvoicesCount() == 100;
            runOnUiThread(() -> renderPaymentHistory(onChain, lightning, limited));
        });
    }
    private void renderPaymentHistory(NavigableMap<Long, List<HistoryEntry>> onChain,
            NavigableMap<Long, List<HistoryEntry>> lightning, boolean limited) {
        page("Payment history");
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"ALL", "Lightning", "On Chain"};
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            Button button = new Button(this); styleButton(button, historyTab == i); button.setText(names[i]); button.setTextSize(13);
            button.setSelected(historyTab == i);
            button.setTypeface(null, historyTab == i ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            button.setTextColor(historyTab == i ? 0xff101b16 : 0xffafbeb6);
            button.setContentDescription(names[i] + (historyTab == i ? ", selected" : ""));
            button.setOnClickListener(v -> { historyTab = tab; renderPaymentHistory(onChain, lightning, limited); });
            LinearLayout.LayoutParams tabSize = new LinearLayout.LayoutParams(0, -2, 1); tabSize.setMargins(dp(2), 0, dp(2), dp(8));
            tabs.addView(button, tabSize);
        }
        page.addView(tabs);
        text("Tap a payment to edit its label.");
        NavigableMap<Long, List<HistoryEntry>> history = new TreeMap<>(Collections.reverseOrder());
        if (historyTab != 1) for (Map.Entry<Long, List<HistoryEntry>> entry : onChain.entrySet())
            history.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
        if (historyTab != 2) for (Map.Entry<Long, List<HistoryEntry>> entry : lightning.entrySet())
            history.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(entry.getValue());
        if (history.isEmpty()) text(historyTab == 1 ? "No Lightning payments yet." : historyTab == 2 ? "No on-chain transactions yet." : "No transactions or payments yet.");
        java.text.DateFormat date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (Map.Entry<Long, List<HistoryEntry>> entry : history.entrySet())
            for (HistoryEntry item : entry.getValue()) {
                String label = prefs.getString(item.labelKey, item.fallbackLabel);
                Button row = button(date.format(new Date(entry.getKey() * 1000)) + "\n" + historyLabel(label) + item.details,
                    () -> editHistoryLabel(item, () -> renderPaymentHistory(onChain, lightning, limited)));
                row.setAllCaps(false); row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setContentDescription(row.getText() + ". Tap to edit label.");
            }
        if (limited && historyTab != 2) text("Showing the latest 100 outgoing Lightning payments and latest 100 invoices.");
        button("Refresh", this::paymentHistory); button("Back", this::home);
    }
    private void backup() {
        page("Before you view your seed", true);
        text("Warning! Anyone who knows this seed has access to your funds.");
        text("Recovery from these words will not restore your Lightning channels. Recovering channel funds on chain requires a separate channel backup and help from your channel peers. This alpha does not yet support that recovery process.");
        button("Cancel", this::home);
        button("I understand · Show seed", this::showSeed);
    }
    private void showSeed() {
        try { String seed = new Vault(this).get("seed");
            page("Back up your seed", true);
            if (seed == null) text("Create your wallet first.");
            else {
                String[] words = seed.trim().split("\\s+");
                for (int i = 0; i < words.length; i++) text((i + 1) + ". " + words[i]);
            }
            text("Write these words down privately. The seed recovers on-chain funds; it is not a complete Lightning channel backup."); button("Done", this::home);
        } catch (Exception e) { error(e); }
    }
    private void testRecovery() {
        try {
            String saved = new Vault(this).get("seed");
            page("Test recovery", true);
            if (saved == null) { text("Create your wallet first."); button("Back", this::home); return; }
            text("Enter the words from your backup in order. This checks them against this wallet's seed; it does not restore a wallet or test Lightning channel recovery.");
            int count = saved.trim().split("\\s+").length;
            List<EditText> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                EditText entry = field("Word " + (i + 1), "");
                entry.setContentDescription("Seed word " + (i + 1));
                entry.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                entry.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    | (i == count - 1 ? android.view.inputmethod.EditorInfo.IME_ACTION_DONE : android.view.inputmethod.EditorInfo.IME_ACTION_NEXT));
                entry.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                entry.setSaveEnabled(false);
                entries.add(entry);
            }
            button("Check backup", () -> {
                try {
                    List<String> entered = new ArrayList<>();
                    for (EditText entry : entries) entered.add(entry.getText().toString().trim());
                    String expected = new Vault(this).get("seed");
                    if (!SeedCheck.matches(expected, entered)) {
                        new AlertDialog.Builder(this).setTitle("Backup does not match")
                            .setMessage("Check that every word is entered in the correct numbered position.")
                            .setPositiveButton("Try again", null).show();
                        return;
                    }
                    for (EditText entry : entries) entry.setText("");
                    getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(page.getWindowToken(), 0);
                    page("Backup verified", true);
                    text("All seed words match this wallet, in the correct order.");
                    text("This verifies your written seed backup only. Lightning channel recovery still requires separate channel backups.");
                    button("Done", this::home);
                } catch (Exception e) { error(e); }
            });
            button("Cancel", () -> {
                for (EditText entry : entries) entry.setText("");
                getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(page.getWindowToken(), 0);
                home();
            });
        } catch (Exception e) { error(e); }
    }
    private void deposit() {
        work(() -> {
            String saved = prefs.getString("deposit", "");
            if (saved.isEmpty()) {
                saved = LightningOuterClass.NewAddressResponse.parseFrom(node.rpc("newAddress", LightningOuterClass.NewAddressRequest.newBuilder().setType(LightningOuterClass.AddressType.WITNESS_PUBKEY_HASH).build())).getAddress();
                if (!prefs.edit().putString("deposit", saved).commit()) throw new Exception("Could not save address");
            }
            final String address = saved;
            LightningOuterClass.TransactionDetails previous = LightningOuterClass.TransactionDetails.parseFrom(
                node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance()));
            labelReceivedTransactions(previous);
            Set<String> baseline = new HashSet<>();
            for (LightningOuterClass.Transaction tx : previous.getTransactionsList()) baseline.add(tx.getTxHash());
            runOnUiThread(() -> {
                receivingAddress = address; receiveBaseline = baseline; page("Receive XBT");
                text("Amount in sats (optional)");
                EditText amount = field("Leave blank for any amount", "");
                amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                ImageView qr = new ImageView(this); qr.setContentDescription("Deposit payment request QR code");
                page.addView(qr, new LinearLayout.LayoutParams(-1, Math.round((getResources().getDisplayMetrics().widthPixels - 56) * UI_SCALE)));
                TextView validation = text(""); validation.setVisibility(View.GONE);
                final String[] request = {address};
                Button copy = button("Copy payment request", () -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("XBT payment request", request[0]));
                    Toast.makeText(this, "Payment request copied", Toast.LENGTH_SHORT).show();
                });
                text(address).setOnClickListener(v -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("XBT address", address));
                    Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
                });
                EditText receiveLabel = labelField(prefs.getString("receive-label", ""));
                prefs.edit().putString("receive-label-address", address).putStringSet("receive-label-baseline", baseline).apply();
                receiveLabel.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        prefs.edit().putString("receive-label", s.toString().trim()).apply();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                Runnable updateRequest = () -> {
                    if (!screen.equals("Receive XBT") || receiveBaseline != baseline) return;
                    try {
                        String value = OnChainPayment.receiveRequest(address, amount.getText().toString());
                        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640);
                        Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
                        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) bitmap.setPixel(x, y, matrix.get(x,y) ? 0xff000000 : 0xffffffff);
                        request[0] = value; qr.setImageBitmap(bitmap); qr.setVisibility(View.VISIBLE);
                        copy.setEnabled(true); validation.setVisibility(View.GONE);
                    } catch (Exception e) {
                        qr.setVisibility(View.INVISIBLE); copy.setEnabled(false);
                        validation.setText(e.getMessage()); validation.setVisibility(View.VISIBLE);
                    }
                };
                amount.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        handler.removeCallbacks(updateRequest);
                        qr.setVisibility(View.INVISIBLE); copy.setEnabled(false);
                        handler.postDelayed(updateRequest, 200);
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                updateRequest.run();
                button("Home", this::home);
            });
        });
    }
    private void checkDeposit() {
        final String address = receivingAddress;
        final Set<String> baseline = receiveBaseline;
        if (address == null) return;
        work(() -> {
            try {
                LightningOuterClass.TransactionDetails transactions = LightningOuterClass.TransactionDetails.parseFrom(
                    node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance()));
                labelReceivedTransactions(transactions);
                if (DepositDetection.received(address, baseline, transactions)) runOnUiThread(() -> {
                    if (!screen.equals("Receive XBT") || receiveBaseline != baseline) return;
                    receivingAddress = null;
                    Toast.makeText(this, "Deposit received", Toast.LENGTH_SHORT).show();
                    // Leave the receive screen immediately; the next poll refreshes balances.
                    page("Home");
                    handler.post(() -> home());
                });
            } catch (Exception ignored) {
                // A temporary node outage is not a receipt. Retry while the receive screen is visible.
            }
        });
    }
    private void coins() {
        work(() -> {
            LightningOuterClass.ListUnspentResponse unspent = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent", LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
            LightningOuterClass.WalletBalanceResponse previewWallet = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            runOnUiThread(() -> {
                page("Open Channel"); text("Select outputs and enter the funds to use.");
                List<LightningOuterClass.Utxo> selected = new ArrayList<>();
                Map<CheckBox, LightningOuterClass.Utxo> coinBoxes = new LinkedHashMap<>();
                for (LightningOuterClass.Utxo coin : unspent.getUtxosList()) { CheckBox box = new CheckBox(this); box.setText((coinLabel(coin).isEmpty() ? "" : coinLabel(coin) + " · ") + coin.getAmountSat() + " sats · " + coin.getOutpoint().getTxidStr() + ":" + coin.getOutpoint().getOutputIndex()); page.addView(box); coinBoxes.put(box, coin); }
                EditText peer = field("pubkey@host:port", ""), amount = field("Funds to use", ""), fee = field("Fee rate in sats/vbyte", "1");
                CheckBox useAll = new CheckBox(this); useAll.setText("Use all selected funds"); page.addView(useAll);
                amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                final String[] manualCapacity = {""};
                Runnable updateTotal = () -> {
                    if (!useAll.isChecked()) return;
                    long total = 0;
                    for (LightningOuterClass.Utxo coin : selected) total = Math.addExact(total, coin.getAmountSat());
                    amount.setText(Long.toString(total));
                };
                for (Map.Entry<CheckBox, LightningOuterClass.Utxo> entry : coinBoxes.entrySet()) {
                    entry.getKey().setOnCheckedChangeListener((view, checked) -> {
                        if (checked) selected.add(entry.getValue()); else selected.remove(entry.getValue());
                        updateTotal.run();
                    });
                }
                useAll.setOnCheckedChangeListener((view, checked) -> {
                    if (checked) { manualCapacity[0] = amount.getText().toString(); updateTotal.run(); }
                    else amount.setText(manualCapacity[0]);
                    amount.setEnabled(!checked);
                });
                CheckBox publicChannel = new CheckBox(this); publicChannel.setText("Public channel"); page.addView(publicChannel);
                TextView breakdown = text("Network fee: —\nChannel capacity: —");
                Runnable updateBreakdown = () -> {
                    try {
                        FundingPreview plan = calculateFundingPreview(selected, unspent, previewWallet,
                            Policy.amount(amount.getText().toString()), Policy.amount(fee.getText().toString()), publicChannel.isChecked());
                        breakdown.setText("Estimated network fee: " + plan.fee + " sats" + (publicChannel.isChecked() ? "\nReserve kept from these funds: " + plan.reserve + " sats" : "") + "\nChannel capacity: " + plan.capacity + " sats");
                    } catch (Exception e) { breakdown.setText("Network fee: —" + (publicChannel.isChecked() ? "\nReserve kept on-chain: —" : "") + "\nChannel capacity: —\n" + e.getMessage()); }
                };
                android.text.TextWatcher watcher = new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { updateBreakdown.run(); }
                    public void afterTextChanged(android.text.Editable value) {}
                };
                amount.addTextChangedListener(watcher); fee.addTextChangedListener(watcher);
                publicChannel.setOnCheckedChangeListener((view, checked) -> updateBreakdown.run());
                for (Map.Entry<CheckBox, LightningOuterClass.Utxo> entry : coinBoxes.entrySet()) {
                    entry.getKey().setOnCheckedChangeListener((view, checked) -> {
                        if (checked) selected.add(entry.getValue()); else selected.remove(entry.getValue());
                        updateTotal.run(); updateBreakdown.run();
                    });
                }
                button("Scan peer QR", () -> scan(peer));
                button("Review channel", () -> {
                    try {
                        String[] p = Policy.peer(peer.getText().toString());
                        long budget = Policy.amount(amount.getText().toString()), rate = Policy.amount(fee.getText().toString());
                        boolean publish = publicChannel.isChecked();
                        List<LightningOuterClass.Utxo> chosen = new ArrayList<>(selected);
                        work(() -> {
                            FundingPreview plan = fundingPreview(chosen, budget, rate, publish);
                            LightningOuterClass.OpenChannelRequest.Builder req = LightningOuterClass.OpenChannelRequest.newBuilder()
                                .setNodePubkey(ByteString.copyFrom(hex(p[0]))).setLocalFundingAmount(plan.capacity)
                                .setSatPerVbyte(rate).setMinConfs(1).setPrivate(!publish)
                                .setCommitmentType(LightningOuterClass.CommitmentType.ANCHORS);
                            for (LightningOuterClass.Utxo c : chosen) req.addOutpoints(c.getOutpoint());
                            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Open XBT channel?")
                                .setMessage((publish ? "Public channel" : "Private channel")
                                    + "\nFunds to use: " + budget + " sats"
                                    + "\nEstimated network fee: " + plan.fee + " sats"
                                    + (publish ? "\nReserve kept from these funds: " + plan.reserve + " sats" : "")
                                    + "\nChannel capacity: " + plan.capacity + " sats"
                                    + "\nFee rate: " + rate + " sats/vbyte")
                            .setNegativeButton("Cancel", null).setPositiveButton("Open Channel", (d,w) -> work(() -> {
                                FundingPreview fresh = fundingPreview(chosen, budget, rate, publish);
                                if (fresh.capacity != plan.capacity || fresh.reserve != plan.reserve || fresh.fee != plan.fee)
                                    throw new Exception("Funding terms changed. Review the channel again.");
                                LightningOuterClass.ListPeersResponse peers = LightningOuterClass.ListPeersResponse.parseFrom(node.rpc("listPeers", LightningOuterClass.ListPeersRequest.getDefaultInstance()));
                                boolean connected = false;
                                for (LightningOuterClass.Peer existing : peers.getPeersList()) if (existing.getPubKey().equalsIgnoreCase(p[0])) connected = true;
                                if (!connected) node.rpc("connectPeer", LightningOuterClass.ConnectPeerRequest.newBuilder().setAddr(LightningOuterClass.LightningAddress.newBuilder().setPubkey(p[0]).setHost(p[1])).build());
                                if (!prefs.edit().putBoolean("opening", true).commit()) throw new Exception("Could not record submission");
                                node.rpc("openChannelSync", req.build()); prefs.edit().putBoolean("hasEverHadChannel", true).putBoolean("opening", false).commit(); runOnUiThread(this::home);
                            })).show());
                        });
                    } catch (Exception e) { error(e); }
                }); button("Home", this::home);
            });
        });
    }
    private FundingPreview fundingPreview(List<LightningOuterClass.Utxo> chosen, long budget, long rate, boolean publish) throws Exception {
        LightningOuterClass.ListUnspentResponse available = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
            LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
        LightningOuterClass.WalletBalanceResponse wallet = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
        return calculateFundingPreview(chosen, available, wallet, budget, rate, publish);
    }
    private FundingPreview calculateFundingPreview(List<LightningOuterClass.Utxo> chosen,
            LightningOuterClass.ListUnspentResponse available, LightningOuterClass.WalletBalanceResponse wallet,
            long budget, long rate, boolean publish) throws Exception {
        Map<String, LightningOuterClass.Utxo> coins = new HashMap<>(); long total = 0, selected = 0, weight = 0;
        for (LightningOuterClass.Utxo coin : available.getUtxosList()) { coins.put(outpoint(coin), coin); total = Math.addExact(total, coin.getAmountSat()); }
        for (LightningOuterClass.Utxo original : chosen) {
            LightningOuterClass.Utxo coin = coins.remove(outpoint(original));
            if (coin == null) throw new Exception("A selected output is no longer available. Select your funds again.");
            selected = Math.addExact(selected, coin.getAmountSat());
            String script = coin.getPkScript().toLowerCase(Locale.ROOT);
            if (script.startsWith("0014") && script.length() == 44) weight += 273;
            else if (script.startsWith("5120") && script.length() == 68) weight += 231;
            else if (script.startsWith("a914") && script.endsWith("87") && script.length() == 46) weight += 365;
            else throw new Exception("Cannot estimate funding fees for this output type.");
        }
        return new FundingPreview(selected, total, budget, rate, wallet.getReservedBalanceAnchorChan(), publish, weight, chosen.size());
    }
    private static byte[] hex(String value) { byte[] b = new byte[value.length()/2]; for (int i=0;i<b.length;i++) b[i]=(byte) Integer.parseInt(value.substring(i*2,i*2+2),16); return b; }
    private void sendOnChain() {
        work(() -> {
            LightningOuterClass.ListUnspentResponse available = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
                LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
            LightningOuterClass.WalletBalanceResponse wallet = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            runOnUiThread(() -> onChainForm(available, wallet));
        });
    }
    private OnChainPayment onChainPreview(List<LightningOuterClass.Utxo> selected,
            LightningOuterClass.ListUnspentResponse available, LightningOuterClass.WalletBalanceResponse wallet,
            String address, long amount, long rate, boolean max) throws Exception {
        Map<String, LightningOuterClass.Utxo> coins = new HashMap<>();
        long total = 0, weight = 0;
        for (LightningOuterClass.Utxo coin : available.getUtxosList()) coins.put(outpoint(coin), coin);
        for (LightningOuterClass.Utxo selectedCoin : selected) {
            LightningOuterClass.Utxo coin = coins.remove(outpoint(selectedCoin));
            if (coin == null) throw new Exception("A selected output is no longer available. Select your funds again.");
            total = Math.addExact(total, coin.getAmountSat());
            String script = coin.getPkScript().toLowerCase(Locale.ROOT);
            if (script.startsWith("0014") && script.length() == 44) weight += 273;
            else if (script.startsWith("5120") && script.length() == 68) weight += 231;
            else if (script.startsWith("a914") && script.endsWith("87") && script.length() == 46) weight += 365;
            else throw new Exception("Cannot estimate fees for this output type.");
        }
        return new OnChainPayment(total, wallet.getTotalBalance(), wallet.getReservedBalanceAnchorChan(), weight,
            selected.size(), max && address.trim().isEmpty() ? 172 : OnChainPayment.outputWeight(address), amount, rate, max);
    }
    private String paymentSummary(OnChainPayment plan) {
        return "Recipient receives: " + plan.amount + " sats\nEstimated network fee: " + plan.fee
            + " sats\nTotal including fee: " + plan.total + " sats"
            + (plan.reserve > 0 ? "\nReserve retained: " + plan.reserve + " sats" : "");
    }
    private void onChainForm(LightningOuterClass.ListUnspentResponse available, LightningOuterClass.WalletBalanceResponse wallet) {
        page("Send on chain");
        List<LightningOuterClass.Utxo> selected = new ArrayList<>();
        Map<CheckBox, LightningOuterClass.Utxo> boxes = new LinkedHashMap<>();
        text("Select UTXOs to spend");
        for (LightningOuterClass.Utxo coin : available.getUtxosList()) {
            CheckBox box = new CheckBox(this);
            box.setText((coinLabel(coin).isEmpty() ? "Unlabelled output" : coinLabel(coin)) + " · " + coin.getAmountSat() + " sats\n" + outpoint(coin));
            page.addView(box); boxes.put(box, coin);
        }
        if (boxes.isEmpty()) text("No confirmed UTXOs available.");
        TextView selectedTotal = text("Selected: 0 sats");
        EditText address = field("Recipient XBT address", "");
        EditText amount = field("Amount in sats", "");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText sendLabel = labelField("");
        CheckBox useAll = new CheckBox(this); useAll.setText("Use entire selection"); useAll.setEnabled(false); page.addView(useAll);
        text("Network fee rate (sat/vbyte)");
        EditText fee = field("", "1");
        fee.setContentDescription("Network fee rate in sats per vbyte");
        fee.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        TextView summary = text("Estimated network fee: —\nTotal including fee: —");
        String[] manualAmount = {""}; boolean[] updating = {false};
        Runnable update = () -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                long total = 0;
                for (LightningOuterClass.Utxo coin : selected) total = Math.addExact(total, coin.getAmountSat());
                selectedTotal.setText("Selected: " + total + " sats");
                OnChainPayment.Request request = OnChainPayment.parse(address.getText().toString());
                OnChainPayment plan = onChainPreview(selected, available, wallet, request.address,
                    useAll.isChecked() ? 0 : Policy.amount(amount.getText().toString()), Policy.amount(fee.getText().toString()), useAll.isChecked());
                if (useAll.isChecked()) amount.setText(Long.toString(plan.amount));
                summary.setText(paymentSummary(plan) + (request.address.isEmpty()
                    ? "\nFee estimated until a recipient address is entered." : ""));
            } catch (Exception e) {
                if (useAll.isChecked()) amount.setText("");
                summary.setText("Estimated network fee: —\nTotal including fee: —\n" + e.getMessage());
            } finally { updating[0] = false; }
        };
        useAll.setOnCheckedChangeListener((v, checked) -> {
            if (checked) manualAmount[0] = amount.getText().toString();
            else amount.setText(manualAmount[0]);
            amount.setEnabled(!checked); update.run();
        });
        for (Map.Entry<CheckBox, LightningOuterClass.Utxo> entry : boxes.entrySet())
            entry.getKey().setOnCheckedChangeListener((v, checked) -> {
                if (checked) selected.add(entry.getValue()); else selected.remove(entry.getValue());
                useAll.setChecked(false);
                useAll.setEnabled(!selected.isEmpty());
                update.run();
            });
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        };
        amount.addTextChangedListener(watcher); fee.addTextChangedListener(watcher);
        address.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) {
                if (updating[0]) return;
                if (s.toString().contains(":")) {
                    try {
                        OnChainPayment.Request request = OnChainPayment.parse(s.toString());
                        updating[0] = true;
                        if (request.amount != null) {
                            useAll.setChecked(false); amount.setEnabled(true);
                            amount.setText(Long.toString(request.amount));
                        }
                        address.setText(request.address);
                    } catch (Exception e) { summary.setText(e.getMessage()); return; }
                    finally { updating[0] = false; }
                }
                update.run();
            }
        });
        button("Scan address QR", () -> scan(address));
        button("Review payment", () -> {
            try {
                OnChainPayment.Request parsed = OnChainPayment.parse(address.getText().toString());
                final String recipient = parsed.address;
                if (recipient.isEmpty()) throw new Exception("Enter a recipient address.");
                final String paymentLabel = sendLabel.getText().toString().trim();
                final boolean max = useAll.isChecked();
                final long sats = max ? 0 : Policy.amount(amount.getText().toString());
                final long rate = Policy.amount(fee.getText().toString());
                List<LightningOuterClass.Utxo> chosen = new ArrayList<>(selected);
                onChainPreview(chosen, available, wallet, recipient, sats, rate, max);
                work(() -> {
                    OnChainPayment plan = freshOnChainPreview(chosen, recipient, sats, rate, max);
                    // Validate recipient checksum/network with the node, using a small dry-run output.
                    // This RPC's automatic fee rate is not used for the user-selected fee preview.
                    LightningOuterClass.EstimateFeeRequest.Builder validation = LightningOuterClass.EstimateFeeRequest.newBuilder()
                        .putAddrToAmount(recipient, Math.min(plan.amount, 1000)).setTargetConf(6).setMinConfs(1);
                    for (LightningOuterClass.Utxo coin : chosen) validation.addInputs(coin.getOutpoint());
                    node.rpc("estimateFee", validation.build());
                    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Send on chain?")
                        .setMessage("Recipient: " + recipient + "\n" + paymentSummary(plan) + "\nFee rate: " + rate + " sat/vbyte"
                            + (max ? "" : "\nRecipient amount is fixed; the network fee is paid in addition."))
                        .setNegativeButton("Cancel", null).setPositiveButton("Send", (d, w) -> work(() -> {
                            OnChainPayment fresh = freshOnChainPreview(chosen, recipient, sats, rate, max);
                            if (fresh.amount != plan.amount || fresh.fee != plan.fee || fresh.reserve != plan.reserve)
                                throw new Exception("Funding changed. Review the payment again.");
                            LightningOuterClass.SendCoinsRequest.Builder request = LightningOuterClass.SendCoinsRequest.newBuilder()
                                .setAddr(recipient).setSatPerVbyte(rate).setMinConfs(1).setSendAll(max).setLabel(paymentLabel);
                            if (!max) request.setAmount(sats);
                            for (LightningOuterClass.Utxo coin : chosen) request.addOutpoints(coin.getOutpoint());
                            try {
                                LightningOuterClass.SendCoinsResponse result = LightningOuterClass.SendCoinsResponse.parseFrom(node.rpc("sendCoins", request.build()));
                                prefs.edit().putString("tx-label-" + result.getTxid(), paymentLabel).apply();
                                runOnUiThread(() -> { page("On-chain payment sent"); text("Transaction: " + result.getTxid()); button("Home", this::home); });
                            } catch (Exception e) {
                                runOnUiThread(() -> { page("Check on-chain payment"); text("The node did not confirm submission: " + e.getMessage());
                                    text("Check payment history before trying again."); button("Payment history", this::paymentHistory); button("Home", this::home); });
                            }
                        })).show());
                });
            } catch (Exception e) { error(e); }
        });
        button("Home", this::home);
    }
    private OnChainPayment freshOnChainPreview(List<LightningOuterClass.Utxo> chosen, String address, long amount, long rate, boolean max) throws Exception {
        LightningOuterClass.ListUnspentResponse available = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
            LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
        LightningOuterClass.WalletBalanceResponse wallet = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
        return onChainPreview(chosen, available, wallet, address, amount, rate, max);
    }
    private void receiveLightning() {
        page("Receive Lightning");
        text("Amount in sats");
        EditText amount = field("(optional)", "");
        amount.setContentDescription("Amount in sats (optional)");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        text("Description");
        EditText memo = field("(optional - sender can see this)", "");
        memo.setSingleLine(false); memo.setMaxLines(3);
        memo.setContentDescription("Description (optional - sender can see this)");
        text("Label");
        EditText privateLabel = labelField(""); privateLabel.setHint("(optional - for your own records)");
        privateLabel.setSingleLine(false); privateLabel.setMaxLines(3);
        privateLabel.setContentDescription("Label (optional - for your own records)");
        button("Create invoice", () -> {
            final long sats;
            try {
                String entered = amount.getText().toString().trim();
                sats = entered.isEmpty() ? 0 : Policy.amount(entered);
            }
            catch (Exception e) { error(e); return; }
            final String description = memo.getText().toString().trim();
            final String invoiceLabel = privateLabel.getText().toString().trim();
            work(() -> {
                LightningOuterClass.AddInvoiceResponse result = LightningOuterClass.AddInvoiceResponse.parseFrom(node.rpc("addInvoice",
                    LightningOuterClass.Invoice.newBuilder().setValue(sats).setMemo(description).setExpiry(3600).setPrivate(true).build()));
                if (!prefs.edit().putString("invoice-label-" + android.util.Base64.encodeToString(result.getRHash().toByteArray(), android.util.Base64.NO_WRAP), invoiceLabel).commit())
                    throw new Exception("Could not save invoice label.");
                String invoice = result.getPaymentRequest();
                BitMatrix matrix = new MultiFormatWriter().encode(invoice, BarcodeFormat.QR_CODE, 640, 640);
                Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) bitmap.setPixel(x, y, matrix.get(x,y) ? 0xff000000 : 0xffffffff);
                runOnUiThread(() -> {
                    receivingInvoiceHash = result.getRHash();
                    page("Lightning invoice"); text((sats == 0 ? "Sender chooses the amount" : sats + " sats") + " · Expires in 1 hour");
                    ImageView qr = new ImageView(this); qr.setImageBitmap(bitmap); qr.setContentDescription("Lightning invoice QR code");
                    page.addView(qr, new LinearLayout.LayoutParams(-1, Math.round((getResources().getDisplayMetrics().widthPixels - 56) * UI_SCALE)));
                    button("Copy invoice", () -> {
                        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Lightning invoice", invoice));
                        Toast.makeText(this, "Invoice copied", Toast.LENGTH_SHORT).show();
                    });
                    text(invoice); button("Home", this::home);
                });
            });
        });
        button("Home", this::home);
    }
    private void checkLightningReceipt() {
        final ByteString hash = receivingInvoiceHash;
        if (hash == null) return;
        work(() -> {
            LightningOuterClass.Invoice invoice = LightningOuterClass.Invoice.parseFrom(node.rpc("lookupInvoice",
                LightningOuterClass.PaymentHash.newBuilder().setRHash(hash).build()));
            if (invoice.getState() == LightningOuterClass.Invoice.InvoiceState.SETTLED) runOnUiThread(() -> {
                if (!screen.equals("Lightning invoice") || !hash.equals(receivingInvoiceHash)) return;
                receivingInvoiceHash = null;
                Toast.makeText(this, "Lightning payment received", Toast.LENGTH_SHORT).show();
                page("Home"); handler.post(this::home);
            });
        });
    }
    private void payment() {
        page("Pay Lightning"); text("XBT invoices only. Review the recipient and amount before paying.");
        EditText invoice = field("Lightning invoice", "");
        EditText sendLabel = labelField("");
        button("Scan invoice QR", () -> scan(invoice));
        button("Review invoice", () -> {
            String raw = invoice.getText().toString().trim().replaceFirst("(?i)^lightning:", "");
            final String paymentLabel = sendLabel.getText().toString().trim();
            work(() -> {
                LightningOuterClass.PayReq pay = LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq", LightningOuterClass.PayReqString.newBuilder().setPayReq(raw).build()));
                if (!Policy.invoiceAllowed(pay.containsFeatures(512) || pay.containsFeatures(513), pay.getNumMsat(), pay.getTimestamp(), pay.getExpiry(), System.currentTimeMillis()/1000)) throw new Exception("Use an unexpired, fixed-amount XBT invoice.");
                if (prefs.contains("payment-" + pay.getPaymentHash())) throw new Exception("This invoice already has a recorded attempt. Reconcile its status before retrying.");
                long feeLimit = Math.max(1, pay.getNumSatoshis()/100);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Pay XBT invoice?").setMessage(pay.getNumMsat() + " XBT millisats\nMaximum routing fee: " + feeLimit + " sats\nRecipient: " + pay.getDestination())
                    .setNegativeButton("Cancel",null).setPositiveButton("Pay",(d,w)->work(()->{
                        if (!prefs.edit().putString("payment-" + pay.getPaymentHash(), "submitted").putString("payment-label-" + pay.getPaymentHash(), paymentLabel).commit()) throw new Exception("Could not record payment");
                        lndmobile.Lndmobile.routerSendPaymentV2(routerrpc.RouterOuterClass.SendPaymentRequest.newBuilder()
                            .setPaymentRequest(raw).setFeeLimitSat(feeLimit).setTimeoutSeconds(30).setMaxParts(1).build().toByteArray(), new lndmobile.RecvStream() {
                            private volatile boolean terminal;
                            public void onResponse(byte[] data) {
                                try {
                                    LightningOuterClass.Payment result = LightningOuterClass.Payment.parseFrom(data);
                                    terminal = result.getStatus() == LightningOuterClass.Payment.PaymentStatus.SUCCEEDED || result.getStatus() == LightningOuterClass.Payment.PaymentStatus.FAILED;
                                    String status = "Payment " + result.getStatus().name().toLowerCase(Locale.ROOT);
                                    if (result.getStatus() == LightningOuterClass.Payment.PaymentStatus.FAILED) status += ": " + result.getFailureReason().name();
                                    prefs.edit().putString("payment-" + pay.getPaymentHash(), status).commit();
                                    final String label = status;
                                    runOnUiThread(() -> { if (!isDestroyed()) { page(label); button("Home", MainActivity.this::home); } });
                                } catch (Exception e) { onError(e); }
                            }
                            public void onError(Exception e) { if (terminal) return; runOnUiThread(() -> { if (!isDestroyed()) error(new Exception("Payment status uncertain. Refresh payment history before any further attempt. " + e.getMessage())); }); }
                        });
                        runOnUiThread(() -> { page("Payment submitted"); text("Waiting for the node to report settlement. Closing this screen does not cancel the payment."); button("Home", this::home); });
                    })).show());
            });
        }); button("Home", this::home);
    }
    private void scan(EditText target) {
        scanTarget = target;
        new com.google.zxing.integration.android.IntentIntegrator(this).setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
            .setPrompt("Scan XBT connection or invoice").setBeepEnabled(false).initiateScan();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        com.google.zxing.integration.android.IntentResult scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(request, result, data);
        if (scan != null && scan.getContents() != null && scanTarget != null) scanTarget.setText(scan.getContents());
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (bound) unbindService(connection); super.onDestroy(); }
}
