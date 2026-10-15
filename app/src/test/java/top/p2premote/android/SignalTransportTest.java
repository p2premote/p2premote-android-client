package top.p2premote.android;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalTransportTest {
    private static JSONObject ready(Object selection) throws Exception {
        return new JSONObject().put("signal_transport", selection);
    }
    @Test public void legacyOmissionUsesMqtt() throws Exception {
        assertFalse(SignalTransport.selectedWs(new JSONObject(), true, false));
    }
    @Test public void wsRequiresOfferAndTraversalV2() throws Exception {
        JSONObject selected = ready(new JSONObject().put("version", 1).put("selected", "ws-v1"));
        assertTrue(SignalTransport.selectedWs(selected, true, true));
        assertThrows(IllegalStateException.class, () -> SignalTransport.selectedWs(selected, true, false));
        assertThrows(IllegalStateException.class, () -> SignalTransport.selectedWs(selected, false, true));
    }
    @Test public void malformedExplicitSelectionIsNotLegacy() throws Exception {
        assertThrows(IllegalStateException.class, () -> SignalTransport.selectedWs(ready(JSONObject.NULL), true, true));
        assertThrows(IllegalStateException.class, () -> SignalTransport.selectedWs(ready(new JSONObject().put("version", "1").put("selected", "ws-v1")), true, true));
        assertThrows(IllegalStateException.class, () -> SignalTransport.selectedWs(ready(new JSONObject().put("version", 2).put("selected", "ws-v1")), true, true));
    }
    @Test public void transitionOfferIncludesBothTransports() throws Exception {
        assertEquals(2, SignalTransport.offer(true).getJSONArray("supported").length());
        assertEquals("mqtt-legacy", SignalTransport.offer(false).getJSONArray("supported").getString(0));
    }
}
