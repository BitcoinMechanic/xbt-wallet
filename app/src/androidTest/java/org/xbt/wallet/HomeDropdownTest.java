package org.xbt.wallet;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeDropdownTest {
    @Test public void dropdownsRemainResponsiveWhileNodeWorkIsBusy() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            try {
                var busy = MainActivity.class.getDeclaredField("busy"); busy.setAccessible(true);
                boolean previous = busy.getBoolean(activity);
                busy.setBoolean(activity, true);
                try {
                    var page = MainActivity.class.getDeclaredMethod("page", String.class); page.setAccessible(true);
                    page.invoke(activity, "Home");
                    var balances = MainActivity.class.getDeclaredMethod("homeBalances", long.class, long.class, long.class);
                    balances.setAccessible(true); balances.invoke(activity, 0L, 0L, 0L);
                    var state = MainActivity.class.getDeclaredField("homeActionsExpanded"); state.setAccessible(true);
                    boolean[] expanded = (boolean[]) state.get(activity);
                    expanded[0] = false; expanded[1] = false;
                    Button chain = find(activity.getWindow().getDecorView(), "On Chain");
                    Button lightning = find(activity.getWindow().getDecorView(), "Lightning");
                    assertNotNull(chain); assertNotNull(lightning);
                    chain.performClick(); assertTrue(expanded[0]); assertFalse(expanded[1]);
                    lightning.performClick(); assertFalse(expanded[0]); assertTrue(expanded[1]);
                    lightning.performClick(); assertFalse(expanded[1]);
                    chain.performClick(); assertTrue(expanded[0]);
                    chain.performClick(); assertFalse(expanded[0]);
                } finally { busy.setBoolean(activity, previous); }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private static Button find(View view, String label) {
        if (view instanceof Button && ((Button)view).getText().toString().equals(label)) return (Button)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                Button found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
