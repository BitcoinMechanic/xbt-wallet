package org.xbt.wallet;

final class ChannelStatus {
    static String confirmations(long remaining, long confirmationHeight, long chainHeight) {
        return confirmations(remaining, confirmationHeight, chainHeight, 0);
    }
    static String confirmations(long remaining, long confirmationHeight, long chainHeight, long knownRequired) {
        long received = confirmationHeight == 0 ? 0 : Math.max(0, chainHeight - confirmationHeight + 1);
        String required = remaining > 0 ? Long.toString(remaining + received)
            : knownRequired > 0 ? Long.toString(knownRequired) : "?";
        return "Confirmations needed: " + remaining + "/" + required;
    }
}
