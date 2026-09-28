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
        assertEquals(Arrays.asList("tcp6", "udp6", "udp4"), TraversalPolicy.plan(true, true, local, remote));
        assertEquals(Arrays.asList("tcp6", "udp6", "udp4"), TraversalPolicy.plan(true, true, remote, local));
    }
    @Test public void hardOnBothSidesOverridesTcpPreference() {
        List<TraversalPolicy.Evidence> hard = Arrays.asList(nat("udp4", "hard"), nat("udp6", "hard"), nat("tcp4", "hard"), nat("tcp6", "symm"));
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, hard, hard));
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, hard, Collections.singletonList(nat("tcp6", "unknown"))));
    }
    @Test public void missingNatEvidenceKeepsUdpButDoesNotGrantTcp() {
        assertEquals(Arrays.asList("udp6", "udp4"), TraversalPolicy.plan(true, true, Collections.emptyList(), Collections.emptyList()));
        List<TraversalPolicy.Evidence> easy = Arrays.asList(nat("tcp4", "easy"), nat("tcp6", "easy"));
        assertEquals(Arrays.asList("udp4", "udp6"), TraversalPolicy.plan(false, true, easy, Collections.emptyList()));
        assertEquals(Arrays.asList("udp4", "udp6"), TraversalPolicy.plan(false, true, Collections.emptyList(), easy));
    }
    @Test public void allPreferenceOrdersMatchDesktop() {
        List<TraversalPolicy.Evidence> all = Arrays.asList(nat("udp4", "easy"), nat("udp6", "easy"), nat("tcp4", "easy"), nat("tcp6", "easy"));
        assertEquals(Arrays.asList("udp4", "udp6", "tcp4", "tcp6"), TraversalPolicy.plan(false, false, all, all));
        assertEquals(Arrays.asList("udp6", "udp4", "tcp6", "tcp4"), TraversalPolicy.plan(true, false, all, all));
        assertEquals(Arrays.asList("tcp4", "tcp6", "udp4", "udp6"), TraversalPolicy.plan(false, true, all, all));
        assertEquals(Arrays.asList("tcp6", "tcp4", "udp6", "udp4"), TraversalPolicy.plan(true, true, all, all));
    }
}
