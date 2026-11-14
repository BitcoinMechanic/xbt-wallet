package org.xbt.wallet;

import java.io.ByteArrayOutputStream;
import java.util.*;
import lnrpc.LightningOuterClass.*;

/** Conservative RBF: same inputs and recipients, fee taken only from unspent wallet change. */
final class ReplacementTransaction {
    static final class Input {
        byte[] hash, script; long index, sequence;
        String txid() { byte[] b=hash.clone(); for(int i=0;i<16;i++){byte t=b[i];b[i]=b[31-i];b[31-i]=t;} return hex(b); }
    }
    static final class Output { long value; byte[] script; Output(long v,byte[] s){value=v;script=s;} }
    static final class Tx {
        long version, locktime; int vbytes;
        final List<Input> inputs=new ArrayList<>(); final List<Output> outputs=new ArrayList<>();
        byte[] unsigned() {
            ByteArrayOutputStream b=new ByteArrayOutputStream(); le(b,version,4); compact(b,inputs.size());
            for(Input in:inputs){write(b,in.hash);le(b,in.index,4);compact(b,0);le(b,in.sequence,4);}
            compact(b,outputs.size());for(Output out:outputs)write(b,output(out));le(b,locktime,4);return b.toByteArray();
        }
    }
    static final class Reader {
        final byte[] b; int p;
        Reader(byte[] b){this.b=b;}
        byte[] take(int n){if(n<0 || n>b.length-p)throw new IllegalArgumentException("Truncated transaction");byte[] v=Arrays.copyOfRange(b,p,p+n);p+=n;return v;}
        long le(int n){byte[] v=take(n);long x=0;for(int i=0;i<n;i++)x|=(v[i]&255L)<<(8*i);if(x<0)throw new IllegalArgumentException("Invalid integer");return x;}
        int count(){long v=le(1);if(v==253)v=le(2);else if(v==254)v=le(4);else if(v==255)v=le(8);if(v>1000000)throw new IllegalArgumentException("Oversized transaction");return (int)v;}
        byte[] variable(){return take(count());}
    }
    static Tx parse(byte[] raw) {
        if(raw.length>1000000)throw new IllegalArgumentException("Oversized transaction");
        Reader r=new Reader(raw); Tx tx=new Tx();tx.version=r.le(4);
        int n=r.count();boolean witness=n==0;
        if(witness){if(r.le(1)!=1)throw new IllegalArgumentException("Unsupported witness flags");n=r.count();}
        if(n<1 || n>1000)throw new IllegalArgumentException("Unsupported input count");
        int base=4+compactSize(n);
        for(int i=0;i<n;i++){Input in=new Input();in.hash=r.take(32);in.index=r.le(4);in.script=r.variable();in.sequence=r.le(4);tx.inputs.add(in);base+=40+compactSize(in.script.length)+in.script.length;}
        int outs=r.count();if(outs<1 || outs>1000)throw new IllegalArgumentException("Unsupported output count");base+=compactSize(outs);
        for(int i=0;i<outs;i++){long amount=r.le(8);byte[] script=r.variable();tx.outputs.add(new Output(amount,script));base+=8+compactSize(script.length)+script.length;}
        if(witness)for(int i=0;i<n;i++){int items=r.count();if(items>1000)throw new IllegalArgumentException("Invalid witness");for(int j=0;j<items;j++)r.variable();}
        tx.locktime=r.le(4);base+=4;
        if(r.p!=raw.length)throw new IllegalArgumentException("Trailing transaction data");
        tx.vbytes=(base*3+raw.length+3)/4;return tx;
    }
    static boolean nativeKeyScript(byte[] s){return (s.length==22 && s[0]==0 && s[1]==20)||(s.length==34 && s[0]==0x51 && s[1]==32);}
    final String txid; final Tx original; final List<Transaction> parents=new ArrayList<>();
    final int change; final long oldFee, inputTotal; final boolean signalsRbf;
    ReplacementTransaction(Transaction tx, Map<String,Transaction> history, Set<String> unspent, Set<String> funding) throws Exception {
        txid=tx.getTxHash();
        if(tx.getNumConfirmations()!=0 || tx.getAmount()>=0 || funding.contains(txid))throw new Exception("Not an unconfirmed outgoing wallet payment");
        original=parse(bytes(tx.getRawTxHex()));
        boolean replaceable=false;long total=0;
        for(Input in:original.inputs){
            replaceable|=in.sequence<0xfffffffeL;
            if(in.script.length!=0)throw new Exception("Only native SegWit inputs are supported for replacement");
            Transaction parent=history.get(in.txid());
            if(parent==null || parent.getNumConfirmations()<=0)throw new Exception("Replacement needs confirmed, known wallet inputs");
            Tx previous=parse(bytes(parent.getRawTxHex()));
            if(in.index>=previous.outputs.size())throw new Exception("Invalid previous output");
            Output output=previous.outputs.get((int)in.index);
            boolean owned=false;for(OutputDetail detail:parent.getOutputDetailsList())if(detail.getOutputIndex()==in.index && detail.getIsOurAddress())owned=true;
            if(!owned || !nativeKeyScript(output.script))throw new Exception("Input is not a supported wallet-owned output");
            total=Math.addExact(total,output.value);parents.add(parent);
        }
        signalsRbf=replaceable;
        int selected=-1;
        for(OutputDetail detail:tx.getOutputDetailsList()) {
            if (detail.getOutputIndex() < 0 || detail.getOutputIndex() >= original.outputs.size()) continue;
            int i=(int)detail.getOutputIndex();
            if(i>=0 && i<original.outputs.size() && detail.getIsOurAddress() && unspent.contains(txid+":"+i)
                    && nativeKeyScript(original.outputs.get(i).script)
                    && (selected<0 || original.outputs.get(i).value>original.outputs.get(selected).value))selected=i;
        }
        if(selected<0)throw new Exception("No unspent wallet change is available to pay a replacement fee");
        long outputTotal=0;for(Output out:original.outputs)outputTotal=Math.addExact(outputTotal,out.value);
        oldFee=Math.subtractExact(total,outputTotal);if(oldFee<0)throw new Exception("Invalid transaction fee");
        inputTotal=total;change=selected;
    }
    static final class Plan {
        final ReplacementTransaction source;final byte[] psbt,unsigned;final long fee,rate;
        Plan(ReplacementTransaction s,byte[] p,byte[] u,long f,long r){source=s;psbt=p;unsigned=u;fee=f;rate=r;}
        void verifySigned(byte[] raw) throws Exception {
            Tx signed=parse(raw);
            if(!Arrays.equals(unsigned,signed.unsigned()))throw new Exception("Node changed the replacement transaction. Nothing broadcast.");
            if(fee<Math.multiplyExact(rate,signed.vbytes))throw new Exception("Signed transaction exceeds the reviewed size. Nothing broadcast.");
        }
    }
    Plan plan(long rate) throws Exception {
        if(rate<=0 || rate>1000000)throw new Exception("Enter a fee rate between 1 and 1,000,000 sats/vByte");
        // Allow for maximum DER signature size while preserving all inputs and outputs.
        int witnessSize=2;
        for(int i=0;i<original.inputs.size();i++) {
            Tx parent=parse(bytes(parents.get(i).getRawTxHex()));
            byte[] script=parent.outputs.get((int)original.inputs.get(i).index).script;
            witnessSize += script.length==34 ? 67 : 109;
        }
        int maxSize=original.unsigned().length+(witnessSize+3)/4;
        long fee=Math.multiplyExact(rate,maxSize);
        if(fee<=oldFee || Math.multiplyExact(fee,original.vbytes)<=Math.multiplyExact(oldFee,maxSize))throw new Exception("Enter a higher fee rate than the original transaction");
        if(fee-oldFee<maxSize)throw new Exception("Increase the fee further to cover the replacement relay fee");
        long value=original.outputs.get(change).value-(fee-oldFee);
        if(value<1000)throw new Exception("Not enough wallet change for this replacement fee; at least 1,000 sats must remain");
        Tx tx=parse(original.unsigned());tx.outputs.get(change).value=value;byte[] unsigned=tx.unsigned();
        ByteArrayOutputStream psbt=new ByteArrayOutputStream();write(psbt,new byte[]{0x70,0x73,0x62,0x74,(byte)0xff});pair(psbt,0,unsigned);psbt.write(0);
        for(int i=0;i<tx.inputs.size();i++){
            byte[] raw=bytes(parents.get(i).getRawTxHex());Tx parent=parse(raw);
            pair(psbt,0,raw);pair(psbt,1,output(parent.outputs.get((int)tx.inputs.get(i).index)));psbt.write(0);
        }
        for(Output ignored:tx.outputs)psbt.write(0);
        return new Plan(this,psbt.toByteArray(),unsigned,fee,rate);
    }
    static String txid(byte[] unsigned) throws Exception {
        java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash=sha.digest(sha.digest(unsigned));
        for(int i=0;i<16;i++){byte t=hash[i];hash[i]=hash[31-i];hash[31-i]=t;}
        return hex(hash);
    }
    static void verifyPsbt(byte[] psbt,byte[] expected) throws Exception {
        Reader r=new Reader(psbt);if(!Arrays.equals(r.take(5),new byte[]{0x70,0x73,0x62,0x74,(byte)0xff}))throw new Exception("Invalid signed PSBT");
        boolean found=false;while(true){byte[] key=r.variable();if(key.length==0)break;byte[] val=r.variable();if(key.length==1 && key[0]==0){if(found || !Arrays.equals(val,expected))throw new Exception("Node changed the replacement transaction");found=true;}}
        if(!found)throw new Exception("Signed PSBT has no unsigned transaction");
    }
    static byte[] bytes(String hex){if(hex==null || hex.length()==0 || hex.length()>2000000 || !hex.matches("(?:[0-9a-fA-F]{2})+"))throw new IllegalArgumentException("Missing or invalid transaction data");byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(2*i,2*i+2),16);return b;}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    static byte[] output(Output o){ByteArrayOutputStream b=new ByteArrayOutputStream();le(b,o.value,8);compact(b,o.script.length);write(b,o.script);return b.toByteArray();}
    static void pair(ByteArrayOutputStream b,int key,byte[] value){compact(b,1);b.write(key);compact(b,value.length);write(b,value);}
    static int compactSize(long n){return n<253?1:n<=65535?3:n<=0xffffffffL?5:9;}
    static void compact(ByteArrayOutputStream b,long n){if(n<253)b.write((int)n);else if(n<=65535){b.write(253);le(b,n,2);}else{b.write(254);le(b,n,4);}}
    static void write(ByteArrayOutputStream b,byte[] data){b.write(data,0,data.length);}
    static void le(ByteArrayOutputStream b,long n,int count){for(int i=0;i<count;i++)b.write((int)(n>>>(8*i))&255);}
}
