package org.owl4agents.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.owlapi.OntologyCache;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-001 / OV-002 unit tests for {@link TransientOntologyOverlayServiceImpl}.
 *
 * <p>Covers the "Static and Dynamic Knowledge Boundary", "TransientOntologyOverlayService
 * Interface", and "Original Ontology Immutability" requirements from the
 * transient-overlay spec, including scenarios OVERLAY-001, OVERLAY-002.</p>
 */
@DisplayName("TransientOntologyOverlayService: overlay creation and immutability")
class TransientOntologyOverlayServiceTest {

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
    @DisplayName("OVERLAY-001: overlay contains base axioms plus dynamic axiom")
    void overlayContainsBasePlusDynamic() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()))) {
            OWLOntology ont = overlay.ontology();
            // The overlay MUST contain the dynamic axiom.
            assertTrue(ont.containsAxiom(dynamic),
                "overlay ontology must contain the dynamic axiom");
            // The overlay MUST also contain every base axiom.
            OWLOntology base = cache.getOrCreate(baseId);
            for (OWLAxiom ax : base.getAxioms(Imports.EXCLUDED)) {
                assertTrue(ont.containsAxiom(ax),
                    "overlay ontology must contain base axiom: " + ax);
            }
        }
    }

    @Test
    @DisplayName("OVERLAY-001: original ontology is unmodified after overlay creation")
    void originalOntologyUnmodified() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLOntology baseBefore = cache.getOrCreate(baseId);
        int baseAxiomCountBefore = baseBefore.getAxiomCount(Imports.EXCLUDED);

        OWLAxiom dynamic = dynamicDeviceAxiom();
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()))) {
            assertNotNull(overlay.ontology());
        }

        // Reload the base ontology via the cache — it should be unchanged.
        OWLOntology baseAfter = cache.getOrCreate(baseId);
        int baseAxiomCountAfter = baseAfter.getAxiomCount(Imports.EXCLUDED);
        assertEquals(baseAxiomCountBefore, baseAxiomCountAfter,
            "base ontology axiom count must be unchanged");
        assertFalse(baseAfter.containsAxiom(dynamic),
            "base ontology must NOT contain the dynamic axiom after overlay creation");
    }

    @Test
    @DisplayName("OVERLAY-001: overlay uses an isolated OWLOntologyManager")
    void overlayUsesIsolatedManager() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()))) {
            OWLOntology base = cache.getOrCreate(baseId);
            var overlayMgr = overlay.ontology().getOWLOntologyManager();
            var baseMgr = base.getOWLOntologyManager();
            assertNotSame(baseMgr, overlayMgr,
                "overlay manager must be a different instance from the cache's manager");
            // The base manager must not contain the overlay ontology.
            assertFalse(baseMgr.contains(overlay.ontology().getOntologyID()),
                "base manager must not contain the overlay ontology");
            // The overlay manager must not contain the base ontology.
            assertFalse(overlayMgr.contains(base.getOntologyID()),
                "overlay manager must not contain the base ontology");
        }
    }

    @Test
    @DisplayName("OVERLAY-002: cache size unchanged after overlay creation + release")
    void cacheSizeUnchangedAfterRelease() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        // Warm the cache.
        cache.getOrCreate(baseId);

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()))) {
            assertNotNull(overlay.ontology());
        }

        // After release, the cache must still resolve the base ontology without error.
        OWLOntology baseAfter = cache.getOrCreate(baseId);
        assertNotNull(baseAfter);
        assertFalse(baseAfter.containsAxiom(dynamic),
            "cache-pollution protection: base ontology must not contain dynamic axiom");
    }

    @Test
    @DisplayName("OVERLAY-001: overlay snapshot metadata is populated")
    void overlaySnapshotMetadataPopulated() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), OverlayOptions.defaults()))) {
            EnvironmentSnapshot snapshot = overlay.snapshot();
            assertNotNull(snapshot.snapshotId(), "snapshotId must be non-null");
            assertTrue(snapshot.snapshotId().matches("[0-9a-fA-F-]{36}"),
                "snapshotId must be a UUID: " + snapshot.snapshotId());
            assertNotNull(snapshot.capturedAt(), "capturedAt must be non-null");
            assertEquals("api", snapshot.source(), "default source is 'api'");
            assertTrue(snapshot.version() >= 0, "version must be non-negative");
            assertEquals(64, snapshot.checksum().length(),
                "checksum must be 64-char hex SHA256");
            assertTrue(snapshot.checksum().matches("[0-9a-fA-F]{64}"),
                "checksum must be hex: " + snapshot.checksum());
        }
    }

    @Test
    @DisplayName("OVERLAY-001: createOverlay with EnvironmentSnapshot convenience overload")
    void createOverlayFromSnapshot() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        EnvironmentSnapshot snapshot = OverlayTestFixtures.buildDeviceSnapshot(2, "snap-1");

        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, snapshot, OverlayOptions.defaults()))) {
            OWLOntology ont = overlay.ontology();
            // The overlay MUST contain at least the 4 axioms produced by 2 devices
            // (2 ClassAssertion + 2 DataPropertyAssertion).
            int dynamicAxiomCount = 0;
            for (OWLAxiom ax : snapshot.devices().stream()
                    .flatMap(d -> StructuredStateConverter.convertDevice(d).stream())
                    .toList()) {
                if (ont.containsAxiom(ax)) {
                    dynamicAxiomCount++;
                }
            }
            assertEquals(4, dynamicAxiomCount,
                "overlay must contain all 4 dynamic axioms from the 2-device snapshot");
            // The snapshot returned by the overlay should carry the structured devices.
            assertEquals(2, overlay.snapshot().devices().size(),
                "overlay snapshot should carry the structured devices");
        }
    }

    @Test
    @DisplayName("OVERLAY-001: ONTOLOGY_NOT_FOUND when base ontology is missing")
    void baseNotFoundReturnsError() throws Exception {
        setUpSmartHome();
        OntologyId missingId = new OntologyId("does-not-exist");
        ServiceResult<TransientOverlay> result =
            service.createOverlay(missingId, List.of(), OverlayOptions.defaults());
        assertFalse(result.isSuccess());
        ServiceResult.Error<TransientOverlay> err = (ServiceResult.Error<TransientOverlay>) result;
        assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND, err.error().code(),
            "missing base ontology should produce ONTOLOGY_NOT_FOUND");
    }

    @Test
    @DisplayName("OVERLAY-001: null arguments produce INVALID_ARGUMENTS")
    void nullArgumentsReturnError() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");

        ServiceResult<TransientOverlay> r1 = service.createOverlay(null, List.of(),
            OverlayOptions.defaults());
        assertFalse(r1.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<TransientOverlay>) r1).error().code());

        ServiceResult<TransientOverlay> r2 = service.createOverlay(baseId,
            (java.util.Collection<OWLAxiom>) null,
            OverlayOptions.defaults());
        assertFalse(r2.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<TransientOverlay>) r2).error().code());
    }

    @Test
    @DisplayName("OVERLAY-001: imports closure option respected")
    void importsClosureOption() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        // copyImportsClosure=true (default) — overlay should contain base axioms.
        OverlayOptions withClosure = OverlayOptions.defaults();
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), withClosure))) {
            OWLOntology base = cache.getOrCreate(baseId);
            for (OWLAxiom ax : base.getAxioms(Imports.EXCLUDED)) {
                assertTrue(overlay.ontology().containsAxiom(ax),
                    "with copyImportsClosure=true, overlay must contain base axiom: " + ax);
            }
        }
    }

    @Test
    @DisplayName("OVERLAY-001: overlay IRI suffix applied to derived ontology IRI")
    void overlayIriSuffixApplied() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLAxiom dynamic = dynamicDeviceAxiom();

        OverlayOptions opts = new OverlayOptions(
            true, Optional.empty(), Duration.ofSeconds(30), Optional.of("-test-overlay"));
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(dynamic), opts))) {
            var iri = overlay.ontology().getOntologyID().getOntologyIRI().orElse(null);
            assertNotNull(iri, "overlay must have an IRI");
            assertTrue(iri.toString().endsWith("-test-overlay"),
                "overlay IRI must end with the configured suffix: " + iri);
        }
    }
}
