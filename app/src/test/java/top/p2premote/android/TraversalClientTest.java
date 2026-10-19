package top.p2premote.android;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TraversalClientTest {
    private static TraversalClient client(TraversalClient.Check check) {
        WsConnection ws=new WsConnection(new SessionStore(new TestPreferences().prefs));
        return new TraversalClient(ws,new ApiClient.OpenResult("connection",1,"grant",3389,"rdp",1,"user",""),
                42,"attempt",new ConnectionPreferences(false,false),()->false,check,()->0L);
    }
    private static JSONObject ready(JSONObject negotiation) throws Exception {
        JSONObject data=new JSONObject().put("type","attempt_ready");
        if(negotiation!=null)data.put("traversal_negotiation",negotiation);
        return data;
    }
    private static void deliver(TraversalClient client,int round,String kind) throws Exception {
        client.receive(new JSONObject().put("type","traversal").put("frame",new JSONObject().put("round",round).put("kind",kind)));
    }

    @Test public void negotiationChecksVersionAndBothPreferencesWhileKeepingLegacyFallback() throws Exception {
        try(TraversalClient c=client(()->{})) {
            assertEquals(ErrorCodes.TraversalPlanNegotiationInvalidPayload,assertThrows(AppException.class,c::negotiated).errorCode);
            c.receive(ready(null));assertFalse(c.negotiated());
            c.receive(ready(null).put("traversal_negotiation",JSONObject.NULL));assertFalse(c.negotiated());
            JSONObject local=c.negotiation();assertEquals(TraversalPolicy.VERSION,local.getInt("version"));
            c.receive(ready(local));assertTrue(c.negotiated());
            for(int variant=0;variant<3;variant++) {
                JSONObject invalid=c.negotiation();
                if(variant==0)invalid.put("version",99);
                else invalid.getJSONObject("preferences").put(variant==1?"prefer_ipv6":"prefer_tcp",true);
                c.receive(ready(invalid));
                assertEquals(ErrorCodes.TraversalPlanNegotiationMismatch,assertThrows(AppException.class,c::negotiated).errorCode);
            }
            c.receive(ready(null));c.configureSignal(1,"token");
            assertNotNull(c.transportOffer());assertEquals("",c.selectedNetwork());assertEquals("",c.localNatType());assertEquals("",c.remoteNatType());
            assertEquals("",c.traversalPlan());assertEquals("",c.traversalSelection());
            c.attemptedNetworks().add("must-not-mutate-original");assertTrue(c.attemptedNetworks().isEmpty());
        }
    }

    @Test public void waitsConsumeEachValidStageAndIgnoreOldFrames() throws Exception {
        try(TraversalClient c=client(()->{})) {
            for(String kind:new String[]{"capabilities","plan","plan_ack","prepare","ready","result","commit","close","committed","closed"}) {
                deliver(c,1,"capabilities");deliver(c,2,kind);
                assertEquals(kind,c.waitFrame(2,kind,1).getString("kind"));
            }
            deliver(c,2,"ready");deliver(c,2,"result");
            assertEquals("result",c.waitFrame(2,"result",1).getString("kind"));
            deliver(c,3,"ready");assertEquals(ErrorCodes.TraversalRoundSyncInvalidSequence,assertThrows(AppException.class,()->c.waitFrame(2,"ready",1)).errorCode);
        }
        try(TraversalClient c=client(()->{})) {
            deliver(c,2,"unknown");assertEquals(ErrorCodes.TraversalRoundSyncInvalidSequence,assertThrows(AppException.class,()->c.waitFrame(2,"result",1)).errorCode);
        }
    }

    @Test public void phaseTimeoutsAndCancellationUseStableCodesWithoutSleeping() throws Exception {
        try(TraversalClient c=client(()->{})) {
            String[] kinds={"capabilities","plan","plan_ack","prepare","ready","result","commit","closed"};
            for(String kind:kinds) {
                String expected=kind.equals("capabilities")?ErrorCodes.TraversalCapabilityExchangeTimeout:
                        kind.equals("plan")||kind.equals("plan_ack")?ErrorCodes.TraversalPlanNegotiationTimeout:ErrorCodes.TraversalRoundSyncTimeout;
                assertEquals(expected,assertThrows(AppException.class,()->c.waitFrame(1,kind,0)).errorCode);
            }
        }
        try(TraversalClient c=client(()->{throw new AppException(ErrorCodes.ConnectionExecutionUserCancelled,"cancelled");})) {
            assertEquals(ErrorCodes.ConnectionExecutionUserCancelled,assertThrows(AppException.class,()->c.waitFrame(1,"ready",1)).errorCode);
            assertEquals(ErrorCodes.ConnectionExecutionUserCancelled,assertThrows(AppException.class,()->c.configureSignal(1,"token")).errorCode);
        }
    }

    @Test public void inboundFailureCancellationMalformedFramesAndOverflowStopTheWait() throws Exception {
        for(int variant=0;variant<6;variant++) {
            try(TraversalClient c=client(()->{})) {
                String expected;
                switch(variant) {
                    case 0:c.receive(new JSONObject().put("type","attempt_cancel"));expected=ErrorCodes.ConnectionExecutionPeerCancelled;break;
                    case 1:c.receive(new JSONObject().put("type","attempt_failed").put("error",new JSONObject().put("error_code",ErrorCodes.SignalSendRejected).put("message","rejected")));expected=ErrorCodes.SignalSendRejected;break;
                    case 2:c.receive(new JSONObject().put("type","attempt_failed"));expected=ErrorCodes.NativeDecodeInvalidPayload;break;
                    case 3:c.receive(new JSONObject().put("type","punch_signal"));expected=ErrorCodes.SignalTransportSelectInvalidSelection;break;
                    case 4:c.receive(new JSONObject().put("type","traversal"));expected=ErrorCodes.SignalReceiveInvalidFrame;break;
                    default:for(int n=0;n<65;n++)deliver(c,1,"ready");expected=ErrorCodes.SignalReceiveInvalidFrame;
                }
                assertEquals(expected,assertThrows(AppException.class,()->c.waitFrame(1,"ready",1)).errorCode);
            }
        }
        try(TraversalClient c=client(()->{})) {
            c.receive(new JSONObject().put("type","unrecognized"));deliver(c,1,"ready");
            assertEquals("ready",c.waitFrame(1,"ready",1).getString("kind"));
        }
    }

    @Test public void nativeResultModelsPreserveCodesAndLastKnownTraversalEvidence() throws Exception {
        try(TraversalClient c=client(()->{})) {
            JSONObject payload=new JSONObject().put("ok",true).put("handle_id","handle").put("local_forward_port",1234)
                    .put("peer_endpoint","127.0.0.1:1234").put("local_nat_type","hard").put("remote_nat_type","symm")
                    .put("network","udp4").put("selected_traversal","lan").put("transport_mode","plain").put("signal_failed",true);
            PunchNative.TunnelResult result=new PunchNative.TunnelResult(payload);
            assertSame(result,c.accept(result));assertTrue(result.getOK());assertEquals("handle",result.getHandleID());
            assertEquals(1234,result.getLocalForwardPort());assertEquals("127.0.0.1:1234",result.getPeerEndpoint());
            assertEquals("lan",result.getSelectedTraversal());assertEquals("plain",result.getTransportMode());assertTrue(result.getSignalFailed());
            assertEquals("",result.getErrorCode());assertEquals("",result.getError());
            c.accept(new PunchNative.TunnelResult(new JSONObject()));
            assertEquals("hard",c.localNatType());assertEquals("symm",c.remoteNatType());assertEquals("udp4",c.selectedNetwork());
            for(String code:new String[]{ErrorCodes.TraversalPunchTimeout,"invalid"}) {
                PunchNative.TunnelResult failed=new PunchNative.TunnelResult(new JSONObject().put("ok",false).put("error_code",code));
                assertEquals(code.equals("invalid")?ErrorCodes.NativeDecodeInvalidPayload:code,failed.getErrorCode());
                PunchNative.ExchangeResult exchange=new PunchNative.ExchangeResult(false,"","failed",code);
                assertFalse(exchange.getOK());assertEquals("failed",exchange.getError());assertEquals("",exchange.getRecvData());
                assertEquals(code.equals("invalid")?ErrorCodes.NativeDecodeInvalidPayload:code,exchange.getErrorCode());
            }
            PunchNative.ExchangeResult exchanged=new PunchNative.ExchangeResult(true,"payload","",ErrorCodes.SignalSendTimeout);
            assertTrue(exchanged.getOK());assertEquals("payload",exchanged.getRecvData());assertEquals("",exchanged.getErrorCode());
        }
    }
}
