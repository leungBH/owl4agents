package org.owl4agents.overlay;

import org.semanticweb.owlapi.model.OWLAxiom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.8.7 OV-005 / D11: TOCTOU (time-of-check-to-time-of-use) protection
 * for the overlay pipeline.
 *
 * <p>Implements three protections per design D11:</p>
 * <ol>
 *   <li><strong>Snapshot version comparison</strong> — each snapshot
 *       carries a {@link StateSnapshotId} with a monotonic
 *       {@code version} per source. The guard records the latest
 *       version seen per source and exposes
 *       {@link #latestVersionFor(String)} so callers can detect logical
 *       version drift between validation and execution.</li>
 *   <li><strong>State hash verification</strong> —
 *       {@link #verifyChecksum(StateSnapshotId, Collection)} recomputes
 *       the canonical axiom checksum via {@link SnapshotChecksum} and
 *       compares it to {@code snapshot.checksum()}. Mismatch produces
 *       {@link TocTouDecision#RETRY_VALIDATION} (per OV-005 "State hash
 *       mismatch triggers retry").</li>
 *   <li><strong>Short validity TTL</strong> —
 *       {@link #checkSnapshot(StateSnapshotId)} rejects snapshots older
 *       than the configured TTL (default 5s, matching HTTP session TTL)
 *       with {@link TocTouDecision#SNAPSHOT_EXPIRED}. Stale validation
 *       results SHALL NOT be used after state changes.</li>
 * </ol>
 *
 * <p>The guard is stateless except for the per-source version map, which
 * is a best-effort logical clock. Callers are responsible for
 * re-validation at execution time (the guard provides the mechanism;
 * it cannot enforce the timing).</p>
 */
public final class TocTouGuard {

    /** Default TTL: 5 seconds, matching HTTP session TTL per D11. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    /**
     * System property name overriding the default snapshot TTL. When set
     * to a positive integer, the guard uses the property value (in
     * seconds) as the default TTL for the no-arg constructor. Per the
     * cache-governance spec "Dynamic state cache invalidation / TTL is
     * configurable".
     */
    public static final String TTL_SYSTEM_PROPERTY = "owl4agents.overlay.snapshot.ttl.seconds";

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Long> latestVersionBySource;

    /**
     * Construct a guard with the default TTL. The default TTL is 5
     * seconds, but it can be overridden via the
     * {@code owl4agents.overlay.snapshot.ttl.seconds} system property
     * (per cache-governance spec "TTL is configurable"). The system
     * property is read once at construction time.
     */
    public TocTouGuard() {
        this(resolveTtlFromSystemProperty(DEFAULT_TTL), Clock.systemUTC());
    }

    /**
     * Construct a guard with an explicit TTL and clock (used by tests
     * to inject a virtual clock).
     *
     * <p>Note: when an explicit {@code ttl} is provided (non-null and
     * positive), the {@code owl4agents.overlay.snapshot.ttl.seconds}
     * system property is NOT consulted — the explicit value wins. The
     * system property only applies to the no-arg constructor.</p>
     *
     * @param ttl   the TTL window; non-positive values fall back to
     *              {@link #DEFAULT_TTL}
     * @param clock the {@link Clock} used to compute snapshot age
     */
    public TocTouGuard(Duration ttl, Clock clock) {
        this.ttl = (ttl == null || ttl.isZero() || ttl.isNegative()) ? DEFAULT_TTL : ttl;
        this.clock = (clock == null) ? Clock.systemUTC() : clock;
        this.latestVersionBySource = new ConcurrentHashMap<>();
    }

    /**
     * Resolve the TTL from the {@value #TTL_SYSTEM_PROPERTY} system
     * property. Returns {@code fallback} when the property is unset,
     * blank, or non-positive.
     */
    private static Duration resolveTtlFromSystemProperty(Duration fallback) {
        String raw = System.getProperty(TTL_SYSTEM_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed <= 0) {
                return fallback;
            }
            return Duration.ofSeconds(parsed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Check whether a snapshot is still within the TTL window.
     *
     * <p>This corresponds to OV-005 "Expired snapshot rejected". When
     * the snapshot's age exceeds the TTL, the caller MUST return
     * {@code SNAPSHOT_EXPIRED} (or pipeline decision
     * {@code RETRY_VALIDATION}) without performing reasoning or SHACL
     * validation.</p>
     *
     * @param snapshot the snapshot metadata to check
     * @return a {@link TocTouResult} with decision
     *         {@link TocTouDecision#VALID} or
     *         {@link TocTouDecision#SNAPSHOT_EXPIRED}
     */
    public TocTouResult checkSnapshot(StateSnapshotId snapshot) {
        if (snapshot == null) {
            return new TocTouResult(
                TocTouDecision.SNAPSHOT_EXPIRED,
                "snapshot metadata is null",
                "",
                "",
                "",
                -1L,
                ttl.toMillis());
        }
        recordVersion(snapshot);
        Instant now = clock.instant();
        long ageMs = now.toEpochMilli() - snapshot.capturedAt().toEpochMilli();
        if (ageMs > ttl.toMillis()) {
            return new TocTouResult(
                TocTouDecision.SNAPSHOT_EXPIRED,
                "snapshot age " + ageMs + "ms exceeds TTL " + ttl.toMillis() + "ms",
                snapshot.snapshotId(),
                snapshot.checksum(),
                "",
                ageMs,
                ttl.toMillis());
        }
        return new TocTouResult(
            TocTouDecision.VALID,
            "snapshot age " + ageMs + "ms within TTL " + ttl.toMillis() + "ms",
            snapshot.snapshotId(),
            snapshot.checksum(),
            snapshot.checksum(),
            ageMs,
            ttl.toMillis());
    }

    /**
     * Verify that the recomputed axiom checksum matches the snapshot's
     * recorded checksum.
     *
     * <p>This corresponds to OV-005 "State hash mismatch triggers
     * retry". When the checksums differ, the caller MUST set
     * {@code decision=RETRY_VALIDATION} and SHALL NOT proceed to OWL
     * claim verification.</p>
     *
     * <p>The method first checks the TTL (returning
     * {@code SNAPSHOT_EXPIRED} if expired) and then verifies the
     * checksum.</p>
     *
     * @param snapshot the snapshot metadata
     * @param axioms   the dynamic axioms to recompute the checksum over
     * @return a {@link TocTouResult} with decision {@code VALID},
     *         {@code SNAPSHOT_EXPIRED}, or {@code RETRY_VALIDATION}
     */
    public TocTouResult verifyChecksum(StateSnapshotId snapshot,
                                       Collection<OWLAxiom> axioms) {
        TocTouResult ttlCheck = checkSnapshot(snapshot);
        if (!ttlCheck.isValid()) {
            return ttlCheck;
        }
        if (axioms == null) {
            return new TocTouResult(
                TocTouDecision.RETRY_VALIDATION,
                "axioms collection is null; cannot verify checksum",
                snapshot.snapshotId(),
                snapshot.checksum(),
                "",
                ttlCheck.ageMillis(),
                ttl.toMillis());
        }
        String actual = SnapshotChecksum.compute(axioms);
        if (!actual.equalsIgnoreCase(snapshot.checksum().trim())) {
            return new TocTouResult(
                TocTouDecision.RETRY_VALIDATION,
                "checksum mismatch: expected=" + snapshot.checksum() + " actual=" + actual,
                snapshot.snapshotId(),
                snapshot.checksum(),
                actual,
                ttlCheck.ageMillis(),
                ttl.toMillis());
        }
        return new TocTouResult(
            TocTouDecision.VALID,
            "checksum verified",
            snapshot.snapshotId(),
            snapshot.checksum(),
            actual,
            ttlCheck.ageMillis(),
            ttl.toMillis());
    }

    /**
     * Convenience overload accepting an {@link EnvironmentSnapshot}.
     */
    public TocTouResult verifyChecksum(EnvironmentSnapshot snapshot,
                                       Collection<OWLAxiom> axioms) {
        if (snapshot == null) {
            return verifyChecksum((StateSnapshotId) null, axioms);
        }
        return verifyChecksum(snapshot.toSnapshotId(), axioms);
    }

    /**
     * Return the latest snapshot version seen for a given source, or
     * -1 if no snapshot from that source has been observed. Callers
     * can use this to detect version drift: if a caller's recorded
     * version differs from {@code latestVersionFor(source)}, a newer
     * snapshot has been observed and the caller's validation result is
     * stale.
     *
     * @param source the snapshot source tag (e.g. "mcp", "cli", "api")
     * @return the latest version, or -1 if none recorded
     */
    public long latestVersionFor(String source) {
        if (source == null || source.isBlank()) return -1L;
        Long v = latestVersionBySource.get(source);
        return v == null ? -1L : v;
    }

    /**
     * Return the configured TTL.
     */
    public Duration ttl() {
        return ttl;
    }

    /**
     * Forget all recorded versions (useful for tests).
     */
    public void reset() {
        latestVersionBySource.clear();
    }

    private void recordVersion(StateSnapshotId snapshot) {
        if (snapshot.source() != null && !snapshot.source().isBlank()) {
            latestVersionBySource.merge(
                snapshot.source(),
                snapshot.version(),
                Math::max);
        }
    }
}
