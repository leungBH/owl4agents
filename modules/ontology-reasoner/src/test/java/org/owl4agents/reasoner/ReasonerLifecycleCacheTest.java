package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.8.4 ReasonerLifecycleManager classification + profile cache (UNIT-3, UNIT-4)")
class ReasonerLifecycleCacheTest {

    @Test
    @DisplayName("UNIT-3: isClassified lifecycle — false → markClassified → true → shutdownReasoner → false")
    void classifiedLifecycle() {
        ReasonerLifecycleManager mgr = new ReasonerLifecycleManager();
        OntologyId ontId = new OntologyId("test-ont");

        assertFalse(mgr.isClassified(ontId), "Fresh ontology must not be classified");

        mgr.markClassified(ontId);
        assertTrue(mgr.isClassified(ontId), "After markClassified, must be true");

        mgr.shutdownReasoner(ontId);
        assertFalse(mgr.isClassified(ontId), "After shutdownReasoner, must be false again");
    }

    @Test
    @DisplayName("shutdownAll() clears classifiedMap and profileCacheMap")
    void shutdownAllClearsMaps() {
        ReasonerLifecycleManager mgr = new ReasonerLifecycleManager();
        OntologyId ont1 = new OntologyId("ont-1");
        OntologyId ont2 = new OntologyId("ont-2");

        mgr.markClassified(ont1);
        mgr.markClassified(ont2);
        mgr.setCachedProfile(ont1, "OWL 2 EL");
        mgr.setCachedProfile(ont2, "OWL 2 DL");

        mgr.shutdownAll();

        assertFalse(mgr.isClassified(ont1), "ont1 classified must be cleared");
        assertFalse(mgr.isClassified(ont2), "ont2 classified must be cleared");
        assertNull(mgr.getCachedProfile(ont1), "ont1 profile must be cleared");
        assertNull(mgr.getCachedProfile(ont2), "ont2 profile must be cleared");
    }

    @Test
    @DisplayName("UNIT-4: profile cache — first null → setCachedProfile → cached → shutdownReasoner → null")
    void profileCacheLifecycle() {
        ReasonerLifecycleManager mgr = new ReasonerLifecycleManager();
        OntologyId ontId = new OntologyId("profile-test");

        assertNull(mgr.getCachedProfile(ontId), "First call must return null (not cached)");

        mgr.setCachedProfile(ontId, "OWL 2 EL");
        assertEquals("OWL 2 EL", mgr.getCachedProfile(ontId), "Second call must return cached value");

        mgr.shutdownReasoner(ontId);
        assertNull(mgr.getCachedProfile(ontId), "After shutdownReasoner, must be null (recompute needed)");
    }
}
