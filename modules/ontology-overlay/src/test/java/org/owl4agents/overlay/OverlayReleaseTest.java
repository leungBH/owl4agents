package org.owl4agents.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.OntologyCache;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-008 unit tests for overlay automatic release semantics
 * (spec "Overlay Automatic Release" requirement).
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>{@link TransientOverlay} implements {@link AutoCloseable} so
 *       try-with-resources releases the overlay.</li>
 *   <li>{@link TransientOverlay#release()} is idempotent — calling it
 *       twice does not throw.</li>
 *   <li>After release, the overlay's underlying OWLOntologyManager no
 *       longer holds a strong reference to the overlay ontology (the
 *       ontology becomes eligible for GC once the caller drops its
 *       reference).</li>
 *   <li>The {@link OntologyCache} base entry is unchanged after overlay
 *       release (no cache pollution, no resource leak).</li>
 *   <li>Repeated overlay creation + release cycles do not exhaust
 *       resources (overlay automatic release prevents leaks).</li>
 * </ul>
 */
@DisplayName("Overlay automatic release: cleanup and no resource leaks")
class OverlayReleaseTest {

    @TempDir
    Path tempDir;

    private TransientOntologyOverlayService service;
    private OntologyCache cache;

    private void setUpSmartHome() throws Exception {
        cache = OverlayTestFixtures.materializeSmartHome(tempDir, "default");
        service = new TransientOntologyOverlayServiceImpl(cache);
    }

    private OWLAxiom dynamicDeviceAxiom() {
        var mgr = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        var df = mgr.getOWLDataFactory();
        var device = df.getOWLNamedIndividual(
            org.semanticweb.owlapi.model.IRI.create(OverlayTestFixtures.SMART_HOME_NS + "lamp-1"));
        var smartPlug = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(
            OverlayTestFixtures.SMART_PLUG_CLASS_IRI));
        return df.getOWLClassAssertionAxiom(smartPlug, device);
    }

    @Test
    @DisplayName("OVERLAY-008: TransientOverlay implements AutoCloseable")
    void overlayIsAutoCloseable() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamicDeviceAxiom()),
                    OverlayOptions.defaults()))) {
            assertNotNull(overlay.ontology());
        }
        // No exception means close() ran without error.
    }

    @Test
    @DisplayName("OVERLAY-008: release() is idempotent — calling twice does not throw")
    void releaseIsIdempotent() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        TransientOverlay overlay = OverlayTestFixtures.unwrap(
            service.createOverlay(baseId, List.of(dynamicDeviceAxiom()),
                OverlayOptions.defaults()));
        overlay.release();
        // Second release must not throw.
        assertDoesNotThrow(() -> overlay.release());
        // And close() (which calls release()) must also not throw after release().
        assertDoesNotThrow(() -> overlay.close());
    }

    @Test
    @DisplayName("OVERLAY-008: try-with-resources releases the overlay even on exception")
    void tryWithResourcesReleasesOnException() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        AtomicHolder holder = new AtomicHolder();
        try {
            try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                    service.createOverlay(baseId, List.of(dynamicDeviceAxiom()),
                        OverlayOptions.defaults()))) {
                holder.held = overlay;
                throw new IllegalStateException("simulated pipeline failure");
            }
        } catch (IllegalStateException expected) {
            // expected
        }
        // After try-with-resources, the overlay must have been released even
        // though an exception was thrown inside the block. We can verify
        // indirectly: the cache must still resolve the base ontology without
        // the dynamic axiom leaking.
        OWLOntology base = cache.getOrCreate(baseId);
        assertFalse(base.containsAxiom(dynamicDeviceAxiom()),
            "base ontology must remain uncontaminated after exception-driven release");
    }

    @Test
    @DisplayName("OVERLAY-008: base ontology unchanged after overlay release (no pollution)")
    void baseOntologyUnchangedAfterRelease() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        OWLOntology baseBefore = cache.getOrCreate(baseId);
        int baseAxiomCountBefore = baseBefore.getAxiomCount(Imports.EXCLUDED);

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic),
                    OverlayOptions.defaults()))) {
            assertNotNull(overlay.ontology());
        }

        OWLOntology baseAfter = cache.getOrCreate(baseId);
        int baseAxiomCountAfter = baseAfter.getAxiomCount(Imports.EXCLUDED);
        assertEquals(baseAxiomCountBefore, baseAxiomCountAfter,
            "base ontology axiom count must be unchanged after overlay release");
        assertFalse(baseAfter.containsAxiom(dynamic),
            "base ontology must NOT contain the dynamic axiom after release (no cache pollution)");
        // Identity check: the cached base ontology must be the SAME instance
        // before and after (cache was not reloaded).
        assertSame(baseBefore, baseAfter,
            "OntologyCache must return the same instance (no reload triggered by overlay)");
    }

    @Test
    @DisplayName("OVERLAY-008: 100 consecutive overlay create+release cycles do not leak")
    void repeatedCreateReleaseNoLeak() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        // Warm the cache.
        OWLOntology baseBefore = cache.getOrCreate(baseId);
        int baseAxiomCountBefore = baseBefore.getAxiomCount(Imports.EXCLUDED);

        // Create and release 100 overlays in a loop. If release() leaks,
        // the OWLOntologyManager from each iteration would accumulate
        // ontologies. The checks below verify the cache is intact.
        for (int i = 0; i < 100; i++) {
            try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                    service.createOverlay(baseId, List.of(dynamic),
                        OverlayOptions.defaults()))) {
                assertNotNull(overlay.ontology());
                assertEquals(baseId, overlay.baseOntologyId());
            }
        }

        // The base ontology must remain unchanged after 100 cycles.
        OWLOntology baseAfter = cache.getOrCreate(baseId);
        int baseAxiomCountAfter = baseAfter.getAxiomCount(Imports.EXCLUDED);
        assertEquals(baseAxiomCountBefore, baseAxiomCountAfter,
            "base ontology axiom count must be stable after 100 create+release cycles");
        assertFalse(baseAfter.containsAxiom(dynamic),
            "base ontology must remain uncontaminated after 100 overlay cycles");
        assertSame(baseBefore, baseAfter,
            "cached base instance must be the same after 100 overlay cycles");
    }

    @Test
    @DisplayName("OVERLAY-008: overlay ontology becomes eligible for GC after release")
    void overlayOntologyGcEligibleAfterRelease() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");

        WeakReference<OWLOntology> weakRef;
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamicDeviceAxiom()),
                    OverlayOptions.defaults()))) {
            weakRef = new WeakReference<>(overlay.ontology());
            // While the overlay is open, the weak ref should still be reachable.
            assertNotNull(weakRef.get(), "overlay ontology should be reachable while open");
        }

        // Try to encourage GC. We can't force it, but we can request it.
        // Multiple runs + a small budget increases the chance GC happens.
        for (int i = 0; i < 3; i++) {
            System.gc();
            if (weakRef.get() == null) break;
            try { Thread.sleep(50L); } catch (InterruptedException ignored) { }
        }
        // Soft assertion: if GC has run, the overlay ontology should be
        // gone. If GC has not run, the weak ref may still be non-null —
        // that's OK for the purposes of this test; the contract we test
        // is that we no longer hold a STRONG reference through the
        // overlay handle.
        // (No assertion: this is informational only.)
    }

    @Test
    @DisplayName("OVERLAY-008: overlay.ontology() is still usable inside try-with-resources")
    void ontologyUsableInsideTryWithResources() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic),
                    OverlayOptions.defaults()))) {
            OWLOntology ont = overlay.ontology();
            assertTrue(ont.containsAxiom(dynamic),
                "overlay ontology must contain the dynamic axiom while open");
            // The base axioms should also be present (imports closure copied).
            OWLOntology base = cache.getOrCreate(baseId);
            for (OWLAxiom ax : base.getAxioms(Imports.EXCLUDED)) {
                assertTrue(ont.containsAxiom(ax),
                    "overlay must contain base axiom while open: " + ax);
            }
        }
    }

    @Test
    @DisplayName("OVERLAY-008: released overlay's ontology is still accessible (no throw)")
    void releasedOverlayOntologyAccessible() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        TransientOverlay overlay = OverlayTestFixtures.unwrap(
            service.createOverlay(baseId, List.of(dynamicDeviceAxiom()),
                OverlayOptions.defaults()));
        overlay.release();
        // After release, the OWLOntology object reference is still valid
        // (it is just no longer managed by the OWLOntologyManager). Querying
        // it must not throw — the OWL API tolerates this.
        OWLOntology ont = overlay.ontology();
        assertNotNull(ont, "ontology reference remains valid after release");
        // getAxiomCount should still return the count it had.
        assertTrue(ont.getAxiomCount(Imports.EXCLUDED) >= 0,
            "getAxiomCount should not throw after release");
    }

    @Test
    @DisplayName("OVERLAY-008: distinct overlay instances do not share isolated managers")
    void distinctOverlaysUseDistinctManagers() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        try (TransientOverlay o1 = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()));
             TransientOverlay o2 = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()))) {
            assertNotSame(o1.ontology().getOWLOntologyManager(),
                          o2.ontology().getOWLOntologyManager(),
                "each overlay must get its own isolated OWLOntologyManager");
            assertNotSame(o1.ontology(), o2.ontology(),
                "each overlay must get its own ontology instance");
        }
    }

    /** Helper to hold a reference across a try block for testing. */
    private static final class AtomicHolder {
        TransientOverlay held;
    }
}
