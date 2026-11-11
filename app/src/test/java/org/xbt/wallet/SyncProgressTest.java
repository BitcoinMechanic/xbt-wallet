package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class SyncProgressTest {
    @Test public void tracksCompletedWorkNotRequestedRanges() {
        SyncProgress p = new SyncProgress();
        p.accept("Syncing to block height 100000 from peer example");
        p.accept("Processed 2000 blocks in the last 10s (height 25000, date)");
        p.accept("Starting cfheaders sync from (block_height=0) to (block_height=25000)");
        assertEquals(0,p.filters); assertEquals(25,p.percent(p.headers));
        p.accept("Verified 2000 filter headers in the last 10s (height 20000, date)");
        assertEquals(20,p.percent(p.filters));
    }
    @Test public void reportsStartupAfterChainSync() {
        SyncProgress p = new SyncProgress();
        p.accept("Chain backend is fully synced!");
        assertEquals("Starting Lightning services…", p.stage);
        p.accept("Funding manager starting");
        assertEquals("Restoring pending channels…", p.stage);
    }
    @Test public void recognizesCheckpointsAndCapsPercent() {
        SyncProgress p = new SyncProgress();
        assertEquals(0,p.percent(123));
        p.accept("Syncing to block height 100 from peer example");
        p.accept("Verified downloaded block header against checkpoint at height 101/hash");
        p.accept("Waiting for more block headers, then will start cfheaders sync from height 99...");
        assertEquals(100,p.percent(p.headers)); assertEquals(99,p.filters);
    }
}
