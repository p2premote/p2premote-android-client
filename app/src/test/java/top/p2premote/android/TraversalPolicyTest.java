package top.p2premote.android;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class TraversalPolicyTest {
    @Test public void sharedProtocolVectorsMatchDesktop() throws Exception {
        JSONObject vectors;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("traversal-policy-v2.json")) {
            assertNotNull("shared traversal vectors", input);
            vectors = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(TraversalPolicy.VERSION, vectors.getInt("version"));
        JSONObject evidence = vectors.getJSONObject("evidence_sets");
        JSONArray cases = vectors.getJSONArray("plan_cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i), preferences = c.getJSONObject("preferences");
            List<String> expected = new ArrayList<>();
            JSONArray networks = c.getJSONArray("expected");
            for (int n = 0; n < networks.length(); n++) expected.add(networks.getString(n));
            assertEquals(c.getString("name"), expected, TraversalPolicy.plan(
                    preferences.getBoolean("prefer_ipv6"), preferences.getBoolean("prefer_tcp"),
                    sharedEvidence(evidence.getJSONArray(c.getString("local"))),
                    sharedEvidence(evidence.getJSONArray(c.getString("remote"))),
                    c.isNull("local_ipv6") ? null : c.getBoolean("local_ipv6"),
                    c.isNull("remote_ipv6") ? null : c.getBoolean("remote_ipv6")));
        }
        cases = vectors.getJSONArray("budget_cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            Integer local = c.isNull("local") ? null : c.getInt("local");
            Integer remote = c.isNull("remote") ? null : c.getInt("remote");
            if (c.isNull("expected")) {
                assertThrows(IllegalArgumentException.class, () -> TraversalPolicy.negotiateLanProbeBudget(local, remote));
            } else {
                assertEquals(c.getInt("expected"), TraversalPolicy.negotiateLanProbeBudget(local, remote));
            }
        }
    }
    private List<TraversalPolicy.Evidence> sharedEvidence(JSONArray values) throws Exception {
        List<TraversalPolicy.Evidence> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            result.add(nat(value.getString("network"), value.getString("nat_type")));
        }
        return result;
    }
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
        List<String> networks = Arrays.asList("tcp4", "udp4");
        assertTrue(TraversalPolicy.tcpRetryRecommended(true, ErrorCodes.TraversalPunchTimeout, networks));
        assertFalse(TraversalPolicy.tcpRetryRecommended(true, ErrorCodes.TraversalLocalForwardIoFailed, networks));
        assertTrue(TraversalPolicy.tcpRetryRecommended(true, ErrorCodes.TraversalExecutionExhausted, networks));
        assertFalse(TraversalPolicy.tcpRetryRecommended(false, ErrorCodes.TraversalExecutionExhausted, networks));
        assertFalse(TraversalPolicy.tcpRetryRecommended(true, ErrorCodes.ConnectionExecutionPeerCancelled, networks));
        assertFalse(TraversalPolicy.tcpRetryRecommended(true, ErrorCodes.TraversalExecutionExhausted, Arrays.asList("udp4")));
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
    @Test public void symmetricNatHelpRequiresNetworkPunchFailure() {
        assertTrue(TraversalPolicy.symmetricNatHelpRecommended(ErrorCodes.TraversalPunchTimeout, "symm", "easy"));
        assertTrue(TraversalPolicy.symmetricNatHelpRecommended(ErrorCodes.TraversalExecutionExhausted, "hard", "SYMM"));
        assertFalse(TraversalPolicy.symmetricNatHelpRecommended(ErrorCodes.RuntimeExecutionUnclassified, "symm", "symm"));
        assertFalse(TraversalPolicy.symmetricNatHelpRecommended(ErrorCodes.TraversalPunchTimeout, "hard", "easy"));
    }
    @Test public void lanBudgetIsShortOnlyWhenBothPeersSupportIt() {
        assertEquals(1, TraversalPolicy.negotiateLanProbeBudget(1, 1));
        assertEquals(TraversalPolicy.LEGACY_LAN_PROBE_BUDGET_SECS,
                TraversalPolicy.negotiateLanProbeBudget(1, null));
        assertEquals(TraversalPolicy.LEGACY_LAN_PROBE_BUDGET_SECS,
                TraversalPolicy.negotiateLanProbeBudget(null, 1));
        assertEquals(2, TraversalPolicy.negotiateLanProbeBudget(1, 2));
    }
    @Test public void lanBudgetRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TraversalPolicy.negotiateLanProbeBudget(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> TraversalPolicy.negotiateLanProbeBudget(1, 7));
    }
}
