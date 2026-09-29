package top.p2premote.android;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class TraversalPolicyTest {
    private TraversalPolicy.Evidence nat(String network, String type) { return new TraversalPolicy.Evidence(network, type); }
    @Test public void tcpAdmissionUsesTheSameNetwork() {
        List<TraversalPolicy.Evidence> local = Arrays.asList(nat("udp4", "easy"), nat("tcp4", "hard"), nat("tcp6", "easy"));
        List<TraversalPolicy.Evidence> remote = Arrays.asList(nat("udp4", "symm"), nat("tcp4", "symm"), nat("tcp6", "hard"));
        assertEquals(Arrays.asList("tcp6", "udp6", "udp4"), TraversalPolicy.plan(true, true, local, remote, null, null));
        assertEquals(Arrays.asList("tcp6", "udp6", "udp4"), TraversalPolicy.plan(true, true, remote, local, null, null));
    }
    @Test public void hardOnBothSidesOverridesTcpPreference() {
        List<TraversalPolicy.Evidence> hard = Arrays.asList(nat("udp4", "hard"), nat("udp6", "hard"), nat("tcp4", "hard"), nat("tcp6", "symm"));
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, hard, hard, null, null));
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, hard, Collections.singletonList(nat("tcp6", "unknown")), null, null));
    }
    @Test public void missingNatEvidenceKeepsUdpButDoesNotGrantTcp() {
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, Collections.emptyList(), Collections.emptyList(), null, null));
        List<TraversalPolicy.Evidence> easy = Arrays.asList(nat("tcp4", "easy"), nat("tcp6", "easy"));
        assertEquals(Arrays.asList("udp4", "udp6"), TraversalPolicy.plan(false, true, easy, Collections.emptyList(), null, null));
        assertEquals(Arrays.asList("udp4", "udp6"), TraversalPolicy.plan(false, true, Collections.emptyList(), easy, null, null));
    }
    @Test public void allPreferenceOrdersMatchDesktop() {
        List<TraversalPolicy.Evidence> all = Arrays.asList(nat("udp4", "easy"), nat("udp6", "easy"), nat("tcp4", "easy"), nat("tcp6", "easy"));
        assertEquals(Arrays.asList("udp4", "udp6", "tcp4", "tcp6"), TraversalPolicy.plan(false, false, all, all, null, null));
        assertEquals(Arrays.asList("udp6", "udp4", "tcp6", "tcp4"), TraversalPolicy.plan(true, false, all, all, null, null));
        assertEquals(Arrays.asList("tcp4", "tcp6", "udp4", "udp6"), TraversalPolicy.plan(false, true, all, all, null, null));
        assertEquals(Arrays.asList("tcp6", "tcp4", "udp6", "udp4"), TraversalPolicy.plan(true, true, all, all, null, null));
    }
    @Test public void eitherSideWithoutIpv6ExcludesBothIpv6Transports() {
        List<TraversalPolicy.Evidence> all = Arrays.asList(nat("udp4", "easy"), nat("udp6", "easy"), nat("tcp4", "easy"), nat("tcp6", "easy"));
        for (boolean ipv6 : new boolean[]{false, true}) for (boolean tcp : new boolean[]{false, true}) {
            for (Boolean other : new Boolean[]{null, false, true}) {
                for (List<String> plan : Arrays.asList(
                        TraversalPolicy.plan(ipv6, tcp, all, all, false, other),
                        TraversalPolicy.plan(ipv6, tcp, all, all, other, false))) {
                    assertFalse(plan.contains("tcp6")); assertFalse(plan.contains("udp6"));
                    assertTrue(plan.contains("tcp4")); assertTrue(plan.contains("udp4"));
                }
            }
        }
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, Collections.emptyList(), Collections.emptyList(), null, true));
    }
    @Test public void hintRequiresActualPublicTcpExecution() {
        for (String error : Arrays.asList("punch_exhausted", "punch_exhausted:", "punch_exhausted:udp4,udp6", "traversal_signal_timeout", "peer_cancelled")) {
            assertFalse(TraversalPolicy.tcpRetryRecommended(true, error));
        }
        assertTrue(TraversalPolicy.tcpRetryRecommended(true, "punch_exhausted:tcp4,udp4,udp6"));
        assertTrue(TraversalPolicy.tcpRetryRecommended(true, "punch_exhausted:tcp6,udp6,udp4"));
        assertFalse(TraversalPolicy.tcpRetryRecommended(false, "punch_exhausted:tcp4,udp4"));
    }
    @Test public void localIpv6AddressesAreNotPublicCapabilities() throws Exception {
        for (String address : Arrays.asList("::", "::1", "fe80::1", "fd00::1", "ff02::1", "2001:db8::1")) {
            assertFalse(TraversalPolicy.usableIpv6(java.net.InetAddress.getByName(address).getAddress()));
        }
        assertTrue(TraversalPolicy.usableIpv6(java.net.InetAddress.getByName("2001:4860:4860::8888").getAddress()));
    }
    @Test public void udp4NatExtractionSkipsInvalidTypes() {
        assertEquals("easy", TraversalPolicy.udp4NatType(Arrays.asList(nat("udp4", "easy"))));
        assertEquals("symm", TraversalPolicy.udp4NatType(Arrays.asList(nat("udp6", "easy"), nat("udp4", "symm"))));
        assertEquals("", TraversalPolicy.udp4NatType(Arrays.asList(nat("udp4", "unknown"))));
        assertEquals("", TraversalPolicy.udp4NatType(Collections.<TraversalPolicy.Evidence>emptyList()));
    }
}
