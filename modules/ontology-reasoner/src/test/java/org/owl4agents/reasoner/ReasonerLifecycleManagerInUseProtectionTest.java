package org.owl4agents.reasoner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D3 / task 5.15: Verifies the reference-counting in-use protection
 * in {@link ReasonerLifecycleManager#evictIfFull}.
 *
 * <p>Scenario: Insert A, B, C, D. Increment A's {@code inUseCount} to 1
 * (simulate an active call). Insert E — eviction must skip A (in-use) and
 * dispose B (the LRU with {@code inUseCount == 0}).</p>
 *
 * <p>This test would fail if {@code evictIfFull} did not check
 * {@code inUseCount} — A would be evicted while another thread is
 * actively using it, causing a use-after-shutdown crash.</p>
 */
@DisplayName("v0.8.6 D3 / task 5.15: ReasonerLifecycleManager in-use protection")
class ReasonerLifecycleManagerInUseProtectionTest {

    private ReasonerLifecycleManager mgr;

    @AfterEach
    void shutdownAll() {
        if (mgr != null) {
            mgr.shutdownAll();
        }
    }

    @Test
    @DisplayName("In-use reasoner (inUseCount > 0) is skipped by eviction")
    void inUseReasonerIsProtectedFromEviction() {
        mgr = new ReasonerLifecycleManager();

        MockReasonerAdapter a = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter b = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter c = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter d = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter e = new MockReasonerAdapter("HermiT");

        mgr.insertReasonerForTest("ont1|HermiT", a); // LRU order: [A]
        mgr.insertReasonerForTest("ont2|HermiT", b); // LRU order: [A, B]
        mgr.insertReasonerForTest("ont3|HermiT", c); // LRU order: [A, B, C]
        mgr.insertReasonerForTest("ont4|HermiT", d); // LRU order: [A, B, C, D]

        assertEquals(4, mgr.activeReasoners.size(),
            "After 4 insertions, size must be 4");

        // Mark A as in-use (simulates an active reasoning call holding a reference).
        AtomicLong aCount = mgr.inUseCount.get("ont1|HermiT");
        assertNotNull(aCount, "inUseCount entry must exist after insertReasonerForTest");
        aCount.incrementAndGet(); // A is now "in use" — count == 1

        // Insert E — eviction must skip A (in-use) and evict B instead.
        mgr.insertReasonerForTest("ont5|HermiT", e);

        assertEquals(4, mgr.activeReasoners.size(),
            "After 5th insertion, size must be 4 (LRU eviction skipped in-use A)");

        assertEquals(0, a.shutdownCount(),
            "A must NOT be evicted — inUseCount > 0 protects it");
        assertEquals(1, b.shutdownCount(),
            "B must be evicted — it was the LRU with inUseCount == 0");
        assertEquals(0, c.shutdownCount(), "C must not be evicted");
        assertEquals(0, d.shutdownCount(), "D must not be evicted");
        assertEquals(0, e.shutdownCount(), "E must not be evicted (just inserted)");

        assertTrue(mgr.activeReasoners.containsKey("ont1|HermiT"),
            "A must still be present (protected by inUseCount)");
        assertFalse(mgr.activeReasoners.containsKey("ont2|HermiT"),
            "B must be absent (evicted as LRU with count == 0)");
        assertTrue(mgr.activeReasoners.containsKey("ont5|HermiT"),
            "E must be present (newly inserted)");
    }

    @Test
    @DisplayName("After releaseReasoner, formerly-in-use entry becomes evictable")
    void afterReleaseEntryBecomesEvictable() {
        mgr = new ReasonerLifecycleManager();

        MockReasonerAdapter a = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter b = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter c = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter d = new MockReasonerAdapter("HermiT");

        mgr.insertReasonerForTest("ont1|HermiT", a);
        mgr.insertReasonerForTest("ont2|HermiT", b);
        mgr.insertReasonerForTest("ont3|HermiT", c);
        mgr.insertReasonerForTest("ont4|HermiT", d);

        // Acquire A, then release A. releaseReasoner removes the inUseCount
        // entry when the count drops to <= 0, so the entry is treated as
        // "count == 0" by evictIfFull (eligible for eviction).
        AtomicLong aCount = mgr.inUseCount.get("ont1|HermiT");
        aCount.incrementAndGet();
        mgr.releaseReasoner("ont1|HermiT");

        assertNull(mgr.inUseCount.get("ont1|HermiT"),
            "After release brings count to 0, inUseCount entry must be removed "
                + "(treated as count == 0 by evictIfFull)");
    }
}
