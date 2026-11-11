package org.xbt.wallet;

import org.json.*;
import com.google.protobuf.*;
import lnrpc.LightningOuterClass.*;
import offersrpc.OffersOuterClass;
import java.math.BigInteger;
import java.util.*;

/** Translation boundary: UI protobufs never reach the embedded runtime for remote profiles. */
final class ClnRemote {
    interface Rpc { JSONObject call(String method, JSONObject params) throws Exception; }
    final Rpc http;
    private final String network;
    private String identity;
    ClnRemote(RemoteConnection config, String network) throws Exception { http = new RemoteRpc(config)::call; this.network=network; identity=config.nodeId; }
    ClnRemote(Rpc rpc, String network, String identity) { this.http=rpc; this.network=network; this.identity=identity; }
    JSONObject call(String method, JSONObject args) throws Exception { return http.call(method,args); }
    JSONObject call(String method) throws Exception { return call(method,new JSONObject()); }
    String verify() throws Exception {
        JSONObject info=call("getinfo");
        String id=info.getString("id");
        if (!id.matches("0[23][0-9a-f]{64}") || (!identity.isEmpty() && !identity.equals(id))) throw new Exception("Node identity does not match the saved Node ID. Connection rejected.");
        String chain=info.getString("network");
        if (!(network.equals("mainnet") ? chain.equals("bitcoin") : chain.equals("regtest"))) throw new Exception("Node network does not match this wallet profile.");
        String bits=info.getJSONObject("our_features").optString("invoice","");
        if (!feature(bits,512) && !feature(bits,513)) throw new Exception("This node does not advertise XBT invoice support. A BTC node cannot control this XBT wallet.");
        identity=id; return id;
    }
    static boolean feature(String hex,int bit) { return !hex.isEmpty() && new BigInteger(hex,16).testBit(bit); }
    static long msat(JSONObject object,String key) throws Exception {
        Object value=object.opt(key); if(value==null || value==JSONObject.NULL)return 0;
        String s=value.toString(); if(s.endsWith("msat"))s=s.substring(0,s.length()-4);
        return new java.math.BigDecimal(s).longValueExact();
    }
    static ByteString bytes(String hex) {
        if (!hex.matches("(?:[0-9a-fA-F]{2})*")) throw new IllegalArgumentException("Node returned invalid hex.");
        byte[] b=new byte[hex.length()/2]; for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16); return ByteString.copyFrom(b);
    }
    static String hex(ByteString bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes.toByteArray())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString(); }
    static JSONArray array(JSONObject value,String key) { JSONArray a=value.optJSONArray(key); return a==null?new JSONArray():a; }
    private JSONArray channels() throws Exception { return array(call("listpeerchannels"),"channels"); }
    private static OffersOuterClass.Offer remoteOffer(JSONObject item) throws Exception {
        String bolt12=item.getString("bolt12"); ByteString id=bytes(item.getString("offer_id"));
        if(!bolt12.startsWith("lno1") || id.size()!=32)throw new Exception("The node returned an invalid BOLT12 offer response.");
        return OffersOuterClass.Offer.newBuilder().setBolt12(bolt12).setOfferId(id)
            .setActive(item.getBoolean("active")).setLabel(item.optString("label")).build();
    }
    private int height() throws Exception { return call("getinfo").getInt("blockheight"); }
    static int confirmations(JSONObject output,int height) { int h=output.optInt("blockheight",0); return h>0 ? Math.max(0,height-h+1):0; }
    private static String point(JSONObject c) { return c.optString("funding_txid","")+":"+c.optInt("funding_outnum",0); }
    private static long scid(String id) { try { String[] p=id.split("x"); return (Long.parseLong(p[0])<<40)|(Long.parseLong(p[1])<<16)|Long.parseLong(p[2]); } catch(Exception e){return 0;} }
    private static JSONArray points(List<OutPoint> points) { JSONArray a=new JSONArray(); for(OutPoint p:points) a.put(p.getTxidStr()+":"+p.getOutputIndex()); return a; }
    byte[] rpc(String method, MessageLite request) throws Exception {
        switch(method) {
        case "offersCreateOffer": {
            OffersOuterClass.CreateOfferRequest r=(OffersOuterClass.CreateOfferRequest)request;
            JSONObject args=new JSONObject().put("amount",r.getAmountMsat()==0 ? "any" : r.getAmountMsat()+"msat");
            if(!r.getDescription().isEmpty())args.put("description",r.getDescription());
            if(!r.getIssuer().isEmpty())args.put("issuer",r.getIssuer());
            if(!r.getLabel().isEmpty())args.put("label",r.getLabel());
            if(r.getAbsoluteExpiry()>0)args.put("absolute_expiry",r.getAbsoluteExpiry());
            JSONObject result=call("offer",args);
            OffersOuterClass.Offer offer=remoteOffer(result).toBuilder().setAmountMsat(r.getAmountMsat())
                .setDescription(r.getDescription()).setIssuer(r.getIssuer()).setLabel(r.getLabel())
                .setAbsoluteExpiry(r.getAbsoluteExpiry()).build();
            return OffersOuterClass.CreateOfferResponse.newBuilder().setOffer(offer).setCreated(result.optBoolean("created")).build().toByteArray();
        }
        case "offersListOffers": {
            OffersOuterClass.ListOffersRequest r=(OffersOuterClass.ListOffersRequest)request;
            JSONObject args=new JSONObject(); if(r.getActiveOnly())args.put("active_only",true);
            JSONArray offers=array(call("listoffers",args),"offers");
            OffersOuterClass.ListOffersResponse.Builder result=OffersOuterClass.ListOffersResponse.newBuilder();
            for(int n=0;n<offers.length();n++) {
                JSONObject item=offers.getJSONObject(n);
                // CLN listoffers omits amount/description; decode its actual offer.
                JSONObject decoded=call("decode",new JSONObject().put("string",item.getString("bolt12")));
                if(!decoded.optBoolean("valid") || !decoded.optString("type").equals("bolt12 offer")
                    || !decoded.optString("offer_id").equals(item.getString("offer_id")))
                    throw new Exception("The node could not validate its saved BOLT12 offer.");
                if(decoded.has("offer_currency"))throw new Exception("Fiat-denominated BOLT12 offers are not supported by this wallet.");
                result.addOffers(remoteOffer(item).toBuilder().setAmountMsat(msat(decoded,"offer_amount_msat"))
                    .setDescription(decoded.optString("offer_description")).setIssuer(decoded.optString("offer_issuer"))
                    .setAbsoluteExpiry(decoded.optLong("offer_absolute_expiry")));
            }
            return result.build().toByteArray();
        }
        case "offersDisableOffer": {
            OffersOuterClass.DisableOfferRequest r=(OffersOuterClass.DisableOfferRequest)request;
            call("disableoffer",new JSONObject().put("offer_id",hex(r.getOfferId())));
            return OffersOuterClass.DisableOfferResponse.getDefaultInstance().toByteArray();
        }
        case "getInfo": {
            JSONObject i=call("getinfo"); if(!i.getString("id").equals(identity)) throw new Exception("Remote node identity changed.");
            return GetInfoResponse.newBuilder().setIdentityPubkey(identity).setAlias(i.optString("alias"))
                .setBlockHeight(i.getInt("blockheight")).setSyncedToChain(!i.has("warning_bitcoind_sync")&&!i.has("warning_lightningd_sync"))
                .setSyncedToGraph(true).setNumPeers(i.optInt("num_peers")).setNumActiveChannels(i.optInt("num_active_channels")).build().toByteArray();
        }
        case "walletBalance": {
            JSONArray outputs=array(call("listfunds"),"outputs"); long confirmed=0,unconfirmed=0,locked=0;
            for(int i=0;i<outputs.length();i++){JSONObject o=outputs.getJSONObject(i); long amount=msat(o,"amount_msat")/1000;
                if(o.optString("status").equals("confirmed"))confirmed+=amount; else if(o.optString("status").equals("unconfirmed"))unconfirmed+=amount;
                if(o.optBoolean("reserved"))locked+=amount;
            }
            return WalletBalanceResponse.newBuilder().setTotalBalance(confirmed+unconfirmed).setConfirmedBalance(confirmed).setUnconfirmedBalance(unconfirmed).setLockedBalance(locked).build().toByteArray();
        }
        case "listUnspent": {
            ListUnspentRequest r=(ListUnspentRequest)request; JSONArray outputs=array(call("listfunds"),"outputs"); int height=height();
            ListUnspentResponse.Builder response=ListUnspentResponse.newBuilder();
            for(int i=0;i<outputs.length();i++){JSONObject o=outputs.getJSONObject(i);int conf=confirmations(o,height);
                if(o.optBoolean("reserved") || conf<r.getMinConfs() || (r.getMaxConfs()>0&&conf>r.getMaxConfs()) || !Arrays.asList("confirmed","unconfirmed").contains(o.optString("status")))continue;
                String script=o.optString("scriptpubkey");
                response.addUtxos(Utxo.newBuilder().setAmountSat(msat(o,"amount_msat")/1000).setAddress(o.optString("address")).setPkScript(script).setConfirmations(conf)
                    .setAddressType(script.startsWith("5120")?AddressType.TAPROOT_PUBKEY:AddressType.WITNESS_PUBKEY_HASH)
                    .setOutpoint(OutPoint.newBuilder().setTxidStr(o.getString("txid")).setOutputIndex(o.getInt("output"))));
            }return response.build().toByteArray();
        }
        case "listChannels": {
            ListChannelsResponse.Builder result=ListChannelsResponse.newBuilder(); JSONArray channels=channels();
            for(int n=0;n<channels.length();n++){JSONObject c=channels.getJSONObject(n); if(!c.optString("state").equals("CHANNELD_NORMAL"))continue;
                long local=msat(c,"to_us_msat")/1000,total=msat(c,"total_msat")/1000;
                Channel.Builder b=Channel.newBuilder().setRemotePubkey(c.getString("peer_id")).setChannelPoint(point(c)).setChanId(scid(c.optString("short_channel_id")))
                    .setActive(c.optBoolean("peer_connected")&&c.optBoolean("reestablished",true)).setPrivate(c.optBoolean("private")).setInitiator(c.optString("opener").equals("local"))
                    .setCapacity(total).setLocalBalance(local).setRemoteBalance(total-local).setLocalChanReserveSat(msat(c,"our_reserve_msat")/1000).setRemoteChanReserveSat(msat(c,"their_reserve_msat")/1000);
                JSONArray htlcs=array(c,"htlcs"); for(int h=0;h<htlcs.length();h++){JSONObject x=htlcs.getJSONObject(h); b.addPendingHtlcs(HTLC.newBuilder().setAmount(msat(x,"amount_msat")/1000).setIncoming(x.optString("direction").equals("in")));}
                result.addChannels(b);
            }return result.build().toByteArray();
        }
        case "pendingChannels": {
            PendingChannelsResponse.Builder result=PendingChannelsResponse.newBuilder(); JSONArray channels=channels();
            for(int n=0;n<channels.length();n++){JSONObject c=channels.getJSONObject(n);String state=c.optString("state");if(state.equals("CHANNELD_NORMAL"))continue;
                long local=msat(c,"to_us_msat")/1000,total=msat(c,"total_msat")/1000;
                PendingChannelsResponse.PendingChannel p=PendingChannelsResponse.PendingChannel.newBuilder().setRemoteNodePub(c.getString("peer_id")).setChannelPoint(point(c)).setCapacity(total).setLocalBalance(local).setRemoteBalance(total-local).build();
                if(state.contains("AWAITING_LOCKIN")||state.contains("OPEN")) result.addPendingOpenChannels(PendingChannelsResponse.PendingOpenChannel.newBuilder().setChannel(p));
                else result.addWaitingCloseChannels(PendingChannelsResponse.WaitingCloseChannel.newBuilder().setChannel(p));
            }return result.build().toByteArray();
        }
        case "closedChannels": {
            ClosedChannelsResponse.Builder result=ClosedChannelsResponse.newBuilder();JSONArray channels=array(call("listclosedchannels"),"closedchannels");
            for(int n=0;n<channels.length();n++){JSONObject c=channels.getJSONObject(n);result.addChannels(ChannelCloseSummary.newBuilder().setRemotePubkey(c.optString("peer_id")).setChannelPoint(point(c)).setCapacity(msat(c,"total_msat")/1000).setSettledBalance(msat(c,"final_to_us_msat")/1000));}
            return result.build().toByteArray();
        }
        case "listPeers": {
            ListPeersResponse.Builder result=ListPeersResponse.newBuilder();JSONArray peers=array(call("listpeers"),"peers");
            for(int n=0;n<peers.length();n++){JSONObject p=peers.getJSONObject(n);if(!p.optBoolean("connected"))continue;JSONArray addresses=array(p,"netaddr");result.addPeers(Peer.newBuilder().setPubKey(p.getString("id")).setAddress(addresses.optString(0,"")).setInbound(p.optBoolean("is_incoming")));}return result.build().toByteArray();
        }
        case "getNodeInfo": {
            String id=((NodeInfoRequest)request).getPubKey();JSONArray nodes=array(call("listnodes",new JSONObject().put("id",id)),"nodes");
            if(nodes.length()==0)throw new Exception("Node is not in the remote node's gossip graph. Add its peer URI manually.");
            JSONObject n=nodes.getJSONObject(0);LightningNode.Builder b=LightningNode.newBuilder().setPubKey(id).setAlias(n.optString("alias"));JSONArray a=array(n,"addresses");
            for(int k=0;k<a.length();k++){JSONObject x=a.getJSONObject(k);String host=x.optString("address");if(host.contains(":"))host="["+host+"]";b.addAddresses(NodeAddress.newBuilder().setAddr(host+":"+x.optInt("port",9735)).setNetwork("tcp"));}return NodeInfo.newBuilder().setNode(b).build().toByteArray();
        }
        case "connectPeer": {
            LightningAddress a=((ConnectPeerRequest)request).getAddr();call("connect",new JSONObject().put("id",a.getPubkey()+"@"+a.getHost()));return ConnectPeerResponse.getDefaultInstance().toByteArray();
        }
        case "newAddress": return NewAddressResponse.newBuilder().setAddress(call("newaddr",new JSONObject().put("addresstype","bech32")).getString("bech32")).build().toByteArray();
        case "decodePayReq": {
            JSONObject d=call("decode",new JSONObject().put("string",((PayReqString)request).getPayReq()));
            if(!d.optBoolean("valid",true)||!d.optString("type").equals("bolt11 invoice"))throw new Exception("The node did not decode a valid BOLT11 invoice.");
            PayReq.Builder p=PayReq.newBuilder().setDestination(d.getString("payee")).setPaymentHash(d.getString("payment_hash")).setDescription(d.optString("description"))
                .setTimestamp(d.getLong("created_at")).setExpiry(d.getLong("expiry")).setNumMsat(msat(d,"amount_msat")).setNumSatoshis(msat(d,"amount_msat")/1000);
            String features=d.optString("features","");for(int bit=0;bit<=515;bit++)if(feature(features,bit))p.putFeatures(bit,Feature.newBuilder().setIsKnown(true).build());return p.build().toByteArray();
        }
        case "addInvoice": {
            Invoice i=(Invoice)request;JSONObject args=new JSONObject().put("amount_msat",i.getValueMsat()>0?i.getValueMsat():i.getValue()>0?Math.multiplyExact(i.getValue(),1000):"any")
                .put("label","wallet-"+UUID.randomUUID()).put("description",i.getMemo()).put("expiry",i.getExpiry()>0?i.getExpiry():3600).put("exposeprivatechannels",true);
            JSONObject r=call("invoice",args);return AddInvoiceResponse.newBuilder().setPaymentRequest(r.getString("bolt11")).setRHash(bytes(r.getString("payment_hash"))).build().toByteArray();
        }
        case "lookupInvoice": {
            JSONArray invoices=array(call("listinvoices",new JSONObject().put("payment_hash",hex(((PaymentHash)request).getRHash()))),"invoices");
            if(invoices.length()!=1)throw new Exception("Invoice was not found on this node.");return invoice(invoices.getJSONObject(0)).toByteArray();
        }
        case "listInvoices": {
            JSONArray invoices=array(call("listinvoices"),"invoices");ListInvoiceResponse.Builder result=ListInvoiceResponse.newBuilder();
            for(int n=Math.max(0,invoices.length()-100);n<invoices.length();n++)result.addInvoices(invoice(invoices.getJSONObject(n)));return result.build().toByteArray();
        }
        case "listPayments": {
            JSONArray payments=array(call("listpays"),"pays");ListPaymentsResponse.Builder result=ListPaymentsResponse.newBuilder();
            for(int n=Math.max(0,payments.length()-100);n<payments.length();n++)result.addPayments(payment(payments.getJSONObject(n)));return result.build().toByteArray();
        }
        case "getTransactions": return transactions().toByteArray();
        case "estimateFee": {
            // CLN validates recipient encoding/network while preparing, then immediately releases the unsigned transaction.
            EstimateFeeRequest r=(EstimateFeeRequest)request;JSONArray outputs=new JSONArray();for(Map.Entry<String,Long> e:r.getAddrToAmountMap().entrySet())outputs.put(new JSONObject().put(e.getKey(),e.getValue()+"sat"));
            JSONObject args=new JSONObject().put("outputs",outputs).put("minconf",r.getMinConfs());if(r.getInputsCount()>0)args.put("utxos",points(r.getInputsList()));
            JSONObject prepared=call("txprepare",args);call("txdiscard",new JSONObject().put("txid",prepared.getString("txid")));
            return EstimateFeeResponse.getDefaultInstance().toByteArray();
        }
        case "sendCoins": {
            SendCoinsRequest r=(SendCoinsRequest)request;JSONObject args=new JSONObject().put("destination",r.getAddr()).put("satoshi",r.getSendAll()?"all":r.getAmount()+"sat")
                .put("feerate",Math.multiplyExact(r.getSatPerVbyte(),1000)+"perkb").put("minconf",r.getMinConfs());if(r.getOutpointsCount()>0)args.put("utxos",points(r.getOutpointsList()));
            return SendCoinsResponse.newBuilder().setTxid(call("withdraw",args).getString("txid")).build().toByteArray();
        }
        case "openChannelSync": {
            OpenChannelRequest r=(OpenChannelRequest)request;JSONObject args=new JSONObject().put("id",hex(r.getNodePubkey())).put("amount",r.getLocalFundingAmount()+"sat")
                .put("announce",!r.getPrivate()).put("feerate",Math.multiplyExact(r.getSatPerVbyte(),1000)+"perkb").put("minconf",r.getMinConfs());if(r.getOutpointsCount()>0)args.put("utxos",points(r.getOutpointsList()));
            JSONObject c=call("fundchannel",args);return ChannelPoint.newBuilder().setFundingTxidStr(c.getString("txid")).setOutputIndex(c.getInt("outnum")).build().toByteArray();
        }
        default: throw new Exception("Remote Core Lightning does not support this wallet operation yet: "+method);
        }
    }
    private Invoice invoice(JSONObject i) throws Exception {
        String state=i.optString("status");long amount=msat(i,"amount_msat"),paid=msat(i,"amount_received_msat");
        return Invoice.newBuilder().setRHash(bytes(i.getString("payment_hash"))).setMemo(i.optString("description")).setPaymentRequest(i.optString("bolt11"))
            .setValueMsat(amount).setValue(amount/1000).setAmtPaidMsat(paid).setAmtPaidSat(paid/1000).setSettleDate(i.optLong("paid_at"))
            .setState(state.equals("paid")?Invoice.InvoiceState.SETTLED:state.equals("expired")?Invoice.InvoiceState.CANCELED:Invoice.InvoiceState.OPEN).build();
    }
    private Payment payment(JSONObject p) throws Exception {
        long value=msat(p,"amount_msat"),sent=msat(p,"amount_sent_msat");String state=p.optString("status");
        return Payment.newBuilder().setPaymentHash(p.getString("payment_hash")).setValueMsat(value).setValueSat(value/1000).setFeeMsat(Math.max(0,sent-value)).setFeeSat(Math.max(0,sent-value)/1000)
            .setCreationTimeNs(Math.multiplyExact(p.optLong("created_at"),1000000000L)).setPaymentRequest(p.optString("bolt11"))
            .setStatus(state.equals("complete")?Payment.PaymentStatus.SUCCEEDED:state.equals("failed")?Payment.PaymentStatus.FAILED:Payment.PaymentStatus.IN_FLIGHT).build();
    }
    Payment pay(routerrpc.RouterOuterClass.SendPaymentRequest r) throws Exception {
        JSONObject args=new JSONObject().put("bolt11",r.getPaymentRequest()).put("maxfee",Math.multiplyExact(r.getFeeLimitSat(),1000)+"msat").put("retry_for",Math.max(1,r.getTimeoutSeconds()));
        if(r.getAmtMsat()>0)args.put("amount_msat",r.getAmtMsat());return payment(call("pay",args));
    }
    CloseStatusUpdate close(CloseChannelRequest r) throws Exception {
        if(r.getForce())throw new Exception("Force close is not supported by this remote wallet.");
        String point=r.getChannelPoint().getFundingTxidStr()+":"+r.getChannelPoint().getOutputIndex();
        JSONArray channels=channels();String id="";for(int n=0;n<channels.length();n++){JSONObject c=channels.getJSONObject(n);if(point(c).equals(point))id=c.getString("channel_id");}
        if(id.isEmpty())throw new Exception("Channel no longer exists on the remote node.");
        // Zero prevents CLN's close timeout from escalating into a unilateral close.
        JSONObject c=call("close",new JSONObject().put("id",id).put("unilateraltimeout",0).put("feerange",new JSONArray().put(r.getSatPerVbyte()*1000+"perkb").put(r.getSatPerVbyte()*1000+"perkb")));
        JSONArray txids = array(c, "txids");
        if (txids.length() == 0) throw new Exception("The node accepted the close but returned no closing transaction. Check channel status before retrying.");
        return CloseStatusUpdate.newBuilder().setClosePending(PendingUpdate.newBuilder().setTxid(bytes(txids.getString(0)))) .build();
    }
    private TransactionDetails transactions() throws Exception {
        JSONArray outputs=array(call("listfunds",new JSONObject().put("spent",true)),"outputs");Map<String,JSONObject> owned=new HashMap<>();
        for(int i=0;i<outputs.length();i++){JSONObject o=outputs.getJSONObject(i);owned.put(o.getString("txid")+":"+o.getInt("output"),o);}
        JSONArray txs=array(call("listtransactions"),"transactions");int height=height();TransactionDetails.Builder result=TransactionDetails.newBuilder();
        for(int i=0;i<txs.length();i++){JSONObject tx=txs.getJSONObject(i);String hash=tx.getString("hash");long net=0;Transaction.Builder t=Transaction.newBuilder().setTxHash(hash).setNumConfirmations(confirmations(tx,height)).setBlockHeight(tx.optInt("blockheight"));
            JSONArray ins=array(tx,"inputs");for(int k=0;k<ins.length();k++){JSONObject in=ins.getJSONObject(k),coin=owned.get(in.getString("txid")+":"+in.getInt("index"));if(coin!=null)net-=msat(coin,"amount_msat")/1000;}
            JSONArray outs=array(tx,"outputs");for(int k=0;k<outs.length();k++){JSONObject out=outs.getJSONObject(k),coin=owned.get(hash+":"+out.getInt("index"));if(coin!=null){long value=msat(coin,"amount_msat")/1000;net+=value;t.addOutputDetails(OutputDetail.newBuilder().setAddress(coin.optString("address")).setAmount(value).setIsOurAddress(true).setOutputIndex(out.getInt("index")));}}
            // CLN does not expose transaction timestamps here. Block height is retained, never invented as a date.
            t.setAmount(net);result.addTransactions(t);
        }return result.build();
    }
}
