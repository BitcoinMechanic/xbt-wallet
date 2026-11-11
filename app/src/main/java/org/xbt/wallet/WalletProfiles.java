package org.xbt.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.*;

/** Stable profile IDs isolate node databases, settings and encrypted secret stores. */
final class WalletProfiles {
    static final String LEGACY = "default";
    static String valid(String id) {
        if (!LEGACY.equals(id) && (id == null || !id.matches("[a-f0-9]{32}"))) throw new IllegalArgumentException("Invalid wallet profile");
        return id;
    }
    static SharedPreferences registry(Context context) { return context.getSharedPreferences("wallet-profiles", 0); }
    static String active(Context context) { return valid(registry(context).getString("active", LEGACY)); }
    static String settingsName(String id) { return LEGACY.equals(valid(id)) ? "wallet" : "wallet-" + id; }
    static String vaultName(String id) { return LEGACY.equals(valid(id)) ? "vault" : "vault-" + id; }
    static String keyAlias(String id) { return LEGACY.equals(valid(id)) ? "xbt-wallet-v1" : "xbt-wallet-v1-" + id; }
    static SharedPreferences settings(Context context, String id) { return context.getSharedPreferences(settingsName(id), 0); }
    static File directory(Context context, String id, String network) {
        if (!network.equals("mainnet") && !network.equals("regtest")) throw new IllegalArgumentException("Invalid wallet network");
        return new File(context.getFilesDir(), LEGACY.equals(valid(id)) ? "node-" + network : "profiles/" + id + "/node-" + network);
    }
    static List<String> ids(Context context) {
        Set<String> all = new TreeSet<>(registry(context).getStringSet("ids", Collections.emptySet()));
        all.add(LEGACY);
        return new ArrayList<>(all);
    }
    static String create(Context context) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        SharedPreferences previous = settings(context, active(context));
        if (!settings(context, id).edit().putString("network", previous.getString("network", "mainnet"))
                .putInt("display-unit", previous.getInt("display-unit", 0))
                .putString("fiat-currency", previous.getString("fiat-currency", "USD")).commit())
            throw new Exception("Could not create wallet profile.");
        Set<String> all = new HashSet<>(ids(context)); all.add(id);
        if (!registry(context).edit().putStringSet("ids", all).commit()) throw new Exception("Could not save wallet profile.");
        return id;
    }
    static void activate(Context context, String id) throws Exception {
        if (!ids(context).contains(valid(id))) throw new Exception("Wallet profile not found.");
        if (!registry(context).edit().putString("active", id).commit()) throw new Exception("Could not switch wallet profile.");
    }
    static String label(Context context, String id) {
        String name = settings(context, id).getString("wallet-name", "");
        return name.isEmpty() ? (LEGACY.equals(id) ? "Wallet 1" : "Wallet " + id.substring(0, 6)) : name;
    }
}
