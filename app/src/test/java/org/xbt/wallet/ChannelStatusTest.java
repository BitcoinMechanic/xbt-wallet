package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChannelStatusTest {
    @Test public void showsRemainingOutOfTotalRequired() {
        assertEquals("Confirmations needed: 3/3", ChannelStatus.confirmations(3, 0, 100));
        assertEquals("Confirmations needed: 2/3", ChannelStatus.confirmations(2, 100, 100));
        assertEquals("Confirmations needed: 1/3", ChannelStatus.confirmations(1, 100, 101));
        assertEquals("Confirmations needed: 3/3", ChannelStatus.confirmations(3, 101, 100));
        assertEquals("Confirmations needed: 0/3", ChannelStatus.confirmations(0, 100, 110, 3));
        assertEquals("Confirmations needed: 0/?", ChannelStatus.confirmations(0, 100, 110, 0));
    }
}
