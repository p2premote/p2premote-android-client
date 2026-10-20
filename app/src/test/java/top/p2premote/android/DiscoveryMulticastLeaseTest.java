package top.p2premote.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryMulticastLeaseTest {
    private static final class Backend implements DiscoveryMulticastLease.Backend {
        int acquired, released; boolean allowed = true;
        public boolean acquire() { acquired++; return allowed; }
        public void release() { released++; }
    }
    @Test public void overlappingDiscoveriesShareOneLockAndReleaseAfterLastOwner() {
        Backend backend = new Backend(); DiscoveryMulticastLease lease = new DiscoveryMulticastLease(backend);
        assertTrue(lease.setActive(true)); assertTrue(lease.setActive(true));
        assertEquals(1, backend.acquired);
        lease.setActive(false); assertEquals(0, backend.released);
        lease.setActive(false); assertEquals(1, backend.released);
        lease.setActive(false); lease.close(); assertEquals(1, backend.released);
        assertTrue(lease.setActive(true)); lease.close();
        assertEquals(2, backend.acquired); assertEquals(2, backend.released);
    }
    @Test public void deniedDiscoveryDoesNotRetainOrReleaseALock() {
        Backend backend = new Backend(); backend.allowed = false;
        DiscoveryMulticastLease lease = new DiscoveryMulticastLease(backend);
        assertFalse(lease.setActive(true)); lease.setActive(false); lease.close();
        assertEquals(0, backend.released);
        backend.allowed = true; assertTrue(lease.setActive(true)); lease.close();
        assertEquals(2, backend.acquired); assertEquals(1, backend.released);
    }
}
