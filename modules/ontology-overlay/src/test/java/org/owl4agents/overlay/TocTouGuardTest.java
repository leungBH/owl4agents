package org.owl4agents.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-005 / D11 unit tests for {@link TocTouGuard}.
 *
 * <p>Verifies the three TOCTOU protections:</p>
 * <ol>
 *   <li>Snapshot TTL: snapshots older than 5s (default) are rejected
 *       with {@link TocTouDecision#SNAPSHOT_EXPIRED}.</li>
 *   <li>State hash verification: a checksum mismatch between the
 *       snapshot metadata and the recomputed axiom checksum produces
 *       {@link TocTouDecision#RETRY_VALIDATION}.</li>
 *   <li>Snapshot version tracking: the guard records the latest version
 *       seen per source and exposes {@link TocTouGuard#latestVersionFor(String)}.</li>
 * </ol>
 */
@DisplayName("TocTouGuard: TOCTOU protection (TTL, checksum, version)")
class TocTouGuardTest {

    private static final String DEVICE_IRI = "https://owl4agents.org/test/d1";
    private static final String DEVICE_TYPE_IRI = "https://owl4agents.org/test/SmartPlug";

    /** A virtual clock so tests can advance time deterministically. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant start) { this.instant = start; }
        void advance(Duration d) { this.instant = instant.plus(d); }
        void setTo(Instant i) { this.instant = i; }
        @Override public Instant instant() { return instant; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    }

    private OWLAxiom deviceClassAssertion() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(DEVICE_IRI));
        OWLClass cls = df.getOWLClass(IRI.create(DEVICE_TYPE_IRI));
        return df.getOWLClassAssertionAxiom(cls, ind);
    }

    private OWLAxiom deviceStateAssertion(String state) {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(DEVICE_IRI));
        OWLDataProperty hasState =
            df.getOWLDataProperty(StructuredStateConverter.HAS_STATE_IRI);
        return df.getOWLDataPropertyAssertionAxiom(hasState, ind, df.getOWLLiteral(state));
    }

    @Test
    @DisplayName("OVERLAY-007: fresh snapshot within TTL returns VALID")
    void freshSnapshotIsValid() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-1", clock.instant(), "mcp", 0L, "checksum-will-be-checked");
        TocTouResult result = guard.checkSnapshot(snapshot);
        assertTrue(result.isValid(), "fresh snapshot must be VALID: " + result.reason());
        assertEquals(TocTouDecision.VALID, result.decision());
        assertTrue(result.ageMillis() <= 1000, "age should be small");
    }

    @Test
    @DisplayName("OVERLAY-007: snapshot older than TTL returns SNAPSHOT_EXPIRED")
    void expiredSnapshotRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-2", clock.instant(), "mcp", 0L, "checksum");
        // Advance clock past TTL.
        clock.advance(Duration.ofSeconds(6));

        TocTouResult result = guard.checkSnapshot(snapshot);
        assertEquals(TocTouDecision.SNAPSHOT_EXPIRED, result.decision(),
            "snapshot older than TTL must be SNAPSHOT_EXPIRED: " + result.reason());
        assertTrue(result.ageMillis() > 5000, "age must exceed TTL");
    }

    @Test
    @DisplayName("OVERLAY-007: snapshot exactly at TTL boundary is still VALID")
    void snapshotAtTtlBoundaryValid() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-3", clock.instant(), "mcp", 0L, "checksum");
        // Advance clock exactly to the TTL (5000ms). Age == TTL is still valid.
        clock.advance(Duration.ofMillis(5000));

        TocTouResult result = guard.checkSnapshot(snapshot);
        assertEquals(TocTouDecision.VALID, result.decision(),
            "age == TTL must still be VALID (strictly greater than is expired)");
    }

    @Test
    @DisplayName("OVERLAY-007: null snapshot returns SNAPSHOT_EXPIRED")
    void nullSnapshotExpired() {
        TocTouGuard guard = new TocTouGuard();
        TocTouResult result = guard.checkSnapshot(null);
        assertEquals(TocTouDecision.SNAPSHOT_EXPIRED, result.decision());
    }

    @Test
    @DisplayName("OVERLAY-007: matching checksum returns VALID")
    void matchingChecksumValid() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        OWLAxiom axiom = deviceClassAssertion();
        String checksum = SnapshotChecksum.compute(List.of(axiom));
        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-4", clock.instant(), "mcp", 0L, checksum);

        TocTouResult result = guard.verifyChecksum(snapshot, List.of(axiom));
        assertTrue(result.isValid(), "matching checksum must be VALID: " + result.reason());
        assertEquals(checksum, result.actualChecksum());
    }

    @Test
    @DisplayName("OVERLAY-007: checksum mismatch returns RETRY_VALIDATION")
    void checksumMismatchTriggersRetry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        OWLAxiom axiom1 = deviceClassAssertion();
        OWLAxiom axiom2 = deviceStateAssertion("on");
        // Snapshot records the checksum for axiom1 only.
        String checksum1 = SnapshotChecksum.compute(List.of(axiom1));
        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-5", clock.instant(), "mcp", 0L, checksum1);

        // Verify against axiom1 + axiom2 (different set, different checksum).
        TocTouResult result = guard.verifyChecksum(snapshot, List.of(axiom1, axiom2));
        assertEquals(TocTouDecision.RETRY_VALIDATION, result.decision(),
            "checksum mismatch must trigger RETRY_VALIDATION: " + result.reason());
        assertNotEquals(result.expectedChecksum(), result.actualChecksum());
    }

    @Test
    @DisplayName("OVERLAY-007: state change detected via different axiom state -> RETRY_VALIDATION")
    void stateChangeDetected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        OWLAxiom onAxiom = deviceStateAssertion("on");
        OWLAxiom offAxiom = deviceStateAssertion("off");
        String onChecksum = SnapshotChecksum.compute(List.of(onAxiom));
        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-6", clock.instant(), "mcp", 0L, onChecksum);

        TocTouResult result = guard.verifyChecksum(snapshot, List.of(offAxiom));
        assertEquals(TocTouDecision.RETRY_VALIDATION, result.decision(),
            "state change (on->off) must trigger RETRY_VALIDATION");
    }

    @Test
    @DisplayName("OVERLAY-007: expired snapshot short-circuits checksum verification")
    void expiredShortCircuitsChecksum() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        OWLAxiom axiom = deviceClassAssertion();
        String checksum = SnapshotChecksum.compute(List.of(axiom));
        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-7", clock.instant(), "mcp", 0L, checksum);
        clock.advance(Duration.ofSeconds(10));

        // Even with a matching axiom set, TTL expiry short-circuits to SNAPSHOT_EXPIRED.
        TocTouResult result = guard.verifyChecksum(snapshot, List.of(axiom));
        assertEquals(TocTouDecision.SNAPSHOT_EXPIRED, result.decision(),
            "expired snapshot must short-circuit checksum verification");
    }

    @Test
    @DisplayName("OVERLAY-007: null axioms collection triggers RETRY_VALIDATION (within TTL)")
    void nullAxiomsTriggersRetry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        StateSnapshotId snapshot = new StateSnapshotId(
            "snap-8", clock.instant(), "mcp", 0L, "checksum");
        TocTouResult result = guard.verifyChecksum(snapshot, null);
        assertEquals(TocTouDecision.RETRY_VALIDATION, result.decision(),
            "null axioms must trigger RETRY_VALIDATION");
    }

    @Test
    @DisplayName("OVERLAY-007: verifyChecksum(EnvironmentSnapshot, axioms) overload works")
    void verifyChecksumEnvironmentSnapshot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        OWLAxiom axiom = deviceClassAssertion();
        String checksum = SnapshotChecksum.compute(List.of(axiom));
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-9", clock.instant(), "mcp", 0L, checksum,
            List.of(), List.of(), List.of());

        TocTouResult result = guard.verifyChecksum(snapshot, List.of(axiom));
        assertTrue(result.isValid(), "EnvironmentSnapshot overload must work: " + result.reason());
    }

    @Test
    @DisplayName("OVERLAY-007: latestVersionFor(source) tracks the latest seen version")
    void latestVersionTracking() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        assertEquals(-1L, guard.latestVersionFor("mcp"), "no snapshot seen yet -> -1");

        StateSnapshotId snap1 = new StateSnapshotId(
            "snap-a", clock.instant(), "mcp", 1L, "c1");
        guard.checkSnapshot(snap1);
        assertEquals(1L, guard.latestVersionFor("mcp"));

        // Same source, newer version.
        clock.advance(Duration.ofMillis(100));
        StateSnapshotId snap2 = new StateSnapshotId(
            "snap-b", clock.instant(), "mcp", 5L, "c2");
        guard.checkSnapshot(snap2);
        assertEquals(5L, guard.latestVersionFor("mcp"));

        // Same source, older version — latest should NOT regress.
        clock.advance(Duration.ofMillis(100));
        StateSnapshotId snap3 = new StateSnapshotId(
            "snap-c", clock.instant(), "mcp", 3L, "c3");
        guard.checkSnapshot(snap3);
        assertEquals(5L, guard.latestVersionFor("mcp"), "latest version must not regress");

        // Different source.
        StateSnapshotId snapOther = new StateSnapshotId(
            "snap-d", clock.instant(), "cli", 10L, "c4");
        guard.checkSnapshot(snapOther);
        assertEquals(10L, guard.latestVersionFor("cli"));
        assertEquals(5L, guard.latestVersionFor("mcp"), "mcp version must not change");
    }

    @Test
    @DisplayName("OVERLAY-007: reset() clears all recorded versions")
    void resetClearsVersions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        TocTouGuard guard = new TocTouGuard(Duration.ofSeconds(5), clock);

        StateSnapshotId snap = new StateSnapshotId(
            "snap-z", clock.instant(), "mcp", 99L, "c");
        guard.checkSnapshot(snap);
        assertEquals(99L, guard.latestVersionFor("mcp"));

        guard.reset();
        assertEquals(-1L, guard.latestVersionFor("mcp"), "reset must clear recorded versions");
    }

    @Test
    @DisplayName("OVERLAY-007: ttl() returns the configured TTL")
    void ttlReturnsConfigured() {
        TocTouGuard guard5s = new TocTouGuard();
        assertEquals(Duration.ofSeconds(5), guard5s.ttl(), "default TTL is 5s");

        TocTouGuard guard10s = new TocTouGuard(Duration.ofSeconds(10), Clock.systemUTC());
        assertEquals(Duration.ofSeconds(10), guard10s.ttl(), "explicit TTL honored");
    }

    @Test
    @DisplayName("OVERLAY-007: non-positive TTL falls back to default 5s")
    void nonPositiveTtlFallsBackToDefault() {
        TocTouGuard zeroTtl = new TocTouGuard(Duration.ZERO, Clock.systemUTC());
        assertEquals(Duration.ofSeconds(5), zeroTtl.ttl(), "zero TTL falls back to default");

        TocTouGuard negTtl = new TocTouGuard(Duration.ofSeconds(-1), Clock.systemUTC());
        assertEquals(Duration.ofSeconds(5), negTtl.ttl(), "negative TTL falls back to default");

        TocTouGuard nullTtl = new TocTouGuard(null, Clock.systemUTC());
        assertEquals(Duration.ofSeconds(5), nullTtl.ttl(), "null TTL falls back to default");
    }
}
