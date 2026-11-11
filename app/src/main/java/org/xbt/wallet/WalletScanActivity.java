package org.xbt.wallet;

import android.content.Context;
import android.content.res.Configuration;
import com.journeyapps.barcodescanner.CaptureActivity;

/** Portrait scanner with the same text scaling as the wallet. */
public final class WalletScanActivity extends CaptureActivity {
    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.densityDpi = Math.round(config.densityDpi * 0.75f);
        config.fontScale *= 1.15f;
        super.attachBaseContext(base.createConfigurationContext(config));
    }
}
