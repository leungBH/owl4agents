package org.owl4agents.reasoner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D3 / task 5.14: Verifies the access-order behavior of the
 * {@link ReasonerLifecycleManager#activeReasoners} {@link java.util.LinkedHashMap}.
 *
 * <p>Scenario: Insert A, B, C, D (cap = 4). Touch A via
 * {@code activeReasoners.get("ont1|HermiT")} so A becomes the
 * most-recently-used. Insert E — eviction must skip A (now MRU) and dispose
 * B (the new LRU).</p>
 *
 * <p>This test would fail if the map used insertion-order instead of
 * access-order — A would be evicted instead of B.</p>
 */
@DisplayName("v0.8.6 D3 / task 5.14: ReasonerLifecycleManager access-order LRU")
class ReasonerLifecycleManagerAccessOrderTest {

    private ReasonerLifecycleManager mgr;

    @AfterEach
    void shutdownAll() {
        if (mgr != null) {
            mgr.shutdownAll();
        }
    }

    @Test
    @DisplayName("Touching A before inserting E evicts B (not A)")
    void accessOrderUpdatesLruPosition() {
        mgr = new ReasonerLifecycleManager();

        MockReasonerAdapter a = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter b = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter c = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter d = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter e = new MockReasonerAdapter("HermiT");

        mgr.insertReasonerForTest("ont1|HermiT", a); // LRU after this: [A]
        mgr.insertReasonerForTest("ont2|HermiT", b); // LRU order: [A, B]
        mgr.insertReasonerForTest("ont3|HermiT", c); // LRU order: [A, B, C]
        mgr.insertReasonerForTest("ont4|HermiT", d); // LRU order: [A, B, C, D]

        assertEquals(4, mgr.activeReasoners.size(),
            "After 4 insertions, size must be 4");

        // Touch A — moves it to the tail (most-recently-used).
        // new LRU order: [B, C, D, A]
        synchronized (mgr.activeReasoners) {
            mgr.activeReasoners.get("ont1|HermiT");
        }

        // Insert E — size becomes 5, evictIfFull runs, finds B as LRU.
        mgr.insertReasonerForTest("ont5|HermiT", e);

        assertEquals(4, mgr.activeReasoners.size(),
            "After 5th insertion, size must be 4 (LRU eviction)");

        assertEquals(0, a.shutdownCount(),
            "A must NOT be evicted — it was touched (MRU). shutdownCount must be 0");
        assertEquals(1, b.shutdownCount(),
            "B must be evicted — it was the LRU after touching A. shutdownCount must be 1");
        assertEquals(0, c.shutdownCount(), "C must not be evicted");
        assertEquals(0, d.shutdownCount(), "D must not be evicted");
        assertEquals(0, e.shutdownCount(), "E (newly inserted) must not be evicted");

        assertTrue(mgr.activeReasoners.containsKey("ont1|HermiT"),
            "A must still be present (was MRU)");
        assertFalse(mgr.activeReasoners.containsKey("ont2|HermiT"),
            "B must be absent (was LRU, evicted)");
        assertTrue(mgr.activeReasoners.containsKey("ont5|HermiT"),
            "E must be present (newly inserted)");
    }
}
