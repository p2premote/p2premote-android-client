package top.p2premote.android;

import java.util.ArrayList;
import java.util.List;

/** Application policy shared with desktop; actual probing remains in gonc. */
final class TraversalPolicy {
    static final int VERSION = 2;
    static final int LAN_PROBE_BUDGET_SECS = 1;
    static final int LEGACY_LAN_PROBE_BUDGET_SECS = 6;
    static final class Evidence {
        final String network, natType;
        Evidence(String network, String natType) { this.network = network; this.natType = natType; }
    }
    static boolean usableIpv6(byte[] address) {
        return address.length == 16 && (address[0] & 0xe0) == 0x20
                && !((address[0] & 0xff) == 0x20 && (address[1] & 0xff) == 1
                && (address[2] & 0xff) == 0x0d && (address[3] & 0xff) == 0xb8);
    }
    static boolean tcpRetryRecommended(boolean preferTcp, String code, List<String> attempted) {
        return preferTcp && (ErrorCodes.TraversalExecutionExhausted.equals(code)
                || "next_network_or_attempt".equals(ErrorCodes.retryClass(code)))
                && (attempted.contains("tcp4") || attempted.contains("tcp6"));
    }

    static boolean symmetricNatHelpRecommended(String errorCode, String localNatType, String remoteNatType) {
        return (ErrorCodes.TraversalPunchTimeout.equals(errorCode) || ErrorCodes.TraversalPunchFailed.equals(errorCode)
                || ErrorCodes.TraversalExecutionExhausted.equals(errorCode) || ErrorCodes.TraversalCandidateSelectionNoUsableCandidate.equals(errorCode))
                && ("symm".equalsIgnoreCase(localNatType) || "symm".equalsIgnoreCase(remoteNatType));
    }
    static int negotiateLanProbeBudget(Integer local, Integer remote) {
        return Math.max(validateLanProbeBudget(local), validateLanProbeBudget(remote));
    }
    private static int validateLanProbeBudget(Integer value) {
        if (value == null) return LEGACY_LAN_PROBE_BUDGET_SECS;
        if (value < 1 || value > LEGACY_LAN_PROBE_BUDGET_SECS) {
            throw new IllegalArgumentException("invalid_lan_probe_budget");
        }
        return value;
    }
    static List<String> plan(boolean ipv6, boolean tcp, List<Evidence> local, List<Evidence> remote,
                             Boolean localIpv6, Boolean remoteIpv6) {
        String[] order = tcp
                ? (ipv6 ? new String[]{"tcp6", "tcp4", "udp6", "udp4"} : new String[]{"tcp4", "tcp6", "udp4", "udp6"})
                : (ipv6 ? new String[]{"udp6", "udp4", "tcp6", "tcp4"} : new String[]{"udp4", "udp6", "tcp4", "tcp6"});
        List<String> result = new ArrayList<>();
        for (String network : order) {
            if (network.endsWith("6") && (Boolean.FALSE.equals(localIpv6) || Boolean.FALSE.equals(remoteIpv6))) continue;
            // UDP support does not depend on successful preliminary STUN.
            if (network.equals("udp4") || network.equals("udp6")) {
                result.add(network);
                continue;
            }
            boolean left = false, right = false, easy = false;
            for (Evidence e : local) if (valid(e, network)) { left = true; easy |= "easy".equals(e.natType); }
            for (Evidence e : remote) if (valid(e, network)) { right = true; easy |= "easy".equals(e.natType); }
            if (left && right && easy) result.add(network);
        }
        return result;
    }
    private static boolean valid(Evidence e, String network) {
        return network.equals(e.network) && ("easy".equals(e.natType) || "hard".equals(e.natType) || "symm".equals(e.natType));
    }

    /** Preliminary udp4 NAT classification used as the report fallback. */
    static String udp4NatType(List<Evidence> evidence) {
        for (Evidence e : evidence) {
            if (valid(e, "udp4")) return e.natType;
        }
        return "";
    }
}
