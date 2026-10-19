package top.p2premote.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceModelTest {
    @Test public void nullStringsNormalizeAndRemoteAccessRequiresAllPrerequisites() {
        DeviceItem empty=new DeviceItem(1,null,null,null,null,null,null,null,3389,null,null,null,null,false,0,false);
        assertEquals("",empty.displayName()); assertEquals("",empty.uuid); assertEquals("",empty.publicIpLocation);
        assertEquals("",empty.systemVersion); assertEquals("",empty.clientVersion); assertTrue(empty.canAcceptP2P());
        assertEquals("",empty.remoteAccessAddress("100.99.0.1"));
        DeviceItem source=new DeviceItem(2,"name","alias"," Android ","uuid","online","public","lan",3389,"location","system","1.14","rdp",true,3390,true);
        assertFalse(source.canAcceptP2P()); assertEquals("alias",source.displayName());
        assertEquals("100.99.0.1:3390",source.remoteAccessAddress("100.99.0.1"));
        assertEquals("",source.remoteAccessAddress(null)); assertEquals("",source.remoteAccessAddress(""));
        DeviceItem copy=source.withRemoteAccess("",true,-1);
        assertEquals("",copy.remoteAccessAddress("100.99.0.1")); assertEquals(3389,copy.remoteAccessPort);
        assertTrue(copy.wakeAvailable); assertEquals("uuid",copy.uuid); assertEquals(3390,source.remoteAccessPort);
        assertEquals("name",new DeviceItem(1,"name","","win10","u","online","","",3389).displayName());
    }

    @Test public void sessionCopiesKeepUnchangedIdentityAndNormalizeOptionalFields() {
        Session empty=new Session("url","a","r",1,null,null,null,null,0,false,0,null,null,null);
        assertEquals("",empty.username); assertEquals("",empty.deviceUuid); assertEquals("",empty.wgPrivateKeyHex);
        assertEquals("",empty.wgPublicKeyHex); assertEquals("",empty.memberLevel); assertEquals("",empty.memberExpireTime); assertEquals("",empty.trialExpireTime);
        Session source=TestPreferences.session("a","r");
        Session copy=source.withTokens("b","s",99,"next","free","m","t",5,false);
        assertEquals("b",copy.accessToken); assertEquals("local-uuid",copy.deviceUuid); assertEquals("private-test",copy.wgPrivateKeyHex);
        assertEquals("a",source.accessToken);
        ApiClient.OpenResult absent=new ApiClient.OpenResult(null,1,null,3389,null,0,null,null);
        assertEquals("",absent.connectionId); assertEquals("",absent.accessGrant); assertEquals("rdp",absent.targetRemoteProtocol);
        assertEquals("",absent.sourceUsername); assertEquals("",absent.sourceEmail);
        ApiClient.OpenResult present=new ApiClient.OpenResult("c",2,"g",22,"ssh",3,"name","email");
        assertEquals("ssh",present.targetRemoteProtocol); assertEquals("name",present.sourceUsername);
    }

    @Test public void activeAndTerminalStatesAreDisjointAndUnknownStatesAreNeither() {
        for(String state:new String[]{TunnelState.PREPARING,TunnelState.EXCHANGING,TunnelState.PUNCHING,TunnelState.CONNECTING,TunnelState.WAITING_APPROVAL,TunnelState.CONNECTED,TunnelState.REQUESTING_VPN,TunnelState.DEGRADED}) {
            assertTrue(state,TunnelState.isActive(state)); assertFalse(state,TunnelState.isTerminal(state));
        }
        for(String state:new String[]{TunnelState.IDLE,TunnelState.FAILED,TunnelState.STOPPED,TunnelState.ABORTED}) {
            assertFalse(state,TunnelState.isActive(state)); assertTrue(state,TunnelState.isTerminal(state));
        }
        assertFalse(TunnelState.isActive(null)); assertFalse(TunnelState.isTerminal(null));
        assertFalse(TunnelState.isActive("unknown")); assertFalse(TunnelState.isTerminal("unknown"));
        for(boolean tcp:new boolean[]{false,true}) for(boolean ipv6:new boolean[]{false,true}) {
            String summary=new ConnectionPreferences(ipv6,tcp).summary();
            assertTrue(summary.contains(tcp?"TCP":"UDP")); assertTrue(summary.contains(ipv6?"IPv6":"IPv4"));
        }
    }

    @Test public void platformFallbacksAndEveryLabelAreStable() {
        assertEquals(DevicePlatform.Kind.UNKNOWN,DevicePlatform.detect((DeviceItem)null));
        assertEquals(DevicePlatform.Kind.UNKNOWN,DevicePlatform.detect(null,null));
        assertEquals(DevicePlatform.Kind.WINDOWS,DevicePlatform.detect("windows","server"));
        assertEquals(DevicePlatform.Kind.WINDOWS_11,DevicePlatform.detect(new DeviceItem(1,"","","win11","","","","",0)));
        String[] labels={"Windows 7","Windows 10","Windows 11","Windows","Ubuntu","Kylin","UOS","Deepin","Android","macOS","Linux","未知系统"};
        DevicePlatform.Kind[] kinds=DevicePlatform.Kind.values(); assertEquals(labels.length,kinds.length);
        for(int i=0;i<kinds.length;i++) assertEquals(labels[i],DevicePlatform.label(kinds[i]));
    }
}
