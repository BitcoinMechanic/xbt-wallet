package org.xbt.wallet;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import lnrpc.LightningOuterClass;

/** Export formats and non-destructive checks only. Never invokes a restore RPC. */
final class ChannelBackupFile {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final class Export {
        final byte[] data; final int channels; final String extension;
        Export(byte[] data,int channels,String extension){this.data=data;this.channels=channels;this.extension=extension;}
    }
    static byte[] read(InputStream input) throws IOException {
        if(input==null)throw new IOException("Could not open the backup file.");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("Backup file exceeds 8 MB.");out.write(buffer,0,n);}
        if(out.size()==0)throw new IOException("Backup file is empty.");return out.toByteArray();
    }
    static LightningOuterClass.ChanBackupSnapshot lndSnapshot(byte[] data) throws Exception {
        if(data.length==0 || data.length>MAX_BYTES)throw new Exception("Invalid channel backup size.");
        return LightningOuterClass.ChanBackupSnapshot.newBuilder().setMultiChanBackup(
            LightningOuterClass.MultiChanBackup.newBuilder().setMultiChanBackup(com.google.protobuf.ByteString.copyFrom(data))).build();
    }
    static String coverage(Collection<String> saved, Collection<String> current) {
        Set<String> missing=new HashSet<>(current);missing.removeAll(saved);
        return saved.size() + " channel(s) in this backup. " + (missing.isEmpty()
            ? "All channels in the current node backup are covered."
            : missing.size()+" current channel(s) are missing. Create a fresh backup.");
    }
    private static Set<String> scbs(JSONArray values) throws Exception {
        if(values.length()>10000)throw new Exception("Too many channel backup entries.");
        Set<String> result=new HashSet<>();
        for(int i=0;i<values.length();i++){
            Object item=values.get(i);
            if(!(item instanceof String))throw new Exception("Invalid channel backup entry.");
            String hex=(String)item;
            if(hex.length()<2 || hex.length()>MAX_BYTES || !hex.matches("(?:[0-9a-f]{2})+"))throw new Exception("Invalid channel backup encoding.");
            if(!result.add(hex))throw new Exception("Duplicate channel backup entry.");
        }
        return result;
    }
    private static String checksum(JSONObject file) throws Exception {
        // Sort to make checks independent of set/JSON ordering.
        List<String> entries=new ArrayList<>(scbs(file.getJSONArray("scb")));Collections.sort(entries);
        String header="xbt-cln-static-backup\n1\n"+file.getString("network")+"\n"+file.getString("node_id")+"\n"+file.getLong("created_at")+"\n";
        MessageDigest sha=MessageDigest.getInstance("SHA-256");sha.update(header.getBytes(StandardCharsets.UTF_8));
        for(String entry:entries)sha.update((entry+"\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:sha.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    static Export clnExport(String network,String nodeId,JSONArray scb) throws Exception {
        scbs(scb);
        JSONObject file=new JSONObject().put("format","xbt-cln-static-backup").put("version",1)
            .put("network",network).put("node_id",nodeId).put("created_at",System.currentTimeMillis()).put("scb",scb);
        file.put("sha256",checksum(file));byte[] bytes=file.toString(2).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new Exception("Channel backup exceeds 8 MB.");
        return new Export(bytes,scb.length(),"json");
    }
    static String testCln(byte[] bytes,String network,String nodeId,JSONArray current) throws Exception {
        if(bytes.length==0 || bytes.length>MAX_BYTES)throw new Exception("Invalid channel backup size.");
        JSONObject file=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!"xbt-cln-static-backup".equals(file.getString("format")) || file.getInt("version")!=1)
            throw new Exception("Select a CLN channel backup exported by this app.");
        if(!network.equals(file.getString("network")) || !nodeId.equals(file.getString("node_id")))
            throw new Exception("This backup is for a different node or network.");
        if(!checksum(file).equals(file.getString("sha256")))throw new Exception("Backup checksum does not match. The file is damaged or changed.");
        Set<String> saved=scbs(file.getJSONArray("scb")),live=scbs(current);
        Set<String> missing=new HashSet<>(live);missing.removeAll(saved);
        Set<String> unmatched=new HashSet<>(saved);unmatched.removeAll(live);
        return "File checksum and node identity match. " + saved.size()+" backup entry/entries.\n"
            + (missing.isEmpty() && unmatched.isEmpty() ? "All entries match the node’s current static backup."
                : missing.size()+" current entries are not matched; "+unmatched.size()+" saved entries differ or are no longer current. Create a fresh backup.")
            + "\nCLN has no non-destructive recovery-verification RPC. This comparison does not prove that an older backup can recover funds. No recovery was attempted and no channels were closed.";
    }
}
