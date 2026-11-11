package org.xbt.wallet;

import lnrpc.LightningOuterClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ReceiveLiquidityTest {
    private LightningOuterClass.Channel channel(long inbound, long reserve) {
        return LightningOuterClass.Channel.newBuilder().setRemoteBalance(inbound)
            .setRemoteConstraints(LightningOuterClass.ChannelConstraints.newBuilder().setChanReserveSat(reserve))
            .setLocalChanReserveSat(999999).build();
    }
    @Test public void subtractsEachRemoteReserveBeforeChoosingLargest() {
        assertEquals(190000, ReceiveLiquidity.largest(LightningOuterClass.ListChannelsResponse.newBuilder()
            .addChannels(channel(250000, 100000)).addChannels(channel(200000, 10000)).build()));
    }
    @Test public void supportsRemoteNodesUsingLegacyReserveField() {
        assertEquals(97000, ReceiveLiquidity.largest(LightningOuterClass.ListChannelsResponse.newBuilder()
            .addChannels(LightningOuterClass.Channel.newBuilder().setRemoteBalance(100000).setRemoteChanReserveSat(3000)).build()));
    }
    @Test public void emptyOrReserveExhaustedChannelsCannotGoNegative() {
        assertEquals(0, ReceiveLiquidity.largest(LightningOuterClass.ListChannelsResponse.getDefaultInstance()));
        assertEquals(0, ReceiveLiquidity.largest(LightningOuterClass.ListChannelsResponse.newBuilder()
            .addChannels(channel(2000, 3000)).build()));
    }
    @Test public void modernReserveOverridesDeprecatedField() {
        assertEquals(100000, ReceiveLiquidity.largest(LightningOuterClass.ListChannelsResponse.newBuilder()
            .addChannels(channel(100000, 0).toBuilder().setRemoteChanReserveSat(5000)).build()));
    }
}
