package org.xbt.wallet;

/** Conservative fee preview for the selected inputs; final miner fee is set by the node. */
final class FundingPreview {
    final long fee, reserve, capacity;
    FundingPreview(long selected, long available, long budget, long feeRate, long currentReserve,
                   boolean publicChannel, long inputWeight, int inputCount) {
        if (budget <= 0 || budget > selected || selected > available || feeRate <= 0 || inputCount == 0)
            throw new IllegalArgumentException("Select outputs and enter funds no greater than their total.");
        long required = Math.min(100000, Math.addExact(currentReserve, publicChannel ? 10000 : 0));
        reserve = Math.max(0, required - (available - budget));
        // Version/locktime, count varints, segwit marker/flag, funding output.
        long weight = 42 + (inputCount >= 253 ? 8 : 0) + inputWeight + 172;
        if (selected > budget || reserve > 0) weight += 172; // Taproot change, as in the fork.
        fee = Math.floorDiv(Math.multiplyExact(weight, Math.max(253, Math.multiplyExact(feeRate, 250))), 1000);
        capacity = budget - reserve - fee;
        if (capacity < 20000) throw new IllegalArgumentException("Funds after fees and reserves must support a channel of at least 20,000 sats.");
    }
}
