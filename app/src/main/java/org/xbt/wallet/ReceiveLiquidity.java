package org.xbt.wallet;

import lnrpc.LightningOuterClass;

/** Largest per-channel inbound balance after the peer's required reserve. */
final class ReceiveLiquidity {
    static long largest(LightningOuterClass.ListChannelsResponse channels) {
        long largest = 0;
        for (LightningOuterClass.Channel channel : channels.getChannelsList()) {
            long reserve = channel.hasRemoteConstraints()
                ? channel.getRemoteConstraints().getChanReserveSat()
                : channel.getRemoteChanReserveSat();
            long inbound = Math.max(0, channel.getRemoteBalance());
            largest = Math.max(largest, Math.max(0, inbound - Math.max(0, reserve)));
        }
        return largest;
    }
}
