package org.xbt.wallet;
import lnrpc.LightningOuterClass.*;

/** Destructive deletion is allowed only after the selected embedded wallet is empty. */
final class WalletDeletion {
    static void check(WalletBalanceResponse funds, ListChannelsResponse channels, PendingChannelsResponse pending) throws Exception {
        if (channels.getChannelsCount() != 0 || pending.getPendingOpenChannelsCount() != 0
                || pending.getPendingClosingChannelsCount() != 0 || pending.getPendingForceClosingChannelsCount() != 0
                || pending.getWaitingCloseChannelsCount() != 0 || pending.getTotalLimboBalance() != 0)
            throw new Exception("This wallet still has open, pending or closing channels. Close its channels and recover the funds before deleting it.");
        if (funds.getTotalBalance() != 0 || funds.getConfirmedBalance() != 0 || funds.getUnconfirmedBalance() != 0 || funds.getLockedBalance() != 0)
            throw new Exception("This wallet still has on-chain funds. Send them to another wallet before deleting it.");
    }
}
