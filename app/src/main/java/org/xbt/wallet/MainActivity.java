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
import offersrpc.OffersOuterClass;
import java.util.*;

/** Native alpha: all balances and operations come from the embedded XBT node. */
public final class MainActivity extends Activity {
    private static final float UI_SCALE = 0.75f;
    @Override protected void attachBaseContext(Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.densityDpi = Math.round(config.densityDpi * UI_SCALE);
        config.fontScale *= 1.15f * 1.10f;
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
    private boolean touchInProgress;
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (WalletPin.locked(this)) { checkPinLock(); return true; }
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) touchInProgress = true;
        clearFieldFocusOutside(getWindow(), event);
        try { return super.dispatchTouchEvent(event); }
        finally {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) touchInProgress = false;
        }
    }
    private final class WalletDialogBuilder extends AlertDialog.Builder {
        WalletDialogBuilder() { super(MainActivity.this); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOnShowListener(ignored -> {
                Window window = dialog.getWindow();
                if (window == null) return;
                styleWalletDialog(dialog);
                ViewGroup content = window.findViewById(android.R.id.content);
                if (content == null || content.getChildCount() != 1) return;
                View body = content.getChildAt(0);
                ViewGroup.LayoutParams size = body.getLayoutParams();
                content.removeView(body);
                FrameLayout wrapper = new FrameLayout(MainActivity.this) {
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                        if (WalletPin.locked(MainActivity.this)) { checkPinLock(); return true; }
                        WalletPin.touch(MainActivity.this);
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
    private void styleWalletDialog(AlertDialog dialog) {
        dialog.getWindow().setBackgroundDrawable(surface(0xff12120f, 18, 0xff69563d));
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(0xffc2b69f); message.setTextSize(15); message.setLineSpacing(dp(3), 1f);
        }
        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) {
            title.setTextColor(0xfff2e6ce); title.setTextSize(20);
            title.setTypeface(getResources().getFont(R.font.wallet_humanist), android.graphics.Typeface.BOLD);
        }
        // Replace the platform button bar with the same equally sized actions as custom forms.
        List<Button> buttons = new ArrayList<>();
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            if (button != null && button.getVisibility() == View.VISIBLE) {
                styleButton(button, which == AlertDialog.BUTTON_POSITIVE); buttons.add(button);
            }
        }
        if (!buttons.isEmpty()) {
            ViewGroup parent = (ViewGroup) buttons.get(0).getParent();
            if (parent != null) {
                parent.removeAllViews();
                LinearLayout row = new LinearLayout(this); row.setBaselineAligned(false);
                row.setPadding(dp(16), dp(8), dp(16), dp(16));
                for (int i = 0; i < buttons.size(); i++) {
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                    if (i > 0) size.leftMargin = dp(5);
                    if (i + 1 < buttons.size()) size.rightMargin = dp(5);
                    row.addView(buttons.get(i), size);
                }
                parent.setPadding(0, 0, 0, 0);
                parent.addView(row, new ViewGroup.LayoutParams(-1, -2));
            }
        }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setBackground(new PaperBackgroundDrawable());
            list.setDivider(new android.graphics.drawable.ColorDrawable(0xff69563d));
            list.setDividerHeight(dp(1));
            list.setPadding(dp(8), 0, dp(8), dp(8));
        }
    }
    private static final class NavigationEntry {
        final String title;
        final Runnable restore;
        NavigationEntry(String title, Runnable restore) { this.title = title; this.restore = restore; }
    }
    private final Deque<NavigationEntry> navigation = new ArrayDeque<>();
    private NavigationEntry currentNavigation;

    private Runnable pageRoute(String title) {
        switch (title) {
            case "Home": return this::home;
            case "On chain funds": return this::utxos;
            case "Channels": return this::channelList;
            case "Peers": return this::peerList;
            case "Wallets": return this::wallets;
            case "Remote Control": return this::remoteControlIntro;
            case "Add peer": return () -> addPeer("");
            case "Swap controllers": return this::swapControllers;
            case "Search controllers": return this::controllerSearch;
            case "Advanced": return this::advanced;
            case "Fiat": return this::fiatSettings;
            case "About": return this::about;
            case "PIN code": return this::pinSettings;
            case "Backup/Recover": return this::backupRecover;
            case "Customization": return this::customization;
            case "Fiat price source": return this::fiatPriceSource;
            case "RBF/CPFP": return this::feeBumps;
            case "Node connection": return this::nodeConnection;
            case "Closed-channel history": return this::closedChannels;
            case "Payment history": return this::paymentHistory;
            case "Before you view your seed": return this::backup;
            case "Test seed recovery": return this::testRecovery;
            case "Create channel backup": return this::createChannelBackup;
            case "Test channel backup": return this::testChannelBackup;
            case "Receive XBT": return this::deposit;
            case "Open Channel": return this::coins;
            case "Send on chain": return this::sendOnChain;
            case "Receive Lightning": return this::receiveLightning;
            case "Create BOLT12": return this::createBolt12;
            case "BOLT12 offers": return this::bolt12Offers;
            case "Swap requests": return this::swapReceipts;
            case "Outgoing swaps": return this::outgoingSwaps;
            case "Pay Lightning": return this::payment;
            default: return null; // Never replay submissions or expose a seed by navigating back.
        }
    }
    private void recordNavigation(String title, Runnable restore) {
        if (title.equals("Home")) navigation.clear();
        else if (currentNavigation != null && !currentNavigation.title.equals(title)) {
            boolean ancestor = false;
            for (NavigationEntry entry : navigation) ancestor |= entry.title.equals(title);
            if (ancestor) {
                while (!navigation.isEmpty() && !navigation.pop().title.equals(title)) { }
            } else if (currentNavigation.restore != null) navigation.push(currentNavigation);
        }
        currentNavigation = new NavigationEntry(title, restore);
    }
    @Override public void onBackPressed() {
        if (WalletPin.locked(this)) { checkPinLock(); return; }
        if (walletPopup != null) { walletPopup.dismiss(); return; }
        if (!busy && WalletProfiles.isDraft(this, profile)) { cancelWalletSetup(); return; }
        if (screen.equals("Home")) { super.onBackPressed(); return; }
        if (busy) {
            Toast.makeText(this, "Please wait for the current operation to finish", Toast.LENGTH_SHORT).show();
            return;
        }
        if (navigation.isEmpty()) { home(); return; }
        NavigationEntry previous = navigation.pop();
        currentNavigation = previous;
        previous.restore.run();
    }
    private LinearLayout page;
    private FrameLayout pageHeader;
    private ImageView headerBrand;
    private FrameLayout headerRateViewport;
    private TextView headerRate;
    private float headerSlide;
    private android.animation.ValueAnimator headerSlideAnimation;
    private String viewingSwapHash;
    private String viewingSendHash;
    private View walletMenuAnchor;
    private PopupMenu walletPopup;
    private NodeService node;
    private boolean bound, busy, refreshingHome;
    private String profile;
    private String screen = "";
    private android.animation.ValueAnimator onboardingReveal;
    private long startingRevealAt;
    private int historyTab;
    private boolean channelDetailsFromHome;
    private String viewingSpliceChannel;
    private String viewingSpliceTxid;
    private TextView spliceDetailsText;
    private TextView spliceRefreshError;
    private final Set<String> expandedHomeChannels = new HashSet<>();
    private final Map<String, android.animation.ValueAnimator> channelExpansions = new HashMap<>();
    private final Set<String> reconnectingPeers = new HashSet<>();
    private final List<TextView> homeChannelBalances = new ArrayList<>();
    private final boolean[] homeActionsExpanded = new boolean[2];
    private final LinearLayout[] homeActionsViews = new LinearLayout[2];
    private final android.animation.ValueAnimator[] homeActionsAnimation = new android.animation.ValueAnimator[2];
    private final Button[] balanceAmounts = new Button[3];
    private final long[] balanceSats = new long[3];
    private TextView unconfirmedBalanceText;
    private long unconfirmedBalanceSats;
    private boolean ratesLoading;
    private long ratesGeneration;
    private long lastRatesAttempt;
    private org.json.JSONObject fiatRates;
    private AmountEntry activeAmountEntry;
    private ByteString receivingInvoiceHash;
    private ScrollView pageScroll;
    private OnboardingTitleView pageTitle;
    private String displayedSwapReceive;
    private TextView swapReceiveAmountText;
    private long swapReceiveAmountSats;
    private TextView receivedAmountText;
    private long receivedAmountSats;
    private EditText scanTarget;
    private boolean scanPayment;
    private String receivingAddress;
    private Set<String> receiveBaseline = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private final Runnable poll = new Runnable() {
        public void run() {
            if (node != null && !busy && (screen.equals("Home") || screen.equals("Starting..."))) home();
            if (node != null && !busy && screen.equals("Channel details") && viewingSpliceChannel != null) refreshSpliceDetails();
            if (node != null && !busy && screen.equals("Receive XBT")) checkDeposit();
            if (node != null && !busy && screen.equals("Lightning invoice")) checkLightningReceipt();
            if (node != null && !busy && screen.equals("Swap invoice") && viewingSwapHash != null) {
                try {
                    if (SwapReceive.needsRefresh(new org.json.JSONObject(prefs.getString("swap-receive-"+viewingSwapHash, "{}")))) refreshSwapReceive(viewingSwapHash, false);
                } catch (org.json.JSONException ignored) { /* Keep malformed saved records for explicit inspection. */ }
            }
            if (node != null && !busy && screen.equals("Review BTC swap") && viewingSendHash != null) {
                try {
                    org.json.JSONObject r = new org.json.JSONObject(prefs.getString("swap-send-" + viewingSendHash, "{}"));
                    org.json.JSONObject reply = r.optJSONObject("reply");
                    if (!r.has("last_error") && reply != null && reply.optBoolean("approved") && !reply.optBoolean("terminal") && (!reply.has("invoice") || prefs.contains("payment-" + viewingSendHash) || r.optBoolean("cancel_pending") || reply.optBoolean("cancel_requested")))
                        updateSwapSend(viewingSendHash, "status");
                } catch (Exception ignored) { }
            }
            handler.postDelayed(this, 5000);
        }
    };
    private boolean pinOpening, pinResumed;
    private final Runnable pinTimer = () -> checkPinLock();
    private void checkPinLock() {
        handler.removeCallbacks(pinTimer);
        if (!pinResumed) return;
        if (WalletPin.locked(this)) {
            getWindow().getDecorView().setAlpha(0f);
            if (!pinOpening) {
                pinOpening=true;
                startActivityForResult(new Intent(this,PinLockActivity.class),6050);
            }
        } else {
            getWindow().getDecorView().setAlpha(1f);
            if(WalletPin.enabled(this) && !WalletPin.restartOnly(this))handler.postDelayed(pinTimer,1000);
        }
    }
    @Override public void onUserInteraction() {
        super.onUserInteraction(); WalletPin.touch(this); checkPinLock();
    }
    @Override protected void onResume() {
        super.onResume(); pinResumed=true; checkPinLock(); handler.postDelayed(poll, 5000);
    }
    @Override protected void onPause() { pinResumed=false; handler.removeCallbacks(pinTimer); if(WalletPin.enabled(this))getWindow().getDecorView().setAlpha(0f); touchInProgress = false; handler.removeCallbacks(poll); super.onPause(); }
    private interface Job { void run() throws Exception; }
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { node = ((NodeService.LocalBinder) binder).service(); home(); }
        public void onServiceDisconnected(ComponentName name) { node = null; }
    };
    private WalletOpeningView launchWallet;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        profile = WalletProfiles.active(this);
        prefs = WalletProfiles.settings(this, profile);
        if (state != null) {
            pendingBackupName = state.getString("pending-backup-name");
            backupOperationProfile = state.getString("backup-operation-profile");
        }
        if (WalletProfiles.hasPendingDeletion(this)) { resumeWalletRemoval(); return; }
        if (state == null) launchWallet = new WalletOpeningView(this, () -> {
            WalletOpeningView cover = launchWallet; launchWallet = null;
            if (cover != null && cover.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) cover.getParent()).removeView(cover);
        });
        openSelectedWallet();
    }
    private void openSelectedWallet() {
        if (BuildConfig.MAINNET_TEST && !prefs.contains("network")) {
            prefs.edit().putString("network", "mainnet").commit();
        }
        if (BuildConfig.MAINNET_TEST && !prefs.getBoolean("dnsDiscoveryConfigured", false)) {
            prefs.edit().remove("syncPeer").putBoolean("useTor", false)
                .putString("feeUrl", "https://mempool.guide/api/v1/fees/precise")
                .putBoolean("dnsDiscoveryConfigured", true).commit();
        }
        if (BuildConfig.MAINNET_TEST && !prefs.getBoolean("modeChoiceMade", false)) modes();
        else if ((prefs.getString("mode", "").equals("easy") || prefs.getString("mode", "").equals("remote"))) start(); else modes();
    }
    private void page(String title) {
        page(title, false);
    }
    private void page(String title, boolean sensitive) {
        page(title, sensitive, pageRoute(title));
    }
    private int pageSurfaceIndex;
    private void page(String title, boolean sensitive, Runnable restore) {
        pageSurfaceIndex = 0;
        boolean changedTitle = !title.equals(screen);
        if (title.equals("Starting...") && changedTitle) startingRevealAt = android.os.SystemClock.elapsedRealtime();
        recordNavigation(title, restore);
        if (onboardingReveal != null) { onboardingReveal.cancel(); onboardingReveal = null; }
        activeAmountEntry = null;
        pageTitle = null; receivedAmountText = null; swapReceiveAmountText = null;
        unconfirmedBalanceText = null;
        for (android.animation.ValueAnimator animation : new ArrayList<>(channelExpansions.values())) animation.cancel();
        channelExpansions.clear(); homeChannelBalances.clear();
        // Allow remote UI testing only in debug builds on the valueless test chain.
        boolean mirrorable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && prefs.getString("network", "regtest").equals("regtest") && !sensitive;
        if (mirrorable) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (walletPopup != null) walletPopup.dismiss();
        walletMenuAnchor = null;
        updateUtxoAmounts = null;
        updateChannelDetailAmounts = null;
        detailChannelPoint = null;
        viewingSpliceChannel = null; viewingSpliceTxid = null; spliceDetailsText = null; spliceRefreshError = null;
        screen = title;
        ScrollView scroll = new ScrollView(this); pageScroll = scroll; page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.setBackground(new PaperBackgroundDrawable()); scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout setupContainer = null;
        if (WalletProfiles.isDraft(this, profile) && !WalletProfiles.hasPendingDeletion(this)) {
            // Keep the cancel footer after the form as its controls are appended.
            setupContainer = new LinearLayout(this); setupContainer.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(20), dp(24), dp(20), dp(8));
            setupContainer.addView(page, new LinearLayout.LayoutParams(-1, -2));
            scroll.addView(setupContainer);
        } else scroll.addView(page);
        if (launchWallet != null) {
            if (launchWallet.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) launchWallet.getParent()).removeView(launchWallet);
            FrameLayout stage = new FrameLayout(this);
            stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            stage.addView(launchWallet, new FrameLayout.LayoutParams(-1, -1));
            setContentView(stage);
        } else setContentView(scroll);
        if (headerSlideAnimation != null) headerSlideAnimation.cancel();
        headerSlideAnimation = null;
        headerSlide = prefs.getBoolean("header-fiat-rate", false) ? 1f : 0f;
        FrameLayout header = new FrameLayout(this); pageHeader = header;
        ImageView brand = new ImageView(this) {
            private final android.graphics.Paint lettering = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private Bitmap coin;
            private final android.graphics.Paint coinPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG);
            @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
                super.onSizeChanged(w,h,oldw,oldh);
                if(w>0)coin=HeaderCoinBitmap.atSize(getResources(),Math.round(w*84f/282f));
            }
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float unit = getWidth() / 282f;
                float middle = getHeight() / 2f;
                boolean showCoin=prefs.getBoolean("header-show-coin",true);
                float textLeft=(showCoin ? 94 : 47)*unit;
                if(showCoin && coin!=null)canvas.drawBitmap(coin,0,Math.round(middle-coin.getHeight()/2f),coinPaint);
                lettering.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
                lettering.setTextSize(80*unit);
                float available=184*unit;
                if(lettering.measureText("XBT")>available)
                    lettering.setTextSize(lettering.getTextSize()*available/lettering.measureText("XBT"));
                lettering.setShader(new android.graphics.LinearGradient(0,middle-32*unit,0,middle+32*unit,
                    new int[]{0xfff3e2bc,0xffe4cfa4,0xffbda179},null,android.graphics.Shader.TileMode.CLAMP));
                android.graphics.Rect ink=new android.graphics.Rect();
                lettering.getTextBounds("XBT",0,3,ink);
                int saved=canvas.save();
                canvas.clipRect(textLeft,0,textLeft+188*unit,getHeight());
                canvas.translate(-headerSlide*188*unit,0);
                float x=textLeft+(188*unit-lettering.measureText("XBT"))/2;
                canvas.drawText("XBT",x,middle-(ink.top+ink.bottom)/2f,lettering);
                canvas.restoreToCount(saved);
            }
        }; headerBrand = brand;
        brand.setContentDescription("XBT. Tap to show the fiat exchange rate.");
        header.addView(brand, new FrameLayout.LayoutParams(dp(282), dp(104), Gravity.CENTER));
        headerRate = new TextView(this) {
            private float gradientTextSize;
            private int gradientWidth;
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                if (gradientTextSize != getTextSize() || gradientWidth != getWidth()) {
                    gradientTextSize = getTextSize(); gradientWidth = getWidth();
                    getPaint().setShader(new android.graphics.LinearGradient(
                        0, 0, Math.max(1, getWidth()), Math.max(1, getTextSize()),
                        new int[]{0xffd3ad6b, 0xffdfbb7b, 0xffd4ac69, 0xffc39556},
                        new float[]{0f, .3f, .55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                }
                super.onDraw(canvas);
            }
        };
        headerRate.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
        headerRate.setGravity(Gravity.CENTER); headerRate.setTextSize(20 * 1.35f * 1.20f * 1.25f * 1.10f);
        headerRate.setTextColor(0xffe8d2a7);
        headerRate.setMaxLines(3);
        int[] fiatPriceSizes = new int[9];
        for (int i = 0; i < fiatPriceSizes.length; i++)
            fiatPriceSizes[i] = Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, (8 + i) * 1.35f * 1.20f * 1.25f * 1.10f, getResources().getDisplayMetrics()));
        headerRate.setAutoSizeTextTypeUniformWithPresetSizes(fiatPriceSizes, android.util.TypedValue.COMPLEX_UNIT_PX);
        FrameLayout rateViewport = new FrameLayout(this); headerRateViewport=rateViewport;
        rateViewport.setClipChildren(true);
        rateViewport.addView(headerRate, new FrameLayout.LayoutParams(-1, -1));
        header.addView(rateViewport, new FrameLayout.LayoutParams(dp(188), dp(104), Gravity.CENTER));
        rateViewport.setTranslationX(prefs.getBoolean("header-show-coin",true) ? dp(47) : 0);
        View.OnClickListener toggleRate = v -> {
            prefs.edit().putBoolean("header-fiat-rate", !prefs.getBoolean("header-fiat-rate", false)).apply();
            if (headerSlideAnimation != null) headerSlideAnimation.cancel();
            headerSlideAnimation = null;
            updateHeaderRate();
            headerSlideAnimation = android.animation.ValueAnimator.ofFloat(headerSlide, prefs.getBoolean("header-fiat-rate", false) ? 1f : 0f);
            headerSlideAnimation.setDuration(500);
            headerSlideAnimation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            headerSlideAnimation.addUpdateListener(animation -> {
                headerSlide = (float) animation.getAnimatedValue();
                positionHeaderRate();
            });
            headerSlideAnimation.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled) {
                        headerSlideAnimation = null;
                        updateHeaderRate();
                    }
                }
            });
            headerSlideAnimation.start();
            if (prefs.getBoolean("header-fiat-rate", false) && !prefs.getString("fiat-currency", "USD").equals("Off")) fetchFiatRates();
        };
        brand.setOnClickListener(toggleRate); headerRate.setOnClickListener(toggleRate);
        updateHeaderRate();
        // Warm the quote while the price is off-screen, before the first logo tap.
        if (!prefs.getString("fiat-currency", "USD").equals("Off")) fetchFiatRates();
        if (title.equals("Home")) {
            Button menu = new Button(this); styleButton(menu, false);
            menu.setText("⋮"); menu.setTextSize(36f * 1.15f); menu.setPadding(0, 0, 0, 0);
            menu.setBackground(null);
            menu.setContentDescription("Wallet menu");
            FrameLayout.LayoutParams menuSize = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
            header.addView(menu, menuSize); walletMenuAnchor = menu; menu.setOnClickListener(v -> more());
        }
        LinearLayout.LayoutParams headerSize = new LinearLayout.LayoutParams(-1, -2); headerSize.bottomMargin = dp(24);
        page.addView(header, headerSize);
        if (!title.equals("Home")) {
            OnboardingTitleView heading = new OnboardingTitleView(this); pageTitle = heading;
            heading.setText(pageTitleCase(title));
            heading.setLineSpacing(dp(3), 1f);
            page.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            heading.setTextSize(28);
            heading.setTypeface(getResources().getFont(R.font.source_sans_semibold));
            heading.setPadding(0, 0, 0, dp(20));
            revealOnboardingTitle(heading);
        }
        if (setupContainer != null) {
            Button cancel = button("Cancel setup", this::cancelWalletSetup);
            page.removeView(cancel);
            LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, -2);
            footer.setMargins(dp(20), dp(5), dp(20), dp(32));
            setupContainer.addView(cancel, footer);
        }
    }
    private static String pageTitleCase(String title) {
        StringBuilder result = new StringBuilder(title.length());
        boolean wordStart = true;
        for (int offset = 0; offset < title.length();) {
            int ch = title.codePointAt(offset);
            result.appendCodePoint(wordStart ? Character.toTitleCase(ch) : ch);
            if (Character.isLetterOrDigit(ch)) wordStart = false;
            else if (Character.isWhitespace(ch) || ch == '/' || ch == '-') wordStart = true;
            offset += Character.charCount(ch);
        }
        return result.toString();
    }
    private void revealOnboardingTitle(OnboardingTitleView heading) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        long elapsed = screen.equals("Starting...") ? Math.max(0, android.os.SystemClock.elapsedRealtime() - startingRevealAt) : 0;
        if (elapsed >= 500) return;
        float from = elapsed / 500f;
        heading.setReveal(from);
        onboardingReveal = android.animation.ValueAnimator.ofFloat(from, 1f);
        onboardingReveal.setDuration(500 - elapsed);
        onboardingReveal.setInterpolator(new android.view.animation.LinearInterpolator());
        onboardingReveal.addUpdateListener(frame -> heading.setReveal((float) frame.getAnimatedValue()));
        onboardingReveal.start();
    }
    private void updateHeaderRate() {
        if (headerBrand == null || headerRate == null) return;
        // Changing text can trigger auto-sizing and layout. Keep one stable price throughout a slide.
        if (headerSlideAnimation != null && headerSlideAnimation.isStarted()) return;
        positionHeaderRate();
        String currency = prefs.getString("fiat-currency", "USD");
        java.math.BigDecimal price = amountPrice();
        String value;
        if (currency.equals("Off")) value = "Fiat display is off";
        else if (price == null) value = currency + " rate unavailable";
        else {
            java.text.NumberFormat format = java.text.NumberFormat.getCurrencyInstance(Locale.US);
            format.setCurrency(java.util.Currency.getInstance(currency));
            format.setMinimumFractionDigits(0); format.setMaximumFractionDigits(0);
            value = format.format(price);
        }
        if (!value.contentEquals(headerRate.getText())) headerRate.setText(value);
        headerRate.setContentDescription(value + ". Tap to show the XBT logo.");
    }
    private void positionHeaderRate() {
        headerBrand.invalidate();
        headerRate.setTranslationX((1f - headerSlide) * dp(188));
        // Keep it laid out from the first frame; the viewport clips it completely at slide=0.
        headerRate.setImportantForAccessibility(headerSlide == 0 ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        headerBrand.setContentDescription(headerSlide >= .5f
            ? "Tap to show the XBT logo." : "XBT. Tap to show the fiat exchange rate.");
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private android.graphics.drawable.Drawable surface(int color, int radius, int border) {
        // Rebuilding this page for fresh balances must reproduce the same finishes.
        long seed = ((long)screen.hashCode() << 32) ^ pageSurfaceIndex++;
        return new PaperDrawable(color, radius, border, false, seed);
    }
    private void styleButton(Button button, boolean accent) {
        button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(getResources().getFont(R.font.wallet_humanist));
        button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{0xff8c8270, accent ? 0xfff5e7ce : 0xffedddbc}));
        button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(14), dp(12), dp(14), dp(12)); button.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(0xff211e18, 14, 0xff40382b));
        states.addState(new int[]{}, surface(accent ? 0xff70441f : 0xff30271e, 14, accent ? 0xffba8245 : 0xff81694b));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33e1b879), states, null));
    }
    private void styleDestructiveButton(Button button) {
        styleButton(button, false);
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{0xff8e7777, 0xfffff5f5}));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(0xff342326, 14, 0));
        states.addState(new int[]{}, surface(0xffa83240, 14, 0));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), states, null));
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
            for (int j = 0; j < rope.length; j++) colors[j] = (Integer) blend.evaluate(softened, 0xff12120f, rope[j]);
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
        view.setTextColor(0xffc2b69f); view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(8), 0, dp(12)); page.addView(view); return view;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label);
        boolean accent = label.startsWith("Review") || label.startsWith("Create") || label.equals("Send") || label.equals("Save") || label.equals("Check backup");
        styleButton(b, accent);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(5); params.bottomMargin = dp(5);
        page.addView(b, params); b.setOnClickListener(v -> {
            if (busy) { showOperationInProgress(); return; }
            if (label.equals("Back") || label.startsWith("Back to ")) onBackPressed(); else action.run();
        }); return b;
    }
    private void showOperationInProgress() {
        Toast.makeText(this, "Please wait for the current operation to finish", Toast.LENGTH_SHORT).show();
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
        return field(page, hint, value);
    }
    private EditText field(LinearLayout container, String hint, String value) {
        EditText e = new EditText(this); setFieldHint(e, hint); e.setText(value); e.setSingleLine(true); e.setTextSize(16);
        e.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}}, new int[]{0xff958a75,0xfff2e6ce}));
        e.setHintTextColor(0xffa89b82); e.setPadding(dp(14), dp(14), dp(14), dp(14)); e.setMinimumHeight(dp(54));
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, surface(0xff191813, 12, 0xff393327));
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff282219, 12, 0xffd6b47b));
        background.addState(new int[]{}, surface(0xff1e1b15, 12, 0xff625039)); e.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(4); params.bottomMargin = dp(10);
        container.addView(e, params); return e;
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
    private void confirmAction(String title, String message, String action, Runnable confirmed) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(20), dp(20), dp(20));
        TextView heading = new TextView(this); heading.setText(pageTitleCase(title)); heading.setTextSize(20);
        heading.setTextColor(0xfff2e6ce); heading.setTypeface(getResources().getFont(R.font.wallet_humanist), android.graphics.Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(16)); form.addView(heading);
        ScrollView scroll = new ScrollView(this);
        TextView details = new TextView(this); details.setText(message); details.setTextSize(15);
        details.setTextColor(0xffc2b69f); details.setLineSpacing(dp(3), 1f);
        details.setPadding(0, 0, 0, dp(16)); scroll.addView(details);
        form.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        LinearLayout actions = new LinearLayout(this); actions.setBaselineAligned(false);
        Button cancel = new Button(this); cancel.setText("Cancel"); styleButton(cancel, false);
        Button accept = new Button(this); accept.setText(action); styleButton(accept, true);
        if (action.equals("Delete")) styleDestructiveButton(accept);
        LinearLayout.LayoutParams cancelSize = new LinearLayout.LayoutParams(0, -2, 1); cancelSize.rightMargin = dp(5);
        LinearLayout.LayoutParams acceptSize = new LinearLayout.LayoutParams(0, -2, 1); acceptSize.leftMargin = dp(5);
        actions.addView(cancel, cancelSize); actions.addView(accept, acceptSize); form.addView(actions);
        AlertDialog dialog = new WalletDialogBuilder().setView(form).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        accept.setOnClickListener(v -> { accept.setEnabled(false); dialog.dismiss(); confirmed.run(); });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(surface(0xff12120f, 18, 0xff69563d));
    }
    private void copyError(String message) {
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Wallet error", message));
        Toast.makeText(this, "Error copied", Toast.LENGTH_SHORT).show();
    }
    private TextView copyableError(String message) {
        TextView failure=text(message);
        failure.setOnClickListener(v -> copyError(message));
        return failure;
    }
    private void error(Exception e) {
        String message=e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage();
        AlertDialog dialog=new WalletDialogBuilder().setTitle("Could not complete action").setMessage(message)
            .setNeutralButton("Copy error", (d,which) -> copyError(message)).setPositiveButton("OK", null).show();
        TextView details=dialog.findViewById(android.R.id.message);
        if (details!=null) details.setOnClickListener(v -> copyError(message));
    }
    private void work(Job job) {
        if (busy || node == null) return;
        busy = true;
        node.io.execute(() -> { try { job.run(); } catch (Exception e) { runOnUiThread(() -> error(e)); }
            finally { runOnUiThread(() -> busy = false); } });
    }
    // Balance polling must not lock navigation or consume button taps. Keep it on
    // the node's serial executor so a user operation still cannot overlap its RPCs.
    private void homeRefreshWork(Job job) {
        if (busy || refreshingHome || node == null) return;
        refreshingHome = true;
        final LinearLayout originPage = page;
        try {
            node.io.execute(() -> {
                try { job.run(); }
                catch (Exception e) { runOnUiThread(() -> {
                    if (!isDestroyed() && page == originPage && !busy) error(e);
                }); }
                finally { runOnUiThread(() -> refreshingHome = false); }
            });
        } catch (RuntimeException e) {
            refreshingHome = false;
            error(e);
        }
    }
    private void wallets() {
        page("Wallets");
        text("Only the selected on-phone wallet runs. Switching stops its node; its channels are not monitored until you return. Remote nodes keep running independently.");
        for (String id : WalletProfiles.ids(this)) {
            android.content.SharedPreferences settings = WalletProfiles.settings(this, id);
            String mode = settings.getString("mode", "");
            String kind = mode.equals("remote") ? "Remote Control" : mode.equals("easy") ? "On this phone" : "Setup incomplete";
            button(WalletProfiles.label(this, id) + " · " + kind + (id.equals(profile) ? " · Selected" : ""), () -> {
                if (id.equals(profile)) { if (mode.isEmpty()) modes(); else home(); }
                else switchWallet(id);
            });
        }
        button("Rename selected wallet", this::renameWallet);
        styleDestructiveButton(button("Delete selected wallet", this::deleteWallet));
        text("To rename or delete another wallet, select it first.");
        button("Create a new wallet", this::newWallet);
        button("Home", this::home);
    }
    private void renameWallet() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView caption = new TextView(this); caption.setText("Name"); caption.setTextSize(15);
        caption.setTextColor(0xffc2b69f); form.addView(caption);
        EditText name = field(form, "Optional", prefs.getString("wallet-name", ""));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSelectAllOnFocus(true);
        name.setContentDescription("Wallet name, up to 32 characters");
        TextView help = new TextView(this); help.setText("Leave blank to use the default name."); help.setTextSize(13);
        help.setTextColor(0xffc2b69f); help.setPadding(0, dp(8), 0, dp(8)); form.addView(help);
        AlertDialog dialog = new WalletDialogBuilder().setTitle("Rename wallet").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (value.isEmpty()) edit.remove("wallet-name"); else edit.putString("wallet-name", value);
            if (!edit.commit()) { error(new Exception("Could not save the wallet name.")); return; }
            dialog.dismiss(); wallets();
        });
    }
    private void newWallet() {
        // Do not register a draft until the current runtime has stopped successfully.
        profileWork(() -> {
            if (node != null) node.stopForSwitch();
            String id = WalletProfiles.createDraft(this);
            WalletProfiles.activate(this, id);
            handler.removeCallbacks(poll);
            runOnUiThread(this::restartSelectedWallet);
        });
    }
    private void cancelWalletSetup() {
        profileWork(() -> {
            if (!WalletProfiles.isDraft(this, profile)) throw new Exception("This wallet has already been created.");
            if (node != null) node.stopForSwitch();
            WalletProfiles.cancelDraft(this, profile);
            handler.removeCallbacks(poll);
            runOnUiThread(this::restartSelectedWallet);
        });
    }
    private void switchWallet(String id) {
        if (busy) return;
        busy = true; handler.removeCallbacks(poll);
        Runnable switchTask = () -> {
            try {
                if (node != null) node.stopForSwitch();
                WalletProfiles.activate(this, id);
                runOnUiThread(this::restartSelectedWallet);
            } catch (Exception e) {
                runOnUiThread(() -> { busy = false; handler.postDelayed(poll, 5000); error(e); });
            }
        };
        Toast.makeText(this, "Switching wallets…", Toast.LENGTH_LONG).show();
        if (node != null) node.io.execute(switchTask); else new Thread(switchTask, "wallet-switch").start();
    }
    private void restartSelectedWallet() {
        startActivity(new Intent(this, WalletRestartActivity.class)
            .putExtra("old_pid", android.os.Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private void profileWork(Job job) {
        if (busy) return;
        busy = true;
        Runnable action = () -> {
            try { job.run(); }
            catch (Exception e) { runOnUiThread(() -> error(e)); }
            finally { runOnUiThread(() -> busy = false); }
        };
        if (node != null) node.io.execute(action); else new Thread(action, "wallet-profile").start();
    }
    private void checkWalletDeletion() throws Exception {
        if (!WalletProfiles.active(this).equals(profile)) throw new Exception("The selected wallet changed. Open Wallets and try again.");
        if (node != null) { node.checkDeletion(); return; }
        String mode = prefs.getString("mode", "");
        if (mode.equals("remote")) return;
        if (mode.isEmpty() && new Vault(this, profile).get("seed") == null && new Vault(this, profile).get("password") == null) return;
        throw new Exception("Open this wallet and wait for it to synchronize before deleting it, so its funds and channels can be checked.");
    }
    private void deleteWallet() {
        profileWork(() -> {
            checkWalletDeletion();
            final boolean remote = prefs.getString("mode", "").equals("remote");
            final String name = WalletProfiles.label(this, profile);
            runOnUiThread(() -> confirmAction(remote ? "Are you sure?" : "Delete “" + name + "”?",
                remote ? "Remove “" + name + "” from this phone?\n\nThis removes this wallet’s saved connection, credentials and labels from this phone. Your remote node, funds and channels will remain unchanged. You will need the connection credentials to add it again."
                    : "This permanently removes this wallet’s seed, node data, labels and payment history from this phone. This cannot be undone. Keep a seed backup if you might need to recover funds sent to its old addresses later.",
                "Delete", () -> profileWork(() -> {
                    // Recheck after confirmation, then close every database before recording removal.
                    checkWalletDeletion();
                    if (node != null) node.stopForSwitch();
                    handler.removeCallbacks(poll);
                    WalletProfiles.beginDeletion(this, profile);
                    runOnUiThread(this::restartSelectedWallet);
                })));
        });
    }
    private void resumeWalletRemoval() {
        busy = true; page("Removing wallet", true, null);
        text("Removing this wallet’s local data…");
        new Thread(() -> {
            try {
                WalletProfiles.finishDeletions(this);
                // Fresh process discards any cached references to removed preferences or keys.
                runOnUiThread(this::restartSelectedWallet);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false; page("Wallet removal interrupted", true, null);
                    text(e.getMessage()); button("Retry removal", this::resumeWalletRemoval);
                });
            }
        }, "wallet-removal").start();
    }
    private java.util.function.Consumer<String> connectionScan;
    private final TorTransport setupTor = NodeService.processTor;
    private void remoteControlIntro() {
        page("Remote Control");
        text("This method requires you to have a Lightning node already set up on the XBT network and a way to connect your phone to it. Tor is supported, but will slow things down significantly. Connect via Start Tunnel, VPN in to your home network, or use Tailscale for a far better user experience.");
        button("Continue", this::remoteConnection);
        button("Back", this::modes);
    }
    private void remoteConnection() {
        page("Remote node connection", true, this::remoteConnection);
        text("Core Lightning · CLNrest");
        text("Scan the CLNrest connection QR from Start9, or enter the connection details below. The API URL is different from the Lightning peer URI.");
        String endpoint = "", runeValue = "", certificateValue = "", nodeIdValue = "";
        try {
            String saved = new Vault(this, profile).get("remote-config");
            if (saved != null) {
                org.json.JSONObject c = new org.json.JSONObject(saved);
                endpoint = c.optString("endpoint"); runeValue = c.optString("rune"); certificateValue = c.optString("certificate"); nodeIdValue = c.optString("nodeId");
            }
        } catch (Exception e) { error(e); }
        text("Wallet name"); EditText name = field("Optional", prefs.getString("wallet-name", ""));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        text("API URL"); EditText url = field("https://node:port", endpoint);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        text("Node ID"); EditText nodeId = field("Optional — verified automatically", nodeIdValue);
        text("Rune"); EditText rune = field("Core Lightning access rune", runeValue);
        rune.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        rune.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        text("Server CA certificate"); EditText certificate = field("Optional — PEM for private HTTPS certificates", certificateValue);
        certificate.setSingleLine(false); certificate.setMaxLines(4);
        text("Onion URLs use Tor automatically. Direct URLs use your phone’s current network, VPN or tunnel. For private HTTPS, install your Start9 CA certificate on your phone or paste it above.");
        java.util.function.Consumer<String> importConfig = raw -> {
            try {
                RemoteConnection config = RemoteConnection.parse(raw);
                url.setText(config.endpoint); rune.setText(config.rune); certificate.setText(config.certificate); nodeId.setText(config.nodeId);
                Toast.makeText(this, "Connection details imported. Check and connect below.", Toast.LENGTH_LONG).show();
            } catch (Exception e) { error(e); }
        };
        Button scanConnection = button("Scan connection QR", () -> { connectionScan = importConfig; launchScanner("Scan Start9 CLNrest connection QR"); });
        Button pasteConnection = button("Paste connection URI", () -> {
            ClipData clip = getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) { error(new Exception("The clipboard is empty.")); return; }
            importConfig.accept(clip.getItemAt(0).coerceToText(this).toString());
        });
        page.removeView(scanConnection); page.addView(scanConnection, 4);
        page.removeView(pasteConnection); page.addView(pasteConnection, 5);
        TextView status = text("");
        button("Test and connect", () -> {
            if (busy) return;
            final RemoteConnection config;
            try { config = new RemoteConnection(url.getText().toString(), rune.getText().toString(), certificate.getText().toString(), nodeId.getText().toString()); }
            catch (Exception e) { error(e); return; }
            final String label = name.getText().toString().trim();
            busy = true; status.setText("Checking connection…");
            new Thread(() -> {
                try {
                    if (config.onion) setupTor.start(this, message -> runOnUiThread(() -> status.setText(message)));
                    ClnRemote client = new ClnRemote(config, prefs.getString("network", "mainnet"));
                    String verified = client.verify();
                    org.json.JSONObject saved = new org.json.JSONObject().put("endpoint", config.endpoint).put("rune", config.rune).put("certificate", config.certificate).put("nodeId", verified);
                    // Existing embedded profiles can never be converted into remote profiles.
                    if (prefs.getString("mode", "").equals("easy")) throw new Exception("Create a new wallet profile for Remote Control.");
                    WalletProfiles.completeSetup(this, profile);
                    new Vault(this, profile).put("remote-config", saved.toString());
                    if (!prefs.edit().putString("mode", "remote").putBoolean("modeChoiceMade", true).putString("wallet-name", label).commit()) throw new Exception("Could not save the remote wallet.");
                    runOnUiThread(() -> {
                        busy = false;
                        if (node != null) switchWallet(profile); else start();
                    });
                } catch (Exception e) { runOnUiThread(() -> { busy = false; status.setText("Connection not saved."); error(e); }); }
            }, "remote-connect").start();
        });
        button("Back", prefs.getString("mode", "").isEmpty() ? this::remoteControlIntro : this::advanced);
    }
    private void modes() {
        page("Your Lightning Wallet");
        text("Easy Mode runs your XBT Lightning wallet on this phone. Remote Control connects to your own Lightning node.");
        button("Easy Mode", this::setup);
        button("Remote Control", this::remoteControlIntro);
    }
    private void createWalletIntro() {
        page("Create your wallet", false, this::createWalletIntro);
        text("Your keys stay on this device. Back up the seed to recover on-chain funds; channel recovery also requires channel backups.");
        button("Create wallet", this::nameWallet);
    }
    private void nameWallet() {
        page("Name your wallet", false, this::nameWallet);
        text("Name");
        EditText name = field("Optional", prefs.getString("wallet-name", "Neutrino"));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setContentDescription("Wallet name, up to 32 characters");
        button("Continue", () -> finishWalletCreation(name.getText().toString().trim()));
        button("Not now", () -> finishWalletCreation(""));
    }
    private void finishWalletCreation(String name) {
        work(() -> {
            // Guard before changing metadata: revisiting onboarding must never alter an existing wallet.
            if (node.walletState() != Stateservice.WalletState.NON_EXISTING)
                throw new Exception("A wallet already exists on this device. Return Home to use it.");
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (name.isEmpty()) edit.remove("wallet-name"); else edit.putString("wallet-name", name);
            if (!edit.commit()) throw new Exception("Could not save the wallet name. Please try again.");
            WalletProfiles.completeSetup(this, profile);
            node.createWallet();
            runOnUiThread(() -> {
                navigation.clear(); currentNavigation = null;
                page("Wallet created");
                if (!name.isEmpty()) text(name + " is ready.");
                button("Back up seed", this::backup);
                button("I'll do it later, I promise", this::home);
            });
        });
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
        page("Starting...");
        Intent intent = new Intent(this, NodeService.class); startForegroundService(intent);
        bound = bindService(intent, connection, BIND_AUTO_CREATE);
    }
    private void home() {
        if (node == null && prefs.getString("mode", "").isEmpty()) { modes(); return; }
        // Keep the sync screen visible while the asynchronous status query runs.
        // Replacing it with Home here causes a flash on every startup poll.
        if ((screen.equals("Home") || screen.equals("Starting...")) && node != null && node.rpcReady) { refresh(); return; }
        page(node == null || !node.rpcReady ? "Starting..." : "Home");
        if (node == null) { text("Connecting to node…"); return; }
        text(node.status);
        if (!node.rpcReady) {
            button("Wallets", this::wallets); button("Refresh", this::home);
            boolean remote = node.isRemote();
            Button connection = button(remote ? "Remote Control connection" : "Node connection", remote ? this::remoteConnection : this::nodeConnection);
            long remaining = 5000 - (android.os.SystemClock.elapsedRealtime() - node.startupAt);
            if (!remote && remaining > 0) {
                connection.setVisibility(View.GONE);
                handler.postDelayed(() -> {
                    if (connection.isAttachedToWindow() && node != null && !node.rpcReady)
                        connection.setVisibility(View.VISIBLE);
                }, remaining);
            }
            return;
        }
        button("Refresh wallet", this::refresh);
        refresh();
    }
    private void refresh() {
        final LinearLayout originPage = page;
        homeRefreshWork(() -> {
            Stateservice.WalletState state = node.walletState();
            if (state == Stateservice.WalletState.NON_EXISTING) {
                runOnUiThread(() -> { if (page == originPage && !busy) createWalletIntro(); }); return;
            }
            if (state == Stateservice.WalletState.LOCKED) { node.unlock(); runOnUiThread(() -> { if (page == originPage && !busy) home(); }); return; }
            if (state != Stateservice.WalletState.SERVER_ACTIVE) {
                SyncProgress progress = node.syncProgress();
                runOnUiThread(() -> {
                    if (page != originPage || busy) return;
                    page("Starting...");
                    text(state == Stateservice.WalletState.UNLOCKED ? "Wallet unlocked · synchronizing chain" : "Preparing wallet");
                    if (progress.target > 0) {
                        syncProgressBar("Block headers", progress.headers, progress.target, progress.percent(progress.headers));
                        syncProgressBar("Filter headers", progress.filters, progress.target, progress.percent(progress.filters));
                        text(progress.headers >= progress.target && progress.filters >= progress.target
                            ? progress.stage : "Initial sync is running. Progress updates automatically.");
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
            LightningOuterClass.ListPeersResponse peers = LightningOuterClass.ListPeersResponse.parseFrom(
                node.rpc("listPeers", LightningOuterClass.ListPeersRequest.getDefaultInstance()));
            rememberPeerAddresses(peers);
            Set<String> connectedPeers = new HashSet<>();
            for (LightningOuterClass.Peer peer : peers.getPeersList()) connectedPeers.add(peer.getPubKey());
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
                if (page != originPage || busy || touchInProgress || walletPopup != null || !channelExpansions.isEmpty() || (headerSlideAnimation != null && headerSlideAnimation.isRunning()) || ((homeActionsAnimation[0] != null && homeActionsAnimation[0].isRunning()) || (homeActionsAnimation[1] != null && homeActionsAnimation[1].isRunning()))) return;
                page("Home");
                long channelFunds = 0, receivable = 0;
                for (LightningOuterClass.Channel channel : active.getChannelsList()) {
                    channelFunds = Math.addExact(channelFunds, channel.getLocalBalance());
                    receivable = Math.addExact(receivable, channel.getRemoteBalance());
                }
                if (!info.getSyncedToChain()) text("Syncing chain · block " + info.getBlockHeight());
                unconfirmedBalanceSats = balance.getUnconfirmedBalance();
                homeBalances(balance.getTotalBalance(), channelFunds, receivable);
                boolean hasPublic = false;
                for (LightningOuterClass.Channel c : active.getChannelsList()) hasPublic |= !c.getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.PendingOpenChannel c : pending.getPendingOpenChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.ClosedChannel c : pending.getPendingClosingChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.ForceClosedChannel c : pending.getPendingForceClosingChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                for (LightningOuterClass.PendingChannelsResponse.WaitingCloseChannel c : pending.getWaitingCloseChannelsList()) hasPublic |= !c.getChannel().getPrivate();
                if (hasPublic && !node.isRemote() && balance.getReservedBalanceAnchorChan() > 0)
                    text("Required on-chain reserve: " + displayAmount(balance.getReservedBalanceAnchorChan()));
                homeScanButton();
                homeChannelStatus(active, pending, connectedPeers, Integer.toUnsignedLong(info.getBlockHeight()));
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
    private void homeChannelStatus(LightningOuterClass.ListChannelsResponse active,
            LightningOuterClass.PendingChannelsResponse pending, Set<String> connectedPeers, long height) {
        if (active.getChannelsCount() == 0 && pending.getPendingOpenChannelsCount() == 0) return;
        LinearLayout channels = new LinearLayout(this); channels.setOrientation(LinearLayout.VERTICAL);
        boolean open = prefs.getBoolean("home-channels-expanded", true);
        Button heading = button(open ? "Channels ▾" : "Channels ▸", () -> {});
        heading.setBackground(null);
        heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        heading.setOnClickListener(v -> {
            boolean expand = !prefs.getBoolean("home-channels-expanded", true);
            prefs.edit().putBoolean("home-channels-expanded", expand).apply();
            expandedHomeChannels.clear();
            if (expand) {
                for (String key : new ArrayList<>(channelExpansions.keySet())) {
                    if (!key.equals("channels-section")) channelExpansions.remove(key).cancel();
                }
                for (int i = 0; i < channels.getChildCount(); i++) {
                    View details = (View) channels.getChildAt(i).getTag();
                    details.setVisibility(View.GONE); details.setAlpha(0f);
                    details.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
                }
            }
            heading.setText(expand ? "Channels ▾" : "Channels ▸");
            heading.setContentDescription("Channels. " + (expand ? "Expanded" : "Collapsed") + ". Tap to toggle.");
            animateChannelsSection(channels, expand);
        });
        heading.setContentDescription("Channels. " + (open ? "Expanded" : "Collapsed") + ". Tap to toggle.");
        channels.setVisibility(open ? View.VISIBLE : View.GONE); channels.setAlpha(open ? 1f : 0f);
        page.addView(channels, new LinearLayout.LayoutParams(-1, -2));
        int number = 1;
        for (LightningOuterClass.Channel channel : active.getChannelsList()) {
            String peer = channel.getRemotePubkey();
            boolean online = channel.getActive() || connectedPeers.contains(peer);
            boolean retrying = reconnectingPeers.contains(peer);
            String status = online ? "Connected" : retrying ? "Reconnecting" : "Disconnected";
            int color = channel.getActive() ? 0xff65d89a : online || retrying ? 0xffffb454 : 0xffed6b73;
            homeChannelRow(channels, SpliceStatus.identity(channel), number++, peer, status, color, "", channel, !online,
                channel.getLocalBalance(), channel.getRemoteBalance(), () -> channelDetails(channel));
        }
        for (LightningOuterClass.PendingChannelsResponse.PendingOpenChannel pendingChannel : pending.getPendingOpenChannelsList()) {
            LightningOuterClass.PendingChannelsResponse.PendingChannel channel = pendingChannel.getChannel();
            String peer = channel.getRemoteNodePub();
            boolean online = connectedPeers.contains(peer);
            boolean retrying = reconnectingPeers.contains(peer);
            String status = online ? "Connected" : retrying ? "Reconnecting" : "Disconnected";
            long remaining = Integer.toUnsignedLong(pendingChannel.getConfirmationsUntilActive());
            long confirmedAt = Integer.toUnsignedLong(pendingChannel.getConfirmationHeight());
            String key = "channel-required-confs-" + channel.getChannelPoint();
            long required = prefs.getLong(key, 0);
            if (remaining > 0) {
                long total = remaining + (confirmedAt == 0 ? 0 : Math.max(0, height - confirmedAt + 1));
                if (total != required) prefs.edit().putLong(key, total).apply();
                required = total;
            }
            String confirmations = ChannelStatus.confirmations(remaining, confirmedAt, height, required);
            homeChannelRow(channels, channel.getChannelPoint(), number++, peer, status,
                online || retrying ? 0xffffb454 : 0xffed6b73, confirmations, null, !online,
                channel.getLocalBalance(), channel.getRemoteBalance(),
                () -> pendingChannelDetails(channel, "Pending opening · " + pendingChannel.getConfirmationsUntilActive() + " confirmations until active"));
        }
    }
    private void homeChannelRow(LinearLayout channels, String point, int number, String peer, String status, int color, String confirmations, LightningOuterClass.Channel spliceChannel, boolean disconnected, long local, long remote, Runnable details) {
        // Preserve the Home channel rows at their previous font scale, including accessibility scaling.
        android.content.res.Configuration channelConfig = new android.content.res.Configuration(getResources().getConfiguration());
        channelConfig.fontScale /= 1.10f;
        Context channelContext = createConfigurationContext(channelConfig);
        String label = prefs.getString("channel-label-" + point, "");
        if (label.isEmpty()) label = "Channel " + number;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView name = new TextView(channelContext);
        name.setText(label); name.setTextSize(16); name.setTextColor(0xffedddbc);
        name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (!confirmations.isEmpty()) {
            TextView confirmation = new TextView(channelContext);
            confirmation.setText(confirmations); confirmation.setTextSize(11);
            confirmation.setTextColor(0xffc2b69f);
            row.addView(confirmation, new LinearLayout.LayoutParams(-2, -2));
        }
        int[] lightColor = {color};
        View light = new View(this) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                // A shared clock keeps the pulse smooth across five-second home refreshes.
                double phase = (android.os.SystemClock.uptimeMillis() % 4000L) * Math.PI * 2 / 4000;
                paint.setColor(lightColor[0]);
                paint.setAlpha((int) (255 * (.35 + .65 * (1 - Math.cos(phase)) / 2)));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, paint);
                if (isShown() && getWindowVisibility() == View.VISIBLE) postInvalidateDelayed(50);
            }
        };
        LinearLayout.LayoutParams lightSize = new LinearLayout.LayoutParams(dp(12), dp(12));
        lightSize.leftMargin = dp(12); lightSize.rightMargin = dp(12);
        row.addView(light, lightSize);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View liquidity = new View(this) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private android.graphics.Shader greenGradient;
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                greenGradient = new android.graphics.LinearGradient(0, 0, 0, Math.max(1, h),
                    0xff35855b, 0xff246545, android.graphics.Shader.TileMode.CLAMP);
            }
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                float fraction = local + (double) remote <= 0 ? 0 : (float) (local / (local + (double) remote));
                android.graphics.Path outline = new android.graphics.Path();
                outline.addRoundRect(0, 0, getWidth(), getHeight(), getHeight() / 2f, getHeight() / 2f, android.graphics.Path.Direction.CW);
                int saved = canvas.save(); canvas.clipPath(outline);
                paint.setColor(0xffb59b72); canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                paint.setShader(greenGradient);
                canvas.drawRect(0, 0, getWidth() * fraction, getHeight(), paint);
                paint.setShader(null);
                canvas.restoreToCount(saved);
            }
        };
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(-1, dp(10));
        barSize.leftMargin = dp(4); barSize.rightMargin = dp(12); barSize.bottomMargin = dp(8);
        card.addView(liquidity, barSize);
        String spliceStatus = spliceChannel == null ? "" : SpliceStatus.summary(spliceChannel, this::displayAmount);
        if (!spliceStatus.isEmpty()) {
            TextView splice = new TextView(channelContext); splice.setText(spliceStatus);
            splice.setTag(spliceChannel); homeChannelBalances.add(splice);
            splice.setTextSize(12.6f); splice.setTextColor(0xffffb454);
            splice.setPadding(dp(4), 0, dp(12), dp(8));
            card.addView(splice, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout expanded = new LinearLayout(this); expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setPadding(dp(4), dp(4), dp(12), dp(12));
        LinearLayout balanceRow = new LinearLayout(this);
        for (int side = 0; side < 2; side++) {
            long value = side == 0 ? local : remote;
            TextView balances = new TextView(channelContext);
            balances.setText((side == 0 ? "Local: " : "Remote: ") + displayAmount(value));
            balances.setTextSize(12.6f); balances.setTextColor(0xffc2b69f);
            balances.setGravity(side == 0 ? Gravity.START : Gravity.END);
            balances.setTag(new long[]{value, side}); homeChannelBalances.add(balances);
            balanceRow.addView(balances, new LinearLayout.LayoutParams(0, -2, 1));
        }
        expanded.addView(balanceRow);
        LinearLayout statusRow = new LinearLayout(this); statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView statusTitle = new TextView(channelContext); statusTitle.setText("Status:"); statusTitle.setTextSize(12.6f); statusTitle.setTextColor(0xffc2b69f);
        statusRow.addView(statusTitle, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout connection = new LinearLayout(this); connection.setGravity(Gravity.CENTER_VERTICAL);
        TextView statusText = new TextView(channelContext); statusText.setText(status); statusText.setTextSize(12.6f); statusText.setTextColor(0xffc2b69f);
        statusText.setGravity(Gravity.END);
        connection.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        if (disconnected) {
            Button retry = new Button(channelContext); styleButton(retry, false); retry.setText("↻"); retry.setTextSize(13.65f);
            retry.setPadding(0, 0, 0, 0); retry.setMinHeight(0); retry.setMinimumHeight(0);
            retry.setBackground(null);
            retry.setContentDescription("Reconnect to channel peer"); retry.setEnabled(!reconnectingPeers.contains(peer));
            android.animation.ObjectAnimator spin = android.animation.ObjectAnimator.ofFloat(retry, View.ROTATION, 0f, 360f);
            spin.setDuration(900); spin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());
            retry.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) { spin.cancel(); }
            });
            retry.setOnClickListener(v -> connectPeerAddress(peer, null, () -> {
                retry.setEnabled(false); statusText.setText("Reconnecting"); lightColor[0] = 0xffffb454;
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) spin.start();
            }, () -> { spin.cancel(); retry.setRotation(0f); home(); }));
            connection.addView(retry, new LinearLayout.LayoutParams(dp(30.03f), dp(30.03f)));
        }
        statusRow.addView(connection, new LinearLayout.LayoutParams(0, -2, 1));
        expanded.addView(statusRow);
        TextView more = new TextView(channelContext); more.setText("Channel details"); more.setTextSize(12.6f); more.setTextColor(0xffe0c18c);
        more.setPadding(0, dp(10), 0, dp(10)); more.setOnClickListener(v -> details.run());
        expanded.addView(more);
        card.addView(expanded, new LinearLayout.LayoutParams(-1, -2));
        card.setTag(expanded);
        expanded.setVisibility(expandedHomeChannels.contains(point) ? View.VISIBLE : View.GONE);
        expanded.setAlpha(expandedHomeChannels.contains(point) ? 1f : 0f);
        card.setContentDescription(label + ". " + status + ". " + confirmations + ". " + spliceStatus + ". Local liquidity on the left, remote on the right. Tap to show or hide balances. Touch and hold to edit the channel label.");
        card.setOnLongClickListener(v -> {
            labelChannel(point, this::home);
            return true;
        });
        card.setFocusable(true); card.setOnClickListener(v -> {
            boolean show = !expandedHomeChannels.contains(point);
            android.animation.ValueAnimator prior = channelExpansions.remove(point);
            if (prior != null) prior.cancel();
            if (show) expandedHomeChannels.add(point); else expandedHomeChannels.remove(point);
            int start = expanded.getVisibility() == View.GONE ? 0 : expanded.getHeight();
            float alpha = expanded.getAlpha();
            expanded.setVisibility(View.VISIBLE);
            expanded.measure(View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int end = show ? expanded.getMeasuredHeight() : 0;
            android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0, 1);
            animation.setDuration(260);
            animation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animation.addUpdateListener(frame -> {
                float progress = (float) frame.getAnimatedValue();
                expanded.getLayoutParams().height = Math.round(start + (end - start) * progress);
                expanded.setAlpha(alpha + ((show ? 1f : 0f) - alpha) * progress);
                expanded.requestLayout();
            });
            animation.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (channelExpansions.get(point) == animation) channelExpansions.remove(point);
                    if (cancelled) return;
                    expanded.setVisibility(show ? View.VISIBLE : View.GONE);
                    expanded.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
                    expanded.requestLayout();
                }
            });
            channelExpansions.put(point, animation); animation.start();
        });
        channels.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
    private void animateChannelsSection(LinearLayout channels, boolean show) {
        animateExpandable("channels-section", channels, show);
    }
    private void animateExpandable(String key, LinearLayout channels, boolean show) {
        android.animation.ValueAnimator previous = channelExpansions.remove(key);
        if (previous != null) previous.cancel();
        int start = channels.getVisibility() == View.GONE ? 0 : channels.getHeight();
        float alpha = channels.getAlpha();
        channels.setVisibility(View.VISIBLE);
        channels.measure(View.MeasureSpec.makeMeasureSpec(page.getWidth() - page.getPaddingLeft() - page.getPaddingRight(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int end = show ? channels.getMeasuredHeight() : 0;
        android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0, 1);
        animation.setDuration(260);
        animation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animation.addUpdateListener(frame -> {
            float progress = (float) frame.getAnimatedValue();
            channels.getLayoutParams().height = Math.round(start + (end - start) * progress);
            channels.setAlpha(alpha + ((show ? 1f : 0f) - alpha) * progress);
            channels.requestLayout();
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (channelExpansions.get(key) == animation) channelExpansions.remove(key);
                if (cancelled) return;
                channels.setVisibility(show ? View.VISIBLE : View.GONE);
                channels.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
                channels.requestLayout();
            }
        });
        channelExpansions.put(key, animation); animation.start();
    }
    private void homeScanButton() {
        android.widget.ImageButton scan = new android.widget.ImageButton(this);
        scan.setImageResource(R.drawable.vintage_camera);
        scan.setContentDescription("Scan a Lightning invoice or Bitcoin address");
        scan.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        scan.setPadding(dp(14), dp(12), dp(14), dp(12));
        scan.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33e1b879),
            surface(0xff30271e, 14, 0xff81694b), null));
        scan.setOnClickListener(v -> {
            if (busy) { showOperationInProgress(); return; }
            scanTarget = null;
            scanPayment = true;
            launchScanner("Scan a Lightning invoice or Bitcoin address");
        });
        page.addView(scan);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(72), dp(72));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = dp(24); size.bottomMargin = dp(16);
        scan.setLayoutParams(size);
    }
    private void homeBalances(long onChain, long channelFunds, long receivable) {
        balanceSats[0] = onChain; balanceSats[1] = channelFunds; balanceSats[2] = receivable;
        balanceDropdown(0, "On Chain", this::sendOnChain, this::deposit);
        balanceDropdown(1, "Lightning", this::payment, this::receiveLightning);
        updateBalanceAmounts();
    }
    private void styleHomeBalance(Button amount) {
        styleButton(amount, false);
        amount.setBackground(null);
        amount.setTextSize(17);
        amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amount.setPadding(dp(4), dp(4), dp(4), dp(4));
        amount.setMinHeight(dp(36)); amount.setMinimumHeight(dp(36));
        amount.setMaxLines(1);
    }
    private void balanceDropdown(int index, String label, Runnable sendAction, Runnable receiveAction) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setClipChildren(true);
        homeActionsViews[index] = actions;
        Button chain = button(label, () -> {});
        // Expanding local controls must not wait for the periodic node queries.
        chain.setOnClickListener(v -> {
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
        chain.setTextSize(16.5f * 1.15f);
        chain.setMinHeight(dp(62.4f * 1.15f)); chain.setMinimumHeight(dp(62.4f * 1.15f));
        chain.setPadding(dp(16.8f * 1.15f), dp(14.4f * 1.15f), dp(16.8f * 1.15f), dp(14.4f * 1.15f));
        balanceRow.addView(chain, new LinearLayout.LayoutParams(-2, -2));
        Button amount = new Button(this); styleHomeBalance(amount);
        amount.setTextColor(0xfff1dfbb);
        balanceAmounts[index] = amount;
        View.OnClickListener cycleUnits = v -> {
            int count = prefs.getString("fiat-currency", "USD").equals("Off") ? 2 : 3;
            int unit = (displayUnit() + 1) % count;
            prefs.edit().putInt("display-unit", unit).apply();
            updateBalanceAmounts();
        };
        amount.setOnClickListener(cycleUnits);
        LinearLayout.LayoutParams amountSize = new LinearLayout.LayoutParams(0, -2, 1f); amountSize.leftMargin = dp(8);
        if (index == 0) {
            balanceRow.addView(amount, amountSize);
        } else {
            LinearLayout liquidity = new LinearLayout(this); liquidity.setOrientation(LinearLayout.VERTICAL);
            Button incoming = new Button(this); styleHomeBalance(incoming);
            incoming.setTextColor(0xffe8d2a7); incoming.setOnClickListener(cycleUnits); balanceAmounts[2] = incoming;
            Button[] values = {amount, incoming};
            String[] labels = {"", "Receivable"};
            for (int i = 0; i < values.length; i++) {
                LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL);
                TextView caption = new TextView(this); caption.setText(labels[i]); caption.setTextSize(12);
                caption.setGravity(Gravity.END); caption.setPadding(0, 0, dp(6), 0);
                caption.setTextColor(i == 0 ? 0xfff1dfbb : 0xffe8d2a7);
                caption.setOnClickListener(cycleUnits);
                line.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
                Button value = values[i];
                line.addView(value, new LinearLayout.LayoutParams(-2, -2));
                if (i == 1 && !prefs.getBoolean("home-show-receivable", false))
                    line.setVisibility(View.GONE);
                liquidity.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            balanceRow.addView(liquidity, amountSize);
        }
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2); rowSize.topMargin = dp(8); rowSize.bottomMargin = dp(8);
        page.addView(balanceRow, rowSize);
        if (index == 0 && unconfirmedBalanceSats > 0) {
            unconfirmedBalanceText = new TextView(this);
            unconfirmedBalanceText.setTextSize(12);
            unconfirmedBalanceText.setTextColor(0xffe8d2a7);
            unconfirmedBalanceText.setGravity(Gravity.END);
            unconfirmedBalanceText.setPadding(0, 0, dp(4), dp(4));
            page.addView(unconfirmedBalanceText, new LinearLayout.LayoutParams(-1, -2));
        }
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
        if (unconfirmedBalanceText != null)
            unconfirmedBalanceText.setText("Includes " + displayAmount(unconfirmedBalanceSats) + " unconfirmed");
        for (TextView balances : homeChannelBalances) {
            if (balances.getTag() instanceof LightningOuterClass.Channel) {
                balances.setText(SpliceStatus.summary((LightningOuterClass.Channel) balances.getTag(), this::displayAmount));
                continue;
            }
            long[] values = (long[]) balances.getTag();
            balances.setText((values[1] == 0 ? "Local: " : "Remote: ") + displayAmount(values[0]));
        }
        String code = prefs.getString("fiat-currency", "USD");
        java.math.BigDecimal price = null;
        try {
            if (fiatRates == null) fiatRates = new org.json.JSONObject(prefs.getString("fiat-rates", "{}"));
            long age = System.currentTimeMillis() - fiatRates.getLong("timestamp");
            if (age >= -300000 && age < 900000 && fiatRates.has(code)) price = new java.math.BigDecimal(fiatRates.getString(code));
        } catch (Exception ignored) {}
        boolean needsFiat = false;
        for (int i = 0; i < 3; i++) {
            int unit = displayUnit();
            if (code.equals("Off") && unit == 2) unit = 0;
            needsFiat |= unit == 2;
            if (balanceAmounts[i] != null) {
                String value = BalanceDisplay.format(balanceSats[i], unit, code, price);
                String label = i == 0 ? "On-chain balance" : i == 1 ? "Spendable" : "Receivable";
                balanceAmounts[i].setText(value);
                balanceAmounts[i].setContentDescription(label + ": " + value + ". Tap to change units.");
            }
        }
        if (needsFiat) fetchFiatRates();
    }
    private void fetchFiatRates() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (ratesLoading || (lastRatesAttempt != 0 && now - lastRatesAttempt < 60000)) return;
        ratesLoading = true; lastRatesAttempt = now;
        final long generation = ratesGeneration;
        final String source = prefs.getString("fiat-source-url", "");
        final String pricePath = prefs.getString("fiat-source-price-path", "ticker.lastPrice");
        final String timestampPath = prefs.getString("fiat-source-time-path", "ticker.computedAt");
        final boolean tor = prefs.getBoolean("useTor", false) || (!source.isEmpty() && ControllerAddress.onion(source));
        new Thread(() -> {
            org.json.JSONObject result = null;
            try {
                if (tor) setupTor.start(this, message -> {});
                result = FiatRates.fetch(tor, source, pricePath, timestampPath);
            } catch (Exception ignored) {}
            final org.json.JSONObject fetched = result;
            runOnUiThread(() -> {
                if (isDestroyed() || generation != ratesGeneration) return;
                ratesLoading = false;
                if (fetched != null) {
                    fiatRates = fetched;
                    prefs.edit().putString("fiat-rates", fetched.toString()).apply();
                }
                updateHeaderRate();
                if (screen.equals("Home")) updateBalanceAmounts();
                if (screen.equals("On chain funds") && updateUtxoAmounts != null) updateUtxoAmounts.run();
                if (screen.equals("Channel details") && updateChannelDetailAmounts != null) updateChannelDetailAmounts.run();
                if (receivedAmountText != null) updateReceivedAmount();
                if (swapReceiveAmountText != null) updateSwapReceiveAmount();
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
    private void saveFiatSource(String url, String pricePath, String timestampPath, org.json.JSONObject quote) throws Exception {
        android.content.SharedPreferences.Editor edit = prefs.edit()
            .putString("fiat-source-url", url).putString("fiat-source-price-path", pricePath)
            .putString("fiat-source-time-path", timestampPath).remove("fiat-rates");
        if (quote != null) edit.putString("fiat-rates", quote.toString());
        if (!edit.commit()) throw new Exception("Could not save the fiat price source.");
        ratesGeneration++; ratesLoading = false; lastRatesAttempt = 0; fiatRates = quote;
    }
    private void fiatPriceSource() {
        page("Fiat price source");
        text("Default: Neoxa XBT/USDC, treating 1 USDC as approximately 1 USD. Other fiat currencies use Frankfurter conversion rates.");
        text("A custom API must return the USD price of one XBT and its quote timestamp. Use XBT (Bitcoin Blake2b), not BTC. Only prices are requested; wallet amounts are converted on your phone.");
        text("Price API URL");
        EditText url = field("https://…", prefs.getString("fiat-source-url", "").isEmpty()
            ? FiatRates.DEFAULT_URL : prefs.getString("fiat-source-url", ""));
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        text("Price JSON field");
        EditText price = field("ticker.lastPrice", prefs.getString("fiat-source-price-path", "ticker.lastPrice"));
        text("Timestamp JSON field");
        EditText timestamp = field("ticker.computedAt", prefs.getString("fiat-source-time-path", "ticker.computedAt"));
        text("Use dot-separated fields, for example data.price. Timestamps must be Unix seconds or milliseconds. Quotes older than 15 minutes are rejected. Onion APIs use Tor automatically.");
        TextView status = text("");
        button("Test and save", () -> {
            final String endpoint, pricePath, timePath;
            try {
                endpoint = FiatRates.sourceUrl(url.getText().toString());
                pricePath = FiatRates.jsonPath(price.getText().toString());
                timePath = FiatRates.jsonPath(timestamp.getText().toString());
            } catch (Exception e) { error(e); return; }
            busy = true; status.setText("Checking price source…");
            final boolean tor = prefs.getBoolean("useTor", false) || ControllerAddress.onion(endpoint);
            new Thread(() -> {
                try {
                    if (tor) setupTor.start(this, message -> {});
                    org.json.JSONObject quote = FiatRates.fetch(tor, endpoint, pricePath, timePath);
                    runOnUiThread(() -> {
                        busy = false;
                        if (isDestroyed()) return;
                        try {
                            saveFiatSource(endpoint, pricePath, timePath, quote);
                            status.setText("Saved · 1 XBT = " + quote.getString("USD") + " USD");
                        } catch (Exception e) { error(e); }
                    });
                } catch (Exception e) { runOnUiThread(() -> {
                    busy = false;
                    if (!isDestroyed()) { status.setText("Source not saved."); error(e); }
                }); }
            }, "test-fiat-source").start();
        });
        button("Use Neoxa default", () -> {
            try { saveFiatSource("", "ticker.lastPrice", "ticker.computedAt", null); fiatPriceSource(); }
            catch (Exception e) { error(e); }
        });
        text("This setting changes fiat valuation only. Swap market comparisons currently use Neoxa’s XBT/BTC market.");
        button("Back", this::fiatSettings);
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
                if (screen.equals("Fiat")) fiatSettings();
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
    private Runnable updateUtxoAmounts;
    private void utxos() {
        work(() -> {
            LightningOuterClass.ListUnspentResponse outputs = LightningOuterClass.ListUnspentResponse.parseFrom(node.rpc("listUnspent",
                    LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(0).setMaxConfs(Integer.MAX_VALUE).build()));
            LightningOuterClass.WalletBalanceResponse balance = LightningOuterClass.WalletBalanceResponse.parseFrom(node.rpc("walletBalance", LightningOuterClass.WalletBalanceRequest.getDefaultInstance()));
            runOnUiThread(() -> {
                page("On chain funds");
                LinearLayout fundsPage = page;
                List<Runnable> amountUpdates = new ArrayList<>();
                TextView totals = text("");
                amountUpdates.add(() -> totals.setText(displayAmount(balance.getConfirmedBalance()) + " confirmed\n"
                    + displayAmount(balance.getUnconfirmedBalance()) + " unconfirmed"));
                updateUtxoAmounts = () -> {
                    if (page == fundsPage) for (Runnable update : amountUpdates) update.run();
                };
                View.OnClickListener cycle = v -> {
                    int count = prefs.getString("fiat-currency", "USD").equals("Off") ? 2 : 3;
                    prefs.edit().putInt("display-unit", (displayUnit() + 1) % count).apply();
                    updateUtxoAmounts.run();
                    if (displayUnit() == 2) fetchFiatRates();
                };
                totals.setOnClickListener(cycle);
                button("Receive XBT", this::deposit);
                if (outputs.getUtxosCount() == 0) text("No unspent outputs.");
                else text("Tap an amount to change units. Tap an output’s label or details to edit its label.");
                for (LightningOuterClass.Utxo coin : outputs.getUtxosList()) {
                    LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(14), dp(8), dp(14), dp(8));
                    card.setBackground(surface(0xff30271e, 14, 0xff81694b));
                    LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
                    cardSize.setMargins(0, dp(5), 0, dp(5)); page.addView(card, cardSize);
                    String label = coinLabel(coin);
                    TextView name = new TextView(this); name.setText(label.isEmpty() ? "Unlabelled output" : label);
                    name.setTextSize(15); name.setTextColor(0xffedddbc); name.setPadding(0, dp(8), 0, dp(8));
                    name.setOnClickListener(v -> labelCoin(coin)); card.addView(name);
                    Button amount = new Button(this); styleButton(amount, false); amount.setBackground(null);
                    amount.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); amount.setPadding(0, dp(8), 0, dp(8));
                    amount.setOnClickListener(cycle); card.addView(amount, new LinearLayout.LayoutParams(-1, -2));
                    amountUpdates.add(() -> {
                        amount.setText(displayAmount(coin.getAmountSat()));
                        amount.setContentDescription(amount.getText() + ". Tap to change units.");
                    });
                    TextView details = new TextView(this); details.setText(coin.getConfirmations() + " confirmations\n" + outpoint(coin));
                    details.setTextColor(0xffc2b69f); details.setTextSize(14); details.setPadding(0, dp(4), 0, dp(8));
                    details.setOnClickListener(v -> labelCoin(coin)); card.addView(details);
                }
                updateUtxoAmounts.run();
                if (displayUnit() == 2) fetchFiatRates();
                button("Refresh", this::utxos); button("Home", this::home);
            });
        });
    }
    private void labelCoin(LightningOuterClass.Utxo coin) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText input = field(form, "Optional", coinLabel(coin));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new WalletDialogBuilder().setTitle("Label on-chain funds").setMessage(outpoint(coin)).setView(form)
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
                for (LightningOuterClass.Channel c : open.getChannelsList()) button(channelLabelPrefix(SpliceStatus.identity(c)) + (c.getActive() ? "Open · online" : "Open · offline") + "\n" + channelBalances(c.getLocalBalance(), c.getRemoteBalance()) + (c.hasPendingSplice() ? "\n" + SpliceStatus.summary(c, this::displayAmount) : ""), () -> channelDetails(c));
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
    private void fundingOutput(String point) {
        TextView output = text("Funding output\n" + point);
        output.setContentDescription("Funding output: " + point + ". Tap to copy.");
        output.setOnClickListener(v -> {
            getSystemService(android.content.ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Funding output", point));
            Toast.makeText(this, "Funding output copied", Toast.LENGTH_SHORT).show();
        });
    }
    private void rememberChannelOrigin() {
        if (screen.equals("Home")) channelDetailsFromHome = true;
        else if (screen.equals("Channels")) channelDetailsFromHome = false;
    }
    private void pendingChannelDetails(LightningOuterClass.PendingChannelsResponse.PendingChannel channel, String status) {
        rememberChannelOrigin();
        page("Pending channel details", false, () -> pendingChannelDetails(channel, status));
        String label = prefs.getString("channel-label-" + channel.getChannelPoint(), "");
        if (!label.isEmpty()) text(label);
        text(status);
        text(channel.getCapacity() + " sats capacity\n" + channel.getLocalBalance() + " sats local balance");
        text("Peer\n" + channel.getRemoteNodePub());
        fundingOutput(channel.getChannelPoint());
        button(label.isEmpty() ? "Add label" : "Edit label", () -> labelChannel(channel.getChannelPoint(), () -> pendingChannelDetails(channel, status)));
        button(channelDetailsFromHome ? "Home" : "Back to channels",
            channelDetailsFromHome ? this::home : this::channelList);
    }
    private String channelLabelPrefix(String point) {
        String label = prefs.getString("channel-label-" + point, "");
        return label.isEmpty() ? "" : label + "\n";
    }
    private void labelChannel(String point, Runnable back) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(20), dp(20), dp(20));
        form.setFocusableInTouchMode(true);
        TextView title = new TextView(this); title.setText("Label channel"); title.setTextSize(20);
        title.setTextColor(0xfff2e6ce); title.setTypeface(getResources().getFont(R.font.wallet_humanist), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(16)); form.addView(title);
        TextView caption = new TextView(this); caption.setText("Label"); caption.setTextSize(15);
        caption.setTextColor(0xffc2b69f); form.addView(caption);
        EditText input = field(form, "Optional", prefs.getString("channel-label-" + point, ""));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        TextView help = new TextView(this); help.setText("Leave blank to remove the label."); help.setTextSize(13);
        help.setTextColor(0xffc2b69f); help.setPadding(0, 0, 0, dp(16)); form.addView(help);
        LinearLayout actions = new LinearLayout(this); actions.setBaselineAligned(false);
        Button cancel = new Button(this); cancel.setText("Cancel"); styleButton(cancel, false);
        Button save = new Button(this); save.setText("Save"); styleButton(save, true);
        LinearLayout.LayoutParams cancelSize = new LinearLayout.LayoutParams(0, -2, 1); cancelSize.rightMargin = dp(5);
        LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(0, -2, 1); saveSize.leftMargin = dp(5);
        actions.addView(cancel, cancelSize); actions.addView(save, saveSize); form.addView(actions);
        AlertDialog dialog = new WalletDialogBuilder().setView(form).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String label = input.getText().toString().trim();
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (label.isEmpty()) edit.remove("channel-label-" + point);
            else edit.putString("channel-label-" + point, label);
            if (!edit.commit()) { error(new Exception("Could not save channel label.")); return; }
            dialog.dismiss(); back.run();
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(surface(0xff12120f, 18, 0xff69563d));
    }

    private Runnable updateChannelDetailAmounts;
    private String detailChannelPoint;
    private void channelDetails(LightningOuterClass.Channel channel) {
        rememberChannelOrigin();
        page("Channel details", false, () -> channelDetails(channel));
        String label = prefs.getString("channel-label-" + SpliceStatus.identity(channel), "");
        if (!label.isEmpty()) text(label);
        button(label.isEmpty() ? "Add label" : "Edit label", () -> labelChannel(SpliceStatus.identity(channel), () -> channelDetails(channel)));
        detailChannelPoint = channel.getChannelPoint();
        text((channel.getActive() ? "Online" : "Offline") + " · " + (channel.getPrivate() ? "Private channel" : "Public channel"));
        TextView capacity = text("");
        TextView balances = text("");
        LinearLayout detailsPage = page;
        updateChannelDetailAmounts = () -> {
            if (page != detailsPage) return;
            capacity.setText(displayAmount(channel.getCapacity()) + " capacity");
            balances.setText(displayAmount(channel.getLocalBalance()) + " local balance\n"
                + displayAmount(channel.getRemoteBalance()) + " remote balance");
            if (channel.hasPendingSplice() && spliceDetailsText != null) {
                LightningOuterClass.Channel current = spliceDetailsText.getTag() instanceof LightningOuterClass.Channel
                    ? (LightningOuterClass.Channel) spliceDetailsText.getTag() : channel;
                spliceDetailsText.setText(SpliceStatus.details(current, this::displayAmount));
            }
        };
        View.OnClickListener cycleAmounts = v -> {
            int count = prefs.getString("fiat-currency", "USD").equals("Off") ? 2 : 3;
            prefs.edit().putInt("display-unit", (displayUnit() + 1) % count).apply();
            updateChannelDetailAmounts.run();
            if (displayUnit() == 2) fetchFiatRates();
        };
        capacity.setOnClickListener(cycleAmounts); balances.setOnClickListener(cycleAmounts);
        text("Peer\n" + channel.getRemotePubkey());
        fundingOutput(channel.getChannelPoint());
        if (channel.hasPendingSplice()) {
            viewingSpliceChannel = SpliceStatus.identity(channel);
            spliceDetailsText = text(SpliceStatus.details(channel, this::displayAmount));
            spliceDetailsText.setTag(channel);
            spliceDetailsText.setOnClickListener(cycleAmounts);
            spliceDetailsText.setTextColor(0xffffb454);
            spliceRefreshError = text(""); spliceRefreshError.setTextColor(0xffed6b73);
            String txid = channel.getPendingSplice().getTxid();
            viewingSpliceTxid = txid;
            if (!txid.isEmpty()) {
                TextView tx = text("Splice transaction\n" + txid);
                tx.setContentDescription("Splice transaction. Tap to copy.");
                tx.setOnClickListener(v -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Splice transaction", txid));
                    Toast.makeText(this, "Splice transaction copied", Toast.LENGTH_SHORT).show();
                });
            }
            button("Refresh splice status", this::refreshSpliceDetails);
            text("Wait for the pending splice to finish before closing this channel.");
        } else {
            String key = "channel-close-" + channel.getChannelPoint();
            String closing = prefs.getString(key, "");
            if (!closing.isEmpty()) text(closing);
            text("Closing returns your remaining funds to your on-chain wallet after fees and confirmation. A cooperative close requires the peer to be online.");
            text("Closing fee rate in sats/vbyte");
            EditText rate = field("", "1");
            rate.setContentDescription("Closing fee rate in sats/vbyte");
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
            Button force = button("Force close", () -> {
                String remoteNote = node != null && node.isRemote()
                    ? "\nCore Lightning first allows a brief cooperative close attempt, then closes unilaterally after one second if necessary." : "";
                new WalletDialogBuilder().setTitle("Force close this channel?")
                    .setMessage("Your local balance is " + displayAmount(channel.getLocalBalance())
                        + " before on-chain fees. This permanently closes the channel, even if the peer is offline. "
                        + "Funds may remain locked for days while timelocks and pending payments resolve. "
                        + "Additional on-chain fees can apply. Keep your node running to recover the funds. "
                        + "The cooperative closing fee field does not apply." + remoteNote)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Force close", (dialog, which) -> closeChannel(channel, 0, true)).show();
            });
            styleDestructiveButton(force);
            force.setEnabled(closing.isEmpty());
        }
        updateChannelDetailAmounts.run();
        if (displayUnit() == 2) fetchFiatRates();
        button(channelDetailsFromHome ? "Home" : "Back to channels",
            channelDetailsFromHome ? this::home : this::channelList);
    }
    private void refreshSpliceDetails() {
        final String identity = viewingSpliceChannel;
        if (identity == null) return;
        work(() -> {
            try {
                LightningOuterClass.ListChannelsResponse response = LightningOuterClass.ListChannelsResponse.parseFrom(
                    node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
                LightningOuterClass.Channel found = null;
                for (LightningOuterClass.Channel c : response.getChannelsList()) if (SpliceStatus.identity(c).equals(identity)) { found = c; break; }
                if (found == null) throw new Exception("Channel not found in the node response. Refresh Channels.");
                final LightningOuterClass.Channel current = found;
                runOnUiThread(() -> {
                    if (!screen.equals("Channel details") || !identity.equals(viewingSpliceChannel)) return;
                    if (!current.hasPendingSplice() || !current.getPendingSplice().getTxid().equals(viewingSpliceTxid)) { channelDetails(current); return; }
                    spliceDetailsText.setTag(current);
                    spliceDetailsText.setText(SpliceStatus.details(current, this::displayAmount));
                    spliceRefreshError.setText("");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (screen.equals("Channel details") && identity.equals(viewingSpliceChannel)) spliceRefreshError.setText("Splice status refresh failed: " + e.getMessage());
                });
            }
        });
    }

    static LightningOuterClass.CloseChannelRequest channelCloseRequest(LightningOuterClass.Channel channel, long fee, boolean force) {
        String[] point = channel.getChannelPoint().split(":");
        LightningOuterClass.CloseChannelRequest.Builder request = LightningOuterClass.CloseChannelRequest.newBuilder()
            .setChannelPoint(LightningOuterClass.ChannelPoint.newBuilder().setFundingTxidStr(point[0]).setOutputIndex(Integer.parseInt(point[1])))
            .setForce(force);
        if (!force) request.setSatPerVbyte(fee).setMaxFeePerVbyte(fee);
        return request.build();
    }
    private void closeChannel(LightningOuterClass.Channel channel, long fee) {
        closeChannel(channel, fee, false);
    }
    private void closeChannel(LightningOuterClass.Channel channel, long fee, boolean force) {
        work(() -> {
            final String key = "channel-close-" + channel.getChannelPoint();
            if (prefs.contains(key)) throw new Exception("A close request is already recorded. Check channel status before any further attempt.");
            // Re-read the node immediately before a user-authorized submission.
            LightningOuterClass.ListChannelsResponse current = LightningOuterClass.ListChannelsResponse.parseFrom(node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
            boolean found = false, online = false;
            for (LightningOuterClass.Channel c : current.getChannelsList())
                if (c.getChannelPoint().equals(channel.getChannelPoint())) {
                    if (c.hasPendingSplice()) throw new Exception("A splice is pending. Wait for it to finish before closing this channel.");
                    found = true; online = c.getActive();
                }
            if (!found || (!force && !online)) throw new Exception("This channel is no longer available for this close. Refresh the channel list.");
            LightningOuterClass.CloseChannelRequest request = channelCloseRequest(channel, fee, force);
            if (!prefs.edit().putString(key, (force ? "Force close" : "Close") + " requested. Check Channels for confirmation progress.").commit())
                throw new Exception("Could not save close request.");
            runOnUiThread(() -> { if (!isDestroyed() && channel.getChannelPoint().equals(detailChannelPoint)) channelDetails(channel); });
            node.closeChannel(request.toByteArray(), new lndmobile.RecvStream() {
                private volatile boolean terminal;
                public void onResponse(byte[] data) {
                    try {
                        LightningOuterClass.CloseStatusUpdate update = LightningOuterClass.CloseStatusUpdate.parseFrom(data == null ? new byte[0] : data);
                        terminal = update.hasChanClose();
                        String status = terminal ? "Channel closed." : update.hasClosePending()
                            ? "Closing transaction broadcast. Waiting for confirmation." : "Close requested. Waiting for the peer.";
                        prefs.edit().putString(key, status).commit();
                        runOnUiThread(() -> { if (!isDestroyed() && channel.getChannelPoint().equals(detailChannelPoint)) channelDetails(channel); });
                    } catch (Exception e) { onError(e); }
                }
                public void onError(Exception e) {
                    if (terminal) return;
                    prefs.edit().putString(key, "Close status needs checking: " + e.getMessage() + ". Refresh Channels before attempting anything else.").commit();
                    runOnUiThread(() -> { if (!isDestroyed() && channel.getChannelPoint().equals(detailChannelPoint)) channelDetails(channel); });
                }
            });
        });
    }
    private void more() {
        if (walletMenuAnchor == null || walletPopup != null) return;
        PopupMenu popup = new PopupMenu(new android.view.ContextThemeWrapper(this, R.style.WalletDotsMenuTheme), walletMenuAnchor, Gravity.END);
        walletPopup = popup;
        String[] labels = {"Wallets", "Payment history", "On chain funds", "Channels", "Swap controllers", "Advanced"};
        Runnable[] actions = {this::wallets, this::paymentHistory, this::utxos, this::channelList, this::swapControllers, this::advanced};
        for (int i = 0; i < labels.length; i++) {
            android.text.SpannableString label = new android.text.SpannableString(labels[i]);
            label.setSpan(new android.text.style.RelativeSizeSpan(1.1f), 0, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            popup.getMenu().add(0, i, i, label);
        }
        popup.setOnMenuItemClickListener(item -> {
            if (busy) { showOperationInProgress(); return true; }
            popup.dismiss(); actions[item.getItemId()].run(); return true;
        });
        popup.setOnDismissListener(menu -> { if (walletPopup == popup) walletPopup = null; });
        popup.show();
    }
    private void rememberPeerAddresses(LightningOuterClass.ListPeersResponse peers) {
        android.content.SharedPreferences.Editor edit = prefs.edit();
        boolean changed = false;
        for (LightningOuterClass.Peer peer : peers.getPeersList()) {
            if (peer.getInbound()) continue;
            try {
                String address = Policy.endpoint(peer.getAddress());
                String key = "peer-address-" + peer.getPubKey().toLowerCase(Locale.ROOT);
                if (!address.equals(prefs.getString(key, ""))) { edit.putString(key, address); changed = true; }
            } catch (IllegalArgumentException ignored) { /* An inbound peer may not advertise a dialable address. */ }
        }
        if (changed) edit.apply();
    }
    private void peerList() {
        work(() -> {
            LightningOuterClass.ListPeersResponse peers = LightningOuterClass.ListPeersResponse.parseFrom(
                node.rpc("listPeers", LightningOuterClass.ListPeersRequest.getDefaultInstance()));
            rememberPeerAddresses(peers);
            runOnUiThread(() -> {
                page("Peers");
                text(peers.getPeersCount() + " connected " + (peers.getPeersCount() == 1 ? "peer" : "peers"));
                for (LightningOuterClass.Peer peer : peers.getPeersList()) {
                    String address = peer.getPubKey() + "@" + peer.getAddress();
                    TextView entry = text(address);
                    entry.setContentDescription("Connected peer " + address + ". Tap to copy.");
                    entry.setOnClickListener(v -> {
                        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Lightning peer", address));
                        Toast.makeText(this, "Peer address copied", Toast.LENGTH_SHORT).show();
                    });
                }
                button("Add peer", () -> addPeer(""));
                button("Refresh", this::peerList); button("Home", this::home);
            });
        });
    }
    private void addPeer(String initial) {
        page("Add peer");
        text("Peer address");
        EditText address = field("pubkey@host:port", initial);
        Button scanPeer = button("Scan QR", () -> scan(address));
        Button pastePeer = button("Paste from Clipboard", () -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
                Toast.makeText(this, "No peer address in clipboard", Toast.LENGTH_SHORT).show(); return;
            }
            address.setText(clip.getItemAt(0).getText().toString().trim());
        });
        text("Use a public key followed by @host:port. Onion addresses require Tor enabled in Node connection and an app restart.");
        TextView status = text("");
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView failureDetails = text("");
        failureDetails.setVisibility(View.GONE);
        address.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                status.setVisibility(View.GONE);
                failureDetails.setVisibility(View.GONE);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        Button[] connect = new Button[1];
        connect[0] = button("Connect", () -> {
            if (busy) return;
            java.util.function.Consumer<Exception> finished = failure -> {
                if (!status.isAttachedToWindow()) return;
                address.setEnabled(true);
                scanPeer.setEnabled(true); pastePeer.setEnabled(true);
                connect[0].setEnabled(true);
                status.setVisibility(View.VISIBLE);
                status.setText(failure == null ? "Connected" : "Failed to connect");
                status.setTextColor(failure == null ? 0xffa8efd5 : 0xffff8c8c);
                failureDetails.setText(failure == null ? "" : failure.getMessage());
                failureDetails.setVisibility(failure == null ? View.GONE : View.VISIBLE);
            };
            try {
                if (node == null) throw new Exception("The wallet is not connected to its node yet. Try again once it is ready.");
                String[] peer = Policy.peer(address.getText().toString());
                connectPeerAddress(peer[0].toLowerCase(Locale.ROOT), peer[1], () -> {
                    address.setEnabled(false);
                    scanPeer.setEnabled(false); pastePeer.setEnabled(false);
                    connect[0].setEnabled(false);
                    failureDetails.setVisibility(View.GONE);
                    status.setVisibility(View.VISIBLE);
                    status.setText("Adding...");
                    status.setTextColor(0xffe8d2a7);
                }, () -> {}, finished);
            } catch (Exception e) { finished.accept(e); }
        });
        button(node != null && node.isRemote() ? "Remote Control connection" : "Node connection", node != null && node.isRemote() ? this::remoteConnection : this::nodeConnection);
        button("Back to peers", this::peerList);
    }
    private void connectPeerAddress(String publicKey, String suppliedAddress, Runnable started, Runnable complete) {
        connectPeerAddress(publicKey, suppliedAddress, started, complete, null);
    }
    private void connectPeerAddress(String publicKey, String suppliedAddress, Runnable started, Runnable complete,
                                    java.util.function.Consumer<Exception> resultHandler) {
        if (busy || node == null) return;
        String peer = publicKey.toLowerCase(Locale.ROOT);
        busy = true; reconnectingPeers.add(peer); started.run();
        node.io.execute(() -> {
            Exception failure = null;
            boolean missingAddress = false;
            try {
                LightningOuterClass.ListPeersResponse current = LightningOuterClass.ListPeersResponse.parseFrom(
                    node.rpc("listPeers", LightningOuterClass.ListPeersRequest.getDefaultInstance()));
                boolean connected = false;
                for (LightningOuterClass.Peer existing : current.getPeersList()) connected |= existing.getPubKey().equalsIgnoreCase(peer);
                if (!connected) {
                    String address = suppliedAddress == null ? prefs.getString("peer-address-" + peer, "") : suppliedAddress;
                    if (address.isEmpty()) {
                        String lookupError = "No advertised addresses.";
                        try {
                            LightningOuterClass.NodeInfo info = LightningOuterClass.NodeInfo.parseFrom(node.rpc("getNodeInfo",
                                LightningOuterClass.NodeInfoRequest.newBuilder().setPubKey(peer).build()));
                            for (LightningOuterClass.NodeAddress candidate : info.getNode().getAddressesList()) {
                                try { address = Policy.endpoint(candidate.getAddr()); break; }
                                catch (IllegalArgumentException ignored) { }
                            }
                        } catch (Exception e) { lookupError = e.getMessage(); }
                        if (address.isEmpty()) {
                            missingAddress = true;
                            throw new Exception("No saved address for this peer. Enter its host and port. Node lookup: " + lookupError);
                        }
                    }
                    address = Policy.endpoint(address);
                    if (address.toLowerCase(Locale.ROOT).contains(".onion:") && !node.supportsOnionPeers)
                        throw new Exception("Tor is not enabled for this node. Enable onion Lightning peers in Node connection, save, and restart the app before connecting.");
                    if (!prefs.edit().putString("peer-address-" + peer, address).commit())
                        throw new Exception("Could not save the peer address. No connection was attempted.");
                    node.rpc("connectPeer", LightningOuterClass.ConnectPeerRequest.newBuilder()
                        .setAddr(LightningOuterClass.LightningAddress.newBuilder().setPubkey(peer).setHost(address))
                        .setTimeout(30).build());
                }
            } catch (Exception e) {
                failure = e;
                // A timeout can race successful connection establishment. Reconcile without retrying.
                try {
                    LightningOuterClass.ListPeersResponse current = LightningOuterClass.ListPeersResponse.parseFrom(
                        node.rpc("listPeers", LightningOuterClass.ListPeersRequest.getDefaultInstance()));
                    for (LightningOuterClass.Peer existing : current.getPeersList())
                        if (existing.getPubKey().equalsIgnoreCase(peer)) { failure = null; missingAddress = false; }
                } catch (Exception check) {
                    failure = new Exception(e.getMessage() + " Connection status check also failed: " + check.getMessage());
                }
            }
            final Exception result = failure;
            final boolean needsAddress = missingAddress;
            runOnUiThread(() -> {
                busy = false; reconnectingPeers.remove(peer);
                if (isDestroyed()) return;
                if (resultHandler != null) {
                    resultHandler.accept(result);
                } else {
                    if (needsAddress) addPeer(peer + "@"); else complete.run();
                    if (result != null) error(new Exception("Peer connection failed: " + result.getMessage()));
                }
            });
        });
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
        controllerAdd(address, expectedIdentity, "", "", "");
    }
    private void controllerAdd(String address, String expectedIdentity, String apiDraft, String keyDraft, String labelDraft) {
        page("Add controller", true, () -> controllerAdd(address, expectedIdentity, apiDraft, keyDraft, labelDraft));
        text("Controller address"); EditText url = field("https://… or http://….onion", address);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        button("Scan controller address", () -> scan(url));
        text("Wallet Swap API address");
        EditText api = field("Optional — https://… or http://….onion", apiDraft);
        api.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        button("Scan API address", () -> scan(api));
        text("Wallet Swap Access key");
        EditText accessKey = field("Optional — from the controller’s Swap Setup menu", keyDraft);
        accessKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Scan access key", () -> scan(accessKey));
        text("Label"); EditText label = field("Optional — for your own records", labelDraft);
        text("Onion addresses connect through Tor automatically.");
        button("Check controller", () -> {
            final String endpoint, apiOrigin;
            final String key = accessKey.getText().toString().trim();
            final String privateLabel = label.getText().toString().trim();
            try {
                endpoint = ControllerAddress.origin(url.getText().toString());
                String apiText = api.getText().toString().trim();
                apiOrigin = apiText.isEmpty() ? "" : ControllerAddress.origin(apiText);
                if (!key.isEmpty() && !key.matches("[a-f0-9]{64}")) throw new Exception("Enter the 64-character Wallet Swap Access key.");
                if (!key.isEmpty() && apiOrigin.isEmpty()) throw new Exception("Enter the Wallet Swap API address for this access key.");
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
                    if (!apiOrigin.isEmpty()) text("Wallet Swap API address\n" + apiOrigin);
                    text(key.isEmpty() ? "Controller connection checked. Configure Wallet Swap Access to use swaps." : "Controller connection checked. Wallet Swap API address and access key will be saved; API access has not yet been checked.");
                    button("Save controller", () -> {
                        try {
                            org.json.JSONArray saved = savedControllers(), updated = new org.json.JSONArray();
                            for (int i=0; i<saved.length(); i++) if (!endpoint.equals(saved.getJSONObject(i).getString("address"))) updated.put(saved.getJSONObject(i));
                            if (updated.length() >= 20) throw new Exception("You can save up to 20 controllers.");
                            org.json.JSONObject value = info.json().put("address", endpoint).put("checked_at", System.currentTimeMillis());
                            if (!privateLabel.isEmpty()) value.put("label", privateLabel);
                            updated.put(value);
                            android.content.SharedPreferences.Editor settings = prefs.edit().putString("swap-controllers", updated.toString());
                            if (!apiOrigin.isEmpty()) settings.putString("receive-api-" + info.identity, apiOrigin);
                            if (!key.isEmpty()) settings.putString("receive-key-" + info.identity, key).putString("receive-provider", info.identity);
                            if (!settings.commit()) throw new Exception("Could not save controller.");
                            swapControllers();
                        } catch (Exception e) { error(e); }
                    });
                    button("Back", () -> controllerAdd(endpoint, expectedIdentity, apiOrigin, key, privateLabel));
                });
            });
        });
        button("Back", this::swapControllers);
    }
    private void controllerDetails(org.json.JSONObject item) {
        page("Controller details", false, () -> controllerDetails(item));
        String address = item.optString("address"), identity = item.optString("provider");
        text(item.optString("label", item.optString("name"))); text(address);
        text("XBT → BTC · " + item.optString("environment")); text("Saved identity\n" + identity);
        text("Checked when saved: " + java.text.DateFormat.getDateTimeInstance().format(new Date(item.optLong("checked_at"))));
        button("Configure wallet swap access", () -> controllerReceiveSetup(item));
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
        page("Wallet swap access", true, () -> controllerReceiveSetup(item));
        String identity = item.optString("provider");
        text("Wallet Swap API address");
        EditText address = field("https://… or http://….onion", prefs.getString("receive-api-" + identity, ""));
        button("Scan API address", () -> scan(address));
        text("Wallet Swap Access key");
        EditText token = field("From the controller’s Swap Setup menu", prefs.getString("receive-key-" + identity, ""));
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Scan access key", () -> scan(token));
        button("Save", () -> {
            try {
                String origin = ControllerAddress.origin(address.getText().toString());
                String key = token.getText().toString().trim();
                if (!key.matches("[a-f0-9]{64}")) throw new Exception("Enter the 64-character Wallet Swap Access key.");
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
        page("Search results", false, () -> controllerResults(results));
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
            if (matches == 0) { TextView empty = new TextView(this); empty.setText("No matching controllers."); empty.setTextColor(0xffc2b69f); rows.addView(empty); }
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
        page("Advanced");
        String walletName = prefs.getString("wallet-name", "");
        if (!walletName.isEmpty()) text("Wallet name: " + walletName);
        text(node == null ? "Node disconnected" : node.status);
        button(node != null && node.isRemote() ? "Remote Control connection" : "Node connection", node != null && node.isRemote() ? this::remoteConnection : this::nodeConnection);
        button("Fiat", this::fiatSettings);
        button("RBF/CPFP", this::feeBumps);
        button("Backup/Recover", this::backupRecover);
        button("Customization", this::customization);
        button(WalletPin.enabled(this) ? "PIN code" : "Add a PIN code", this::pinSettings);
        button("Peers", this::peerList);
        button("Create BOLT12", this::createBolt12);
        button("Closed-channel history", this::closedChannels);
        button("About", this::about);
        button("Home", this::home);
    }
    private void fiatSettings() {
        page("Fiat");
        button("Fiat price source", this::fiatPriceSource);
        button("Fiat currency: " + prefs.getString("fiat-currency", "USD"), this::fiatCurrency);
        button("Back", this::advanced);
    }
    private void about() {
        page("About");
        text("XBT Wallet");
        text("Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")");
        button("GitHub repository", () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/BitcoinMechanic/xbt-wallet")));
            } catch (ActivityNotFoundException e) {
                error(new Exception("No browser is installed. Repository: https://github.com/BitcoinMechanic/xbt-wallet"));
            }
        });
        button("Back", this::advanced);
    }
    private EditText pinField(String hint) {
        EditText input=field(hint,""); input.setInputType(18); input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        return input;
    }
    private void pinSettings() {
        page("PIN code");
        text("This PIN locks access to all wallets in this app. The Lightning node continues running while locked.");
        TextView warning=text("Warning: if you forget your PIN, it cannot be reset. You will need to recover your wallet using your seed and channel backup, or reconnect your remote node. Back up before enabling a PIN. Seed recovery alone does not restore Lightning channels. In-app seed recovery is not available yet; keep an external recovery path. Do not clear app data or reinstall without your backups.");
        warning.setTextColor(0xffffa18b);
        boolean enabled=WalletPin.enabled(this);
        EditText current=enabled ? pinField("Current PIN") : null;
        text("New PIN (6–12 digits)"); EditText first=pinField("PIN");
        text("Confirm PIN"); EditText confirm=pinField("Repeat PIN");
        CheckBox backedUp=new CheckBox(this); backedUp.setText("I have my recovery information and understand the warning.");
        page.addView(backedUp);
        button(enabled ? "Change PIN" : "Add PIN",()->{
            if(!backedUp.isChecked()) { error(new Exception("Confirm that you have your recovery information first.")); return; }
            String value=first.getText().toString(), repeated=confirm.getText().toString();
            if(!value.equals(repeated)) { error(new Exception("PINs do not match.")); return; }
            if(!value.matches("[0-9]{6,12}")) { error(new Exception("Use 6–12 digits.")); return; }
            String old=current==null ? "" : current.getText().toString();
            first.setText(""); confirm.setText(""); if(current!=null)current.setText("");
            pinUpdate(()->{
                if(enabled&&!WalletPin.verify(this,old))throw new Exception("Incorrect current PIN.");
                WalletPin.set(this,value);
            });
        });
        if(enabled) {
            button("Lock now",()->{ WalletPin.lock(); checkPinLock(); });
            Button remove=button("Remove PIN",()->{
                String old=current.getText().toString(); current.setText("");
                pinUpdate(()->{
                    if(!WalletPin.verify(this,old))throw new Exception("Incorrect current PIN.");
                    WalletPin.remove(this);
                });
            });
            styleDestructiveButton(remove);
        }
        CheckBox restartOnly=new CheckBox(this);
        restartOnly.setText("Only require PIN on restart");
        restartOnly.setChecked(WalletPin.restartOnly(this)); page.addView(restartOnly);
        text("Lock wallet after");
        String[] units={"Seconds","Minutes","Hours","Days"};
        long[] multipliers={1000,60000,3600000,86400000};
        int initialUnit=0;
        long saved=WalletPin.timeout(this);
        for(int i=0;i<multipliers.length;i++)
            if(saved%multipliers[i]==0 && saved/multipliers[i]>=1 && saved/multipliers[i]<=60)initialUnit=i;
        final int[] unit={initialUnit};
        final int[] count={(int)Math.max(1,Math.min(60,saved/multipliers[initialUnit]))};
        String[] counts=new String[60]; for(int i=0;i<60;i++)counts[i]=Integer.toString(i+1);
        LinearLayout timeoutRow=new LinearLayout(this);
        Button number=new Button(this); styleButton(number,false);
        Button timeframe=new Button(this); styleButton(timeframe,false);
        number.setContentDescription("Lock timeout number");
        timeframe.setContentDescription("Lock timeout time frame");
        timeoutRow.addView(number,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1); right.leftMargin=dp(8);
        timeoutRow.addView(timeframe,right); page.addView(timeoutRow);
        Runnable renderTimeout=()->{
            number.setText(count[0]+" ▾"); timeframe.setText(units[unit[0]]+" ▾");
            number.setEnabled(!restartOnly.isChecked()); timeframe.setEnabled(!restartOnly.isChecked());
        };
        Runnable saveTimeout=()->{
            WalletPin.prefs(this).edit().putLong("timeout",count[0]*multipliers[unit[0]]).apply();
            renderTimeout.run(); checkPinLock();
        };
        number.setOnClickListener(v->new WalletDialogBuilder().setTitle("Number")
            .setSingleChoiceItems(counts,count[0]-1,(d,index)->{
                count[0]=index+1; d.dismiss(); saveTimeout.run();
            }).show());
        timeframe.setOnClickListener(v->new WalletDialogBuilder().setTitle("Time frame")
            .setSingleChoiceItems(units,unit[0],(d,index)->{
                unit[0]=index; d.dismiss(); saveTimeout.run();
            }).show());
        restartOnly.setOnCheckedChangeListener((v,checked)->{
            WalletPin.prefs(this).edit().putBoolean("restart-only",checked).apply();
            renderTimeout.run(); checkPinLock();
        });
        renderTimeout.run();
        text("Locks after inactivity, including time spent outside the app. With restart-only enabled, the PIN is required when the app process restarts, not each time you return to it.");
        button("Home",this::home);
    }
    private void pinUpdate(Job job) {
        if(busy)return; busy=true;
        new Thread(()->{
            try { job.run(); runOnUiThread(()->{busy=false; pinSettings(); checkPinLock();}); }
            catch(Exception e) { runOnUiThread(()->{busy=false; error(e);}); }
        },"pin-settings").start();
    }
    private void backupRecover() {
        page("Backup/Recover");
        button(node != null && node.isRemote() ? "Node backup" : "Back up seed", this::backup);
        if (node == null || !node.isRemote()) button("Test seed recovery", this::testRecovery);
        button("Create channel backup", this::createChannelBackup);
        button("Test channel backup", this::testChannelBackup);
        button("Back", this::advanced);
    }
    private List<ReplacementTransaction> replacementCandidates(List<String> unavailable) throws Exception {
        LightningOuterClass.TransactionDetails transactions = LightningOuterClass.TransactionDetails.parseFrom(
            node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance()));
        LightningOuterClass.ListUnspentResponse coins = LightningOuterClass.ListUnspentResponse.parseFrom(
            node.rpc("listUnspent", LightningOuterClass.ListUnspentRequest.newBuilder().setMinConfs(0).setMaxConfs(Integer.MAX_VALUE).build()));
        LightningOuterClass.ListChannelsResponse channels = LightningOuterClass.ListChannelsResponse.parseFrom(
            node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
        LightningOuterClass.PendingChannelsResponse pending = LightningOuterClass.PendingChannelsResponse.parseFrom(
            node.rpc("pendingChannels", LightningOuterClass.PendingChannelsRequest.getDefaultInstance()));
        Set<String> funding = new HashSet<>(), unspent = new HashSet<>();
        for (LightningOuterClass.Channel c : channels.getChannelsList()) {
            funding.add(c.getChannelPoint().split(":")[0]);
            if (c.hasPendingSplice()) funding.add(c.getPendingSplice().getTxid());
        }
        for (LightningOuterClass.PendingChannelsResponse.PendingOpenChannel c : pending.getPendingOpenChannelsList()) funding.add(c.getChannel().getChannelPoint().split(":")[0]);
        for (LightningOuterClass.Utxo coin : coins.getUtxosList()) unspent.add(outpoint(coin));
        Map<String, LightningOuterClass.Transaction> history = new HashMap<>();
        for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) history.put(tx.getTxHash(), tx);
        Set<String> confirmedSpends = new HashSet<>();
        for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) {
            if (tx.getNumConfirmations() <= 0 || tx.getRawTxHex().isEmpty()) continue;
            for (ReplacementTransaction.Input input : ReplacementTransaction.parse(ReplacementTransaction.bytes(tx.getRawTxHex())).inputs)
                confirmedSpends.add(input.txid() + ":" + input.index);
        }
        List<ReplacementTransaction> eligible = new ArrayList<>();
        for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) {
            if (tx.getNumConfirmations() != 0 || tx.getAmount() >= 0 || funding.contains(tx.getTxHash())) continue;
            String submitted = prefs.getString("rbf-submission-" + tx.getTxHash(), "");
            // CLN can retain an evicted original and its old change as unconfirmed.
            // A known replacement or a confirmed conflicting spend supersedes it.
            if (!submitted.isEmpty() && history.containsKey(submitted) && history.get(submitted).getNumConfirmations() >= 0) continue;
            try {
                ReplacementTransaction candidate = new ReplacementTransaction(tx, history, unspent, funding);
                boolean superseded = false;
                for (ReplacementTransaction.Input input : candidate.original.inputs)
                    superseded |= confirmedSpends.contains(input.txid() + ":" + input.index);
                if (!superseded) eligible.add(candidate);
            }
            catch (Exception e) { unavailable.add(tx.getTxHash() + "\n" + e.getMessage()); }
        }
        return eligible;
    }
    private void feeBumps() {
        page("RBF/CPFP");
        TextView loading = text("Checking unconfirmed payments…");
        Button homeButton = button("Home", this::home);
        LinearLayout origin = page;
        work(() -> {
            List<String> unavailable = new ArrayList<>();
            List<ReplacementTransaction> candidates = replacementCandidates(unavailable);
            runOnUiThread(() -> {
                if (page != origin) return;
                page.removeView(loading);
                page.removeView(homeButton);
                if (candidates.isEmpty()) {
                    text(unavailable.isEmpty() ? "No unconfirmed outgoing payments available."
                        : "No unconfirmed outgoing payments are eligible for RBF.\n\n" + android.text.TextUtils.join("\n\n", unavailable));
                    page.addView(homeButton);
                    return;
                }
                text("Select an unconfirmed payment. The replacement keeps its recipients and amounts unchanged; the extra fee comes from wallet change.");
                RadioGroup choices = new RadioGroup(this); page.addView(choices);
                final ReplacementTransaction[] selected = {null};
                for (ReplacementTransaction candidate : candidates) {
                    RadioButton option = new RadioButton(this); option.setId(View.generateViewId());
                    option.setText(candidate.txid + "\nCurrent fee: " + candidate.oldFee + " sats · "
                        + String.format(Locale.US, "%.2f", candidate.oldFee / (double) candidate.original.vbytes) + " sats/vByte");
                    option.setPadding(0, dp(10), 0, dp(10));
                    option.setOnCheckedChangeListener((v, checked) -> { if (checked) selected[0] = candidate; });
                    choices.addView(option);
                }
                text("New fee rate (sats/vByte)");
                EditText rate = field("Enter a higher fee rate", "");
                rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                rate.setContentDescription("New fee rate in sats per vByte");
                button("Broadcast", () -> {
                    try {
                        if (selected[0] == null) throw new Exception("Select an unconfirmed payment.");
                        ReplacementTransaction.Plan plan = selected[0].plan(Policy.amount(rate.getText().toString()));
                        String key = "rbf-submission-" + plan.source.txid;
                        if (prefs.contains(key)) throw new Exception("A replacement was already submitted for this transaction. Check payment history before retrying. Expected transaction: " + prefs.getString(key, ""));
                        confirmAction("Broadcast replacement?", "New fee rate: " + plan.rate + " sats/vByte\nTotal replacement fee: " + plan.fee
                            + " sats\nAdditional fee: " + (plan.fee - plan.source.oldFee) + " sats\nRecipient amounts stay unchanged." + (plan.source.signalsRbf ? "" : "\nThis transaction needs full-RBF relay support because it did not signal replacement when sent."), "Broadcast", () -> work(() -> {
                                ReplacementTransaction fresh = null;
                                for (ReplacementTransaction candidate : replacementCandidates(new ArrayList<>()))
                                    if (candidate.txid.equals(plan.source.txid)) fresh = candidate;
                                if (fresh == null) throw new Exception("The payment confirmed, was replaced, or its change is no longer available. Reopen RBF/CPFP.");
                                ReplacementTransaction.Plan checked = fresh.plan(plan.rate);
                                if (!Arrays.equals(checked.unsigned, plan.unsigned)) throw new Exception("Transaction details changed. Review again.");
                                if (prefs.contains(key)) throw new Exception("A replacement request is already recorded. Check payment history.");
                                byte[] signed = node.signReplacement(checked);
                                String expected = ReplacementTransaction.txid(checked.unsigned);
                                // Record before a network submission; a timeout must not look like a safe retry.
                                if (!prefs.edit().putString(key, expected).commit()) throw new Exception("Could not save the replacement request. Nothing broadcast.");
                                try {
                                    String txid = node.publishReplacement(signed, checked);
                                    prefs.edit().putString("tx-label-" + txid, prefs.getString("tx-label-" + plan.source.txid, "")).apply();
                                    runOnUiThread(() -> { page("Replacement broadcast"); text("Transaction\n" + txid); button("Payment history", this::paymentHistory); button("Home", this::home); });
                                } catch (Exception e) {
                                    throw new Exception("Replacement submission needs checking: " + e.getMessage() + "\nExpected transaction: " + expected + "\nCheck payment history before retrying.", e);
                                }
                            }));
                    } catch (Exception e) { error(e); }
                });
                if (!unavailable.isEmpty()) text("Other unconfirmed payments cannot be replaced:\n" + android.text.TextUtils.join("\n\n", unavailable));
                page.addView(homeButton);
            });
        });
    }
    private void customization() {
        page("Customization");
        Switch coin=new Switch(this);
        coin.setText("Show Bitcoin"); coin.setTextSize(15); coin.setTextColor(0xffedddbc);
        coin.setPadding(dp(14),dp(12),dp(14),dp(12)); coin.setMinimumHeight(dp(52));
        coin.setChecked(prefs.getBoolean("header-show-coin",true));
        coin.setOnCheckedChangeListener((view,checked)->{
            prefs.edit().putBoolean("header-show-coin",checked).apply();
            headerRateViewport.setTranslationX(checked ? dp(47) : 0);
            headerBrand.invalidate();
        });
        page.addView(coin,new LinearLayout.LayoutParams(-1,-2));
        Switch receivable = new Switch(this);
        receivable.setText("Show funds receivable in Lightning");
        receivable.setTextSize(15);
        receivable.setTextColor(0xffedddbc);
        receivable.setPadding(dp(14), dp(12), dp(14), dp(12));
        receivable.setMinimumHeight(dp(52));
        receivable.setChecked(prefs.getBoolean("home-show-receivable", false));
        receivable.setOnCheckedChangeListener((view, checked) ->
            prefs.edit().putBoolean("home-show-receivable", checked).apply());
        page.addView(receivable, new LinearLayout.LayoutParams(-1, -2));
        button("Back", this::advanced);
    }
    private void createBolt12() {
        page("Create BOLT12");
        text("Create a reusable XBT offer. Keep the wallet online so it can answer invoice requests. This offer has no expiration.");
        AmountEntry amount = new AmountEntry("Amount", "Optional - payer chooses if blank");
        text("Description"); EditText description = field("Optional - payer can see this", "");
        text("Issuer"); EditText issuer = field("Optional - payer can see this", "");
        text("Label"); EditText label = field("Optional - for your own records", "");
        LinearLayout form = page;
        button("Create BOLT12", () -> {
            final OffersOuterClass.CreateOfferRequest request;
            try {
                long sats = amount.input.getText().toString().trim().isEmpty() ? 0 : amount.sats();
                String memo = description.getText().toString().trim();
                if (sats > 0 && memo.isEmpty()) throw new Exception("A description is required for a fixed-amount BOLT12 offer.");
                request = OffersOuterClass.CreateOfferRequest.newBuilder().setAmountMsat(Math.multiplyExact(sats, 1000))
                    .setDescription(memo).setIssuer(issuer.getText().toString().trim()).setLabel(label.getText().toString().trim()).build();
            } catch (Exception e) { error(e); return; }
            work(() -> {
                OffersOuterClass.CreateOfferResponse result = OffersOuterClass.CreateOfferResponse.parseFrom(node.rpc("offersCreateOffer", request));
                if (!result.hasOffer() || !result.getOffer().getBolt12().startsWith("lno1"))
                    throw new Exception("The node did not return a BOLT12 offer. Check saved offers before trying again.");
                runOnUiThread(() -> {
                    if (page == form) showBolt12(result.getOffer());
                    else Toast.makeText(this, "BOLT12 created. Find it under Advanced → Create BOLT12 → Saved offers.", Toast.LENGTH_LONG).show();
                });
            });
        });
        button("Saved offers", this::bolt12Offers);
        button("Back", this::advanced);
    }
    private void bolt12Offers() {
        page("BOLT12 offers");
        LinearLayout target = page;
        work(() -> {
            OffersOuterClass.ListOffersResponse result = OffersOuterClass.ListOffersResponse.parseFrom(
                node.rpc("offersListOffers", OffersOuterClass.ListOffersRequest.getDefaultInstance()));
            runOnUiThread(() -> {
                if (page != target) return;
                for (OffersOuterClass.Offer offer : result.getOffersList()) {
                    String label = offer.getLabel().isEmpty() ? offer.getDescription() : offer.getLabel();
                    if (label.isEmpty()) label = "BOLT12 offer";
                    button(label + " · " + (offer.getActive() ? "Active" : "Inactive"), () -> showBolt12(offer));
                }
                if (result.getOffersCount() == 0) text("No saved offers yet.");
                button("Back", this::createBolt12);
            });
        });
    }
    private void showBolt12(OffersOuterClass.Offer offer) {
        page("BOLT12 offer", false, () -> showBolt12(offer));
        if (!offer.getLabel().isEmpty()) text(offer.getLabel());
        text(offer.getAmountMsat() == 0 ? "Payer chooses the amount" : amountReview(offer.getAmountMsat(), amountPrice()));
        if (!offer.getDescription().isEmpty()) text(offer.getDescription());
        text(offer.getActive() ? "Reusable offer · Keep the wallet online" : "This offer is inactive");
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(offer.getBolt12(), BarcodeFormat.QR_CODE, 640, 640);
            Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? 0xff000000 : 0x00000000);
            ImageView qr = new ImageView(this); qr.setImageBitmap(bitmap); qr.setContentDescription("BOLT12 offer QR code");
            addQrFrame(qr, Math.round((getResources().getDisplayMetrics().widthPixels - 56) * UI_SCALE));
        } catch (Exception e) { text("Could not render QR code: " + e.getMessage()); }
        button("Copy offer", () -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("BOLT12 offer", offer.getBolt12()));
            Toast.makeText(this, "Offer copied", Toast.LENGTH_SHORT).show();
        });
        text(offer.getBolt12());
        if (offer.getActive()) button("Disable offer", () -> confirmAction("Disable this offer?",
            "The node will stop issuing new invoices for this offer. Already-issued invoices are unaffected.", "Disable", () -> work(() -> {
                node.rpc("offersDisableOffer", OffersOuterClass.DisableOfferRequest.newBuilder().setOfferId(offer.getOfferId()).build());
                runOnUiThread(() -> showBolt12(offer.toBuilder().setActive(false).build()));
            })));
        button("Back", this::createBolt12);
    }
    private void nodeConnection() {
        page("Node connection");
        text("Network: " + prefs.getString("network", "regtest") + " · compact-filter P2P peer");
        boolean mainnet = prefs.getString("network", "regtest").equals("mainnet");
        EditText peer = field(mainnet ? "Automatic discovery (leave blank)" : "Full-node host:port",
            prefs.getString("syncPeer", mainnet ? "" : "10.0.2.2:19444"));
        CheckBox tor = new CheckBox(this); tor.setText("Use Tor"); tor.setChecked(prefs.getBoolean("useTor", false)); page.addView(tor);
        CheckBox onionPeers = new CheckBox(this); onionPeers.setText("Enable onion Lightning peers (keep clearnet direct)");
        onionPeers.setChecked(prefs.getBoolean("onionPeers", false)); page.addView(onionPeers);
        onionPeers.setEnabled(!tor.isChecked());
        tor.setOnCheckedChangeListener((v, checked) -> onionPeers.setEnabled(!checked));
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
                android.content.SharedPreferences.Editor edit = prefs.edit().putString("syncPeer", endpoint).putBoolean("useTor", tor.isChecked()).putBoolean("onionPeers", onionPeers.isChecked());
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
                for (LightningOuterClass.ChannelCloseSummary c : closed.getChannelsList()) text(channelLabelPrefix(c.getChannelPoint()) + displayAmount(c.getCapacity()) + " · " + (node.isRemote() ? "Closed" : c.getCloseType().name()) + "\n" + c.getChannelPoint());
                button("Back", this::advanced);
            });
        });
    }
    private static final class HistoryEntry {
        final String labelKey, fallbackLabel, details;
        final int direction;
        HistoryEntry(String labelKey, String fallbackLabel, String details, int direction) {
            this.labelKey = labelKey; this.fallbackLabel = fallbackLabel; this.details = details;
            this.direction = direction;
        }
    }
    private void editHistoryLabel(HistoryEntry entry, Runnable refresh) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText input = field(form, "Optional", prefs.getString(entry.labelKey, entry.fallbackLabel));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new WalletDialogBuilder().setTitle("Edit payment label").setView(form)
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
                onChain.computeIfAbsent(tx.getTimeStamp(), key -> new ArrayList<>()).add(new HistoryEntry("tx-label-" + tx.getTxHash(), transactionHistoryLabel(tx), direction + " · " + displayAmount(Math.abs(tx.getAmount())) + "\n" + status + "\n" + tx.getTxHash(), Long.compare(tx.getAmount(), 0)));
            }
            for (LightningOuterClass.Payment p : payments.getPaymentsList())
                lightning.computeIfAbsent(p.getCreationTimeNs() / 1000000000L, key -> new ArrayList<>()).add(new HistoryEntry("payment-label-" + p.getPaymentHash(), "", "Lightning sent · " + displayAmount(p.getValueSat()) + "\n" + p.getStatus().name() + "\n" + p.getPaymentHash(), -1));
            for (LightningOuterClass.Invoice invoice : invoices.getInvoicesList()) {
                if (invoice.getState() != LightningOuterClass.Invoice.InvoiceState.SETTLED) continue;
                lightning.computeIfAbsent(invoice.getSettleDate(), key -> new ArrayList<>()).add(new HistoryEntry("invoice-label-" + android.util.Base64.encodeToString(invoice.getRHash().toByteArray(), android.util.Base64.NO_WRAP), "",
                    "Lightning received · " + displayAmount(invoice.getAmtPaidSat()) + "\nSettled" + (invoice.getMemo().isEmpty() ? "" : "\nDescription: " + invoice.getMemo()), 1));
            }
            boolean limited = payments.getPaymentsCount() == 100 || invoices.getInvoicesCount() == 100;
            runOnUiThread(() -> renderPaymentHistory(onChain, lightning, limited));
        });
    }
    private void renderPaymentHistory(NavigableMap<Long, List<HistoryEntry>> onChain,
            NavigableMap<Long, List<HistoryEntry>> lightning, boolean limited) {
        page("Payment history");
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"ALL", "Lightning", "On Chain", "Home"};
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            Button button = new Button(this); styleButton(button, historyTab == i); button.setText(names[i]); button.setTextSize(13);
            button.setSelected(historyTab == i);
            button.setTypeface(getResources().getFont(R.font.wallet_humanist), historyTab == i ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            button.setTextColor(historyTab == i ? 0xfff5e7ce : 0xffc2b69f);
            button.setContentDescription(names[i] + (historyTab == i ? ", selected" : ""));
            button.setPadding(dp(4), dp(12), dp(4), dp(12));
            button.setOnClickListener(v -> {
                if (tab == 3) { home(); return; }
                historyTab = tab; renderPaymentHistory(onChain, lightning, limited);
            });
            LinearLayout.LayoutParams tabSize = new LinearLayout.LayoutParams(0, -2, 1); tabSize.setMargins(dp(2), 0, dp(2), dp(8));
            tabs.addView(button, tabSize);
        }
        page.addView(tabs);
        // Keep the original layout slot, but pin the controls at the viewport's
        // top once the heading scrolls away. Elevation keeps entries beneath it.
        tabs.setBackground(new PaperBackgroundDrawable());
        tabs.setElevation(dp(8));
        ScrollView historyScroll = pageScroll;
        historyScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) ->
            tabs.setTranslationY(Math.max(0, y - tabs.getTop())));
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
                Button row = button((entry.getKey() > 0 ? date.format(new Date(entry.getKey() * 1000)) + "\n" : "") + historyLabel(label) + item.details,
                    () -> editHistoryLabel(item, () -> renderPaymentHistory(onChain, lightning, limited)));
                if (item.direction != 0) {
                    // The paper surface supplies a soft diagonal wash and fine grain.
                    int tint = item.direction > 0 ? 0xff253a2d : 0xff422b2b;
                    int rim = item.direction > 0 ? 0xff536b50 : 0xff795650;
                    row.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33e1b879), surface(tint, 14, rim), null));
                }
                row.setAllCaps(false); row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setContentDescription(row.getText() + ". Tap to edit label.");
            }
        if (limited && historyTab != 2) text("Showing the latest 100 outgoing Lightning payments and latest 100 invoices.");
        button("Back", this::home);
    }
    private void backup() {
        if (node != null && node.isRemote()) { error(new Exception("Back up the seed and channels on your remote node. This phone holds connection credentials, not its seed.")); return; }
        page("Before you view your seed", true);
        text("Warning! Anyone who knows this seed has access to your funds.");
        text("Recovery from these words will not restore your Lightning channels. Recovering channel funds on chain requires a separate channel backup and help from your channel peers. This alpha does not yet support that recovery process.");
        button("Cancel", this::home);
        button("I understand · Show seed", this::showSeed);
    }
    private void showSeed() {
        try { String seed = new Vault(this, profile).get("seed");
            page("Back up your seed", true);
            if (seed == null) text("Create your wallet first.");
            else {
                String[] words = seed.trim().split("\\s+");
                for (int i = 0; i < words.length; i++) text((i + 1) + ". " + words[i]);
            }
            text("Write these words down privately. The seed recovers on-chain funds; it is not a complete Lightning channel backup."); button("Done", this::home);
        } catch (Exception e) { error(e); }
    }
    private static final int SAVE_CHANNEL_BACKUP = 6041, TEST_CHANNEL_BACKUP = 6042;
    private String pendingBackupName, backupOperationProfile;
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending-backup-name", pendingBackupName);
        state.putString("backup-operation-profile", backupOperationProfile);
    }
    private void clearPendingBackup() {
        if (pendingBackupName != null && pendingBackupName.matches("channel-backup-[a-f0-9-]+\\.pending"))
            new java.io.File(getCacheDir(), pendingBackupName).delete();
        pendingBackupName = null; backupOperationProfile = null;
    }
    private void createChannelBackup() {
        page("Create channel backup", true);
        TextView warning = text("Warning: if recovering this wallet, this will allow you to rediscover the channels you had and will trigger their closure. If you lose your wallet and have no channel backup, you must hope that your channel peers close channels themselves so that your funds end up in your on chain wallet.");
        warning.setTextColor(0xffff7373);
        text("Recovery still depends on your peers responding. Keep the matching seed or node secret as well as this backup. Create a fresh backup after opening, closing or splicing channels, and save it somewhere separate from this phone.");
        if (node != null && node.isRemote())
            text("This exports the remote CLN node’s static channel backup, not its seed or hsm_secret. Keep your Start9/node backup too. The exported file contains private channel details; store it privately.");
        else text("The file uses LND’s encrypted channel.backup format. It helps recover funds by closing channels; it does not restore live channels. Recovery import is not yet available in this app.");
        button("Create channel backup", () -> work(() -> {
            ChannelBackupFile.Export backup = node.exportChannelBackup();
            final String sourceProfile = profile;
            if (backup.channels == 0) {
                runOnUiThread(() -> { page("Create channel backup", true); text("No channels to back up."); button("Home", this::home); });
                return;
            }
            clearPendingBackup();
            String name = "channel-backup-" + UUID.randomUUID() + ".pending";
            java.io.File temporary = new java.io.File(getCacheDir(), name);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
                output.write(backup.data); output.getFD().sync();
            }
            runOnUiThread(() -> {
                if (isDestroyed() || !sourceProfile.equals(profile)) { temporary.delete(); return; }
                pendingBackupName = name; backupOperationProfile = sourceProfile;
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(backup.extension.equals("json") ? "application/json" : "application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, "xbt-channel-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "." + backup.extension);
                try { startActivityForResult(save, SAVE_CHANNEL_BACKUP); }
                catch (Exception e) { clearPendingBackup(); error(e); }
            });
        }));
        button("Home", this::home);
    }
    private void testChannelBackup() {
        page("Test channel backup", true);
        text("Choose the backup file you saved. This test never restores a backup or closes channels.");
        text(node != null && node.isRemote()
            ? "For CLN, this checks the file checksum, node identity and matching entries in the node’s current static backup. It cannot prove recovery from an older backup."
            : "This checks whether the backup decrypts with this wallet’s keys and covers its current channels. It does not prove that peers will respond during recovery.");
        button("Choose channel backup", () -> {
            backupOperationProfile = profile;
            Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(select, TEST_CHANNEL_BACKUP); }
            catch (Exception e) { backupOperationProfile = null; error(e); }
        });
        button("Home", this::home);
    }
    private void channelBackupResult(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) { clearPendingBackup(); return; }
        if (!profile.equals(backupOperationProfile)) {
            clearPendingBackup(); error(new Exception("The selected wallet changed. Start the backup operation again.")); return;
        }
        android.net.Uri uri = data.getData();
        if (request == SAVE_CHANNEL_BACKUP) {
            if (pendingBackupName == null || !pendingBackupName.matches("channel-backup-[a-f0-9-]+\\.pending")) {
                clearPendingBackup(); error(new Exception("The prepared backup is unavailable. Create it again.")); return;
            }
            java.io.File temporary = new java.io.File(getCacheDir(), pendingBackupName);
            busy = true;
            new Thread(() -> {
                try {
                    byte[] bytes;
                    try (java.io.InputStream input = new java.io.FileInputStream(temporary)) { bytes = ChannelBackupFile.read(input); }
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new Exception("Could not open the destination file.");
                        output.write(bytes); output.flush();
                    }
                    runOnUiThread(() -> {
                        busy = false; clearPendingBackup();
                        if (isDestroyed()) return;
                        page("Channel backup saved", true); text("Your channel backup was saved. Keep it with your recovery records, separate from this phone.");
                        button("Test channel backup", this::testChannelBackup); button("Home", this::home);
                    });
                } catch (Exception e) { runOnUiThread(() -> {
                    busy = false; clearPendingBackup();
                    if (!isDestroyed()) error(new Exception("Could not finish saving the backup: " + e.getMessage() + ". The destination may contain an incomplete file; create the backup again.", e));
                }); }
            }, "save-channel-backup").start();
            return;
        }
        backupOperationProfile = null;
        if (node == null || !node.rpcReady) { error(new Exception("Wait for the wallet to connect, then choose the backup file again.")); return; }
        work(() -> {
            final byte[] bytes;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) { bytes = ChannelBackupFile.read(input); }
            String report = node.testChannelBackup(bytes);
            runOnUiThread(() -> {
                page("Channel backup checked", true); text(report);
                button("Create channel backup", this::createChannelBackup); button("Home", this::home);
            });
        });
    }
    private void testRecovery() {
        try {
            String saved = new Vault(this, profile).get("seed");
            page("Test seed recovery", true);
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
                    String expected = new Vault(this, profile).get("seed");
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
                EditText amount = funds.input;
                text("Network fee rate (sats/vByte)");
                EditText fee = field("", "1");
                fee.setContentDescription("Network fee rate in sats per vByte");
                fee.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
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
                                if (!prefs.edit().putString("peer-address-" + p[0].toLowerCase(Locale.ROOT), p[1]).commit())
                                    throw new Exception("Could not save peer address.");
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
    private final class ExpirationEntry {
        final EditText count;
        int unit;
        ExpirationEntry() {
            unit = prefs.getInt("receive-expiration-unit", 2);
            if (unit < 0 || unit >= InvoiceExpiration.UNITS.length) unit = 2;
            LinearLayout fields = new LinearLayout(MainActivity.this);
            fields.setGravity(Gravity.CENTER_VERTICAL);
            final boolean[] expanded = {false};
            Button toggle = button("Expiration (default 1hr) ▾", () -> {});
            toggle.setBackground(null); toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            toggle.setTextColor(0xffc2b69f);
            toggle.setTypeface(getResources().getFont(R.font.wallet_humanist), android.graphics.Typeface.NORMAL);
            count = field("Number", prefs.getString("receive-expiration-count", "1"));
            page.removeView(count);
            count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            count.setContentDescription("Expiration number");
            fields.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
            Button timeframe = new Button(MainActivity.this); styleButton(timeframe, false);
            timeframe.setText(InvoiceExpiration.UNITS[unit] + " ▾");
            timeframe.setContentDescription("Expiration time frame");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1); size.leftMargin = dp(8);
            fields.addView(timeframe, size);
            fields.setVisibility(View.GONE); fields.setAlpha(0f);
            page.addView(fields, new LinearLayout.LayoutParams(-1, -2));
            Runnable update = () -> {
                String summary;
                try { long seconds = seconds(); summary = seconds == 3600 ? "default 1hr" : InvoiceExpiration.describe(seconds); }
                catch (IllegalArgumentException e) { summary = "set duration"; }
                toggle.setText("Expiration (" + summary + ") " + (expanded[0] ? "▴" : "▾"));
            };
            toggle.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                animateExpandable("invoice-expiration", fields, expanded[0]);
                update.run();
            });
            timeframe.setOnClickListener(v -> new WalletDialogBuilder().setTitle("Time frame")
                .setSingleChoiceItems(InvoiceExpiration.UNITS, unit, (dialog, which) -> {
                    unit = which; prefs.edit().putInt("receive-expiration-unit", unit).apply();
                    timeframe.setText(InvoiceExpiration.UNITS[unit] + " ▾"); update.run(); dialog.dismiss();
                }).setNegativeButton("Cancel", null).show());
            count.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    prefs.edit().putString("receive-expiration-count", s.toString()).apply(); update.run();
                }
                public void afterTextChanged(android.text.Editable value) {}
            });
            update.run();
        }
        long seconds() { return InvoiceExpiration.seconds(count.getText().toString(), unit); }
    }
    private final class InvoiceRoutingEntry {
        final CheckBox hints = new CheckBox(MainActivity.this);
        final CheckBox blinded = new CheckBox(MainActivity.this);
        InvoiceRoutingEntry() {
            LinearLayout fields = new LinearLayout(MainActivity.this);
            fields.setOrientation(LinearLayout.VERTICAL);
            Button toggle = button("Advanced ▾", () -> {});
            toggle.setBackground(null); toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            toggle.setTextColor(0xffc2b69f);
            toggle.setTypeface(getResources().getFont(R.font.wallet_humanist), android.graphics.Typeface.NORMAL);
            hints.setText("Include route hints"); hints.setChecked(true);
            blinded.setText("Blinded paths"); blinded.setChecked(false);
            fields.addView(hints); fields.addView(blinded);
            hints.setOnCheckedChangeListener((v, checked) -> { if (checked) blinded.setChecked(false); });
            blinded.setOnCheckedChangeListener((v, checked) -> { if (checked) hints.setChecked(false); });
            if (node != null && node.isRemote()) {
                blinded.setEnabled(false);
                TextView explanation = new TextView(MainActivity.this);
                explanation.setText("Blinded paths are not supported by this remote node’s BOLT11 invoice API.");
                explanation.setTextColor(0xffc2b69f);
                fields.addView(explanation);
            }
            fields.setVisibility(View.GONE); fields.setAlpha(0f);
            page.addView(fields, new LinearLayout.LayoutParams(-1, -2));
            final boolean[] expanded = {false};
            toggle.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                toggle.setText("Advanced " + (expanded[0] ? "▴" : "▾"));
                animateExpandable("invoice-routing", fields, expanded[0]);
            });
        }
    }
    private void receiveLightning() {
        page("Receive Lightning");
        Switch swaps = new Switch(this);
        LinearLayout swapToggle = new LinearLayout(this); swapToggle.setGravity(Gravity.CENTER_VERTICAL);
        swapToggle.addView(swaps);
        TextView swapsLabel = new TextView(this); swapsLabel.setText("Swaps"); swapsLabel.setTextColor(0xffe0c18c);
        swapsLabel.setPadding(dp(6), 0, 0, 0); swapsLabel.setOnClickListener(v -> swaps.performClick());
        swapToggle.addView(swapsLabel);
        swaps.setContentDescription("Swaps — receive BTC as XBT");
        FrameLayout.LayoutParams toggleSize = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        pageHeader.addView(swapToggle, toggleSize);
        swaps.setChecked(prefs.getBoolean("receive-lightning-swaps", false));
        final org.json.JSONObject pendingSwap;
        try { pendingSwap=pendingSwapReceive(); }
        catch (Exception e) { error(e); return; }
        if (swaps.isChecked() && pendingSwap!=null) {
            swaps.setOnCheckedChangeListener((v,enabled) -> {
                prefs.edit().putBoolean("receive-lightning-swaps",enabled).apply();
                receiveLightning();
            });
            button("Existing swap", () -> showSwapReceive(pendingSwap));
            button("Cancel swap", () -> cancelSwapReceive(pendingSwap));
            return;
        }
        TextView receiveLimit = text("Checking incoming liquidity…");
        receiveLimit.setTextColor(0xffff7373);
        final Long[] largestInbound = {null};
        Runnable showReceiveLimit = () -> {
            if (largestInbound[0] == null) return;
            String value = displayUnit() == 2 && amountPrice() == null
                ? String.format(Locale.US, "%,d sats", largestInbound[0]) : displayAmount(largestInbound[0]);
            receiveLimit.setText("The largest amount you can receive in one Lightning payment is " + value);
            receiveLimit.setContentDescription("Largest incoming Lightning payment: " + value + ". Tap to cycle sats, XBT and fiat.");
        };
        if (node != null) node.io.execute(() -> {
            try {
                LightningOuterClass.ListChannelsResponse channels = LightningOuterClass.ListChannelsResponse.parseFrom(
                    node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance()));
                long largest = ReceiveLiquidity.largest(channels);
                runOnUiThread(() -> {
                    if (!receiveLimit.isAttachedToWindow()) return;
                    largestInbound[0] = largest;
                    showReceiveLimit.run();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (receiveLimit.isAttachedToWindow())
                        receiveLimit.setText("Could not determine incoming liquidity: " + e.getMessage());
                });
            }
        });
        AmountEntry entry = new AmountEntry("Amount", "Optional");
        entry.changed = showReceiveLimit;
        receiveLimit.setContentDescription("Largest incoming Lightning payment. Tap to cycle sats, XBT and fiat.");
        receiveLimit.setMinHeight(dp(48));
        receiveLimit.setOnClickListener(v -> entry.currency.performClick());
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
        ExpirationEntry expiration = new ExpirationEntry();
        InvoiceRoutingEntry routing = new InvoiceRoutingEntry();
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
        Button createInvoice = button("Create invoice", () -> {
            final long sats, expirySeconds;
            try {
                expirySeconds = expiration.seconds();
                String entered = amount.getText().toString().trim();
                sats = entered.isEmpty() ? 0 : entry.sats();
                if (swaps.isChecked() && sats == 0) throw new Exception("Enter the XBT amount to receive through the swap.");
            }
            catch (Exception e) { error(e); return; }
            final String description = memo.getText().toString().trim();
            final String invoiceLabel = privateLabel.getText().toString().trim();
            final boolean includeHints = routing.hints.isChecked(), blindedPaths = routing.blinded.isChecked();
            if (swaps.isChecked()) { createSwapReceive(sats, description, invoiceLabel, expirySeconds, includeHints, blindedPaths); return; }
            work(() -> {
                LightningOuterClass.AddInvoiceResponse result = LightningOuterClass.AddInvoiceResponse.parseFrom(node.rpc("addInvoice",
                    LightningOuterClass.Invoice.newBuilder().setValue(sats).setMemo(description).setExpiry(expirySeconds).setPrivate(includeHints).setIsBlinded(blindedPaths).build()));
                if (!prefs.edit().putString("invoice-label-" + android.util.Base64.encodeToString(result.getRHash().toByteArray(), android.util.Base64.NO_WRAP), invoiceLabel).commit())
                    throw new Exception("Could not save invoice label.");
                String invoice = result.getPaymentRequest();
                BitMatrix matrix = new MultiFormatWriter().encode(invoice, BarcodeFormat.QR_CODE, 640, 640);
                Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) bitmap.setPixel(x, y, matrix.get(x,y) ? 0xff000000 : 0x00000000);
                runOnUiThread(() -> {
                    receivingInvoiceHash = result.getRHash();
                    page("Lightning invoice"); text((sats == 0 ? "Sender chooses the amount" : displayAmount(sats)) + " · Expires in " + InvoiceExpiration.describe(expirySeconds));
                    ImageView qr = new ImageView(this); qr.setImageBitmap(bitmap); qr.setContentDescription("Lightning invoice QR code");
                    addQrFrame(qr, Math.round((getResources().getDisplayMetrics().widthPixels - 56) * UI_SCALE));
                    button("Copy invoice", () -> {
                        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Lightning invoice", invoice));
                        Toast.makeText(this, "Invoice copied", Toast.LENGTH_SHORT).show();
                    });
                    copyableInvoice(invoice); button("Home", this::home);
                });
            });
        });
        swaps.setOnCheckedChangeListener((v,enabled) -> {
            prefs.edit().putBoolean("receive-lightning-swaps", enabled).apply();
            if (enabled && pendingSwap!=null) { receiveLightning(); return; }
            createInvoice.setText(enabled ? "Create invoice (swap)" : "Create invoice");
            amountTitle.setText("Amount");
            setFieldHint(amount, enabled ? "Required for swaps" : "Optional");
            setFieldHint(memo, enabled ? "Optional - for your XBT invoice" : "Optional - sender can see this");
            controller.setVisibility(enabled ? View.VISIBLE : View.GONE); receipts.setVisibility(enabled ? View.VISIBLE : View.GONE);
        });
        createInvoice.setText(swaps.isChecked() ? "Create invoice (swap)" : "Create invoice");
        setFieldHint(amount, swaps.isChecked() ? "Required for swaps" : "Optional");
        setFieldHint(memo, swaps.isChecked() ? "Optional - for your XBT invoice" : "Optional - sender can see this");
        controller.setVisibility(swaps.isChecked() ? View.VISIBLE : View.GONE);
        receipts.setVisibility(swaps.isChecked() ? View.VISIBLE : View.GONE);
        button("Home", this::home);
    }
    private void saveSwapSend(org.json.JSONObject record) throws Exception {
        if (!prefs.edit().putString("swap-send-" + record.getString("hash"), record.toString()).commit())
            throw new Exception("Could not save the outgoing swap. No payment will be sent.");
    }
    private void prepareSwapSend(String raw, LightningOuterClass.PayReq pay, String label) throws Exception {
        if (pay.getNumMsat() <= 0) throw new Exception("For a BTC swap, ask the recipient for an invoice with the amount included. Amountless BTC swap invoices are not supported yet.");
        if (pay.getNumMsat() % 1000 != 0) throw new Exception("This controller requires whole BTC satoshis. Ask the recipient for an invoice without fractional sats.");
        if (!Policy.invoiceAllowed(true, pay.getNumMsat(), pay.getTimestamp(), pay.getExpiry(), System.currentTimeMillis()/1000))
            throw new Exception("This BTC invoice has expired or has an invalid amount.");
        String hash = pay.getPaymentHash();
        if (prefs.contains("payment-" + hash)) throw new Exception("This invoice already has a recorded payment attempt. Check payment history before retrying.");
        org.json.JSONObject record;
        if (prefs.contains("swap-send-" + hash)) {
            record = new org.json.JSONObject(prefs.getString("swap-send-" + hash, "{}"));
            if (!raw.equals(record.getString("recipient_invoice"))) throw new Exception("This payment hash already belongs to another saved invoice. Check Outgoing swaps.");
            SwapSend.reopenUncreated(record,prefs.contains("payment-"+hash));
            saveSwapSend(record);
        } else {
            String provider = prefs.getString("receive-provider", ""), api = prefs.getString("receive-api-" + provider, "");
            if (api.isEmpty() || prefs.getString("receive-key-" + provider, "").isEmpty())
                throw new Exception("This is a BTC invoice. Add a swap controller and configure Wallet Swap Access in the dots menu → Swap controllers first.");
            record = new org.json.JSONObject().put("hash", hash).put("recipient_invoice", raw).put("btc_msat", pay.getNumMsat())
                .put("recipient", pay.getDestination()).put("recipient_expiry", Math.addExact(pay.getTimestamp(), pay.getExpiry()))
                .put("provider", provider).put("api", api).put("environment", prefs.getString("network", "regtest"))
                .put("label", label).put("created", System.currentTimeMillis());
            saveSwapSend(record);
        }
        runOnUiThread(() -> showSwapSend(record));
        requestSwapSend(record, "quote");
    }
    private void updateSwapSend(String hash, String action) {
        work(() -> requestSwapSend(new org.json.JSONObject(prefs.getString("swap-send-" + hash, "{}")), action));
    }
    private void requestSwapSend(org.json.JSONObject record, String action) throws Exception {
        record.remove("last_error");
        if (record.optBoolean("dismissed")) throw new Exception("This swap request was dismissed.");
        if (action.equals("cancel")) {
            if (prefs.contains("payment-"+record.optString("hash"))) throw new Exception("A payment was already attempted. Check payment history and swap status before taking further action.");
            record.put("cancel_pending",true); saveSwapSend(record);
        }

        try {
            if (action.equals("cancel") && record.optJSONObject("reply")==null) {
                // A failed quote response does not establish whether it was recorded.
                // Status is read-only; never create/retry a quote during cancellation.
                requestSwapSend(record,"status");
                if (record.has("last_error")) throw new Exception(record.getString("last_error"));
                if (!record.getJSONObject("reply").optBoolean("terminal")) requestSwapSend(record,"cancel");
                return;
            }
            String api = record.getString("api"); prepareControllerTransport(api);
            org.json.JSONObject request = new org.json.JSONObject().put("action", action).put("invoice", record.getString("recipient_invoice"))
                .put("environment", record.getString("environment")).put("provider", record.getString("provider"));
            if (action.equals("accept") || action.equals("cancel")) request.put("id", record.getJSONObject("reply").getString("id"));
            org.json.JSONObject reply = SwapControllers.send(api, prefs.getString("receive-key-" + record.getString("provider"), ""), request, controllerTor(api));
            LightningOuterClass.PayReq decoded = reply.has("invoice") ? LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq",
                LightningOuterClass.PayReqString.newBuilder().setPayReq(reply.getString("invoice")).build())) : null;
            SwapSend.validate(record, reply, decoded);
            record.put("reply", reply); record.remove("cancel_pending");
            if (!reply.optBoolean("terminal") && System.currentTimeMillis() - record.optLong("market_checked") >= 60000) {
                record.put("market_checked", System.currentTimeMillis()); record.remove("market_error");
                try { record.put("market", FiatRates.swapMarket(controllerTor(api))); }
                catch (Exception e) { record.put("market_error", e.getMessage() == null ? "Market price unavailable" : e.getMessage()); }
            }
        } catch (Exception e) {
            record.put("last_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (action.equals("cancel")) SwapSend.dismissUncreatedCancellation(record,
                prefs.contains("payment-"+record.optString("hash")),e.getMessage());
        }
        saveSwapSend(record);
        runOnUiThread(() -> { if (screen.equals("Review BTC swap") && record.optString("hash").equals(viewingSendHash)) {
            if (record.optBoolean("dismissed")) outgoingSwaps(); else showSwapSend(record);
        } });
    }
    private String swapFeeText(org.json.JSONObject reply) {
        try {
            org.json.JSONObject f = reply.getJSONObject("fees");
            return "Provider fee: " + java.math.BigDecimal.valueOf(f.getLong("markup_bps"), 2).stripTrailingZeros().toPlainString() + "% ("
                + SwapSend.sats(f.getLong("provider_fee_msat")) + " " + f.getString("provider_fee_asset").toUpperCase(Locale.ROOT) + " sats)"
                + "\nCoordinator routing cost: " + SwapSend.sats(f.getLong("routing_fee_msat")) + " " + f.getString("routing_fee_asset").toUpperCase(Locale.ROOT) + " sats"
                + "\nBoth are included in the quoted swap amount.";
        } catch (Exception e) { return "This controller did not disclose a fee breakdown."; }
    }
    private String swapSendMarketText(org.json.JSONObject record) {
        try {
            if (record.has("market_error")) throw new Exception(record.getString("market_error"));
            org.json.JSONObject market = record.getJSONObject("market"), reply = record.getJSONObject("reply");
            SwapPrice.requireFresh(market.getLong("timestamp"), System.currentTimeMillis());
            java.math.BigDecimal rate = new java.math.BigDecimal(market.getString("btc_per_xbt"));
            SwapPrice comparison = new SwapPrice(reply.getLong("xbt_msat"), reply.getLong("btc_msat"), false, rate);
            java.math.BigDecimal delta = new java.math.BigDecimal(comparison.percent());
            return (comparison.warning() ? "Warning: over 5% above market.\n" : "")
                + "Quoted cost: " + delta.abs().toPlainString() + "% " + (delta.signum() >= 0 ? "higher" : "lower") + " than Neoxa market"
                + "\nNeoxa last trade: 1 XBT = " + rate.stripTrailingZeros().toPlainString() + " BTC"
                + "\nIncludes provider fee and quoted coordinator routing cost. Your wallet’s routing fee is additional.";
        } catch (Exception e) { return "Warning: market comparison unavailable. " + (e.getMessage() == null ? "No fresh price." : e.getMessage()); }
    }
    private void showSwapSend(org.json.JSONObject record) {
        boolean refreshing=screen.equals("Review BTC swap") && record.optString("hash").equals(viewingSendHash);
        int previousScroll=refreshing && pageScroll!=null ? pageScroll.getScrollY() : 0;
        page("Review BTC swap"); viewingSendHash = record.optString("hash");
        if (refreshing && pageTitle!=null) {
            if (onboardingReveal!=null) { onboardingReveal.cancel(); onboardingReveal=null; }
            pageTitle.setReveal(1f);
        }
        text("Recipient receives: " + SwapSend.sats(record.optLong("btc_msat")) + " BTC sats");
        text("Recipient: " + record.optString("recipient"));
        text("Provider: " + record.optString("provider"));
        org.json.JSONObject reply = record.optJSONObject("reply");
        if (record.has("last_error")) copyableError("Swap request failed: " + record.optString("last_error") + "\nCheck this saved request before retrying. A failed reply does not establish whether the controller accepted it.");
        if (reply != null) {
            text("Status: " + reply.optString("state").replace('_', ' '));
            text("You pay: " + amountReview(reply.optLong("xbt_msat"), amountPrice()));
            text(swapFeeText(reply)); text(swapSendMarketText(record));
            text("Pay before " + java.text.DateFormat.getDateTimeInstance().format(new Date(reply.optLong("expires_at") * 1000)));
            if (prefs.contains("payment-" + record.optString("hash"))) {
                text("Wallet payment: " + prefs.getString("payment-" + record.optString("hash"), "unknown") + ". Check payment history; this request will not be paid again automatically.");
                button("Payment history", this::paymentHistory);
            } else if (!reply.optBoolean("terminal") && !reply.optBoolean("blocked") && !reply.optBoolean("cancel_requested") && !record.optBoolean("cancel_pending") && reply.optLong("expires_at") > System.currentTimeMillis()/1000) {
                if (!reply.optBoolean("approved")) {
                    button("Accept quote", () -> updateSwapSend(record.optString("hash"), "accept"));

                } else if (reply.has("invoice") && !record.has("last_error")) {
                    button("Review payment", () -> reviewSwapSendPayment(record.optString("hash")));
                } else text("Preparing your XBT payment invoice…");
            }
            if (reply.optBoolean("cancel_requested") || record.optBoolean("cancel_pending")) text("Cancelling… Waiting for the controller to confirm cleanup.");
        } else text(record.optBoolean("dismissed") ? "Not created. This request was dismissed." : "No quote is available yet. No payment has been sent.");
        if (SwapSend.canCancel(record,prefs.contains("payment-"+record.optString("hash"))))
            button(record.optBoolean("cancel_pending") ? "Retry cancellation" : "Cancel swap", () -> confirmAction("Cancel swap?", "The controller will be checked first. If it never created this swap, the request will be dismissed. An issued invoice may need to expire before cancellation completes.", "Cancel swap", () -> updateSwapSend(record.optString("hash"), "cancel")));
        if (!record.optBoolean("dismissed")) {
            button("Check status", () -> updateSwapSend(record.optString("hash"), "status"));
            if (reply == null && !record.optBoolean("cancel_pending")) button("Retry quote", () -> updateSwapSend(record.optString("hash"), "quote"));
        }
        button("Outgoing swaps", this::outgoingSwaps); button("Home", this::home);
        if (refreshing) restorePageScroll(previousScroll);
    }
    private void outgoingSwaps() {
        page("Outgoing swaps");
        List<org.json.JSONObject> records = new ArrayList<>();
        try {
            for (String key : prefs.getAll().keySet()) if (key.startsWith("swap-send-")) records.add(new org.json.JSONObject(prefs.getString(key, "{}")));
            records.sort((a,b) -> Long.compare(b.optLong("created"), a.optLong("created")));
            for (org.json.JSONObject r : records) {
                org.json.JSONObject reply = r.optJSONObject("reply");
                String hash=r.optString("hash");
                button(SwapSend.sats(r.optLong("btc_msat")) + " BTC sats · " + SwapSend.historyState(r,prefs.getString("payment-"+hash,"")), () -> {
                    try {
                        org.json.JSONObject fresh=new org.json.JSONObject(prefs.getString("swap-send-"+hash,"{}"));
                        showSwapSend(fresh);
                        org.json.JSONObject current=fresh.optJSONObject("reply");
                        if (current!=null && !current.optBoolean("terminal")) updateSwapSend(hash,"status");
                    } catch (Exception e) { error(e); }
                });
            }
            if (records.isEmpty()) text("No outgoing swaps yet.");
        } catch (Exception e) { error(e); }
        button("Back", this::payment);
    }
    private void reviewSwapSendPayment(String hash) {
        work(() -> {
            org.json.JSONObject record = new org.json.JSONObject(prefs.getString("swap-send-" + hash, "{}"));
            requestSwapSend(record, "status");
            if (record.has("last_error")) throw new Exception(record.getString("last_error"));
            org.json.JSONObject reply = record.getJSONObject("reply");
            String invoice = reply.getString("invoice");
            LightningOuterClass.PayReq pay = LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq", LightningOuterClass.PayReqString.newBuilder().setPayReq(invoice).build()));
            SwapSend.validate(record, reply, pay);
            long msats = reply.getLong("xbt_msat"), feeLimit = Math.max(1, msats/1000/100);
            String review = "Recipient receives: " + SwapSend.sats(record.getLong("btc_msat")) + " BTC sats\nYou pay: " + amountReview(msats, amountPrice())
                + "\n\n" + swapFeeText(reply) + "\n\n" + swapSendMarketText(record)
                + "\nMaximum wallet routing fee: " + String.format(Locale.US, "%,d", feeLimit) + " XBT sats (" + displayAmount(feeLimit) + ")";
            runOnUiThread(() -> confirmAction("Pay BTC invoice through swap?", review, "Pay", () -> work(() -> {
                // The reviewed quote is immutable; only its expiry and local payment marker can change here.
                if (reply.getLong("expires_at") <= System.currentTimeMillis()/1000) throw new Exception("Swap quote expired. No payment was submitted.");
                if (!record.has("market_error") && record.has("market")) {
                    try { SwapPrice.requireFresh(record.getJSONObject("market").getLong("timestamp"), System.currentTimeMillis()); }
                    catch (Exception e) { throw new Exception("The market comparison has expired. Review the payment again before paying."); }
                }
                submitLightningPayment(invoice, pay, msats, record.optString("label"), feeLimit);
            })));
        });
    }
    private org.json.JSONObject pendingSwapReceive() throws Exception {
        org.json.JSONObject latest=null;
        for (String name:prefs.getAll().keySet()) if (name.startsWith("swap-receive-")) {
            org.json.JSONObject saved=new org.json.JSONObject(prefs.getString(name,"{}"));
            if (SwapReceive.inProgress(saved) && (latest==null || saved.optLong("created")>latest.optLong("created"))) latest=saved;
        }
        return latest;
    }
    private void cancelSwapReceive(org.json.JSONObject record) {
        confirmAction("Cancel swap?",
            "The controller will check whether this swap can still be cancelled. Cancellation is complete only after the controller confirms it. An issued invoice may need to expire first.",
            "Cancel swap", () -> {
                showSwapReceive(record);
                work(() -> requestSwapReceive(new org.json.JSONObject(prefs.getString("swap-receive-"+record.getString("hash"),"{}")), "cancel"));
            });
    }
    private void createSwapReceive(long sats, String description, String label, long expirySeconds, boolean includeHints, boolean blindedPaths) {
        work(() -> {
            String provider = prefs.getString("receive-provider", ""), api=prefs.getString("receive-api-"+provider,""), key=prefs.getString("receive-key-"+provider,"");
            if (api.isEmpty() || key.isEmpty()) throw new Exception("Select a controller and configure its Wallet Swap Access first.");
            if (pendingSwapReceive()!=null)
                throw new Exception("A swap request is still pending. Open Swap requests to resume it before creating another.");
            LightningOuterClass.AddInvoiceResponse result=LightningOuterClass.AddInvoiceResponse.parseFrom(node.rpc("addInvoice",
                LightningOuterClass.Invoice.newBuilder().setValue(sats).setMemo(description).setExpiry(expirySeconds).setPrivate(includeHints).setIsBlinded(blindedPaths).build()));
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
        button("Clear swap requests", () -> confirmAction("Clear swap requests?",
            "Clear finished and dismissed requests from this list. Swaps still in progress will remain.",
            "Clear swap requests", () -> {
                try {
                    android.content.SharedPreferences.Editor edit=prefs.edit();
                    for (String key:prefs.getAll().keySet()) if (key.startsWith("swap-receive-")) {
                        org.json.JSONObject saved=new org.json.JSONObject(prefs.getString(key,"{}"));
                        if (!SwapReceive.inProgress(saved)) edit.putString(key,saved.put("history_hidden",true).toString());
                    }
                    if (!edit.commit()) throw new Exception("Could not clear swap requests.");
                    swapReceipts();
                } catch (Exception e) { error(e); }
            }));
        java.util.List<org.json.JSONObject> records=new ArrayList<>();
        try {
            for (String name:prefs.getAll().keySet()) if (name.startsWith("swap-receive-")) {
                org.json.JSONObject saved=new org.json.JSONObject(prefs.getString(name,"{}"));
                if (!saved.optBoolean("history_hidden") || SwapReceive.inProgress(saved)) records.add(saved);
            }
            records.sort((a,b)->Long.compare(b.optLong("created"),a.optLong("created")));
            for (org.json.JSONObject r:records) button(displayAmount(r.optLong("sats"))+" · "+swapReceiveState(r),()->showSwapReceive(r));
            if (records.isEmpty()) text("No swap requests yet.");
        } catch(Exception e) { error(e); }
        button("Back",this::receiveLightning);
    }
    private String swapReceiveState(org.json.JSONObject record) {
        if (record.optBoolean("dismissed")) return "Not created";
        if (record.optBoolean("received")) return "Received";
        if (record.optBoolean("cancel_pending")) return "Cancelling…";
        org.json.JSONObject reply=record.optJSONObject("reply");
        return reply==null ? "Request pending" : reply.optString("state","pending").replace('_',' ');
    }
    private void refreshSwapReceive(String hash, boolean create) {
        work(() -> requestSwapReceive(new org.json.JSONObject(prefs.getString("swap-receive-"+hash,"{}")), create));
    }
    private void requestSwapReceive(org.json.JSONObject record, boolean create) throws Exception {
        requestSwapReceive(record, create ? "create" : "status");
    }
    private void requestSwapReceive(org.json.JSONObject record, String action) throws Exception {
        record.remove("last_error");
        if (action.equals("cancel")) {
            record.put("cancel_pending",true);
            if (!prefs.edit().putString("swap-receive-"+record.getString("hash"),record.toString()).commit()) throw new Exception("Could not save cancellation request.");
            runOnUiThread(() -> showSwapReceive(record));
        }
        try { requestSwapReceiveChecked(record, action); }
        catch (Exception e) {
            record.put("last_error", e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage());
            if (!action.equals("create") && e.getMessage()!=null && e.getMessage().contains("Code: wallet_receive_not_found")) {
                record.put("not_found",true);
                SwapReceive.dismissUncreatedCancellation(record);
            }
            if (!prefs.edit().putString("swap-receive-"+record.getString("hash"),record.toString()).commit()) throw new Exception("Could not save receive error; check the existing request before retrying.");
            runOnUiThread(() -> {
                if (screen.equals("Swap invoice") && record.optString("hash").equals(viewingSwapHash)) {
                    if (record.optBoolean("dismissed")) swapReceipts();
                    else showSwapReceive(record);
                }
            });
        }
    }
    private void requestSwapReceiveChecked(org.json.JSONObject record, String action) throws Exception {
        if (action.equals("create")) record.remove("not_found");
        String hash=record.getString("hash");
        LightningOuterClass.Invoice own=LightningOuterClass.Invoice.parseFrom(node.rpc("lookupInvoice",LightningOuterClass.PaymentHash.newBuilder().setRHashStr(hash).build()));
        if (own.getState()==LightningOuterClass.Invoice.InvoiceState.SETTLED) {
            record.put("received",true).put("received_sats", own.getAmtPaidSat());
        } else {
            String api=record.getString("api"); prepareControllerTransport(api);
            org.json.JSONObject request=new org.json.JSONObject().put("action",action).put("invoice",record.getString("invoice"))
                .put("environment",record.getString("environment")).put("provider",record.getString("provider"));
            org.json.JSONObject reply=SwapControllers.receive(api,prefs.getString("receive-key-"+record.getString("provider"),""),request,controllerTor(api));
            LightningOuterClass.PayReq decoded=reply.has("invoice") ? LightningOuterClass.PayReq.parseFrom(node.rpc("decodePayReq",LightningOuterClass.PayReqString.newBuilder().setPayReq(reply.getString("invoice")).build())) : null;
            SwapReceive.validate(record,reply,decoded); record.put("reply",reply); record.remove("cancel_pending");
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
        if (record.optBoolean("received")) {
            showReceived(record.optLong("received_sats", record.optLong("sats")));
            return;
        }
        boolean refreshing=screen.equals("Swap invoice") && record.optString("hash").equals(viewingSwapHash);
        org.json.JSONObject currentReply=record.optJSONObject("reply");
        boolean expired=currentReply!=null && currentReply.optLong("expires_at")<=System.currentTimeMillis()/1000;
        String content=record.toString()+"|"+expired;
        if (refreshing && content.equals(displayedSwapReceive)) return;
        int previousScroll=refreshing && pageScroll!=null ? pageScroll.getScrollY() : 0;
        page("Swap invoice"); viewingSwapHash=record.optString("hash"); displayedSwapReceive=content;
        if (refreshing && pageTitle!=null) {
            if (onboardingReveal!=null) { onboardingReveal.cancel(); onboardingReveal=null; }
            pageTitle.setReveal(1f);
        }
        swapReceiveAmountSats=record.optLong("sats");
        swapReceiveAmountText=text(""); updateSwapReceiveAmount();
        swapReceiveAmountText.setMinHeight(dp(48));
        swapReceiveAmountText.setOnClickListener(v -> {
            int count=prefs.getString("fiat-currency","USD").equals("Off") ? 2 : 3;
            prefs.edit().putInt("display-unit",(displayUnit()+1)%count).apply();
            updateSwapReceiveAmount();
            if (displayUnit()==2) fetchFiatRates();
        });
        text(swapReceiveState(record));
        if (record.has("last_error")) {
            String message=record.optString("last_error");
            copyableError(message);
        }
        if (record.optBoolean("not_found") && !record.optBoolean("dismissed")) button("Dismiss uncreated request", () -> {
            try { record.put("dismissed",true); prefs.edit().putString("swap-receive-"+record.getString("hash"),record.toString()).commit(); swapReceipts(); }
            catch(Exception e) { error(e); }
        });
        org.json.JSONObject reply=record.optJSONObject("reply");
        if (!record.optBoolean("received") && !record.optBoolean("cancel_pending") && reply!=null && !reply.optBoolean("terminal") && !reply.optBoolean("cancel_requested")) {
            long deadline=reply.optLong("expires_at");
            text("Sender pays "+SwapSend.sats(reply.optLong("btc_msat"))+" BTC sats");
            text(swapFeeText(reply));
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
                    copyableInvoice(invoice);
                } catch(Exception e) { error(e); }
            } else if (deadline<=System.currentTimeMillis()/1000) text("Payment window ended. Checking the final swap outcome.");
        }
        if (!record.optBoolean("dismissed") && !record.optBoolean("received") && (reply==null || !reply.optBoolean("terminal"))) {
            button("Check status",()->refreshSwapReceive(record.optString("hash"),false));
            boolean cancelling=record.optBoolean("cancel_pending") || (reply!=null && reply.optBoolean("cancel_requested"));
            if (!cancelling) button("Retry request",()->refreshSwapReceive(record.optString("hash"),true));
            if (reply==null || reply.optBoolean("can_cancel") || record.optBoolean("cancel_pending"))
                button(record.optBoolean("cancel_pending") ? "Retry cancellation" : "Cancel swap", () -> cancelSwapReceive(record));
            if (reply!=null && reply.optBoolean("cancel_requested")) text("Cancelling… Waiting for the controller to confirm cleanup. The payment invoice is hidden.");
        }
        button("Swap requests",this::swapReceipts); button("Home",this::home);
        if (refreshing) restorePageScroll(previousScroll);
    }
    private void restorePageScroll(int previousScroll) {
        ScrollView restored=pageScroll;
        restored.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                restored.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (pageScroll==restored) restored.scrollTo(0,previousScroll);
            }
        });
    }
    private TextView copyableInvoice(String invoice) {
        TextView value=text(invoice);
        value.setContentDescription("Lightning invoice. Tap to copy.");
        value.setOnClickListener(v -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Lightning invoice",invoice));
            Toast.makeText(this,"Invoice copied",Toast.LENGTH_SHORT).show();
        });
        return value;
    }
    private void updateSwapReceiveAmount() {
        if (swapReceiveAmountText!=null) {
            swapReceiveAmountText.setText("Receive " + displayAmount(swapReceiveAmountSats));
            swapReceiveAmountText.setContentDescription(swapReceiveAmountText.getText() + ". Tap to change currency.");
        }
    }
    private void updateReceivedAmount() {
        if (receivedAmountText!=null) {
            receivedAmountText.setText("Received " + displayAmount(receivedAmountSats));
            receivedAmountText.setContentDescription(receivedAmountText.getText() + ". Tap to change currency.");
        }
    }
    private void revealPaymentCheck(String description) {
        String successScreen=screen;
        OnboardingTitleView heading=pageTitle;
        int position=page.indexOfChild(heading);
        page.removeView(heading);
        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER); titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.addView(heading,new LinearLayout.LayoutParams(-2,-2));
        TextView check=new TextView(this); check.setText("✓"); check.setTextSize(28);
        check.setTextColor(0xff63bb89); check.setPadding(dp(10),0,0,dp(20));
        check.setContentDescription(description); check.setVisibility(View.INVISIBLE);
        titleRow.addView(check,new LinearLayout.LayoutParams(-2,-2));
        page.addView(titleRow,position,new LinearLayout.LayoutParams(-1,-2));
        Runnable revealCheck=() -> {
            if (pageTitle!=heading || !screen.equals(successScreen)) return;
            check.setVisibility(View.VISIBLE);
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                check.setAlpha(0f); check.animate().alpha(1f).setDuration(180).start();
            }
        };
        if (onboardingReveal!=null && onboardingReveal.isRunning()) {
            onboardingReveal.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled=true; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (!cancelled) revealCheck.run(); }
            });
        } else revealCheck.run();
    }
    private void showPaymentSuccessful() {
        page("Payment successful");
        revealPaymentCheck("Payment successful");
        button("Home",this::home);
    }
    private void showReceived(long sats) {
        page("Received", false, () -> showReceived(sats));
        revealPaymentCheck("Payment received");
        receivedAmountSats=sats;
        receivedAmountText=text(""); receivedAmountText.setGravity(Gravity.CENTER); receivedAmountText.setTextSize(20);
        receivedAmountText.setMinHeight(dp(56)); updateReceivedAmount();
        receivedAmountText.setOnClickListener(v -> {
            int count=prefs.getString("fiat-currency","USD").equals("Off") ? 2 : 3;
            prefs.edit().putInt("display-unit",(displayUnit()+1)%count).apply();
            updateReceivedAmount();
            if (displayUnit()==2) fetchFiatRates();
        });
        button("Home",this::home);
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
                + "\nQuoted cost: " + new java.math.BigDecimal(comparison.percent()).abs().toPlainString() + "% "
                + (comparison.costRatio.compareTo(java.math.BigDecimal.ONE) >= 0 ? "higher" : "lower") + " than Neoxa market"
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
                showReceived(invoice.getAmtPaidSat());
            });
        });
    }
    private void payment() { payment(""); }
    private void payment(String scanned) {
        page("Pay Lightning"); text("XBT invoices pay directly. BTC invoices use your selected swap controller; review its fee and exchange rate before paying.");
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
            // Let wrapped labels determine row height; both actions stretch equally.
            for (Button choice : new Button[]{scanChoice, pasteChoice}) {
                choice.setSingleLine(false);
                choice.setMinHeight(dp(72)); choice.setMinimumHeight(dp(72));
                choice.setGravity(Gravity.CENTER);
            }
            LinearLayout.LayoutParams scanSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            LinearLayout.LayoutParams pasteSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
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
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(surface(0xff12120f, 18, 0xff69563d));
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
                if (!(pay.containsFeatures(512) || pay.containsFeatures(513))) {
                    if (pay.containsFeatures(514) || pay.containsFeatures(515)) throw new Exception("This invoice uses an unsupported chain feature.");
                    prepareSwapSend(raw, pay, paymentLabel);
                    return;
                }
                final boolean amountless = pay.getNumMsat() == 0;
                if (amountless && entered.trim().isEmpty()) {
                    runOnUiThread(() -> entry.visible(true));
                    throw new Exception("Enter an amount for this amountless invoice.");
                }
                final long msats = amountless ? Math.multiplyExact(exact != null ? exact : AmountUnits.sats(entered, enteredUnit, reviewPrice), 1000) : pay.getNumMsat();
                if (!Policy.invoiceAllowed(true, msats, pay.getTimestamp(), pay.getExpiry(), System.currentTimeMillis()/1000)) throw new Exception("This invoice has expired or has an invalid amount.");
                if (prefs.contains("payment-" + pay.getPaymentHash())) throw new Exception("This invoice already has a recorded attempt. Reconcile its status before retrying.");
                long feeLimit = Math.max(1, msats/1000/100);
                runOnUiThread(() -> confirmAction("Pay XBT invoice?", amountReview(msats, reviewPrice) + "\nMaximum routing fee: " + displayAmount(feeLimit) + "\nRecipient: " + pay.getDestination(), "Pay", () -> work(()->{
                        submitLightningPayment(raw, pay, msats, paymentLabel, feeLimit);
                    })));
            });
        }); button("Outgoing swaps", this::outgoingSwaps); button("Home", this::home);
        if (!scanned.isEmpty()) invoice.setText(scanned);
    }
    private void submitLightningPayment(String raw, LightningOuterClass.PayReq pay, long msats, String paymentLabel, long feeLimit) throws Exception {
        if (prefs.contains("payment-" + pay.getPaymentHash())) throw new Exception("This invoice already has a recorded payment attempt. Check payment history before retrying.");
        if (!Policy.invoiceAllowed(pay.containsFeatures(512) || pay.containsFeatures(513), msats, pay.getTimestamp(), pay.getExpiry(), System.currentTimeMillis()/1000))
            throw new Exception("The payment invoice has expired. No payment was submitted.");
        if (!prefs.edit().putString("payment-" + pay.getPaymentHash(), "submitted").putString("payment-label-" + pay.getPaymentHash(), paymentLabel).commit()) throw new Exception("Could not record payment");
        routerrpc.RouterOuterClass.SendPaymentRequest.Builder request = routerrpc.RouterOuterClass.SendPaymentRequest.newBuilder()
            .setPaymentRequest(raw).setFeeLimitSat(feeLimit).setTimeoutSeconds(30).setMaxParts(1);
        if (pay.getNumMsat() == 0) request.setAmtMsat(msats);
        runOnUiThread(() -> { page("Payment submitted"); text("Waiting for the node to report settlement. Closing this screen does not cancel the payment."); button("Home", this::home); });
        node.sendPayment(request.build().toByteArray(), new lndmobile.RecvStream() {
            private volatile boolean terminal;
            public void onResponse(byte[] data) {
                try {
                    LightningOuterClass.Payment result = LightningOuterClass.Payment.parseFrom(data);
                    terminal = result.getStatus() == LightningOuterClass.Payment.PaymentStatus.SUCCEEDED || result.getStatus() == LightningOuterClass.Payment.PaymentStatus.FAILED;
                    String status = "Payment " + result.getStatus().name().toLowerCase(Locale.ROOT);
                    if (result.getStatus() == LightningOuterClass.Payment.PaymentStatus.FAILED) status += ": " + result.getFailureReason().name();
                    prefs.edit().putString("payment-" + pay.getPaymentHash(), status).commit();
                    final String label = status;
                    final boolean succeeded = result.getStatus() == LightningOuterClass.Payment.PaymentStatus.SUCCEEDED;
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (succeeded) showPaymentSuccessful();
                        else {
                            page(label);
                            button("Home", MainActivity.this::home);
                        }
                    });
                } catch (Exception e) { onError(e); }
            }
            public void onError(Exception e) { if (terminal) return; runOnUiThread(() -> { if (!isDestroyed()) error(new Exception("Payment status uncertain. Refresh payment history before any further attempt. " + e.getMessage())); }); }
        });
    }
    private void scan(EditText target) {
        scanTarget = target;
        scanPayment = false;
        launchScanner("Scan XBT connection or invoice");
    }
    private void launchScanner(String prompt) {
        new com.google.zxing.integration.android.IntentIntegrator(this).setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
            .setCaptureActivity(WalletScanActivity.class).setPrompt(prompt).setBeepEnabled(false).initiateScan();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==6050) { pinOpening=false; checkPinLock(); return; }
        if (request == SAVE_CHANNEL_BACKUP || request == TEST_CHANNEL_BACKUP) { channelBackupResult(request, result, data); return; }
        com.google.zxing.integration.android.IntentResult scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(request, result, data);
        if (scan == null) return;
        if (connectionScan != null) {
            java.util.function.Consumer<String> consume = connectionScan; connectionScan = null;
            if (scan.getContents() != null) consume.accept(scan.getContents().trim());
            return;
        }
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
    @Override protected void onDestroy() { if (launchWallet != null) launchWallet.dispose(); if (onboardingReveal != null) onboardingReveal.cancel(); if (headerSlideAnimation != null) headerSlideAnimation.cancel(); handler.removeCallbacksAndMessages(null); if (bound) unbindService(connection); super.onDestroy(); }
}
