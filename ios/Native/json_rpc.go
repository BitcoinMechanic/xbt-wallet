//go:build mobile

package lndmobile

import (
	"fmt"
	"github.com/golang/protobuf/jsonpb"
	"github.com/golang/protobuf/proto"
	"github.com/lightningnetwork/lnd/lnrpc"
	"github.com/lightningnetwork/lnd/lnrpc/routerrpc"
)

// JSONCall preserves protobuf's decimal-string representation of 64-bit values.
// It never logs requests, which may contain seeds, passwords or payment secrets.
func JSONCall(method, input string, callback Callback) {
	var request proto.Message
	var response func() proto.Message
	var invoke func([]byte, Callback)
	switch method {
	case "getState":
		request = &lnrpc.GetStateRequest{}
		response = func() proto.Message { return &lnrpc.GetStateResponse{} }
		invoke = GetState
	case "genSeed":
		request = &lnrpc.GenSeedRequest{}
		response = func() proto.Message { return &lnrpc.GenSeedResponse{} }
		invoke = GenSeed
	case "initWallet":
		request = &lnrpc.InitWalletRequest{}
		response = func() proto.Message { return &lnrpc.InitWalletResponse{} }
		invoke = InitWallet
	case "unlockWallet":
		request = &lnrpc.UnlockWalletRequest{}
		response = func() proto.Message { return &lnrpc.UnlockWalletResponse{} }
		invoke = UnlockWallet
	case "getInfo":
		request = &lnrpc.GetInfoRequest{}
		response = func() proto.Message { return &lnrpc.GetInfoResponse{} }
		invoke = GetInfo
	case "walletBalance":
		request = &lnrpc.WalletBalanceRequest{}
		response = func() proto.Message { return &lnrpc.WalletBalanceResponse{} }
		invoke = WalletBalance
	case "listChannels":
		request = &lnrpc.ListChannelsRequest{}
		response = func() proto.Message { return &lnrpc.ListChannelsResponse{} }
		invoke = ListChannels
	case "pendingChannels":
		request = &lnrpc.PendingChannelsRequest{}
		response = func() proto.Message { return &lnrpc.PendingChannelsResponse{} }
		invoke = PendingChannels
	case "closedChannels":
		request = &lnrpc.ClosedChannelsRequest{}
		response = func() proto.Message { return &lnrpc.ClosedChannelsResponse{} }
		invoke = ClosedChannels
	case "listPeers":
		request = &lnrpc.ListPeersRequest{}
		response = func() proto.Message { return &lnrpc.ListPeersResponse{} }
		invoke = ListPeers
	case "connectPeer":
		request = &lnrpc.ConnectPeerRequest{}
		response = func() proto.Message { return &lnrpc.ConnectPeerResponse{} }
		invoke = ConnectPeer
	case "getNodeInfo":
		request = &lnrpc.NodeInfoRequest{}
		response = func() proto.Message { return &lnrpc.NodeInfo{} }
		invoke = GetNodeInfo
	case "listUnspent":
		request = &lnrpc.ListUnspentRequest{}
		response = func() proto.Message { return &lnrpc.ListUnspentResponse{} }
		invoke = ListUnspent
	case "getTransactions":
		request = &lnrpc.GetTransactionsRequest{}
		response = func() proto.Message { return &lnrpc.TransactionDetails{} }
		invoke = GetTransactions
	case "newAddress":
		request = &lnrpc.NewAddressRequest{}
		response = func() proto.Message { return &lnrpc.NewAddressResponse{} }
		invoke = NewAddress
	case "decodePayReq":
		request = &lnrpc.PayReqString{}
		response = func() proto.Message { return &lnrpc.PayReq{} }
		invoke = DecodePayReq
	case "addInvoice":
		request = &lnrpc.Invoice{}
		response = func() proto.Message { return &lnrpc.AddInvoiceResponse{} }
		invoke = AddInvoice
	case "lookupInvoice":
		request = &lnrpc.PaymentHash{}
		response = func() proto.Message { return &lnrpc.Invoice{} }
		invoke = LookupInvoice
	case "listInvoices":
		request = &lnrpc.ListInvoiceRequest{}
		response = func() proto.Message { return &lnrpc.ListInvoiceResponse{} }
		invoke = ListInvoices
	case "listPayments":
		request = &lnrpc.ListPaymentsRequest{}
		response = func() proto.Message { return &lnrpc.ListPaymentsResponse{} }
		invoke = ListPayments
	case "estimateFee":
		request = &lnrpc.EstimateFeeRequest{}
		response = func() proto.Message { return &lnrpc.EstimateFeeResponse{} }
		invoke = EstimateFee
	case "sendCoins":
		request = &lnrpc.SendCoinsRequest{}
		response = func() proto.Message { return &lnrpc.SendCoinsResponse{} }
		invoke = SendCoins
	case "openChannelSync":
		request = &lnrpc.OpenChannelRequest{}
		response = func() proto.Message { return &lnrpc.ChannelPoint{} }
		invoke = OpenChannelSync
	default:
		callback.OnError(fmt.Errorf("unsupported wallet RPC: %s", method))
		return
	}
	data, err := jsonRequest(input, request)
	if err != nil {
		callback.OnError(err)
		return
	}
	invoke(data, &jsonCallback{callback: callback, response: response})
}

// JSONStream forwards payment/close events without retrying either operation.
func JSONStream(method, input string, callback RecvStream) {
	switch method {
	case "sendPayment":
		data, err := jsonRequest(input, &routerrpc.SendPaymentRequest{})
		if err != nil {
			callback.OnError(err)
			return
		}
		RouterSendPaymentV2(data, &jsonCallback{callback: callback, response: func() proto.Message { return &lnrpc.Payment{} }})
	case "closeChannel":
		data, err := jsonRequest(input, &lnrpc.CloseChannelRequest{})
		if err != nil {
			callback.OnError(err)
			return
		}
		CloseChannel(data, &jsonCallback{callback: callback, response: func() proto.Message { return &lnrpc.CloseStatusUpdate{} }})
	default:
		callback.OnError(fmt.Errorf("unsupported wallet stream: %s", method))
	}
}
func jsonRequest(input string, request proto.Message) ([]byte, error) {
	if len(input) > 1024*1024 {
		return nil, fmt.Errorf("wallet RPC request too large")
	}
	if err := jsonpb.UnmarshalString(input, request); err != nil {
		return nil, fmt.Errorf("invalid wallet RPC parameters: %w", err)
	}
	return proto.Marshal(request)
}

type jsonReceiver interface {
	OnResponse([]byte)
	OnError(error)
}
type jsonCallback struct {
	callback jsonReceiver
	response func() proto.Message
}

func (c *jsonCallback) OnError(err error) { c.callback.OnError(err) }
func (c *jsonCallback) OnResponse(data []byte) {
	message := c.response()
	if err := proto.Unmarshal(data, message); err != nil {
		c.callback.OnError(err)
		return
	}
	text, err := (&jsonpb.Marshaler{OrigName: true, EmitDefaults: true}).MarshalToString(message)
	if err != nil {
		c.callback.OnError(err)
		return
	}
	c.callback.OnResponse([]byte(text))
}
