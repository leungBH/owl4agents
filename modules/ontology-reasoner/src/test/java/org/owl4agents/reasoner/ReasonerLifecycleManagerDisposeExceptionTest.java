package org.owl4agents.reasoner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D3 / task 5.16: Verifies that a {@link OWLReasonerAdapter#shutdown()}
 * exception thrown during LRU eviction is caught and logged (SEVERE) by
 * {@link ReasonerLifecycleManager#evictIfFull}, and that the entry is still
 * removed from the map (LRU state stays consistent).
 *
 * <p>Rationale: HermiT/Openllet native resources (thread pools, memory) may
 * fail to release under OOM or native-error conditions. The LRU evictor
 * must not propagate the exception — doing so would leave the map
 * over-capacity indefinitely and break the calling
 * {@code getOrCreateReasoner} flow. The leaked resources will be cleaned
 * up by JVM exit or {@code -XX:+ExitOnOutOfMemoryError}.</p>
 *
 * <p>No Mockito — uses {@link MockReasonerAdapter} configured to throw on
 * {@code shutdown()}.</p>
 */
@DisplayName("v0.8.6 D3 / task 5.16: ReasonerLifecycleManager dispose-exception path")
class ReasonerLifecycleManagerDisposeExceptionTest {

    private ReasonerLifecycleManager mgr;

    @AfterEach
    void shutdownAll() {
        if (mgr != null) {
            // shutdownAll also catches per-adapter exceptions — safe to call
            // even with throwing mocks still in the map.
            mgr.shutdownAll();
        }
    }

    @Test
    @DisplayName("shutdown() exception during eviction is swallowed; entry still removed")
    void disposeExceptionIsCaughtAndEntryRemoved() {
        mgr = new ReasonerLifecycleManager();

        // A throws on shutdown(). B, C, D are well-behaved.
        MockReasonerAdapter a = new MockReasonerAdapter("HermiT",
            new RuntimeException("Simulated HermiT native shutdown failure"));
        MockReasonerAdapter b = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter c = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter d = new MockReasonerAdapter("HermiT");
        MockReasonerAdapter e = new MockReasonerAdapter("HermiT");

        mgr.insertReasonerForTest("ont1|HermiT", a); // LRU — will be evicted
        mgr.insertReasonerForTest("ont2|HermiT", b);
        mgr.insertReasonerForTest("ont3|HermiT", c);
        mgr.insertReasonerForTest("ont4|HermiT", d);

        assertEquals(4, mgr.activeReasoners.size(),
            "After 4 insertions, size must be 4");

        // 5th insertion triggers evictIfFull, which calls A.shutdown() —
        // A.shutdown() throws, but evictIfFull MUST catch and log it.
        assertDoesNotThrow(() -> mgr.insertReasonerForTest("ont5|HermiT", e),
            "evictIfFull must NOT propagate the shutdown() exception");

        // Even though shutdown() threw, the entry was removed from the map
        // BEFORE shutdown() was called — LRU state is consistent.
        assertEquals(4, mgr.activeReasoners.size(),
            "After 5th insertion, size must be 4 (eviction succeeded despite exception)");
        assertFalse(mgr.activeReasoners.containsKey("ont1|HermiT"),
            "A must be removed from the map even though its shutdown() threw");
        assertTrue(mgr.activeReasoners.containsKey("ont5|HermiT"),
            "E must be present (newly inserted)");

        // shutdown() was actually called on A (the mock records the invocation
        // BEFORE throwing).
        assertEquals(1, a.shutdownCount(),
            "A.shutdown() must have been invoked exactly once (even though it threw)");
        assertEquals(0, b.shutdownCount(), "B must not have been shut down");
        assertEquals(0, e.shutdownCount(), "E must not have been shut down");
    }

    @Test
    @DisplayName("shutdownReasoner(ontologyId) catches per-adapter exceptions")
    void shutdownReasonerCatchesExceptions() {
        mgr = new ReasonerLifecycleManager();

        MockReasonerAdapter a = new MockReasonerAdapter("HermiT",
            new RuntimeException("Simulated shutdown failure"));
        mgr.insertReasonerForTest("ont1|HermiT", a);

        // shutdownReasoner must not propagate the exception.
        assertDoesNotThrow(() -> mgr.shutdownReasoner(new org.owl4agents.core.OntologyId("ont1")),
            "shutdownReasoner must catch per-adapter shutdown() exceptions");

        assertEquals(0, mgr.activeReasoners.size(),
            "Map must be empty after shutdownReasoner");
        assertEquals(1, a.shutdownCount(),
            "A.shutdown() must have been called exactly once");
    }
}
