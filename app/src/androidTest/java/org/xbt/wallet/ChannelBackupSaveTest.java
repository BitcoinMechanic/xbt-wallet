package org.xbt.wallet;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelBackupSaveTest {
    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    @Test public void exportResultWritesChosenFileAndRemovesPrivateTemporaryCopy() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",context.getPackageName());
        var activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String name="channel-backup-"+java.util.UUID.randomUUID()+".pending";
        File temporary=new File(context.getCacheDir(),name),destination=new File(context.getCacheDir(),"test-export.backup");
        byte[] contents={42,31,20,19,8};Files.write(temporary.toPath(),contents);
        try {
            instrumentation.runOnMainSync(()->{try {
                field("pendingBackupName").set(activity,name);
                field("backupOperationProfile").set(activity,field("profile").get(activity));
                var method=MainActivity.class.getDeclaredMethod("channelBackupResult",int.class,int.class,Intent.class);method.setAccessible(true);
                method.invoke(activity,6041,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(destination)));
            }catch(Exception e){throw new AssertionError(e);}});
            for(int i=0;i<100 && temporary.exists();i++)Thread.sleep(20);
            instrumentation.waitForIdleSync();
            assertArrayEquals(contents,Files.readAllBytes(destination.toPath()));
            assertFalse(temporary.exists());
            instrumentation.runOnMainSync(()->{try{
                assertEquals("Channel backup saved",field("screen").get(activity));
                assertNull(field("pendingBackupName").get(activity));
                assertFalse(field("busy").getBoolean(activity));
            }catch(Exception e){throw new AssertionError(e);}});
        } finally {
            instrumentation.runOnMainSync(activity::finish);temporary.delete();destination.delete();
        }
    }
}
