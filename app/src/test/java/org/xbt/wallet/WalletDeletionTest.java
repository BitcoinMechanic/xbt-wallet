package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import lnrpc.LightningOuterClass.*;

public class WalletDeletionTest {
    private static final WalletBalanceResponse EMPTY=WalletBalanceResponse.getDefaultInstance();
    private static final ListChannelsResponse NONE=ListChannelsResponse.getDefaultInstance();
    private static final PendingChannelsResponse NO_PENDING=PendingChannelsResponse.getDefaultInstance();
    @Test public void allowsEmptyWallet() throws Exception { WalletDeletion.check(EMPTY,NONE,NO_PENDING); }
    @Test public void refusesEveryBalanceClass() {
        for(WalletBalanceResponse balance:new WalletBalanceResponse[]{
                WalletBalanceResponse.newBuilder().setTotalBalance(1).build(),
                WalletBalanceResponse.newBuilder().setConfirmedBalance(1).build(),
                WalletBalanceResponse.newBuilder().setUnconfirmedBalance(1).build(),
                WalletBalanceResponse.newBuilder().setLockedBalance(1).build()}) {
            try {WalletDeletion.check(balance,NONE,NO_PENDING);fail("Funded wallet allowed");}catch(Exception expected){assertTrue(expected.getMessage().contains("funds"));}
        }
    }
    @Test public void refusesOpenAndEveryPendingChannelState() {
        try {WalletDeletion.check(EMPTY,ListChannelsResponse.newBuilder().addChannels(Channel.getDefaultInstance()).build(),NO_PENDING);fail();}catch(Exception expected){assertTrue(expected.getMessage().contains("channels"));}
        for(PendingChannelsResponse pending:new PendingChannelsResponse[]{
                PendingChannelsResponse.newBuilder().addPendingOpenChannels(PendingChannelsResponse.PendingOpenChannel.getDefaultInstance()).build(),
                PendingChannelsResponse.newBuilder().addPendingClosingChannels(PendingChannelsResponse.ClosedChannel.getDefaultInstance()).build(),
                PendingChannelsResponse.newBuilder().addPendingForceClosingChannels(PendingChannelsResponse.ForceClosedChannel.getDefaultInstance()).build(),
                PendingChannelsResponse.newBuilder().addWaitingCloseChannels(PendingChannelsResponse.WaitingCloseChannel.getDefaultInstance()).build(),
                PendingChannelsResponse.newBuilder().setTotalLimboBalance(1).build()}){
            try{WalletDeletion.check(EMPTY,NONE,pending);fail("Channel state allowed");}catch(Exception expected){assertTrue(expected.getMessage().contains("channels"));}
        }
    }
}
