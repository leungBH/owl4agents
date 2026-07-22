package org.owl4agents.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.OntologyCache;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-006 performance tests for the overlay pipeline.
 *
 * <p>Measures overlay creation latency at device counts of
 * 10 / 50 / 100 / 200 / 500 (matching design Test Matrix
 * OVERLAY-001 ~ OVERLAY-005), plus a concurrency test
 * (OVERLAY-006 ~ OVERLAY-011) that exercises parallel overlay
 * creation + release to verify thread safety and resource bounds.</p>
 *
 * <p><strong>Note:</strong> these are not benchmark tests — they enforce
 * loose upper bounds (e.g. 5s for 500 devices) that catch regressions
 * rather than measure absolute performance.</p>
 */
@DisplayName("Overlay performance: 10/50/100/200/500 devices + concurrency")
class OverlayPerformanceTest {

    @TempDir
    Path tempDir;

    private TransientOntologyOverlayService service;
    private OntologyCache cache;

    private void setUpSmartHome() throws Exception {
        cache = OverlayTestFixtures.materializeSmartHome(tempDir, "default");
        service = new TransientOntologyOverlayServiceImpl(cache);
    }

    private long measureOverlayCreation(int deviceCount) throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        List<OWLAxiom> dynamicAxioms = OverlayTestFixtures.buildDeviceAxioms(deviceCount);
        OverlayOptions opts = OverlayOptions.defaults();

        // Warm up: one overlay creation before timing.
        try (TransientOverlay warmup = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, dynamicAxioms, opts))) {
            assertNotNull(warmup.ontology());
        }

        // Timed run.
        long startNanos = System.nanoTime();
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, dynamicAxioms, opts))) {
            OWLOntology ont = overlay.ontology();
            // Sanity check: the overlay MUST contain all dynamic axioms.
            for (OWLAxiom ax : dynamicAxioms) {
                assertTrue(ont.containsAxiom(ax),
                    "overlay must contain dynamic axiom for device count " + deviceCount);
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        return elapsedNanos / 1_000_000L; // ms
    }

    @Test
    @DisplayName("OVERLAY-001: 10 devices — overlay creation under 1s")
    void overlayCreation10Devices() throws Exception {
        long elapsedMs = measureOverlayCreation(10);
        assertTrue(elapsedMs < 1000,
            "10-device overlay creation should be under 1s, was " + elapsedMs + "ms");
        System.out.println("[OVERLAY-001] 10 devices: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("OVERLAY-002: 50 devices — overlay creation under 1s")
    void overlayCreation50Devices() throws Exception {
        long elapsedMs = measureOverlayCreation(50);
        assertTrue(elapsedMs < 1000,
            "50-device overlay creation should be under 1s, was " + elapsedMs + "ms");
        System.out.println("[OVERLAY-002] 50 devices: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("OVERLAY-003: 100 devices — overlay creation under 2s")
    void overlayCreation100Devices() throws Exception {
        long elapsedMs = measureOverlayCreation(100);
        assertTrue(elapsedMs < 2000,
            "100-device overlay creation should be under 2s, was " + elapsedMs + "ms");
        System.out.println("[OVERLAY-003] 100 devices: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("OVERLAY-004: 200 devices — overlay creation under 3s")
    void overlayCreation200Devices() throws Exception {
        long elapsedMs = measureOverlayCreation(200);
        assertTrue(elapsedMs < 3000,
            "200-device overlay creation should be under 3s, was " + elapsedMs + "ms");
        System.out.println("[OVERLAY-004] 200 devices: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("OVERLAY-005: 500 devices — overlay creation under 5s")
    void overlayCreation500Devices() throws Exception {
        long elapsedMs = measureOverlayCreation(500);
        assertTrue(elapsedMs < 5000,
            "500-device overlay creation should be under 5s, was " + elapsedMs + "ms");
        System.out.println("[OVERLAY-005] 500 devices: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("OVERLAY-009: overlay axiom count scales linearly with device count")
    void overlayAxiomCountScalesLinearly() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        OWLOntology base = cache.getOrCreate(baseId);
        int baseAxiomCount = base.getAxiomCount(Imports.EXCLUDED);

        // Each device contributes 2 axioms (ClassAssertion + DataPropertyAssertion).
        for (int deviceCount : new int[]{10, 50, 100}) {
            List<OWLAxiom> dynamicAxioms = OverlayTestFixtures.buildDeviceAxioms(deviceCount);
            try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                    service.createOverlay(baseId, dynamicAxioms, OverlayOptions.defaults()))) {
                int overlayAxiomCount = overlay.ontology().getAxiomCount(Imports.EXCLUDED);
                int expected = baseAxiomCount + (deviceCount * 2);
                assertEquals(expected, overlayAxiomCount,
                    "overlay axiom count must equal base + (2 * deviceCount) for " + deviceCount + " devices");
            }
        }
    }

    @Test
    @DisplayName("OVERLAY-010: concurrent overlay creation — 10 threads, 10 devices each, no errors")
    void concurrentOverlayCreationNoErrors() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        int threadCount = 10;
        int devicesPerThread = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicReference<String> firstError = new AtomicReference<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                final int threadIdx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        List<OWLAxiom> axioms =
                            OverlayTestFixtures.buildDeviceAxioms(devicesPerThread);
                        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                                service.createOverlay(baseId, axioms, OverlayOptions.defaults()))) {
                            // Verify the overlay contains the dynamic axioms we added.
                            OWLOntology ont = overlay.ontology();
                            for (OWLAxiom ax : axioms) {
                                if (!ont.containsAxiom(ax)) {
                                    firstError.compareAndSet(null,
                                        "thread " + threadIdx + ": overlay missing dynamic axiom");
                                    errors.incrementAndGet();
                                    return;
                                }
                            }
                            success.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        firstError.compareAndSet(null,
                            "thread " + threadIdx + ": " + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
                        errors.incrementAndGet();
                    }
                });
            }

            // Wait for all threads to be ready, then release them all at once.
            assertTrue(ready.await(5, TimeUnit.SECONDS), "all threads should be ready within 5s");
            start.countDown();

            // Wait for completion.
            executor.shutdown();
            assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS),
                "all threads should complete within 60s");
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }

        assertEquals(0, errors.get(),
            "no errors expected in concurrent overlay creation, but got: " + firstError.get());
        assertEquals(threadCount, success.get(),
            "all " + threadCount + " concurrent overlays should succeed");
    }

    @Test
    @DisplayName("OVERLAY-011: concurrent overlay creation does not pollute the base cache entry")
    void concurrentOverlayDoesNotPolluteBase() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");

        // Capture the base ontology's identity and axiom count before concurrent overlays.
        OWLOntology baseBefore = cache.getOrCreate(baseId);
        int baseAxiomCountBefore = baseBefore.getAxiomCount(Imports.EXCLUDED);

        int threadCount = 8;
        int devicesPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        List<OWLAxiom> axioms =
                            OverlayTestFixtures.buildDeviceAxioms(devicesPerThread);
                        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                                service.createOverlay(baseId, axioms, OverlayOptions.defaults()))) {
                            // Touch the overlay.
                            assertNotNull(overlay.ontology());
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS),
                "all concurrent overlays should complete within 60s");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, errors.get(), "no errors expected during concurrent overlays");

        // After all overlays are released, the base ontology must be unchanged.
        OWLOntology baseAfter = cache.getOrCreate(baseId);
        assertSame(baseBefore, baseAfter,
            "cached base instance must be the same after concurrent overlays");
        assertEquals(baseAxiomCountBefore, baseAfter.getAxiomCount(Imports.EXCLUDED),
            "base axiom count must be unchanged after concurrent overlays");

        // None of the dynamic axioms should have leaked into the base.
        List<OWLAxiom> sampleDynamicAxioms = OverlayTestFixtures.buildDeviceAxioms(devicesPerThread);
        for (OWLAxiom ax : sampleDynamicAxioms) {
            assertFalse(baseAfter.containsAxiom(ax),
                "base must not contain any dynamic axiom after concurrent overlays: " + ax);
        }
    }

    @Test
    @DisplayName("OVERLAY-006/010: heap usage stays bounded for 500-device overlay")
    void heapUsageBounded() throws Exception {
        setUpSmartHome();
        OntologyId baseId = new OntologyId("smart-home");
        List<OWLAxiom> axioms = OverlayTestFixtures.buildDeviceAxioms(500);

        // Force a baseline GC and record used heap.
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        // Create and immediately release an overlay.
        try (TransientOverlay overlay = OverlayTestFixtures.unwrap(
                service.createOverlay(baseId, axioms, OverlayOptions.defaults()))) {
            assertNotNull(overlay.ontology());
            // While the overlay is open, heap usage may increase, but should
            // remain bounded (under 200 MB delta for 500 devices).
            long heapDuring = rt.totalMemory() - rt.freeMemory();
            long deltaDuring = heapDuring - heapBefore;
            assertTrue(deltaDuring < 200L * 1024 * 1024,
                "heap delta during overlay lifecycle should be under 200MB, was "
                    + (deltaDuring / (1024 * 1024)) + "MB");
        }

        // After release + GC, heap usage should return close to baseline.
        System.gc();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long deltaAfter = heapAfter - heapBefore;
        // Allow some slack (the JVM may not return memory immediately).
        assertTrue(deltaAfter < 100L * 1024 * 1024,
            "heap delta after overlay release should be under 100MB, was "
                + (deltaAfter / (1024 * 1024)) + "MB");
    }
}
