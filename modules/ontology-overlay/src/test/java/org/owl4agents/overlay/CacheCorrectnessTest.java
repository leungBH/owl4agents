package org.owl4agents.overlay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.owlapi.OntologyCache;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 D16 / REL-003 (task 8.7): Cache correctness test matrix.
 *
 * <p>Implements the 6 scenarios mandated by the cache-governance spec
 * "Cache correctness test scenarios":</p>
 * <ol>
 *   <li><b>Snapshot isolation</b> — two overlays with different snapshots
 *       are distinct; no axiom leakage.</li>
 *   <li><b>Cache invalidation</b> — invalidating the cache then creating
 *       a new overlay reflects the reloaded base ontology.</li>
 *   <li><b>Concurrent calls</b> — 10 threads concurrently create
 *       overlays; each gets its own instance, no leakage.</li>
 *   <li><b>Ontology reload</b> — modifying the base file on disk then
 *       creating a new overlay reflects the changes; old overlay
 *       unaffected.</li>
 *   <li><b>Stale state</b> — a snapshot older than the TTL is rejected
 *       with SNAPSHOT_EXPIRED; the TTL is configurable via
 *       {@code owl4agents.overlay.snapshot.ttl.seconds}.</li>
 *   <li><b>Same ontology different households</b> — two households with
 *       distinct ontologyIds produce distinct cache entries; no
 *       cross-contamination.</li>
 * </ol>
 */
@DisplayName("v0.8.7 D16 (task 8.7): Cache correctness test matrix")
class CacheCorrectnessTest {

    @TempDir
    Path tempDir;

    private OntologyCache cache;
    private TransientOntologyOverlayService service;

    @BeforeEach
    void setUp() throws Exception {
        cache = OverlayTestFixtures.materializeSmartHome(tempDir, "default");
        service = new TransientOntologyOverlayServiceImpl(cache);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(TocTouGuard.TTL_SYSTEM_PROPERTY);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. Snapshot isolation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-1: snapshot isolation — distinct snapshots produce distinct overlays")
    void snapshotIsolation_distinctOverlays() throws Exception {
        OntologyId baseId = new OntologyId("smart-home");

        // Build two distinct dynamic axiom sets (different device IRIs).
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLClass smartPlug = df.getOWLClass(
            IRI.create(OverlayTestFixtures.SMART_PLUG_CLASS_IRI));

        var devA = df.getOWLNamedIndividual(
            IRI.create(OverlayTestFixtures.SMART_HOME_NS + "device-A"));
        var devB = df.getOWLNamedIndividual(
            IRI.create(OverlayTestFixtures.SMART_HOME_NS + "device-B"));
        var axiomA = df.getOWLClassAssertionAxiom(smartPlug, devA);
        var axiomB = df.getOWLClassAssertionAxiom(smartPlug, devB);

        try (TransientOverlay overlayA = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(axiomA), OverlayOptions.defaults()));
             TransientOverlay overlayB = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(axiomB), OverlayOptions.defaults()))) {

            // Overlays must be distinct object references.
            assertNotSame(overlayA, overlayB,
                "two overlays from different snapshots must be distinct objects");
            assertNotSame(overlayA.ontology(), overlayB.ontology(),
                "two overlay ontologies must be distinct objects");
            assertNotEquals(overlayA.snapshotId(), overlayB.snapshotId(),
                "snapshotIds must be distinct");

            // No axiom leakage: A's axiom must not appear in B, and vice versa.
            assertTrue(overlayA.ontology().containsAxiom(axiomA),
                "overlay A must contain axiom A");
            assertFalse(overlayA.ontology().containsAxiom(axiomB),
                "overlay A must NOT contain axiom B (no leakage)");
            assertTrue(overlayB.ontology().containsAxiom(axiomB),
                "overlay B must contain axiom B");
            assertFalse(overlayB.ontology().containsAxiom(axiomA),
                "overlay B must NOT contain axiom A (no leakage)");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. Cache invalidation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-2: cache invalidation — new overlay after invalidate reflects reloaded base")
    void cacheInvalidation_newOverlayReflectsReloadedBase() throws Exception {
        OntologyId baseId = new OntologyId("smart-home");

        // Step 1: Create an overlay from the original base.
        OWLOntology baseBefore = cache.getOrCreate(baseId);
        int baseAxiomCountBefore = baseBefore.getAxiomCount(Imports.EXCLUDED);

        try (TransientOverlay overlayBefore = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(), OverlayOptions.defaults()))) {
            assertEquals(baseAxiomCountBefore,
                overlayBefore.ontology().getAxiomCount(Imports.EXCLUDED),
                "overlay (no dynamic axioms) must match base axiom count");
        }

        // Step 2: Modify the base ontology file on disk — add a new class.
        Path ontologyFile = tempDir.resolve("default")
            .resolve("ontologies").resolve("smart-home")
            .resolve("canonical").resolve("ontology.owl");
        assertTrue(Files.exists(ontologyFile),
            "base ontology file must exist on disk");

        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology modified = mgr.loadOntologyFromOntologyDocument(ontologyFile.toFile());
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLClass newClass = df.getOWLClass(
            IRI.create(OverlayTestFixtures.SMART_HOME_NS + "NewDevice"));
        OWLDeclarationAxiom newDecl = df.getOWLDeclarationAxiom(newClass);
        modified.addAxiom(newDecl);
        try (var out = Files.newOutputStream(ontologyFile)) {
            mgr.saveOntology(modified, out);
        }

        // Ensure the file mtime advances (some filesystems have 1s granularity).
        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}

        // Step 3: Invalidate the cache entry — forces reload on next getOrCreate.
        cache.invalidate(baseId);

        // Step 4: Create a new overlay — should reflect the reloaded base.
        try (TransientOverlay overlayAfter = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, List.of(), OverlayOptions.defaults()))) {
            assertTrue(overlayAfter.ontology().containsAxiom(newDecl),
                "new overlay after invalidation must contain the new class declaration");
            assertTrue(overlayAfter.ontology().getAxiomCount(Imports.EXCLUDED)
                    > baseAxiomCountBefore,
                "new overlay must have more axioms than the original base");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. Concurrent calls
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-3: concurrent calls — 10 threads get distinct overlays, no leakage")
    void concurrentCalls_tenThreadsNoLeakage() throws Exception {
        OntologyId baseId = new OntologyId("smart-home");
        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);
        // Array indexed by thread ID so we can verify per-thread ownership.
        TransientOverlay[] overlaysByThread = new TransientOverlay[threadCount];
        List<OWLAxiom> axiomPerThread = new ArrayList<>(threadCount);

        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLClass smartPlug = df.getOWLClass(
            IRI.create(OverlayTestFixtures.SMART_PLUG_CLASS_IRI));

        for (int i = 0; i < threadCount; i++) {
            var dev = df.getOWLNamedIndividual(
                IRI.create(OverlayTestFixtures.SMART_HOME_NS + "thread-dev-" + i));
            axiomPerThread.add(df.getOWLClassAssertionAxiom(smartPlug, dev));
        }

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final OWLAxiom axiom = axiomPerThread.get(idx);
            tasks.add(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(30, TimeUnit.SECONDS),
                        "thread " + idx + " timed out waiting for start signal");
                    ServiceResult<TransientOverlay> result =
                        service.createOverlay(baseId, List.of(axiom), OverlayOptions.defaults());
                    if (!result.isSuccess()) {
                        errors.incrementAndGet();
                        return;
                    }
                    overlaysByThread[idx] =
                        ((ServiceResult.Success<TransientOverlay>) result).data();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        for (Runnable t : tasks) pool.submit(t);
        ready.await(30, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
            "all threads must complete within 60s");

        assertEquals(0, errors.get(),
            "no thread should produce an error");

        // Verify each overlay contains only its own dynamic axiom (no leakage).
        for (int i = 0; i < threadCount; i++) {
            TransientOverlay overlay = overlaysByThread[i];
            assertNotNull(overlay, "thread " + i + " overlay must not be null");
            assertTrue(overlay.ontology().containsAxiom(axiomPerThread.get(i)),
                "thread " + i + " overlay must contain its own dynamic axiom");
            for (int j = 0; j < threadCount; j++) {
                if (j == i) continue;
                assertFalse(overlay.ontology().containsAxiom(axiomPerThread.get(j)),
                    "thread " + i + " overlay must NOT contain thread " + j + "'s axiom (no leakage)");
            }
            overlay.release();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Ontology reload
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-4: ontology reload — old overlay unaffected, new overlay reflects changes")
    void ontologyReload_oldUnaffectedNewReflectsChanges() throws Exception {
        OntologyId baseId = new OntologyId("smart-home");

        // Step 1: Create an overlay from the original base.
        TransientOverlay overlayV1 = OverlayTestFixtures.unwrap(
            service.createOverlay(baseId, List.of(), OverlayOptions.defaults()));
        int v1AxiomCount = overlayV1.ontology().getAxiomCount(Imports.EXCLUDED);

        // Step 2: Modify the base ontology file on disk.
        Path ontologyFile = tempDir.resolve("default")
            .resolve("ontologies").resolve("smart-home")
            .resolve("canonical").resolve("ontology.owl");

        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology modified = mgr.loadOntologyFromOntologyDocument(ontologyFile.toFile());
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLClass newClass = df.getOWLClass(
            IRI.create(OverlayTestFixtures.SMART_HOME_NS + "ReloadedDevice"));
        OWLDeclarationAxiom newDecl = df.getOWLDeclarationAxiom(newClass);
        modified.addAxiom(newDecl);
        try (var out = Files.newOutputStream(ontologyFile)) {
            mgr.saveOntology(modified, out);
        }
        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}

        // Step 3: Invalidate the cache to force reload.
        cache.invalidate(baseId);

        // Step 4: Create a new overlay.
        TransientOverlay overlayV2 = OverlayTestFixtures.unwrap(
            service.createOverlay(baseId, List.of(), OverlayOptions.defaults()));

        // Step 5: Verify the new overlay reflects the changes.
        assertTrue(overlayV2.ontology().containsAxiom(newDecl),
            "new overlay must contain the reloaded class declaration");
        assertTrue(overlayV2.ontology().getAxiomCount(Imports.EXCLUDED) > v1AxiomCount,
            "new overlay must have more axioms than v1");

        // Step 6: Verify the old overlay is unaffected (still reflects v1).
        assertFalse(overlayV1.ontology().containsAxiom(newDecl),
            "old overlay must NOT contain the reloaded class declaration");
        assertEquals(v1AxiomCount,
            overlayV1.ontology().getAxiomCount(Imports.EXCLUDED),
            "old overlay axiom count must be unchanged");

        overlayV1.release();
        overlayV2.release();
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. Stale state (TTL expiry + configurability)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-5: stale state — snapshot older than TTL is rejected with SNAPSHOT_EXPIRED")
    void staleState_expiredSnapshotRejected() {
        // Use a virtual clock so the test is deterministic.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-stale", clock.instant(), "mcp", 0L, "checksum");
        // Advance past the TTL.
        clock.advance(Duration.ofSeconds(6));

        TocTouResult result = guard.checkSnapshot(snapshot);
        assertEquals(TocTouDecision.SNAPSHOT_EXPIRED, result.decision(),
            "snapshot older than TTL must be SNAPSHOT_EXPIRED: " + result.reason());
        assertTrue(result.ageMillis() > 5000,
            "age must exceed the 5s TTL");
    }

    @Test
    @DisplayName("CC-5b: TTL is configurable via owl4agents.overlay.snapshot.ttl.seconds")
    void ttlIsConfigurableViaSystemProperty() {
        // Set the system property to 10 seconds.
        System.setProperty(TocTouGuard.TTL_SYSTEM_PROPERTY, "10");
        TocTouGuard guard = new TocTouGuard();
        assertEquals(Duration.ofSeconds(10), guard.ttl(),
            "TTL must be 10s when system property is set to 10");

        // Verify a snapshot at 7s is accepted (would be rejected with default 5s).
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard10 = new TocTouGuard(
            TocTouGuard.DEFAULT_TTL, clock); // explicit TTL ignores system property
        // Use the system-property-aware constructor instead.
        TocTouGuard guardViaProperty = new TocTouGuard();
        assertEquals(Duration.ofSeconds(10), guardViaProperty.ttl());

        // A snapshot at 7s should be VALID with TTL=10s.
        StateSnapshotId snap7s = new StateSnapshotId(
            "snap-7s",
            Instant.parse("2026-07-21T00:00:00Z"),
            "mcp", 0L, "checksum");
        TocTouResult result = new TocTouGuard(Duration.ofSeconds(10),
            new MutableClock(Instant.parse("2026-07-21T00:00:07Z"))).checkSnapshot(snap7s);
        assertEquals(TocTouDecision.VALID, result.decision(),
            "snapshot at 7s must be VALID when TTL=10s");

        // A snapshot at 11s should be EXPIRED with TTL=10s.
        TocTouResult result11 = new TocTouGuard(Duration.ofSeconds(10),
            new MutableClock(Instant.parse("2026-07-21T00:00:11Z"))).checkSnapshot(snap7s);
        assertEquals(TocTouDecision.SNAPSHOT_EXPIRED, result11.decision(),
            "snapshot at 11s must be SNAPSHOT_EXPIRED when TTL=10s");
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. Same ontology different households
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-6: same ontology different households — distinct cache entries, no leakage")
    void sameOntologyDifferentHouseholds_isolated() throws Exception {
        // Materialize two ontology IDs from the same base TBox.
        OWLOntology tbox = OverlayTestFixtures.buildSmartHomeTBox();
        OntologyCache multiCache = OverlayTestFixtures.materializeWorkspace(
            tempDir, "default", "smart-home-household-A", tbox);
        // Re-use the same TBox for household B but under a different ID.
        Path ontPathB = tempDir.resolve("default")
            .resolve("ontologies").resolve("smart-home-household-B")
            .resolve("canonical").resolve("ontology.owl");
        Files.createDirectories(ontPathB.getParent());
        try (var out = Files.newOutputStream(ontPathB)) {
            tbox.getOWLOntologyManager().saveOntology(tbox, out);
        }

        OntologyId idA = new OntologyId("smart-home-household-A");
        OntologyId idB = new OntologyId("smart-home-household-B");

        // Create distinct dynamic axioms per household.
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLClass smartPlug = df.getOWLClass(
            IRI.create(OverlayTestFixtures.SMART_PLUG_CLASS_IRI));

        var devA = df.getOWLNamedIndividual(
            IRI.create(OverlayTestFixtures.SMART_HOME_NS + "household-A-device"));
        var devB = df.getOWLNamedIndividual(
            IRI.create(OverlayTestFixtures.SMART_HOME_NS + "household-B-device"));
        var axiomA = df.getOWLClassAssertionAxiom(smartPlug, devA);
        var axiomB = df.getOWLClassAssertionAxiom(smartPlug, devB);

        TransientOntologyOverlayService multiService =
            new TransientOntologyOverlayServiceImpl(multiCache);

        try (TransientOverlay overlayA = OverlayTestFixtures.unwrap(
                multiService.createOverlay(idA, List.of(axiomA), OverlayOptions.defaults()));
             TransientOverlay overlayB = OverlayTestFixtures.unwrap(
                multiService.createOverlay(idB, List.of(axiomB), OverlayOptions.defaults()))) {

            // Distinct snapshotIds.
            assertNotEquals(overlayA.snapshotId(), overlayB.snapshotId(),
                "households must have distinct snapshotIds");

            // No cross-contamination.
            assertTrue(overlayA.ontology().containsAxiom(axiomA),
                "household A overlay must contain its own axiom");
            assertFalse(overlayA.ontology().containsAxiom(axiomB),
                "household A overlay must NOT contain household B's axiom");
            assertTrue(overlayB.ontology().containsAxiom(axiomB),
                "household B overlay must contain its own axiom");
            assertFalse(overlayB.ontology().containsAxiom(axiomA),
                "household B overlay must NOT contain household A's axiom");

            // The base ontologies in the cache must be distinct entries.
            OWLOntology baseA = multiCache.getOrCreate(idA);
            OWLOntology baseB = multiCache.getOrCreate(idB);
            assertNotSame(baseA, baseB,
                "cache entries for different households must be distinct OWLOntology instances");
            assertFalse(baseA.containsAxiom(axiomA),
                "base ontology A must not contain dynamic axiom A (overlay is isolated)");
            assertFalse(baseB.containsAxiom(axiomB),
                "base ontology B must not contain dynamic axiom B (overlay is isolated)");
        }

        // Invalidating household A must not affect household B.
        multiCache.invalidate(idA);
        OWLOntology baseBAfter = multiCache.getOrCreate(idB);
        assertNotNull(baseBAfter,
            "household B's cache entry must remain after invalidating household A");
    }

    // ──────────────────────────────────────────────────────────────────
    // Helper: a mutable Clock for deterministic TTL tests
    // ──────────────────────────────────────────────────────────────────

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant start) { this.instant = start; }
        void advance(Duration d) { this.instant = instant.plus(d); }
        @Override public Instant instant() { return instant; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    }
}
