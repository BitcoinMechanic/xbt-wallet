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
    private void clearFieldFocusOutside(Window window, android.view.MotionEvent event) {
        if (event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return;
        View focused = window.getCurrentFocus();
        if (!(focused instanceof EditText)) return;
        int[] position = new int[2];
        focused.getLocationOnScreen(position);
        android.graphics.Rect bounds = new android.graphics.Rect(position[0], position[1],
            position[0] + focused.getWidth(), position[1] + focused.getHeight());
        if (bounds.contains((int) event.getRawX(), (int) event.getRawY())) return;
        View content = window.findViewById(android.R.id.content);
        if (content instanceof ViewGroup root) {
            int policy = root.getDescendantFocusability();
            root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            root.setFocusableInTouchMode(true);
            root.requestFocus();
            root.setDescendantFocusability(policy);
        } else focused.clearFocus();
        getSystemService(android.view.inputmethod.InputMethodManager.class)
            .hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        clearFieldFocusOutside(getWindow(), event);
        return super.dispatchTouchEvent(event);
    }
    private final class WalletDialogBuilder extends AlertDialog.Builder {
        WalletDialogBuilder() { super(MainActivity.this); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOnShowListener(ignored -> {
                Window window = dialog.getWindow();
                if (window == null) return;
                ViewGroup content = window.findViewById(android.R.id.content);
                if (content == null || content.getChildCount() != 1) return;
                View body = content.getChildAt(0);
                ViewGroup.LayoutParams size = body.getLayoutParams();
                content.removeView(body);
                FrameLayout wrapper = new FrameLayout(MainActivity.this) {
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                        clearFieldFocusOutside(window, event);
                        return super.dispatchTouchEvent(event);
                    }
                };
                wrapper.addView(body, new FrameLayout.LayoutParams(-1, -1));
                content.addView(wrapper, size);
            });
            return dialog;
        }
    }
    private LinearLayout page;
    private FrameLayout pageHeader;
    private ImageView headerBrand;
    private TextView headerRate;
    private float headerSlide;
    private android.animation.ValueAnimator headerSlideAnimation;
    private String viewingSwapHash;
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
    private AmountEntry activeAmountEntry;
    private ByteString receivingInvoiceHash;
    private EditText scanTarget;
    private boolean scanPayment;
    private String receivingAddress;
    private Set<String> receiveBaseline = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private final Runnable poll = new Runnable() {
        public void run() {
            if (node != null && !busy && (screen.equals("Home") || screen.equals("Wallet is starting"))) home();
            if (node != null && !busy && screen.equals("Receive XBT")) checkDeposit();
            if (node != null && !busy && screen.equals("Lightning invoice")) checkLightningReceipt();
            if (node != null && !busy && screen.equals("Swap invoice") && viewingSwapHash != null) {
                try {
                    if (SwapReceive.needsRefresh(new org.json.JSONObject(prefs.getString("swap-receive-"+viewingSwapHash, "{}")))) refreshSwapReceive(viewingSwapHash, false);
                } catch (org.json.JSONException ignored) { /* Keep malformed saved records for explicit inspection. */ }
            }
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
            prefs.edit().putString("network", "mainnet").commit();
        }
        if (BuildConfig.MAINNET_TEST && !prefs.getBoolean("dnsDiscoveryConfigured", false)) {
            prefs.edit().remove("syncPeer").putBoolean("useTor", false)
                .putString("feeUrl", "https://mempool.guide/api/v1/fees/precise")
                .putBoolean("dnsDiscoveryConfigured", true).commit();
        }
        if (BuildConfig.MAINNET_TEST && !prefs.getBoolean("modeChoiceMade", false)) modes();
        else if (prefs.getString("mode", "").equals("easy")) start(); else modes();
    }
    private void page(String title) {
        page(title, false);
    }
    private void page(String title, boolean sensitive) {
        activeAmountEntry = null;
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
        if (headerSlideAnimation != null) headerSlideAnimation.cancel();
        headerSlideAnimation = null;
        headerSlide = prefs.getBoolean("header-fiat-rate", false) ? 1f : 0f;
        FrameLayout header = new FrameLayout(this); pageHeader = header;
        ImageView brand = new ImageView(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                // Draw fixed rope flourishes, then slide only the central lettering.
                android.graphics.drawable.Drawable logo = getDrawable();
                if (logo == null) return;
                float scale = Math.min((float) getWidth() / logo.getIntrinsicWidth(), (float) getHeight() / logo.getIntrinsicHeight());
                float w = logo.getIntrinsicWidth() * scale, h = logo.getIntrinsicHeight() * scale;
                float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                android.graphics.Path wings = new android.graphics.Path();
                for (int side = 0; side < 2; side++) {
                    float[][] outline = {{0,.38f},{.25f,.38f},{.33f,.56f},{.25f,.72f},{0,.72f}};
                    for (int i = 0; i < outline.length; i++) {
                        float px = x + (side == 0 ? outline[i][0] : 1 - outline[i][0]) * w;
                        float py = y + outline[i][1] * h;
                        if (i == 0) wings.moveTo(px, py); else wings.lineTo(px, py);
                    }
                    wings.close();
                }
                int saved = canvas.save(); canvas.clipPath(wings);
                super.onDraw(canvas); canvas.restoreToCount(saved);
                saved = canvas.save();
                canvas.clipRect(x + .26f * w, y, x + .74f * w, y + h);
                canvas.clipOutPath(wings);
                canvas.translate(-headerSlide * dp(110), 0);
                // Also clip the source after translating, so the opposite flourish
                // cannot slide into the center along with the lettering.
                canvas.clipRect(x + .26f * w, y, x + .74f * w, y + h);
                canvas.clipOutPath(wings);
                super.onDraw(canvas); canvas.restoreToCount(saved);
            }
        }; headerBrand = brand;
        brand.setImageResource(org.xbt.wallet.R.drawable.xbt_logo);
        brand.setContentDescription("XBT. Tap to show the fiat exchange rate.");
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(brand, new FrameLayout.LayoutParams(dp(220), dp(79.2f), Gravity.CENTER));
        headerRate = new TextView(this);
        headerRate.setGravity(Gravity.CENTER); headerRate.setTextSize(20);
        headerRate.setTextColor(0xffe8d2a7);
        headerRate.setMaxLines(3);
        headerRate.setAutoSizeTextTypeUniformWithConfiguration(8, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        FrameLayout rateViewport = new FrameLayout(this);
        rateViewport.setClipChildren(true);
        rateViewport.addView(headerRate, new FrameLayout.LayoutParams(-1, -1));
        header.addView(rateViewport, new FrameLayout.LayoutParams(dp(77), dp(79.2f), Gravity.CENTER));
        View.OnClickListener toggleRate = v -> {
            prefs.edit().putBoolean("header-fiat-rate", !prefs.getBoolean("header-fiat-rate", false)).apply();
            if (headerSlideAnimation != null) headerSlideAnimation.cancel();
            headerSlideAnimation = android.animation.ValueAnimator.ofFloat(headerSlide, prefs.getBoolean("header-fiat-rate", false) ? 1f : 0f);
            headerSlideAnimation.setDuration(100);
            headerSlideAnimation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            headerSlideAnimation.addUpdateListener(animation -> {
                headerSlide = (float) animation.getAnimatedValue();
                positionHeaderRate();
            });
            headerSlideAnimation.start();
            updateHeaderRate();
            if (prefs.getBoolean("header-fiat-rate", false) && !prefs.getString("fiat-currency", "USD").equals("Off")) fetchFiatRates();
        };
        brand.setOnClickListener(toggleRate); headerRate.setOnClickListener(toggleRate);
        updateHeaderRate();
        if (prefs.getBoolean("header-fiat-rate", false) && !prefs.getString("fiat-currency", "USD").equals("Off")) fetchFiatRates();
        if (title.equals("Home")) {
            Button menu = new Button(this); styleButton(menu, false);
            menu.setText("⋮"); menu.setTextSize(25); menu.setPadding(0, 0, 0, 0);
            menu.setBackground(null);
            menu.setContentDescription("Wallet menu");
            FrameLayout.LayoutParams menuSize = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
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
    private void updateHeaderRate() {
        if (headerBrand == null || headerRate == null) return;
        positionHeaderRate();
        String currency = prefs.getString("fiat-currency", "USD");
        java.math.BigDecimal price = amountPrice();
        String value;
        if (currency.equals("Off")) value = "Fiat display is off";
        else if (price == null) value = "1 XBT\n" + currency + " rate unavailable";
        else {
            java.text.NumberFormat format = java.text.NumberFormat.getCurrencyInstance(Locale.US);
            format.setCurrency(java.util.Currency.getInstance(currency));
            int decimals = Math.max(0, java.util.Currency.getInstance(currency).getDefaultFractionDigits());
            format.setMinimumFractionDigits(decimals); format.setMaximumFractionDigits(decimals);
            value = format.format(price) + "\n/XBT";
        }
        headerRate.setText(value);
        headerRate.setContentDescription(value + ". Tap to show the XBT logo.");
    }
    private void positionHeaderRate() {
        headerBrand.invalidate();
        headerRate.setTranslationX((1f - headerSlide) * dp(110));
        headerRate.setVisibility(headerSlide == 0 ? View.INVISIBLE : View.VISIBLE);
        headerBrand.setContentDescription(headerSlide >= .5f
            ? "Tap to show the XBT logo." : "XBT. Tap to show the fiat exchange rate.");
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
    private void addQrFrame(ImageView qr, int height) {
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int[] rope = {0xfff4e5c7, 0xffe8d2a7, 0xffd9bd8c};
        int steps = Math.max(1, dp(6));
        android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[steps + 1];
        android.animation.ArgbEvaluator blend = new android.animation.ArgbEvaluator();
        for (int i = 0; i <= steps; i++) {
            float progress = (float) i / steps;
            float softened = progress * progress * (3 - 2 * progress);
            int[] colors = new int[rope.length];
            for (int j = 0; j < rope.length; j++) colors[j] = (Integer) blend.evaluate(softened, 0xff101517, rope[j]);
            android.graphics.drawable.GradientDrawable layer = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colors);
            layer.setCornerRadius(Math.max(0, dp(14) - i));
            layers[i] = layer;
        }
        android.graphics.drawable.LayerDrawable background = new android.graphics.drawable.LayerDrawable(layers);
        for (int i = 0; i <= steps; i++) background.setLayerInset(i, i, i, i, i);
        qr.setBackground(background);
        int gap = 0;
        qr.setPadding(gap, gap, gap, gap);
        int edge = Math.min(height + 2 * gap, getResources().getDisplayMetrics().widthPixels - dp(40));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(edge, edge);
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = dp(8); size.bottomMargin = dp(8);
        page.addView(qr, size);
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
    private void setFieldHint(EditText field, String hint) {
        android.text.SpannableString styled = new android.text.SpannableString(hint);
        if (hint.toLowerCase(Locale.ROOT).contains("optional") || hint.equals("Leave blank for any amount") || hint.equals("ln...") || hint.equals("Required for swaps")) {
            styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                0, styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        field.setHint(styled);
    }
    private EditText field(String hint, String value) {
        EditText e = new EditText(this); setFieldHint(e, hint); e.setText(value); e.setSingleLine(true); e.setTextSize(16);
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
        EditText label = field("Label Optional", value);
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
        Map<String, String> labels = ReceiveAddresses.labels(prefs);
        if (labels.isEmpty()) return;
        String legacyAddress = prefs.getString("receive-label-address", "");
        Set<String> baseline = prefs.getStringSet("receive-label-baseline", Collections.emptySet());
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) {
            if (tx.getNumConfirmations() < 0 || tx.getAmount() <= 0) continue;
            for (LightningOuterClass.OutputDetail output : tx.getOutputDetailsList()) {
                if (!output.getIsOurAddress() || output.getAmount() <= 0) continue;
                String label = labels.get(output.getAddress());
                if (label == null || (legacyAddress.equals(output.getAddress()) && baseline.contains(tx.getTxHash()))) continue;
                String txKey = "tx-label-" + tx.getTxHash();
                String coinKey = "utxo-label-" + tx.getTxHash() + ":" + output.getOutputIndex();
                if (!prefs.contains(txKey)) edit.putString(txKey, label);
                if (!prefs.contains(coinKey)) edit.putString(coinKey, label);
            }
        }
        edit.apply();
    }
    private void error(Exception e) { new WalletDialogBuilder().setTitle("Could not complete action").setMessage(e.getMessage()).setPositiveButton("OK", null).show(); }
    private void work(Job job) {
        if (busy || node == null) return;
        busy = true;
        node.io.execute(() -> { try { job.run(); } catch (Exception e) { runOnUiThread(() -> error(e)); }
            finally { runOnUiThread(() -> busy = false); } });
    }
    private void modes() {
        page("Your Lightning wallet");
        text("Easy Mode runs your XBT Lightning wallet on this phone. Hard Mode will let you connect to your own Lightning node.");
        button("Easy Mode", this::setup);
        Button hard = button("Hard Mode · coming later", () -> {}); hard.setEnabled(false);
    }
    private void setup() {
        if (BuildConfig.MAINNET_TEST) {
            if (!prefs.edit().putString("mode", "easy").putBoolean("modeChoiceMade", true).commit()) {
                error(new Exception("Could not save your mode selection.")); return;
            }
            start();
            return;
        }
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
        // Keep the sync screen visible while the asynchronous status query runs.
        // Replacing it with Home here causes a flash on every startup poll.
        if ((screen.equals("Home") || screen.equals("Wallet is starting")) && node != null && node.rpcReady) { refresh(); return; }
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
            if (!ReceiveAddresses.labels(prefs).isEmpty()) labelReceivedTransactions(
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
                if (!screen.equals(origin) || walletPopup != null || (headerSlideAnimation != null && headerSlideAnimation.isRunning()) || ((homeActionsAnimation[0] != null && homeActionsAnimation[0].isRunning()) || (homeActionsAnimation[1] != null && homeActionsAnimation[1].isRunning()))) return;
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
                if (hasPublic) text("Required on-chain reserve: " + displayAmount(balance.getReservedBalanceAnchorChan()));
                homeScanButton();
                page.setOnClickListener(v -> {
                    for (int i = 0; i < homeActionsExpanded.length; i++) {
                        if (homeActionsExpanded[i]) animateHomeActions(i, false);
                    }
                });
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
    private void homeScanButton() {
        LinearLayout homePage = page;
        View spacer = new View(this);
        homePage.addView(spacer, new LinearLayout.LayoutParams(1, 0));
        Button scan = button("Scan", () -> {
            scanTarget = null;
            scanPayment = true;
            launchScanner("Scan a Lightning invoice or Bitcoin address");
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(160), dp(56));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        scan.setLayoutParams(size);
        homePage.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            View viewport = (View) homePage.getParent();
            int height = viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
            int gap = Math.max(dp(24), Math.round(height * (2f / 3f + .10f)) - spacer.getTop() - scan.getHeight() / 2);
            if (spacer.getLayoutParams().height != gap) {
                spacer.getLayoutParams().height = gap;
                spacer.requestLayout();
            }
        });
    }
    private void homeBalances(long onChain, long channelFunds) {
        balanceSats[0] = onChain; balanceSats[1] = channelFunds;
        balanceDropdown(0, "On Chain", this::sendOnChain, this::deposit);
        balanceDropdown(1, "Lightning", this::payment, this::receiveLightning);
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
        chain.setTextSize(16.5f);
        chain.setMinHeight(dp(62.4f)); chain.setMinimumHeight(dp(62.4f));
        chain.setPadding(dp(16.8f), dp(14.4f), dp(16.8f), dp(14.4f));
        balanceRow.addView(chain, new LinearLayout.LayoutParams(-2, -2));
        Button amount = new Button(this); styleButton(amount, false);
        amount.setBackground(null);
        amount.setTextColor(0xffa8efd5); amount.setTextSize(17); amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        balanceAmounts[index] = amount;
        amount.setOnClickListener(v -> {
            int count = prefs.getString("fiat-currency", "USD").equals("Off") ? 2 : 3;
            int unit = (displayUnit() + 1) % count;
            prefs.edit().putInt("display-unit", unit).apply();
            updateBalanceAmounts();
        });
        LinearLayout.LayoutParams amountSize = new LinearLayout.LayoutParams(0, -2, 1f); amountSize.leftMargin = dp(8);
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
            int unit = displayUnit();
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
                updateHeaderRate();
                if (screen.equals("Home")) updateBalanceAmounts();
                if (activeAmountEntry != null) {
                    activeAmountEntry.changed.run();
                    activeAmountEntry.updateEquivalent();
                }
            });
        }, "fiat-rates").start();
    }
    private int displayUnit() {
        int unit = prefs.getInt("display-unit", 0);
        return unit < 0 || unit > 2 || (unit == 2 && prefs.getString("fiat-currency", "USD").equals("Off")) ? 0 : unit;
    }
    private String displayAmount(long sats) {
        return BalanceDisplay.format(sats, displayUnit(), prefs.getString("fiat-currency", "USD"), amountPrice());
    }
    private java.math.BigDecimal amountPrice() {
        try {
            org.json.JSONObject rates = fiatRates == null ? new org.json.JSONObject(prefs.getString("fiat-rates", "{}")) : fiatRates;
            long age = System.currentTimeMillis() - rates.getLong("timestamp");
            java.math.BigDecimal price = new java.math.BigDecimal(rates.getString(prefs.getString("fiat-currency", "USD")));
            if (age >= -300000 && age < 900000 && price.signum() > 0) return price;
        } catch (Exception ignored) {}
        return null;
    }
    private String amountReview(long msats, java.math.BigDecimal price) {
        java.math.BigDecimal sats = java.math.BigDecimal.valueOf(msats, 3);
        String value = sats.stripTrailingZeros().toPlainString() + " sats\n" + sats.movePointLeft(8).stripTrailingZeros().toPlainString() + " XBT";
        String currency = prefs.getString("fiat-currency", "USD");
        if (!currency.equals("Off")) value += "\n" + (price == null ? currency + " rate unavailable" : "≈ " + sats.movePointLeft(8).multiply(price).setScale(Math.max(0, java.util.Currency.getInstance(currency).getDefaultFractionDigits()), java.math.RoundingMode.HALF_UP).toPlainString() + " " + currency);
        return value;
    }
    private final class AmountEntry {
        final TextView title;
        final LinearLayout row;
        final EditText input;
        final Button currency;
        final TextView equivalent;
        int unit;
        Long exactSats;
        String exactText;
        Runnable changed = () -> {};
        boolean changing;
        AmountEntry(String heading, String hint) {
            unit = displayUnit();
            title = text(heading);
            input = field(hint, ""); page.removeView(input);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setContentDescription(heading + " in " + unitLabel());
            row = new LinearLayout(MainActivity.this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
            currency = new Button(MainActivity.this); styleButton(currency, false); currency.setText(unitLabel());
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(82), -2); size.leftMargin = dp(8);
            row.addView(currency, size); page.addView(row);
            equivalent = text(""); equivalent.setTextSize(13);
            equivalent.setTypeface(equivalent.getTypeface(), android.graphics.Typeface.ITALIC);
            equivalent.setVisibility(View.GONE);
            activeAmountEntry = this;
            currency.setContentDescription("Amount currency. Tap to cycle sats, XBT and fiat.");
            currency.setOnClickListener(v -> {
                try {
                    int next = (unit + 1) % (prefs.getString("fiat-currency", "USD").equals("Off") ? 2 : 3);
                    java.math.BigDecimal price = amountPrice();
                    Long value = input.getText().toString().trim().isEmpty() ? null : sats();
                    if (value != null && next == 2 && price == null) { fetchFiatRates(); throw new Exception("Fetching an exchange rate. Try again shortly or use sats/XBT."); }
                    // Compute before changing units, so a failed conversion leaves the form intact.
                    String converted = value == null ? null : AmountUnits.input(value, next, price);
                    changing = true; unit = next;
                    prefs.edit().putInt("display-unit", unit).apply();
                    currency.setText(unitLabel());
                    input.setContentDescription(heading + " in " + currency.getText());
                    if (value != null) { exactText = converted; exactSats = value; input.setText(converted); }
                } catch (Exception e) { error(e); }
                finally { changing = false; changed.run(); updateEquivalent(); }
            });
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { if (!changing) { exactSats = null; changed.run(); updateEquivalent(); } }
                public void afterTextChanged(android.text.Editable s) {}
            });
            if (!prefs.getString("fiat-currency", "USD").equals("Off")) fetchFiatRates();
        }
        String unitLabel() { return unit == 0 ? "sats" : unit == 1 ? "XBT" : prefs.getString("fiat-currency", "USD"); }
        long sats() {
            if (exactSats != null && input.getText().toString().equals(exactText)) return exactSats;
            return AmountUnits.sats(input.getText().toString(), unit, amountPrice());
        }
        void setSats(long value) {
            boolean wasChanging = changing; changing = true;
            try {
                exactText = AmountUnits.input(value, unit, amountPrice()); exactSats = value; input.setText(exactText);
            } finally { changing = wasChanging; updateEquivalent(); }
        }
        void updateEquivalent() {
            String value = "";
            String code = prefs.getString("fiat-currency", "USD");
            if (row.getVisibility() == View.VISIBLE && !input.getText().toString().trim().isEmpty() && !code.equals("Off")) {
                try {
                    long amount = sats();
                    value = unit == 2
                        ? (amount >= 1_000_000 ? BalanceDisplay.format(amount, 1, code, null) : String.format(Locale.US, "%,d sats", amount))
                        : BalanceDisplay.format(amount, 2, code, amountPrice());
                } catch (IllegalArgumentException ignored) {
                    if (unit == 2 && amountPrice() == null) value = "Exchange rate unavailable";
                }
            }
            equivalent.setText(value); equivalent.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void visible(boolean show) { title.setVisibility(show ? View.VISIBLE : View.GONE); row.setVisibility(show ? View.VISIBLE : View.GONE); updateEquivalent(); }
        void enabled(boolean enabled) { input.setEnabled(enabled); currency.setEnabled(enabled); }
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
        new WalletDialogBuilder().setTitle("Fiat currency")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                android.content.SharedPreferences.Editor edit = prefs.edit();
                edit.putString("fiat-currency", which == 0 ? "Off" : FiatRates.CURRENCIES[which - 1]);
                if (which == 0 && prefs.getInt("display-unit", 0) == 2) edit.putInt("display-unit", 0);
                edit.apply();
                updateBalanceAmounts();
                updateHeaderRate();
                if (prefs.getBoolean("header-fiat-rate", false) && which != 0) fetchFiatRates();
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
                    button((label.isEmpty() ? "Unlabelled output" : label) + "\n" + displayAmount(coin.getAmountSat()) + " · " + coin.getConfirmations() + " confirmations\n" + outpoint(coin), () -> labelCoin(coin));
                }
                button("Refresh", this::utxos); button("Home", this::home);
            });
        });
    }
    private void labelCoin(LightningOuterClass.Utxo coin) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Label"); input.setText(coinLabel(coin));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new WalletDialogBuilder().setTitle("Label UTXO").setMessage(outpoint(coin)).setView(input)
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
        return "Local: " + displayAmount(local) + "\nRemote: " + displayAmount(remote);
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
        new WalletDialogBuilder().setTitle("Label channel").setMessage("Leave blank to remove the label.")
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
                new WalletDialogBuilder().setTitle("Close this channel?")
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
                    text("Connection checked. Configure Wallet Receive Access after saving to receive BTC through this controller.");
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
        button("Configure swap receiving", () -> controllerReceiveSetup(item));
        button("Check connection", () -> work(() -> {
            prepareControllerTransport(address);
            SwapControllers.inspect(address, prefs.getString("network", "regtest"), identity, controllerTor(address));
            runOnUiThread(() -> new WalletDialogBuilder().setTitle("Controller reachable")
                .setMessage("The controller responded with its saved identity.").setPositiveButton("OK", null).show());
        }));
        button("Remove controller", () -> new WalletDialogBuilder().setTitle("Remove saved controller?")
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
    private void controllerReceiveSetup(org.json.JSONObject item) {
        page("Swap receive access", true);
        String identity = item.optString("provider");
        text("Wallet Receive API address");
        EditText address = field("https://… or http://….onion", prefs.getString("receive-api-" + identity, ""));
        text("Wallet Receive Access key");
        EditText token = field("From the controller’s Swap Setup menu", prefs.getString("receive-key-" + identity, ""));
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Scan access key", () -> scan(token));
        button("Save", () -> {
            try {
                String origin = ControllerAddress.origin(address.getText().toString());
                String key = token.getText().toString().trim();
                if (!key.matches("[a-f0-9]{64}")) throw new Exception("Enter the 64-character Wallet Receive Access key.");
                if (!prefs.edit().putString("receive-api-"+identity, origin).putString("receive-key-"+identity,key)
                    .putString("receive-provider",identity).commit()) throw new Exception("Could not save receive settings.");
                controllerDetails(item);
            } catch (Exception e) { error(e); }
        });
        button("Back", () -> controllerDetails(item));
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
            runOnUiThread(() -> new WalletDialogBuilder().setTitle("Tor connected")
                .setMessage("Tor established a circuit. The configured node connection is unchanged until you save and restart.")
                .setPositiveButton("OK", null).show());
        }));
        button("Test onion peer", () -> {
            final String endpoint;
            try { endpoint = Policy.onionEndpoint(peer.getText().toString()); } catch (Exception e) { error(e); return; }
            work(() -> {
                node.testTorPeer(endpoint);
                runOnUiThread(() -> new WalletDialogBuilder().setTitle("Onion peer reachable")
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
                new WalletDialogBuilder().setTitle("Connection saved").setMessage("Restart the app to use this connection. Your wallet and channels are preserved.").setPositiveButton("OK", null).show();
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
                for (LightningOuterClass.ChannelCloseSummary c : closed.getChannelsList()) text(channelLabelPrefix(c.getChannelPoint()) + displayAmount(c.getCapacity()) + " · " + c.getCloseType().name() + "\n" + c.getChannelPoint());
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
        EditText input = new EditText(this); input.setSingleLine(true); setFieldHint(input, "Label Optional");
        input.setText(prefs.getString(entry.labelKey, entry.fallbackLabel));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new WalletDialogBuilder().setTitle("Edit payment label").setView(input)
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
                onChain.computeIfAbsent(tx.getTimeStamp(), key -> new ArrayList<>()).add(new HistoryEntry("tx-label-" + tx.getTxHash(), transactionHistoryLabel(tx), direction + " · " + displayAmount(Math.abs(tx.getAmount())) + "\n" + status + "\n" + tx.getTxHash()));
            }
            for (LightningOuterClass.Payment p : payments.getPaymentsList())
                lightning.computeIfAbsent(p.getCreationTimeNs() / 1000000000L, key -> new ArrayList<>()).add(new HistoryEntry("payment-label-" + p.getPaymentHash(), "", "Lightning sent · " + displayAmount(p.getValueSat()) + "\n" + p.getStatus().name() + "\n" + p.getPaymentHash()));
            for (LightningOuterClass.Invoice invoice : invoices.getInvoicesList()) {
                if (invoice.getState() != LightningOuterClass.Invoice.InvoiceState.SETTLED) continue;
                lightning.computeIfAbsent(invoice.getSettleDate(), key -> new ArrayList<>()).add(new HistoryEntry("invoice-label-" + android.util.Base64.encodeToString(invoice.getRHash().toByteArray(), android.util.Base64.NO_WRAP), "",
                    "Lightning received · " + displayAmount(invoice.getAmtPaidSat()) + "\nSettled" + (invoice.getMemo().isEmpty() ? "" : "\nDescription: " + invoice.getMemo())));
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
                        new WalletDialogBuilder().setTitle("Backup does not match")
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
            final String address = ReceiveAddresses.fresh(prefs, () ->
                LightningOuterClass.NewAddressResponse.parseFrom(node.rpc("newAddress",
                    LightningOuterClass.NewAddressRequest.newBuilder().setType(LightningOuterClass.AddressType.WITNESS_PUBKEY_HASH).build())).getAddress());
            LightningOuterClass.TransactionDetails previous = LightningOuterClass.TransactionDetails.parseFrom(
                node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance()));
            labelReceivedTransactions(previous);
            Set<String> baseline = new HashSet<>();
            for (LightningOuterClass.Transaction tx : previous.getTransactionsList()) baseline.add(tx.getTxHash());
            runOnUiThread(() -> {
                receivingAddress = address; receiveBaseline = baseline; page("Receive XBT");
                AmountEntry entry = new AmountEntry("Amount (optional)", "Leave blank for any amount");
                EditText amount = entry.input;
                ImageView qr = new ImageView(this); qr.setContentDescription("Deposit payment request QR code");
                addQrFrame(qr, Math.round((getResources().getDisplayMetrics().widthPixels - 56) * UI_SCALE));
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
                EditText receiveLabel = labelField("");
                setFieldHint(receiveLabel, "Label (optional)");
                receiveLabel.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        prefs.edit().putString(ReceiveAddresses.LABEL_PREFIX + address, s.toString().trim()).apply();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                Runnable updateRequest = () -> {
                    if (!screen.equals("Receive XBT") || receiveBaseline != baseline) return;
                    try {
                        String value = OnChainPayment.receiveRequest(address, amount.getText().toString().trim().isEmpty() ? "" : Long.toString(entry.sats()));
                        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640);
                        Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
                        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) bitmap.setPixel(x, y, matrix.get(x,y) ? 0xff000000 : 0x00000000);
                        request[0] = value; qr.setImageBitmap(bitmap); qr.setVisibility(View.VISIBLE);
                        copy.setEnabled(true); validation.setVisibility(View.GONE);
                    } catch (Exception e) {
                        qr.setVisibility(View.INVISIBLE); copy.setEnabled(false);
                        validation.setText(e.getMessage()); validation.setVisibility(View.VISIBLE);
                    }
                };
                entry.changed = updateRequest;
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
                for (LightningOuterClass.Utxo coin : unspent.getUtxosList()) { CheckBox box = new CheckBox(this); box.setText((coinLabel(coin).isEmpty() ? "" : coinLabel(coin) + " · ") + displayAmount(coin.getAmountSat()) + " · " + coin.getOutpoint().getTxidStr() + ":" + coin.getOutpoint().getOutputIndex()); page.addView(box); coinBoxes.put(box, coin); }
                EditText peer = field("pubkey@host:port", "");
                AmountEntry funds = new AmountEntry("Funds to use", "");
                EditText amount = funds.input, fee = field("Fee rate in sats/vbyte", "1");
                CheckBox useAll = new CheckBox(this); useAll.setText("Use all selected funds"); page.addView(useAll);
                final String[] manualCapacity = {""};
                Runnable updateTotal = () -> {
                    if (!useAll.isChecked()) return;
                    long total = 0;
                    for (LightningOuterClass.Utxo coin : selected) total = Math.addExact(total, coin.getAmountSat());
                    try { funds.setSats(total); }
                    catch (IllegalArgumentException e) { amount.setText(""); error(e); }
                };
                CheckBox publicChannel = new CheckBox(this); publicChannel.setText("Public channel"); page.addView(publicChannel);
                TextView breakdown = text("Network fee: —\nChannel capacity: —");
                Runnable updateBreakdown = () -> {
                    for (Map.Entry<CheckBox, LightningOuterClass.Utxo> item : coinBoxes.entrySet()) {
                        LightningOuterClass.Utxo coin = item.getValue();
                        item.getKey().setText((coinLabel(coin).isEmpty() ? "" : coinLabel(coin) + " · ") + displayAmount(coin.getAmountSat()) + " · " + outpoint(coin));
                    }
                    try {
                        FundingPreview plan = calculateFundingPreview(selected, unspent, previewWallet,
                            funds.sats(), Policy.amount(fee.getText().toString()), publicChannel.isChecked());
                        breakdown.setText("Estimated network fee: " + displayAmount(plan.fee) + (publicChannel.isChecked() ? "\nReserve kept from these funds: " + displayAmount(plan.reserve) : "") + "\nChannel capacity: " + displayAmount(plan.capacity));
                    } catch (Exception e) { breakdown.setText("Network fee: —" + (publicChannel.isChecked() ? "\nReserve kept on-chain: —" : "") + "\nChannel capacity: —\n" + e.getMessage()); }
                };
                android.text.TextWatcher watcher = new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { updateBreakdown.run(); }
                    public void afterTextChanged(android.text.Editable value) {}
                };
                funds.changed = updateBreakdown; fee.addTextChangedListener(watcher);
                useAll.setOnCheckedChangeListener((view, checked) -> {
                    if (checked) { manualCapacity[0] = amount.getText().toString(); updateTotal.run(); }
                    else amount.setText(manualCapacity[0]);
                    funds.enabled(!checked); updateBreakdown.run();
                });
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
                        long budget = funds.sats(), rate = Policy.amount(fee.getText().toString());
                        boolean publish = publicChannel.isChecked();
                        List<LightningOuterClass.Utxo> chosen = new ArrayList<>(selected);
                        work(() -> {
                            FundingPreview plan = fundingPreview(chosen, budget, rate, publish);
                            LightningOuterClass.OpenChannelRequest.Builder req = LightningOuterClass.OpenChannelRequest.newBuilder()
                                .setNodePubkey(ByteString.copyFrom(hex(p[0]))).setLocalFundingAmount(plan.capacity)
                                .setSatPerVbyte(rate).setMinConfs(1).setPrivate(!publish)
                                .setCommitmentType(LightningOuterClass.CommitmentType.ANCHORS);
                            for (LightningOuterClass.Utxo c : chosen) req.addOutpoints(c.getOutpoint());
                            runOnUiThread(() -> new WalletDialogBuilder().setTitle("Open XBT channel?")
                                .setMessage((publish ? "Public channel" : "Private channel")
                                    + "\nFunds to use: " + displayAmount(budget)
                                    + "\nEstimated network fee: " + displayAmount(plan.fee)
                                    + (publish ? "\nReserve kept from these funds: " + displayAmount(plan.reserve) : "")
                                    + "\nChannel capacity: " + displayAmount(plan.capacity)
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
    private void sendOnChain() { sendOnChain(""); }
    private void sendOnChain(String scanned) {
        work(() -> {
            LightningOuterClass.ListUnspentResponse available = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
                LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
            LightningOuterClass.WalletBalanceResponse wallet = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            runOnUiThread(() -> onChainForm(available, wallet, scanned));
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
            selected.size(), OnChainPayment.previewOutputWeight(address), amount, rate, max);
    }
    private String paymentSummary(OnChainPayment plan) {
        return "Recipient receives: " + displayAmount(plan.amount) + "\nEstimated network fee: " + displayAmount(plan.fee)
            + "\nTotal including fee: " + displayAmount(plan.total)
            + (plan.reserve > 0 ? "\nReserve retained: " + displayAmount(plan.reserve) : "");
    }
    private void onChainForm(LightningOuterClass.ListUnspentResponse available, LightningOuterClass.WalletBalanceResponse wallet, String scanned) {
        page("Send on chain");
        List<LightningOuterClass.Utxo> selected = new ArrayList<>();
        Map<CheckBox, LightningOuterClass.Utxo> boxes = new LinkedHashMap<>();
        text("Select UTXOs to spend");
        for (LightningOuterClass.Utxo coin : available.getUtxosList()) {
            CheckBox box = new CheckBox(this);
            box.setText((coinLabel(coin).isEmpty() ? "Unlabelled output" : coinLabel(coin)) + " · " + displayAmount(coin.getAmountSat()) + "\n" + outpoint(coin));
            page.addView(box); boxes.put(box, coin);
        }
        if (boxes.isEmpty()) text("No confirmed UTXOs available.");
        TextView selectedTotal = text("Selected: " + displayAmount(0));
        EditText address = field("Recipient XBT address", "");
        AmountEntry entry = new AmountEntry("Amount", "");
        EditText amount = entry.input;
        CheckBox useAll = new CheckBox(this); useAll.setText("Use entire selection"); useAll.setEnabled(false); page.addView(useAll);
        text("Label");
        EditText sendLabel = labelField("");
        setFieldHint(sendLabel, "Optional");
        text("Network fee rate (sat/vbyte)");
        EditText fee = field("", "1");
        fee.setContentDescription("Network fee rate in sats per vbyte");
        fee.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        TextView summary = text("Estimated network fee: —\nTotal including fee: —");
        boolean[] updating = {false};
        Runnable update = () -> {
            if (updating[0]) return;
            updating[0] = true;
            for (Map.Entry<CheckBox, LightningOuterClass.Utxo> item : boxes.entrySet()) {
                LightningOuterClass.Utxo coin = item.getValue();
                item.getKey().setText((coinLabel(coin).isEmpty() ? "Unlabelled output" : coinLabel(coin)) + " · " + displayAmount(coin.getAmountSat()) + "\n" + outpoint(coin));
            }
            try {
                long total = 0;
                for (LightningOuterClass.Utxo coin : selected) total = Math.addExact(total, coin.getAmountSat());
                selectedTotal.setText("Selected: " + displayAmount(total));
                OnChainPayment.Request request = OnChainPayment.parse(address.getText().toString());
                boolean provisional = selected.isEmpty();
                boolean amountMissing = !useAll.isChecked() && amount.getText().toString().trim().isEmpty();
                List<LightningOuterClass.Utxo> estimateInputs = provisional ? available.getUtxosList() : selected;
                if (estimateInputs.isEmpty()) {
                    long estimatedFee = OnChainPayment.unfundedFee(request.address, Policy.amount(fee.getText().toString()));
                    Long recipient = amountMissing ? null : entry.sats();
                    summary.setText("Recipient receives: " + (recipient == null ? "—" : displayAmount(recipient))
                        + "\nEstimated network fee: " + displayAmount(estimatedFee)
                        + "\nTotal including fee: " + (recipient == null ? "—" : displayAmount(Math.addExact(recipient, estimatedFee)))
                        + "\nIllustrative estimate for one native SegWit input and a change output. No confirmed funds are available to spend."
                        + (request.address.isEmpty() ? "\nFee estimated until a recipient address is entered." : ""));
                    return;
                }
                OnChainPayment plan = onChainPreview(estimateInputs, available, wallet, request.address,
                    useAll.isChecked() ? 0 : amountMissing ? 546 : entry.sats(), Policy.amount(fee.getText().toString()), useAll.isChecked());
                if (useAll.isChecked()) entry.setSats(plan.amount);
                summary.setText((amountMissing ? "Recipient receives: —\nEstimated network fee: " + displayAmount(plan.fee)
                    + "\nTotal including fee: —" : paymentSummary(plan))
                    + (provisional ? "\nEstimate uses available outputs. Select outputs to refine it." : "") + (request.address.isEmpty()
                    ? "\nFee estimated until a recipient address is entered." : ""));
            } catch (Exception e) {
                if (useAll.isChecked()) amount.setText("");
                summary.setText("Estimated network fee: —\nTotal including fee: —\n" + e.getMessage());
            } finally { updating[0] = false; }
        };
        useAll.setOnCheckedChangeListener((v, checked) -> {
            amount.setEnabled(!checked); update.run();
        });
        for (Map.Entry<CheckBox, LightningOuterClass.Utxo> coinEntry : boxes.entrySet())
            coinEntry.getKey().setOnCheckedChangeListener((v, checked) -> {
                if (checked) selected.add(coinEntry.getValue()); else selected.remove(coinEntry.getValue());
                useAll.setChecked(false);
                useAll.setEnabled(!selected.isEmpty());
                update.run();
            });
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        };
        entry.changed = update; fee.addTextChangedListener(watcher);
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
                            useAll.setChecked(false); entry.enabled(true);
                            entry.setSats(request.amount);
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
                final long sats = max ? 0 : entry.sats();
                final long rate = Policy.amount(fee.getText().toString());
                final java.math.BigDecimal reviewPrice = amountPrice();
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
                    runOnUiThread(() -> new WalletDialogBuilder().setTitle("Send on chain?")
                        .setMessage("Recipient: " + recipient + "\n" + amountReview(Math.multiplyExact(plan.amount, 1000), reviewPrice) + "\n" + paymentSummary(plan) + "\nFee rate: " + rate + " sat/vbyte"
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
        if (!scanned.isEmpty()) address.setText(scanned);
        update.run();
    }
    private OnChainPayment freshOnChainPreview(List<LightningOuterClass.Utxo> chosen, String address, long amount, long rate, boolean max) throws Exception {
        LightningOuterClass.ListUnspentResponse available = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
            LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
        LightningOuterClass.WalletBalanceResponse wallet = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
        return onChainPreview(chosen, available, wallet, address, amount, rate, max);
    }
    private void receiveLightning() {
        page("Receive Lightning");
        Switch swaps = new Switch(this);
        LinearLayout swapToggle = new LinearLayout(this); swapToggle.setGravity(Gravity.CENTER_VERTICAL);
        swapToggle.addView(swaps);
        TextView swapsLabel = new TextView(this); swapsLabel.setText("Swaps"); swapsLabel.setTextColor(0xffa8efd5);
        swapsLabel.setPadding(dp(6), 0, 0, 0); swapsLabel.setOnClickListener(v -> swaps.performClick());
        swapToggle.addView(swapsLabel);
        swaps.setContentDescription("Swaps — receive BTC as XBT");
        FrameLayout.LayoutParams toggleSize = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        pageHeader.addView(swapToggle, toggleSize);
        AmountEntry entry = new AmountEntry("Amount", "Optional");
        TextView amountTitle = entry.title;
        EditText amount = entry.input;
        text("Description");
        EditText memo = field("Optional - sender can see this", "");
        memo.setSingleLine(false); memo.setMaxLines(3);
        memo.setContentDescription("Description Optional - sender can see this");
        text("Label");
        EditText privateLabel = labelField(""); setFieldHint(privateLabel, "Optional - for your own records");
        privateLabel.setSingleLine(false); privateLabel.setMaxLines(3);
        privateLabel.setContentDescription("Label Optional - for your own records");
        Button controller = button("Select swap controller", () -> {
            try {
                org.json.JSONArray saved = savedControllers(); java.util.List<org.json.JSONObject> available = new ArrayList<>();
                for (int i=0;i<saved.length();i++) {
                    org.json.JSONObject item=saved.getJSONObject(i);
                    if (prefs.getString("network","regtest").equals(item.optString("environment"))) available.add(item);
                }
                if (available.isEmpty()) { controllerAdd("", ""); return; }
                String[] names = new String[available.size()];
                for (int i=0;i<names.length;i++) names[i]=available.get(i).optString("label", available.get(i).optString("name"));
                new WalletDialogBuilder().setTitle("Swap controller").setItems(names, (d,which) -> {
                    org.json.JSONObject item=available.get(which); String id=item.optString("provider");
                    prefs.edit().putString("receive-provider",id).apply();
                    if (!prefs.contains("receive-key-"+id)) controllerReceiveSetup(item);
                    else Toast.makeText(this,"Selected " + names[which],Toast.LENGTH_SHORT).show();
                }).show();
            } catch (Exception e) { error(e); }
        });
        controller.setVisibility(View.GONE);
        Button receipts = button("Swap requests", this::swapReceipts); receipts.setVisibility(View.GONE);
        swaps.setOnCheckedChangeListener((v,enabled) -> {
            amountTitle.setText("Amount");
            setFieldHint(amount, enabled ? "Required for swaps" : "Optional");
            setFieldHint(memo, enabled ? "Optional - for your XBT invoice" : "Optional - sender can see this");
            controller.setVisibility(enabled ? View.VISIBLE : View.GONE); receipts.setVisibility(enabled ? View.VISIBLE : View.GONE);
        });
        button("Create invoice", () -> {
            final long sats;
            try {
                String entered = amount.getText().toString().trim();
                sats = entered.isEmpty() ? 0 : entry.sats();
                if (swaps.isChecked() && sats == 0) throw new Exception("Enter the XBT amount to receive through the swap.");
            }
            catch (Exception e) { error(e); return; }
            final String description = memo.getText().toString().trim();
            final String invoiceLabel = privateLabel.getText().toString().trim();
            if (swaps.isChecked()) { createSwapReceive(sats, description, invoiceLabel); return; }
            work(() -> {
                LightningOuterClass.AddInvoiceResponse result = LightningOuterClass.AddInvoiceResponse.parseFrom(node.rpc("addInvoice",
                    LightningOuterClass.Invoice.newBuilder().setValue(sats).setMemo(description).setExpiry(3600).setPrivate(true).build()));
                if (!prefs.edit().putString("invoice-label-" + android.util.Base64.encodeToString(result.getRHash().toByteArray(), android.util.Base64.NO_WRAP), invoiceLabel).commit())
                    throw new Exception("Could not save invoice label.");
                String invoice = result.getPaymentRequest();
                BitMatrix matrix = new MultiFormatWriter().encode(invoice, BarcodeFormat.QR_CODE, 640, 640);
                Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) bitmap.setPixel(x, y, matrix.get(x,y) ? 0xff000000 : 0x00000000);
                runOnUiThread(() -> {
                    receivingInvoiceHash = result.getRHash();
                    page("Lightning invoice"); text((sats == 0 ? "Sender chooses the amount" : displayAmount(sats)) + " · Expires in 1 hour");
                    ImageView qr = new ImageView(this); qr.setImageBitmap(bitmap); qr.setContentDescription("Lightning invoice QR code");
                    addQrFrame(qr, Math.round((getResources().getDisplayMetrics().widthPixels - 56) * UI_SCALE));
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
    private void createSwapReceive(long sats, String description, String label) {
        work(() -> {
            String provider = prefs.getString("receive-provider", ""), api=prefs.getString("receive-api-"+provider,""), key=prefs.getString("receive-key-"+provider,"");
            if (api.isEmpty() || key.isEmpty()) throw new Exception("Select a controller and configure its Wallet Receive Access first.");
            for (String name : prefs.getAll().keySet()) if (name.startsWith("swap-receive-")) {
                org.json.JSONObject saved=new org.json.JSONObject(prefs.getString(name,"{}"));
                org.json.JSONObject reply=saved.optJSONObject("reply");
                if (!saved.optBoolean("dismissed") && !saved.optBoolean("received") && (reply==null || !reply.optBoolean("terminal")))
                    throw new Exception("A swap request is still pending. Open Swap requests to resume it before creating another.");
            }
            LightningOuterClass.AddInvoiceResponse result=LightningOuterClass.AddInvoiceResponse.parseFrom(node.rpc("addInvoice",
                LightningOuterClass.Invoice.newBuilder().setValue(sats).setMemo(description).setExpiry(3600).setPrivate(true).build()));
            String hash=SwapReceive.hash(result.getRHash().toByteArray());
            org.json.JSONObject record=new org.json.JSONObject().put("hash",hash).put("invoice",result.getPaymentRequest()).put("sats",sats)
                .put("provider",provider).put("api",api).put("environment",prefs.getString("network","regtest")).put("created",System.currentTimeMillis());
            if (!prefs.edit().putString("swap-receive-"+hash,record.toString())
                .putString("invoice-label-"+android.util.Base64.encodeToString(result.getRHash().toByteArray(),android.util.Base64.NO_WRAP),label).commit())
                throw new Exception("Could not save the receive request. No request was sent to the controller.");
            runOnUiThread(() -> showSwapReceive(record));
            requestSwapReceive(record,true);
        });
    }
    private void swapReceipts() {
        page("Swap requests");
        java.util.List<org.json.JSONObject> records=new ArrayList<>();
        try {
            for (String name:prefs.getAll().keySet()) if (name.startsWith("swap-receive-")) records.add(new org.json.JSONObject(prefs.getString(name,"{}")));
            records.sort((a,b)->Long.compare(b.optLong("created"),a.optLong("created")));
            for (org.json.JSONObject r:records) button(displayAmount(r.optLong("sats"))+" · "+swapReceiveState(r),()->showSwapReceive(r));
            if (records.isEmpty()) text("No swap requests yet.");
        } catch(Exception e) { error(e); }
        button("Back",this::receiveLightning);
    }
    private String swapReceiveState(org.json.JSONObject record) {
        if (record.optBoolean("dismissed")) return "Not created";
        if (record.optBoolean("received")) return "Received";
        org.json.JSONObject reply=record.optJSONObject("reply");
        return reply==null ? "Request pending" : reply.optString("state","pending").replace('_',' ');
    }
    private void refreshSwapReceive(String hash, boolean create) {
        work(() -> requestSwapReceive(new org.json.JSONObject(prefs.getString("swap-receive-"+hash,"{}")), create));
    }
    private void requestSwapReceive(org.json.JSONObject record, boolean create) throws Exception {
        record.remove("last_error");
        try { requestSwapReceiveChecked(record, create); }
        catch (Exception e) {
            record.put("last_error", e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage());
            if (!create && e.getMessage()!=null && e.getMessage().contains("Code: wallet_receive_not_found")) record.put("not_found",true);
            if (!prefs.edit().putString("swap-receive-"+record.getString("hash"),record.toString()).commit()) throw new Exception("Could not save receive error; check the existing request before retrying.");
            runOnUiThread(() -> { if (screen.equals("Swap invoice") && record.optString("hash").equals(viewingSwapHash)) showSwapReceive(record); });
        }
    }
    private void requestSwapReceiveChecked(org.json.JSONObject record, boolean create) throws Exception {
        if (create) record.remove("not_found");
        String hash=record.getString("hash");
        LightningOuterClass.Invoice own=LightningOuterClass.Invoice.parseFrom(node.rpc("lookupInvoice",LightningOuterClass.PaymentHash.newBuilder().setRHashStr(hash).build()));
        if (own.getState()==LightningOuterClass.Invoice.InvoiceState.SETTLED) {
            record.put("received",true);
        } else {
            String api=record.getString("api"); prepareControllerTransport(api);
            org.json.JSONObject request=new org.json.JSONObject().put("action",create ? "create":"status").put("invoice",record.getString("invoice"))
                .put("environment",record.getString("environment")).put("provider",record.getString("provider"));
            org.json.JSONObject reply=SwapControllers.receive(api,prefs.getString("receive-key-"+record.getString("provider"),""),request,controllerTor(api));
            LightningOuterClass.PayReq decoded=reply.has("invoice") ? LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq",LightningOuterClass.PayReqString.newBuilder().setPayReq(reply.getString("invoice")).build())) : null;
            SwapReceive.validate(record,reply,decoded); record.put("reply",reply);
            if (!reply.optBoolean("terminal")) {
                long now = System.currentTimeMillis();
                if (now - record.optLong("market_checked") >= 60000) {
                    record.put("market_checked", now);
                    record.remove("market_error");
                    try { record.put("market", FiatRates.swapMarket(controllerTor(api))); }
                    catch (Exception e) {
                        record.remove("market");
                        record.put("market_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                }
            }
        }
        if (!prefs.edit().putString("swap-receive-"+hash,record.toString()).commit()) throw new Exception("Could not save swap status.");
        runOnUiThread(() -> { if (screen.equals("Swap invoice") && hash.equals(viewingSwapHash)) showSwapReceive(record); });
    }
    private void showSwapReceive(org.json.JSONObject record) {
        page("Swap invoice"); viewingSwapHash=record.optString("hash");
        text("Receive "+displayAmount(record.optLong("sats"))); text(swapReceiveState(record));
        if (record.has("last_error")) text(record.optString("last_error"));
        if (record.optBoolean("not_found") && !record.optBoolean("dismissed")) button("Dismiss uncreated request", () -> {
            try { record.put("dismissed",true); prefs.edit().putString("swap-receive-"+record.getString("hash"),record.toString()).commit(); swapReceipts(); }
            catch(Exception e) { error(e); }
        });
        org.json.JSONObject reply=record.optJSONObject("reply");
        if (!record.optBoolean("received") && reply!=null && !reply.optBoolean("terminal")) {
            long deadline=reply.optLong("expires_at");
            text("Sender pays "+(reply.optLong("btc_msat")/1000.0)+" BTC sats");
            text("Pay before "+java.text.DateFormat.getTimeInstance().format(new Date(deadline*1000)));
            if (deadline>System.currentTimeMillis()/1000 && reply.has("invoice") && !reply.optBoolean("blocked") && swapMarketReview(record, reply)) {
                try {
                    String invoice=reply.getString("invoice");
                    BitMatrix matrix=new MultiFormatWriter().encode(invoice,BarcodeFormat.QR_CODE,640,640);
                    Bitmap bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
                    for(int y=0;y<640;y++) for(int x=0;x<640;x++) bitmap.setPixel(x,y,matrix.get(x,y)?0xff000000:0x00000000);
                    ImageView qr=new ImageView(this);qr.setImageBitmap(bitmap);qr.setContentDescription("BTC Lightning invoice QR code");
                    addQrFrame(qr, dp(280));
                    button("Copy BTC invoice",()->getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("BTC Lightning invoice",invoice)));
                    text(invoice);
                } catch(Exception e) { error(e); }
            } else if (deadline<=System.currentTimeMillis()/1000) text("Payment window ended. Checking the final swap outcome.");
        }
        if (!record.optBoolean("dismissed") && !record.optBoolean("received") && (reply==null || !reply.optBoolean("terminal"))) {
            button("Check status",()->refreshSwapReceive(record.optString("hash"),false));
            button("Retry request",()->refreshSwapReceive(record.optString("hash"),true));
        }
        button("Swap requests",this::swapReceipts); button("Home",this::home);
    }
    private boolean swapMarketReview(org.json.JSONObject record, org.json.JSONObject reply) {
        String approvalKey;
        String warning = null;
        try {
            org.json.JSONObject market = record.getJSONObject("market");
            SwapPrice.requireFresh(market.getLong("timestamp"), System.currentTimeMillis());
            java.math.BigDecimal price = new java.math.BigDecimal(market.getString("btc_per_xbt"));
            SwapPrice comparison = new SwapPrice(reply.getLong("btc_msat"), reply.getLong("xbt_msat"), true, price);
            text("Neoxa market: 1 XBT = " + price.stripTrailingZeros().toPlainString() + " BTC"
                + "\nQuoted cost versus market: " + comparison.percent() + "%"
                + "\nBased on Neoxa's last trade. Includes charges built into the swap amounts; the sender's routing fee is additional.");
            if (!comparison.warning()) return true;
            warning = "Warning: this swap costs " + comparison.percent() + "% more than Neoxa's market price (over 5%).";
            approvalKey = market.toString();
        } catch (Exception e) {
            warning = "Warning: cannot check this swap against Neoxa's market price. "
                + record.optString("market_error", e.getMessage() == null ? "Price unavailable." : e.getMessage());
            approvalKey = "unavailable-" + record.optLong("market_checked");
        }
        TextView notice = text(warning); notice.setTextColor(0xffffc482);
        if (approvalKey.equals(record.optString("market_accepted"))) return true;
        final String accepted = approvalKey;
        button("Accept rate and show invoice", () -> {
            try {
                record.put("market_accepted", accepted);
                if (!prefs.edit().putString("swap-receive-" + record.getString("hash"), record.toString()).commit())
                    throw new Exception("Could not save rate acknowledgement.");
                showSwapReceive(record);
            } catch (Exception e) { error(e); }
        });
        text("The payment QR code and copy button stay hidden until you accept this warning.");
        return false;
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
    private void payment() { payment(""); }
    private void payment(String scanned) {
        page("Pay Lightning"); text("XBT invoices only. Review the recipient and amount before paying.");
        text("Invoice");
        EditText invoice = field("ln...", "");
        invoice.setShowSoftInputOnFocus(false);
        invoice.setFocusable(false);
        invoice.setCursorVisible(false);
        invoice.setContentDescription("Invoice. Tap to scan a QR code or paste from clipboard.");
        invoice.setOnClickListener(v -> {
            LinearLayout choices = new LinearLayout(this);
            choices.setOrientation(LinearLayout.HORIZONTAL);
            choices.setBaselineAligned(false);
            choices.setGravity(Gravity.CENTER_VERTICAL);
            choices.setPadding(dp(20), dp(12), dp(20), dp(20));
            Button scanChoice = new Button(this), pasteChoice = new Button(this);
            styleButton(scanChoice, false); styleButton(pasteChoice, false);
            scanChoice.setText("Scan QR"); pasteChoice.setText("Paste from Clipboard");
            LinearLayout.LayoutParams scanSize = new LinearLayout.LayoutParams(0, dp(72), 1);
            LinearLayout.LayoutParams pasteSize = new LinearLayout.LayoutParams(0, dp(72), 1);
            scanSize.rightMargin = dp(4); pasteSize.leftMargin = dp(4);
            choices.addView(scanChoice, scanSize); choices.addView(pasteChoice, pasteSize);
            AlertDialog dialog = new WalletDialogBuilder().setTitle("Invoice").setView(choices).create();
            scanChoice.setOnClickListener(choice -> { dialog.dismiss(); scan(invoice); });
            pasteChoice.setOnClickListener(choice -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                ClipData clip = clipboard.getPrimaryClip();
                CharSequence copied = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
                if (copied == null || copied.toString().trim().isEmpty()) {
                    Toast.makeText(this, "No text on the clipboard", Toast.LENGTH_SHORT).show();
                    return;
                }
                invoice.setText(copied.toString().trim());
                invoice.setSelection(invoice.length());
                dialog.dismiss();
            });
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(surface(0xff101517, 18, 0xff304037));
        });
        AmountEntry entry = new AmountEntry("Amount", "Enter an amount"); entry.visible(false);
        Runnable detectAmount = () -> {
            final String raw = invoice.getText().toString().trim().replaceFirst("(?i)^lightning:", "");
            if (node == null || raw.isEmpty()) return;
            node.io.execute(() -> {
                boolean amountless = false;
                try {
                    LightningOuterClass.PayReq decoded = LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq", LightningOuterClass.PayReqString.newBuilder().setPayReq(raw).build()));
                    amountless = decoded.getNumMsat() == 0 && (decoded.containsFeatures(512) || decoded.containsFeatures(513));
                } catch (Exception ignored) { /* Review reports invalid invoices. */ }
                final boolean show = amountless;
                runOnUiThread(() -> { if (invoice.isAttachedToWindow() && raw.equals(invoice.getText().toString().trim().replaceFirst("(?i)^lightning:", ""))) entry.visible(show); });
            });
        };
        invoice.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { entry.visible(false); handler.removeCallbacks(detectAmount); handler.postDelayed(detectAmount, 300); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        text("Label");
        EditText sendLabel = labelField("");
        setFieldHint(sendLabel, "Optional");
        button("Review invoice", () -> {
            String raw = invoice.getText().toString().trim().replaceFirst("(?i)^lightning:", "");
            final String paymentLabel = sendLabel.getText().toString().trim();
            final String entered = entry.input.getText().toString();
            final int enteredUnit = entry.unit;
            final Long exact = entry.exactSats != null && entered.equals(entry.exactText) ? entry.exactSats : null;
            final java.math.BigDecimal reviewPrice = amountPrice();
            work(() -> {
                LightningOuterClass.PayReq pay = LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq", LightningOuterClass.PayReqString.newBuilder().setPayReq(raw).build()));
                if (!(pay.containsFeatures(512) || pay.containsFeatures(513))) throw new Exception("This is not an XBT invoice.");
                final boolean amountless = pay.getNumMsat() == 0;
                if (amountless && entered.trim().isEmpty()) {
                    runOnUiThread(() -> entry.visible(true));
                    throw new Exception("Enter an amount for this amountless invoice.");
                }
                final long msats = amountless ? Math.multiplyExact(exact != null ? exact : AmountUnits.sats(entered, enteredUnit, reviewPrice), 1000) : pay.getNumMsat();
                if (!Policy.invoiceAllowed(true, msats, pay.getTimestamp(), pay.getExpiry(), System.currentTimeMillis()/1000)) throw new Exception("This invoice has expired or has an invalid amount.");
                if (prefs.contains("payment-" + pay.getPaymentHash())) throw new Exception("This invoice already has a recorded attempt. Reconcile its status before retrying.");
                long feeLimit = Math.max(1, msats/1000/100);
                runOnUiThread(() -> new WalletDialogBuilder().setTitle("Pay XBT invoice?").setMessage(amountReview(msats, reviewPrice) + "\nMaximum routing fee: " + displayAmount(feeLimit) + "\nRecipient: " + pay.getDestination())
                    .setNegativeButton("Cancel",null).setPositiveButton("Pay",(d,w)->work(()->{
                        if (!prefs.edit().putString("payment-" + pay.getPaymentHash(), "submitted").putString("payment-label-" + pay.getPaymentHash(), paymentLabel).commit()) throw new Exception("Could not record payment");
                        routerrpc.RouterOuterClass.SendPaymentRequest.Builder request = routerrpc.RouterOuterClass.SendPaymentRequest.newBuilder()
                            .setPaymentRequest(raw).setFeeLimitSat(feeLimit).setTimeoutSeconds(30).setMaxParts(1);
                        if (amountless) request.setAmtMsat(msats);
                        lndmobile.Lndmobile.routerSendPaymentV2(request.build().toByteArray(), new lndmobile.RecvStream() {
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
        if (!scanned.isEmpty()) invoice.setText(scanned);
    }
    private void scan(EditText target) {
        scanTarget = target;
        scanPayment = false;
        launchScanner("Scan XBT connection or invoice");
    }
    private void launchScanner(String prompt) {
        new com.google.zxing.integration.android.IntentIntegrator(this).setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
            .setPrompt(prompt).setBeepEnabled(false).initiateScan();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        com.google.zxing.integration.android.IntentResult scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(request, result, data);
        if (scan == null) return;
        boolean routePayment = scanPayment;
        EditText target = scanTarget;
        scanPayment = false;
        scanTarget = null;
        if (scan.getContents() == null) return;
        String contents = scan.getContents().trim();
        if (!routePayment) {
            if (target != null) target.setText(contents);
            return;
        }
        try {
            String invoice = contents.replaceFirst("(?i)^lightning:", "");
            if (invoice.toLowerCase(Locale.ROOT).matches("ln(bc|tb|bcrt)[0-9a-z]+")) {
                payment(invoice);
            } else {
                OnChainPayment.Request payment = OnChainPayment.parse(contents);
                OnChainPayment.outputWeight(payment.address);
                sendOnChain(contents);
            }
        } catch (Exception e) {
            error(new Exception("This QR code is not a supported Lightning invoice or Bitcoin payment address. " + e.getMessage()));
        }
    }
    @Override protected void onDestroy() { if (headerSlideAnimation != null) headerSlideAnimation.cancel(); handler.removeCallbacksAndMessages(null); if (bound) unbindService(connection); super.onDestroy(); }
}
