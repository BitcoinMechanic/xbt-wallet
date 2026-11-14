package org.xbt.wallet;

import lnrpc.LightningOuterClass.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.*;

public class ReplacementTransactionTest {
    static byte[] script(int tag){byte[] b=new byte[22];b[1]=20;Arrays.fill(b,2,22,(byte)tag);return b;}
    static ReplacementTransaction.Tx tx(String parent,long sequence,long... amounts){
        ReplacementTransaction.Tx tx=new ReplacementTransaction.Tx();tx.version=2;
        ReplacementTransaction.Input input=new ReplacementTransaction.Input();input.hash=ReplacementTransaction.bytes(parent);
        for(int i=0;i<16;i++){byte t=input.hash[i];input.hash[i]=input.hash[31-i];input.hash[31-i]=t;}
        input.index=0;input.sequence=sequence;input.script=new byte[0];tx.inputs.add(input);
        for(int i=0;i<amounts.length;i++)tx.outputs.add(new ReplacementTransaction.Output(amounts[i],script(i+1)));
        return tx;
    }
    static final class Fixture {
        final Transaction parent, payment; final Map<String,Transaction> history; final Set<String> unspent;
        Fixture(long sequence) throws Exception {
            byte[] parentRaw=tx("00".repeat(32),0xffffffffL,100000).unsigned();String parentId=ReplacementTransaction.txid(parentRaw);
            parent=Transaction.newBuilder().setTxHash(parentId).setNumConfirmations(10).setRawTxHex(ReplacementTransaction.hex(parentRaw))
                .addOutputDetails(OutputDetail.newBuilder().setOutputIndex(0).setIsOurAddress(true)).build();
            byte[] raw=tx(parentId,sequence,40000,59000).unsigned();String id=ReplacementTransaction.txid(raw);
            payment=Transaction.newBuilder().setTxHash(id).setAmount(-41000).setRawTxHex(ReplacementTransaction.hex(raw))
                .addOutputDetails(OutputDetail.newBuilder().setOutputIndex(0).setIsOurAddress(false))
                .addOutputDetails(OutputDetail.newBuilder().setOutputIndex(1).setIsOurAddress(true)).build();
            history=new HashMap<>();history.put(parentId,parent);unspent=new HashSet<>(Set.of(id+":1"));
        }
        ReplacementTransaction candidate() throws Exception{return new ReplacementTransaction(payment,history,unspent,Set.of());}
    }
    @Test public void replacesOnlyChangeAndPreservesRecipientsAndInputs() throws Exception {
        Fixture f=new Fixture(0xfffffffdL);ReplacementTransaction source=f.candidate();ReplacementTransaction.Plan plan=source.plan(20);
        ReplacementTransaction.Tx updated=ReplacementTransaction.parse(plan.unsigned);
        assertEquals(40000,updated.outputs.get(0).value);
        assertArrayEquals(source.original.outputs.get(0).script,updated.outputs.get(0).script);
        assertEquals(100000-40000-plan.fee,updated.outputs.get(1).value);
        assertArrayEquals(source.original.inputs.get(0).hash,updated.inputs.get(0).hash);
        assertEquals(0xfffffffdL,updated.inputs.get(0).sequence);
        ReplacementTransaction.verifyPsbt(plan.psbt,plan.unsigned);
        plan.verifySigned(plan.unsigned);
        updated.outputs.get(0).value--;assertThrows(Exception.class,()->plan.verifySigned(updated.unsigned()));
    }
    @Test public void rejectsUnsafeOrUnavailableReplacements() throws Exception {
        Fixture f=new Fixture(0xfffffffdL);
        assertThrows(Exception.class,()->f.candidate().plan(1));
        assertThrows(Exception.class,()->f.candidate().plan(1000000));
        assertFalse(new Fixture(0xffffffffL).candidate().signalsRbf);
        assertThrows(Exception.class,()->new ReplacementTransaction(f.payment.toBuilder().setNumConfirmations(1).build(),f.history,f.unspent,Set.of()));
        assertThrows(Exception.class,()->new ReplacementTransaction(f.payment,f.history,f.unspent,Set.of(f.payment.getTxHash())));
        f.unspent.clear();assertThrows(Exception.class,f::candidate);
    }
    @Test public void refusesUnknownAndUnconfirmedInputs() throws Exception {
        Fixture f=new Fixture(0xfffffffdL);
        f.history.put(f.parent.getTxHash(),f.parent.toBuilder().setNumConfirmations(0).build());assertThrows(Exception.class,f::candidate);
        f.history.clear();assertThrows(Exception.class,f::candidate);
    }
    @Test public void remotePreparationDoesNotBroadcastAndChecksSignedPsbt() throws Exception {
        Fixture f=new Fixture(0xfffffffdL);ReplacementTransaction.Plan plan=f.candidate().plan(20);
        List<String> calls=new ArrayList<>();
        ClnRemote remote=new ClnRemote((method,args)->{
            calls.add(method);
            if(method.equals("reserveinputs")){assertFalse(args.getBoolean("exclusive"));return new org.json.JSONObject();}
            assertEquals("signpsbt",method);assertEquals(1,args.getJSONArray("signonly").length());
            return new org.json.JSONObject().put("signed_psbt",args.getString("psbt"));
        },"regtest","");
        remote.signReplacement(plan);assertEquals(Arrays.asList("reserveinputs","signpsbt"),calls);
    }
    @Test public void exportOptionalRegtestFixture() throws Exception {
        var context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",context.getPackageName());
        File file=new File(context.getFilesDir(),"rbf-integration.json");
        if(!file.exists())return;
        org.json.JSONObject fixture=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        org.json.JSONArray parents=fixture.getJSONArray("parents");Map<String,Transaction> history=new HashMap<>();
        for(int i=0;i<parents.length();i++){
            org.json.JSONObject p=parents.getJSONObject(i);Transaction.Builder parent=Transaction.newBuilder().setTxHash(p.getString("txid")).setRawTxHex(p.getString("raw")).setNumConfirmations(10);
            ReplacementTransaction.Tx parsed=ReplacementTransaction.parse(ReplacementTransaction.bytes(p.getString("raw")));
            for(int j=0;j<parsed.outputs.size();j++)parent.addOutputDetails(OutputDetail.newBuilder().setOutputIndex(j).setIsOurAddress(true));
            history.put(p.getString("txid"),parent.build());
        }
        String txid=fixture.getString("txid");int change=fixture.getInt("change");
        Transaction payment=Transaction.newBuilder().setTxHash(txid).setAmount(-1).setRawTxHex(fixture.getString("raw"))
            .addOutputDetails(OutputDetail.newBuilder().setOutputIndex(change).setIsOurAddress(true)).build();
        ReplacementTransaction.Plan plan=new ReplacementTransaction(payment,history,Set.of(txid+":"+change),Set.of()).plan(fixture.getLong("rate"));
        org.json.JSONObject output=new org.json.JSONObject().put("psbt",android.util.Base64.encodeToString(plan.psbt,android.util.Base64.NO_WRAP))
            .put("txid",ReplacementTransaction.txid(plan.unsigned)).put("fee",plan.fee).put("unsigned",ReplacementTransaction.hex(plan.unsigned));
        java.nio.file.Files.write(new File(context.getFilesDir(),"rbf-plan.json").toPath(),output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(fixture.has("signed"))plan.verifySigned(ReplacementTransaction.bytes(fixture.getString("signed")));
    }
}
