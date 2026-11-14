package org.xbt.wallet;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Separate opaque window: async wallet rendering cannot replace this lock. */
public final class PinLockActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER); int pad=Math.round(28*getResources().getDisplayMetrics().density);
        panel.setPadding(pad,pad,pad,pad); panel.setBackground(new PaperBackgroundDrawable());
        OnboardingTitleView title=new OnboardingTitleView(this); title.setText("Wallet Locked");
        title.setTextSize(28); title.setTypeface(android.graphics.Typeface.DEFAULT,1); panel.addView(title);
        EditText pin=new EditText(this); pin.setHint("PIN"); pin.setTextColor(0xffedddbc);
        pin.setInputType(18); pin.setSingleLine(true); pin.setSaveEnabled(false);
        pin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        panel.addView(pin,new LinearLayout.LayoutParams(-1,-2));
        TextView status=new TextView(this); status.setTextColor(0xffedddbc); panel.addView(status);
        Button unlock=new Button(this); unlock.setText("Unlock"); unlock.setAllCaps(false);
        unlock.setTextColor(0xffedddbc); unlock.setBackground(new PaperDrawable(0xff70441f,14,0xffba8245,false));
        panel.addView(unlock,new LinearLayout.LayoutParams(-1,-2));
        unlock.setOnClickListener(v->{
            String entered=pin.getText().toString(); pin.setText(""); unlock.setEnabled(false);
            new Thread(()->{
                try {
                    if(!WalletPin.verify(this,entered))throw new Exception("Incorrect PIN.");
                    runOnUiThread(()->{ WalletPin.unlock(); setResult(RESULT_OK); finish(); });
                } catch(Exception e) { runOnUiThread(()->{status.setText(e.getMessage()); unlock.setEnabled(true);}); }
            },"pin-unlock").start();
        });
        TextView help=new TextView(this); help.setTextColor(0xffc2b69f);
        help.setText("Forgotten PIN? There is no PIN reset. Recover using your wallet backups or reconnect your remote node after reinstalling. Do not erase the app without your recovery information.");
        help.setPadding(0,pad,0,0); panel.addView(help);
        setContentView(panel);
    }
    @Override public void onBackPressed() { moveTaskToBack(true); }
}
