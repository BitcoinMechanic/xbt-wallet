package org.xbt.wallet;

import android.content.SharedPreferences;
import java.util.*;

/** Persist issuance before display; retain labels for older outstanding requests. */
final class ReceiveAddresses {
    static final String LABEL_PREFIX = "receive-address-label-";
    interface Source { String create() throws Exception; }
    static String fresh(SharedPreferences prefs, Source source) throws Exception {
        Set<String> shown = new HashSet<>(prefs.getStringSet("receive-addresses-shown", Collections.emptySet()));
        String legacy = prefs.getString("deposit", "");
        if (!legacy.isEmpty()) shown.add(legacy);
        String address = source.create();
        if (address == null || address.isEmpty()) throw new Exception("The node did not return a receive address.");
        if (!shown.add(address)) throw new Exception("The node returned a previously displayed address. Reopen Receive to request a new one.");
        if (!prefs.edit().putStringSet("receive-addresses-shown", shown).remove("deposit").commit())
            throw new Exception("Could not record the new receive address. It was not displayed.");
        return address;
    }
    static Map<String, String> labels(SharedPreferences prefs) {
        Map<String, String> labels = new HashMap<>();
        String legacy = prefs.getString("receive-label-address", "");
        if (!legacy.isEmpty()) labels.put(legacy, prefs.getString("receive-label", ""));
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
            if (entry.getKey().startsWith(LABEL_PREFIX) && entry.getValue() instanceof String)
                labels.put(entry.getKey().substring(LABEL_PREFIX.length()), (String) entry.getValue());
        labels.values().removeIf(String::isEmpty);
        return labels;
    }
}
