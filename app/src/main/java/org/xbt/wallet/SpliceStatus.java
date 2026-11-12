package org.xbt.wallet;

import java.util.Locale;
import java.util.function.LongFunction;
import lnrpc.LightningOuterClass.Channel;
import lnrpc.LightningOuterClass.PendingSplice;

final class SpliceStatus {
    static String identity(Channel channel) {
        return channel.getOriginalChannelPoint().isEmpty() ? channel.getChannelPoint() : channel.getOriginalChannelPoint();
    }
    static String sats(long amount) { return String.format(Locale.US, "%,d sats", amount); }
    static String signed(long amount, LongFunction<String> format) {
        String value = format.apply(amount);
        if (amount <= 0) return value;
        return value.startsWith("≈ ") ? "≈ +" + value.substring(2) : "+" + value;
    }
    static String progress(PendingSplice splice) {
        if (splice.getDead()) return "This splice can no longer confirm.";
        if (splice.getRelayStatus().equals("rejected")) return "Broadcast rejected: " + splice.getRelayReason();
        if (splice.getTxid().isEmpty()) return "Negotiating splice";
        long confirmations = Integer.toUnsignedLong(splice.getConfirmations());
        long required = Integer.toUnsignedLong(splice.getLockDepth());
        String count = "Confirmations: " + confirmations + (required > 0 ? "/" + required : " (target unavailable)");
        if (splice.getWeLocked() && !splice.getPeerLocked()) return count + " · waiting for peer";
        if (required > 0 && confirmations >= required) return count + " · completing splice";
        return count;
    }
    static String summary(Channel channel) { return summary(channel, SpliceStatus::sats); }
    static String summary(Channel channel, LongFunction<String> format) {
        if (!channel.hasPendingSplice()) return "";
        PendingSplice s = channel.getPendingSplice();
        String title = s.getDead() ? "Splice failed" : "Splice pending";
        if (s.getLocalDeltaSat() != 0) title += "\nSpendable: " + signed(s.getLocalDeltaSat(), format);
        if (s.getRemoteDeltaSat() != 0) title += "\nReceivable: " + signed(s.getRemoteDeltaSat(), format);
        return title + "\n" + progress(s);
    }
    static String details(Channel channel) { return details(channel, SpliceStatus::sats); }
    static String details(Channel channel, LongFunction<String> format) {
        if (!channel.hasPendingSplice()) return "";
        PendingSplice s = channel.getPendingSplice();
        String result = summary(channel, format);
        if (s.getNewCapacitySat() > 0) result += "\nCapacity after splice: " + format.apply(s.getNewCapacitySat());
        if (!s.getTxid().isEmpty()) result += "\nNetwork fee: " + format.apply(s.getFeeSat()) + "\nPaid by this wallet: " + format.apply(s.getOurFeeSat());
        return result + "\nCurrent balances remain in effect until the splice locks. Changes shown are before channel reserves.";
    }
}
