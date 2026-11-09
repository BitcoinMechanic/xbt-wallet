package org.xbt.wallet;

import java.util.Set;
import lnrpc.LightningOuterClass;

final class DepositDetection {
    static boolean received(String address, Set<String> existing, LightningOuterClass.TransactionDetails transactions) {
        for (LightningOuterClass.Transaction tx : transactions.getTransactionsList()) {
            if (existing.contains(tx.getTxHash()) || tx.getNumConfirmations() < 0 || tx.getAmount() <= 0) continue;
            for (LightningOuterClass.OutputDetail output : tx.getOutputDetailsList()) {
                if (output.getIsOurAddress() && output.getAmount() > 0 && address.equals(output.getAddress())) return true;
            }
        }
        return false;
    }
}
