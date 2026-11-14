package org.xbt.wallet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Isolated presentation test: no node, invoices, or payments. */
public class ReceiveLimitCurrencyTest {
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static TextView limit(View view) {
        if (view instanceof TextView && String.valueOf(view.getContentDescription()).startsWith("Largest incoming Lightning")) return (TextView)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView result = limit(((ViewGroup)view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static TextView named(View view, String name) {
        if (view instanceof TextView && name.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView found = named(((ViewGroup)view).getChildAt(i),name); if(found!=null)return found;
        }
        return null;
    }
    @Test public void tappingLimitCyclesGlobalUnitsWithoutChangingEnteredSats() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        assertEquals("org.xbt.wallet.profiletest", instrumentation.getTargetContext().getPackageName());
        var activity = (MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            try {
                var prefsField = field(MainActivity.class,"prefs"); Object oldPrefs = prefsField.get(activity);
                var nodeField = field(MainActivity.class,"node"); Object oldNode = nodeField.get(activity);
                var ratesField = field(MainActivity.class,"fiatRates"); Object oldRates = ratesField.get(activity);
                var attemptField = field(MainActivity.class,"lastRatesAttempt"); long oldAttempt = attemptField.getLong(activity);
                SharedPreferences prefs = activity.getSharedPreferences("receive-limit-unit-test",0);
                prefs.edit().clear().putString("network","regtest").putString("fiat-currency","USD").putInt("display-unit",0).commit();
                try {
                    prefsField.set(activity,prefs); nodeField.set(activity,null);
                    ratesField.set(activity,new JSONObject().put("timestamp",System.currentTimeMillis()).put("USD","250"));
                    attemptField.setLong(activity,android.os.SystemClock.elapsedRealtime());
                    var receive = MainActivity.class.getDeclaredMethod("receiveLightning"); receive.setAccessible(true); receive.invoke(activity);
                    View root=activity.getWindow().getDecorView();
                    android.widget.CheckBox hints=(android.widget.CheckBox)named(root,"Include route hints");
                    android.widget.CheckBox blinded=(android.widget.CheckBox)named(root,"Blinded paths");
                    assertNotNull(hints); assertNotNull(blinded);
                    assertTrue(hints.isChecked()); assertFalse(blinded.isChecked());
                    assertEquals(View.GONE,((View)hints.getParent()).getVisibility());
                    named(root,"Advanced ▾").performClick();
                    assertEquals(View.VISIBLE,((View)hints.getParent()).getVisibility());
                    blinded.performClick(); assertTrue(blinded.isChecked()); assertFalse(hints.isChecked());
                    hints.performClick(); assertTrue(hints.isChecked()); assertFalse(blinded.isChecked());
                    Object entry = field(MainActivity.class,"activeAmountEntry").get(activity);
                    EditText input = (EditText)field(entry.getClass(),"input").get(entry); input.setText("1000000");
                    var sats = entry.getClass().getDeclaredMethod("sats"); sats.setAccessible(true);
                    TextView limit = limit(activity.getWindow().getDecorView()); assertNotNull(limit);
                    for (int expected : new int[]{1,2,0}) {
                        limit.performClick(); assertEquals(expected,prefs.getInt("display-unit",-1));
                        assertEquals(1000000L,((Long)sats.invoke(entry)).longValue());
                    }
                    prefs.edit().putString("fiat-currency","Off").apply();
                    limit.performClick(); assertEquals(1,prefs.getInt("display-unit",-1));
                    limit.performClick(); assertEquals(0,prefs.getInt("display-unit",-1));
                } finally {
                    prefsField.set(activity,oldPrefs); nodeField.set(activity,oldNode); ratesField.set(activity,oldRates); attemptField.setLong(activity,oldAttempt); activity.finish();
                }
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
