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
        if (!registry(context).contains("ids")) all.add(LEGACY);
        return new ArrayList<>(all);
    }
    static String create(Context context) throws Exception { return create(context, false); }
    static String createDraft(Context context) throws Exception { return create(context, true); }
    static boolean isDraft(Context context, String id) { return settings(context, id).contains("setup-return-wallet"); }
    static void completeSetup(Context context, String id) throws Exception {
        if (!settings(context, id).edit().remove("setup-return-wallet").commit()) throw new Exception("Could not finish wallet setup.");
    }
    static void cancelDraft(Context context, String id) throws Exception {
        if (!isDraft(context, id)) throw new Exception("This wallet has already been created. Use Wallets to manage it.");
        Vault vault = new Vault(context, id);
        if (vault.get("seed") != null || vault.get("password") != null || vault.get("remote-config") != null)
            throw new Exception("This wallet already contains keys or connection credentials. It cannot be discarded as unfinished setup.");
        beginDeletion(context, id, settings(context, id).getString("setup-return-wallet", ""));
    }
    private static String create(Context context, boolean draft) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        SharedPreferences previous = settings(context, active(context));
        SharedPreferences.Editor setup = settings(context, id).edit();
        if (draft) setup.putString("setup-return-wallet", active(context));
        if (!setup.putString("network", previous.getString("network", "mainnet"))
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
    /** Commit removal before erasing files. A crash resumes cleanup before any node starts. */
    static void beginDeletion(Context context, String id) throws Exception { beginDeletion(context, id, ""); }
    private static void beginDeletion(Context context, String id, String preferred) throws Exception {
        valid(id);
        if (!active(context).equals(id) || !ids(context).contains(id)) throw new Exception("Select this wallet before deleting it.");
        Set<String> remaining = new HashSet<>(ids(context)); remaining.remove(id);
        String next;
        if (remaining.isEmpty()) {
            next = create(context); remaining.add(next);
        } else next = remaining.contains(preferred) ? preferred : new TreeSet<>(remaining).first();
        Set<String> pending = new HashSet<>(registry(context).getStringSet("pending-deletions", Collections.emptySet()));
        pending.add(id);
        if (!registry(context).edit().putStringSet("ids", remaining).putString("active", next)
                .putStringSet("pending-deletions", pending).commit()) throw new Exception("Could not record wallet deletion. No wallet files were removed.");
    }
    static boolean hasPendingDeletion(Context context) {
        return !registry(context).getStringSet("pending-deletions", Collections.emptySet()).isEmpty();
    }
    static void finishDeletions(Context context) throws Exception {
        Set<String> pending = new HashSet<>(registry(context).getStringSet("pending-deletions", Collections.emptySet()));
        for (String id : new HashSet<>(pending)) {
            valid(id);
            if (active(context).equals(id) || ids(context).contains(id)) throw new Exception("Wallet deletion record conflicts with an active wallet. No files were removed.");
            if (LEGACY.equals(id)) {
                erase(directory(context, id, "mainnet")); erase(directory(context, id, "regtest"));
            } else erase(new File(context.getFilesDir(), "profiles/" + id));
            // Clear cached preferences as well as disk copies before removing the key.
            if (!context.getSharedPreferences(vaultName(id), 0).edit().clear().commit()) throw new Exception("Could not remove wallet secrets. Cleanup will resume on restart.");
            context.deleteSharedPreferences(vaultName(id));
            java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (keys.containsAlias(keyAlias(id))) keys.deleteEntry(keyAlias(id));
            if (!settings(context, id).edit().clear().commit()) throw new Exception("Could not remove wallet settings. Cleanup will resume on restart.");
            context.deleteSharedPreferences(settingsName(id));
            pending.remove(id);
            if (!registry(context).edit().putStringSet("pending-deletions", new HashSet<>(pending)).commit()) throw new Exception("Could not finish wallet removal. Cleanup will resume on restart.");
        }
    }
    private static void erase(File path) throws java.io.IOException {
        // Do not follow a symlink out of this profile's directory.
        if (java.nio.file.Files.isSymbolicLink(path.toPath())) { java.nio.file.Files.delete(path.toPath()); return; }
        if (!path.exists()) return;
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children == null) throw new java.io.IOException("Could not read wallet files for removal.");
            for (File child : children) erase(child);
        }
        if (!path.delete()) throw new java.io.IOException("Could not remove wallet files. Cleanup will resume on restart.");
    }
    static String label(Context context, String id) {
        String name = settings(context, id).getString("wallet-name", "");
        return name.isEmpty() ? (LEGACY.equals(id) ? "Wallet 1" : "Wallet " + id.substring(0, 6)) : name;
    }
}
