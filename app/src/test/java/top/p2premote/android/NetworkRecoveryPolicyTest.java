package top.p2premote.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkRecoveryPolicyTest {
    @Test
    public void initialObservationOnlyEstablishesBaseline() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        assertEquals(NetworkRecoveryPolicy.Observation.BASELINE,
                policy.observe("wifi-1", true, true, 0));
        assertFalse(policy.canStart(policy.generation(), 20_000));
    }

    @Test
    public void wifiCellularWifiWaitsForFinalStableNetwork() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        policy.observe("wifi-1", true, false, 0);

        assertEquals(NetworkRecoveryPolicy.Observation.RECOVERY_REQUIRED,
                policy.observe("cell-1", true, true, 1_000));
        long cellularGeneration = policy.generation();
        assertFalse(policy.canStart(cellularGeneration, 6_000));

        assertEquals(NetworkRecoveryPolicy.Observation.RECOVERY_REQUIRED,
                policy.observe("wifi-2", true, true, 6_000));
        long wifiGeneration = policy.generation();
        assertFalse(policy.canStart(cellularGeneration, 20_000));
        assertFalse(policy.canStart(wifiGeneration, 13_999));
        assertTrue(policy.canStart(wifiGeneration, 14_000));
    }

    @Test
    public void unavailableNetworkCannotStartRecovery() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        policy.observe("wifi-1", true, false, 0);
        policy.observe("none", false, true, 1_000);
        assertFalse(policy.canStart(policy.generation(), 30_000));
    }

    @Test
    public void cancelInvalidatesLateRecoveryTask() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        policy.observe("wifi-1", true, false, 0);
        policy.observe("cell-1", true, true, 1_000);
        long staleGeneration = policy.generation();
        policy.cancel();
        assertFalse(policy.isCurrent(staleGeneration));
        assertFalse(policy.canStart(staleGeneration, 20_000));
    }

    @Test
    public void attemptsAreScopedToCurrentGeneration() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        policy.observe("wifi-1", true, false, 0);
        policy.observe("cell-1", true, true, 1_000);
        long cellularGeneration = policy.generation();
        assertEquals(1, policy.nextAttempt(cellularGeneration));
        assertEquals(2, policy.nextAttempt(cellularGeneration));

        policy.observe("wifi-2", true, true, 2_000);
        long wifiGeneration = policy.generation();
        assertEquals(-1, policy.currentAttempt(cellularGeneration));
        assertEquals(1, policy.nextAttempt(wifiGeneration));
    }

    @Test
    public void transientCapabilityLossDoesNotImmediatelyInvalidateTunnel() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        policy.observe("wifi-1", true, false, 0);
        assertEquals(NetworkRecoveryPolicy.Observation.USABILITY_CHANGED,
                policy.observe("wifi-1", false, true, 1_000));
        assertFalse(policy.isCurrent(policy.generation()));

        assertEquals(NetworkRecoveryPolicy.Observation.USABILITY_CHANGED,
                policy.observe("wifi-1", true, true, 5_000));
        assertFalse(policy.isCurrent(policy.generation()));
    }

    @Test
    public void sustainedCapabilityLossCanBeConfirmedAfterGrace() {
        NetworkRecoveryPolicy policy = new NetworkRecoveryPolicy(8_000);
        policy.observe("wifi-1", true, false, 0);
        policy.observe("wifi-1", false, true, 1_000);

        assertEquals(NetworkRecoveryPolicy.Observation.RECOVERY_REQUIRED,
                policy.confirmUnusable(true, 16_000));
        assertTrue(policy.isCurrent(policy.generation()));
        assertFalse(policy.canStart(policy.generation(), 30_000));
    }
}
