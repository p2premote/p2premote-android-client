package top.p2premote.android;

/** Holds Wi-Fi multicast reception only while native discovery is running. */
final class DiscoveryMulticastLease implements AutoCloseable {
    interface Backend { boolean acquire(); void release(); }
    private final Backend backend;
    private int users;
    DiscoveryMulticastLease(Backend backend) { this.backend = backend; }
    synchronized boolean setActive(boolean active) {
        if (active) {
            if (users == 0 && !backend.acquire()) return false;
            users++;
        } else if (users > 0 && --users == 0) {
            backend.release();
        }
        return true;
    }
    @Override public synchronized void close() {
        if (users > 0) { users = 0; backend.release(); }
    }
}
