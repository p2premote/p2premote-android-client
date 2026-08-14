package top.p2premote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientVersionPolicyTest {
    @Test
    public void comparesBuildSuffixAndVersionPrefix() {
        assertFalse(new ClientVersionPolicy("1.7.9", "1.7.9", "")
                .requiresForceUpdate("1.7.9-f9fec8"));
        assertTrue(new ClientVersionPolicy("1.8.0", "1.7.10", "")
                .requiresForceUpdate("v1.7.9-f9fec8"));
    }

    @Test
    public void distinguishesOptionalAndForcedUpdates() {
        ClientVersionPolicy policy = new ClientVersionPolicy("2.0.0", "1.5.0", "notes");
        assertFalse(policy.requiresForceUpdate("1.6.0"));
        assertTrue(policy.hasUpdate("1.6.0"));
        assertTrue(policy.requiresForceUpdate("1.4.9"));
    }
}
