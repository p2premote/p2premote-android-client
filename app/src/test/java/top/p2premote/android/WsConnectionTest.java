package top.p2premote.android;

import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class WsConnectionTest {
    private static Object field(WsConnection ws,String name) throws Exception {
        Field f=WsConnection.class.getDeclaredField(name);f.setAccessible(true);return f.get(ws);
    }
    @SuppressWarnings("unchecked")
    private static <T> Map<String,CompletableFuture<T>> waiters(WsConnection ws,String name) throws Exception {
        return (Map<String,CompletableFuture<T>>)field(ws,name);
    }
    private static void message(WsConnection ws,String text) throws Exception {
        Method m=WsConnection.class.getDeclaredMethod("handleMessage",String.class);m.setAccessible(true);m.invoke(ws,text);
    }
    private static void shutdown(WsConnection ws) throws Exception {
        ws.stop();((ExecutorService)field(ws,"heartbeatExecutor")).shutdownNow();((ExecutorService)field(ws,"connectionExecutor")).shutdownNow();
    }
    private static String notification(String connection,long peer,String attempt) throws Exception {
        return new JSONObject().put("type","p2p_notify").put("payload",new JSONObject().put("connection_id",connection).put("source_device_id",peer).put("data",new JSONObject().put("type","traversal").put("attempt_id",attempt).toString())).toString();
    }

    @Test public void acknowledgementsResolveOnlyTheMatchingWaiterAndKeepStableRejectionCodes() throws Exception {
        WsConnection ws=new WsConnection(new SessionStore(new TestPreferences().prefs), (tag,text) -> {});
        try {
            for(boolean accepted:new boolean[]{false,true}) {
                CompletableFuture<Boolean> regular=new CompletableFuture<>();CompletableFuture<String> signal=new CompletableFuture<>();
                WsConnectionTest.<Boolean>waiters(ws,"pendingAcks").put("m",regular);WsConnectionTest.<String>waiters(ws,"signalAcks").put("m",signal);
                message(ws,new JSONObject().put("type","p2p_notify_ack").put("payload",new JSONObject().put("message_id","other").put("accepted",true)).toString());
                assertFalse(regular.isDone());assertFalse(signal.isDone());
                String ack=new JSONObject().put("type","p2p_notify_ack").put("payload",new JSONObject().put("message_id","m").put("accepted",accepted)).toString();message(ws,ack);message(ws,ack);
                assertEquals(accepted,regular.get());assertEquals(accepted?"":ErrorCodes.SignalSendRejected,signal.get());
                assertTrue(waiters(ws,"pendingAcks").isEmpty());assertTrue(waiters(ws,"signalAcks").isEmpty());
            }
            message(ws,"malformed");message(ws,"{}");message(ws,"{\"type\":\"p2p_notify\"}");
        } finally {shutdown(ws);}
    }

    @Test public void traversalMessagesRequireConnectionPeerAttemptAndCurrentSocketGeneration() throws Exception {
        WsConnection ws=new WsConnection(new SessionStore(new TestPreferences().prefs), (tag,text) -> {});AtomicInteger received=new AtomicInteger();
        try {
            ws.setTraversalListener("connection",42,"new",data->received.incrementAndGet());
            message(ws,notification("wrong",42,"new"));message(ws,notification("connection",99,"new"));message(ws,notification("connection",42,"old"));assertEquals(0,received.get());
            ws.clearTraversalListener("old");message(ws,notification("connection",42,"new"));assertEquals(1,received.get());
            ((AtomicLong)field(ws,"socketGeneration")).incrementAndGet();message(ws,notification("connection",42,"new"));assertEquals(1,received.get());
            ws.setTraversalListener("connection",42,"next",data->received.incrementAndGet());ws.clearTraversalListener("new");message(ws,notification("connection",42,"next"));assertEquals(2,received.get());
            ws.clearTraversalListener("next");message(ws,notification("connection",42,"next"));assertEquals(2,received.get());
        } finally {shutdown(ws);}
    }

    @Test public void stoppingWakesBothAckKindsAndInvalidatesTheLifecycleWithoutOpeningSockets() throws Exception {
        WsConnection ws=new WsConnection(new SessionStore(new TestPreferences().prefs), (tag,text) -> {});AtomicInteger disconnected=new AtomicInteger();
        try {
            CompletableFuture<Boolean> regular=new CompletableFuture<>();CompletableFuture<String> signal=new CompletableFuture<>();
            WsConnectionTest.<Boolean>waiters(ws,"pendingAcks").put("m",regular);WsConnectionTest.<String>waiters(ws,"signalAcks").put("s",signal);
            ws.setTraversalListener("connection",42,"attempt",data->{},disconnected::incrementAndGet);
            long generation=((AtomicLong)field(ws,"lifecycleGeneration")).get();ws.stop();
            assertFalse(regular.get());assertEquals(ErrorCodes.SignalSendDisconnected,signal.get());assertFalse(ws.isConnected());assertEquals(1,disconnected.get());
            assertTrue(waiters(ws,"pendingAcks").isEmpty());assertTrue(waiters(ws,"signalAcks").isEmpty());assertTrue(((AtomicLong)field(ws,"lifecycleGeneration")).get()>generation);
        } finally {shutdown(ws);}
    }
}
