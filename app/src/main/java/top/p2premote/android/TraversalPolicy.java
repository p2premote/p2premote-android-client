package top.p2premote.android;

import java.util.ArrayList;
import java.util.List;

/** Application policy shared with desktop; actual probing remains in gonc. */
final class TraversalPolicy {
    static final int VERSION = 2;
    static final class Evidence {
        final String network, natType;
        Evidence(String network, String natType) { this.network = network; this.natType = natType; }
    }
    static List<String> plan(boolean ipv6, boolean tcp, List<Evidence> local, List<Evidence> remote) {
        String[] order = tcp
                ? (ipv6 ? new String[]{"tcp6", "tcp4", "udp6", "udp4"} : new String[]{"tcp4", "tcp6", "udp4", "udp6"})
                : (ipv6 ? new String[]{"udp6", "udp4", "tcp6", "tcp4"} : new String[]{"udp4", "udp6", "tcp4", "tcp6"});
        List<String> result = new ArrayList<>();
        for (String network : order) {
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
}
