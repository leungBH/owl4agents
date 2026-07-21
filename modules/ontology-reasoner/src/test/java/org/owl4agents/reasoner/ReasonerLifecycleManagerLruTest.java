package org.owl4agents.reasoner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D3 / task 5.13: Verifies {@link ReasonerLifecycleManager#evictIfFull}
 * enforces the {@link ReasonerLifecycleManager#MAX_ACTIVE_REASONERS} (=4) cap
 * by disposing the eldest (least-recently-used) reasoner when the 5th is
 * inserted.
 *
 * <p>Uses {@link MockReasonerAdapter} to avoid loading real ontologies and
 * to assert that {@code shutdown()} was called on the evicted adapter.</p>
 */
@DisplayName("v0.8.6 D3 / task 5.13: ReasonerLifecycleManager LRU eviction")
class ReasonerLifecycleManagerLruTest {

    private ReasonerLifecycleManager mgr;

    @AfterEach
    void shutdownAll() {
        if (mgr != null) {
            mgr.shutdownAll();
        }
    }

    @Test
    @DisplayName("Inserting 5 reasoners into a 4-cap map evicts the eldest")
    void fifthInsertEvictsEldest() {
        mgr = new ReasonerLifecycleManager();

        MockReasonerAdapter a = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter b = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter c = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter d = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter e = new MockReasonerAdapter("HermiT");

        mgr.insertReasonerForTest("ont1|HermiT", a);
        mgr.insertReasonerForTest("ont2|HermiT", b);
        mgr.insertReasonerForTest("ont3|HermiT", c);
        mgr.insertReasonerForTest("ont4|HermiT", d);

        assertEquals(4, mgr.activeReasoners.size(),
            "After 4 insertions, size must be 4 (cap not yet exceeded)");

        // 5th insertion triggers evictIfFull — eldest (A) must be evicted.
        mgr.insertReasonerForTest("ont5|HermiT", e);

        assertEquals(4, mgr.activeReasoners.size(),
            "After 5 insertions, size must still be 4 (LRU eviction)");
        assertEquals(1, a.shutdownCount(),
            "Eldest adapter A must have been shut down exactly once");
        assertEquals(0, b.shutdownCount(),
            "B must NOT have been shut down (still in map)");
        assertEquals(0, e.shutdownCount(),
            "Newly-inserted E must NOT have been shut down");
        assertFalse(mgr.activeReasoners.containsKey("ont1|HermiT"),
            "Evicted key ont1|HermiT must be absent from the map");
        assertTrue(mgr.activeReasoners.containsKey("ont5|HermiT"),
            "Newly-inserted ont5|HermiT must be present in the map");
    }

    @Test
    @DisplayName("Below-cap insertions do not trigger eviction")
    void belowCapNoEviction() {
        mgr = new ReasonerLifecycleManager();

        MockReasonerAdapter a = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter b = new MockReasonerAdapter("HermiT");

        mgr.insertReasonerForTest("ont1|HermiT", a);
        mgr.insertReasonerForTest("ont2|HermiT", b);

        assertEquals(2, mgr.activeReasoners.size(),
            "2 insertions below cap must produce size == 2");
        assertEquals(0, a.shutdownCount(), "A must not be shut down");
        assertEquals(0, b.shutdownCount(), "B must not be shut down");
    }
}
