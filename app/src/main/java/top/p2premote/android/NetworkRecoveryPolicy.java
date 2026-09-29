package top.p2premote.android;

import java.util.Objects;

/**
 * 网络切换恢复的纯状态机。Android 网络回调只负责生成网络指纹，稳定窗口、
 * generation 和重试次数由这里统一仲裁，避免迟到任务覆盖新网络状态。
 */
final class NetworkRecoveryPolicy {
    enum Observation {
        BASELINE,
        UNCHANGED,
        USABILITY_CHANGED,
        RECOVERY_REQUIRED
    }

    private final long stableWindowMs;
    private boolean initialized;
    private String fingerprint = "none";
    private boolean usable;
    private boolean recoveryRequired;
    private long generation;
    private long stableSinceMs;
    private int attempt;

    NetworkRecoveryPolicy(long stableWindowMs) {
        this.stableWindowMs = stableWindowMs;
    }

    synchronized Observation observe(String newFingerprint, boolean newUsable,
                                     boolean tunnelActive, long nowMs) {
        String normalized = newFingerprint == null || newFingerprint.isEmpty()
                ? "none" : newFingerprint;
        if (!initialized) {
            initialized = true;
            fingerprint = normalized;
            usable = newUsable;
            return Observation.BASELINE;
        }
        boolean sameFingerprint = Objects.equals(fingerprint, normalized);
        if (sameFingerprint && usable == newUsable) {
            return Observation.UNCHANGED;
        }

        fingerprint = normalized;
        usable = newUsable;
        if (sameFingerprint && !recoveryRequired) {
            return Observation.USABILITY_CHANGED;
        }
        if (!tunnelActive && !recoveryRequired) {
            return Observation.BASELINE;
        }

        generation++;
        recoveryRequired = true;
        stableSinceMs = nowMs;
        attempt = 0;
        return Observation.RECOVERY_REQUIRED;
    }

    synchronized Observation confirmUnusable(boolean tunnelActive, long nowMs) {
        if (usable || (!tunnelActive && !recoveryRequired)) {
            return Observation.UNCHANGED;
        }
        generation++;
        recoveryRequired = true;
        stableSinceMs = nowMs;
        attempt = 0;
        return Observation.RECOVERY_REQUIRED;
    }

    synchronized boolean canStart(long expectedGeneration, long nowMs) {
        return recoveryRequired
                && generation == expectedGeneration
                && usable
                && nowMs - stableSinceMs >= stableWindowMs;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return recoveryRequired && generation == expectedGeneration;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized boolean usable() {
        return usable;
    }

    synchronized int nextAttempt(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) {
            return -1;
        }
        return ++attempt;
    }

    synchronized int currentAttempt(long expectedGeneration) {
        return isCurrent(expectedGeneration) ? attempt : -1;
    }

    synchronized void markConnected(long expectedGeneration) {
        if (generation == expectedGeneration) {
            recoveryRequired = false;
            attempt = 0;
        }
    }

    synchronized void cancel() {
        generation++;
        recoveryRequired = false;
        attempt = 0;
    }
}
