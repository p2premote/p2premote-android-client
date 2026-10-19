package top.p2premote.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionStoreTest {
    private static DeviceItem device() { return new DeviceItem(42,"remote","","win10","new-uuid","online","","",3389); }

    @Test public void incompleteCredentialsAreRejectedAndCompleteSessionRoundTrips() {
        TestPreferences memory=new TestPreferences(); SessionStore store=new SessionStore(memory.prefs);
        assertNull(store.load()); assertThrows(IllegalStateException.class,store::require);
        memory.values.put("access_token",null); assertNull(store.load());
        memory.values.put("access_token","access"); assertNull(store.load());
        memory.values.put("refresh_token",null); assertNull(store.load());
        Session source=TestPreferences.session("a","r"); store.save(source);
        Session loaded=store.require(); assertEquals("a",loaded.accessToken); assertEquals("r",loaded.refreshToken);
        assertEquals("private-test",loaded.wgPrivateKeyHex); assertEquals("pro",loaded.memberLevel);
        assertEquals(source.accessTokenExpiresAt,loaded.accessTokenExpiresAt); assertTrue(loaded.isPro);
        store.setSelectedDeviceId(42); assertEquals(42,store.getSelectedDeviceId());
        store.setSelectedDeviceId(0); assertEquals(0,store.getSelectedDeviceId());
    }

    @Test public void lateDeviceAndKeyResultsCannotOverwriteAnotherLoginGeneration() {
        TestPreferences memory=new TestPreferences(); SessionStore first=new SessionStore(memory.prefs), second=new SessionStore(memory.prefs);
        long emptyGeneration=first.accountGeneration();
        assertThrows(IllegalStateException.class,()->first.updateDeviceIfCurrent(emptyGeneration,device()));
        assertThrows(IllegalStateException.class,()->first.updateWgKeypairIfCurrent(emptyGeneration,"p","q"));
        first.save(TestPreferences.session("a","r")); long generation=first.accountGeneration();
        Session updated=second.updateDeviceIfCurrent(generation,device()); assertEquals(42,updated.deviceId);
        assertEquals("new-uuid",first.require().deviceUuid);
        Session keys=first.updateWgKeypairIfCurrent(generation,"new-private","new-public");
        assertEquals("new-private",keys.wgPrivateKeyHex); assertEquals("a",keys.accessToken);
        second.save(TestPreferences.session("other","other-refresh"));
        assertThrows(IllegalStateException.class,()->first.updateDeviceIfCurrent(generation,device()));
        assertThrows(IllegalStateException.class,()->first.updateWgKeypairIfCurrent(generation,"late","late"));
        assertEquals("other",first.require().accessToken);
        first.clear(); assertNull(second.load()); assertTrue(first.accountGeneration()>generation);
    }

    @Test public void tokenRotationChecksBothTokensAndPreservesDeviceAndKeys() {
        TestPreferences memory=new TestPreferences(); SessionStore store=new SessionStore(memory.prefs);
        Session expected=TestPreferences.session("a","r");
        assertThrows(IllegalStateException.class,()->rotate(store,expected));
        store.save(expected);
        assertThrows(IllegalStateException.class,()->rotate(store,TestPreferences.session("wrong","r")));
        assertThrows(IllegalStateException.class,()->rotate(store,TestPreferences.session("a","wrong")));
        Session updated=rotate(store,expected);
        assertEquals("new-access",updated.accessToken); assertEquals("new-refresh",updated.refreshToken);
        assertEquals(7,updated.deviceId); assertEquals("private-test",updated.wgPrivateKeyHex);
        assertThrows(IllegalStateException.class,()->rotate(store,expected));
    }
    private static Session rotate(SessionStore store,Session expected) {
        return store.updateTokensIfCurrent(expected,"new-access","new-refresh",123,"new-user","free","","",0,false);
    }

    @Test public void failedDurableWritesAreReportedAndDoNotAdvanceGeneration() {
        TestPreferences memory=new TestPreferences(); SessionStore store=new SessionStore(memory.prefs);
        long generation=store.accountGeneration(); memory.commitSucceeds=false;
        assertThrows(IllegalStateException.class,()->store.save(TestPreferences.session("a","r")));
        assertEquals(generation,store.accountGeneration());
        memory.commitSucceeds=true; Session expected=TestPreferences.session("a","r"); store.save(expected);
        memory.commitSucceeds=false; assertThrows(IllegalStateException.class,()->rotate(store,expected));
    }
}
