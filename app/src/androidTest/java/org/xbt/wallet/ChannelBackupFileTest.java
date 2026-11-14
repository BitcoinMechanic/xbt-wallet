package org.xbt.wallet;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ChannelBackupFileTest {
    private static final String NODE="02"+"11".repeat(32);
    @Test public void clnBackupRoundTripsAndComparesWithoutRestoring() throws Exception {
        JSONArray entries=new JSONArray().put("01020304").put("05060708");
        ChannelBackupFile.Export backup=ChannelBackupFile.clnExport("regtest",NODE,entries);
        assertEquals(2,backup.channels);assertEquals("json",backup.extension);
        String result=ChannelBackupFile.testCln(backup.data,"regtest",NODE,new JSONArray().put("05060708").put("01020304"));
        assertTrue(result.contains("All entries match"));assertTrue(result.contains("No recovery was attempted"));
        assertFalse(result.contains("recovery verified"));
    }
    @Test public void clnRejectsCorruptionWrongNodeAndWrongNetwork() throws Exception {
        ChannelBackupFile.Export backup=ChannelBackupFile.clnExport("regtest",NODE,new JSONArray().put("01020304"));
        assertThrows(Exception.class,()->ChannelBackupFile.testCln(backup.data,"mainnet",NODE,new JSONArray()));
        assertThrows(Exception.class,()->ChannelBackupFile.testCln(backup.data,"regtest","03"+"22".repeat(32),new JSONArray()));
        JSONObject edited=new JSONObject(new String(backup.data,StandardCharsets.UTF_8));edited.getJSONArray("scb").put(0,"01020305");
        assertThrows(Exception.class,()->ChannelBackupFile.testCln(edited.toString().getBytes(StandardCharsets.UTF_8),"regtest",NODE,new JSONArray()));
        assertThrows(Exception.class,()->ChannelBackupFile.clnExport("regtest",NODE,new JSONArray().put("not hex")));
        assertThrows(Exception.class,()->ChannelBackupFile.clnExport("regtest",NODE,new JSONArray().put("00").put("00")));
    }
    @Test public void oldBackupDoesNotClaimCurrentCoverage() throws Exception {
        ChannelBackupFile.Export backup=ChannelBackupFile.clnExport("regtest",NODE,new JSONArray().put("01020304"));
        String result=ChannelBackupFile.testCln(backup.data,"regtest",NODE,new JSONArray().put("01020304").put("05060708"));
        assertTrue(result.contains("1 current entries are not matched"));
        assertTrue(ChannelBackupFile.coverage(List.of("old:0"),List.of("old:0","new:1")).contains("1 current channel(s) are missing"));
    }
    @Test public void boundedFileReadAndLndPayloadPreservation() throws Exception {
        byte[] bytes={1,2,3,4};assertArrayEquals(bytes,ChannelBackupFile.read(new ByteArrayInputStream(bytes)));
        assertArrayEquals(bytes,ChannelBackupFile.lndSnapshot(bytes).getMultiChanBackup().getMultiChanBackup().toByteArray());
        assertThrows(IOException.class,()->ChannelBackupFile.read(new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class,()->ChannelBackupFile.read(new ByteArrayInputStream(new byte[ChannelBackupFile.MAX_BYTES+1])));
        assertThrows(Exception.class,()->ChannelBackupFile.lndSnapshot(new byte[0]));
    }
}
