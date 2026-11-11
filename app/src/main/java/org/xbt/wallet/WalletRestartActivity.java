package org.xbt.wallet;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.widget.TextView;
import android.view.Gravity;

/** A separate process reopens the selected profile after the old runtime exits. */
public final class WalletRestartActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        TextView label = new TextView(this); label.setText("Switching wallets…"); label.setTextSize(20);
        label.setTextColor(0xffe8d2a7); label.setBackgroundColor(0xff101517); label.setGravity(Gravity.CENTER);
        setContentView(label);
        int oldPid = getIntent().getIntExtra("old_pid", -1);
        handler.post(new Runnable() {
            public void run() {
                boolean running = false;
                if (oldPid > 0) {
                    try { android.system.Os.kill(oldPid, 0); running = true; }
                    catch (android.system.ErrnoException ignored) { }
                }
                if (running) { handler.postDelayed(this, 100); return; }
                startActivity(new Intent(WalletRestartActivity.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finishAndRemoveTask();
            }
        });
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
