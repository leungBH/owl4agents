package org.owl4agents.overlay;

import java.time.Duration;
import java.util.Optional;

/**
 * v0.8.7 OV-002 / D9: Options bag for
 * {@link TransientOntologyOverlayService#createOverlay}.
 *
 * <p>Defaults (per design D9):</p>
 * <ul>
 *   <li>{@code copyImportsClosure} = {@code true} — the overlay includes the
 *       base ontology's imports closure (copied into the isolated manager,
 *       not re-resolved).</li>
 *   <li>{@code reasoner} = empty — auto-select reasoner via
 *       {@code AutoReasonerSelector}; callers may override with an explicit
 *       reasoner name (e.g. {@code "ELK"}, {@code "HermiT"}).</li>
 *   <li>{@code timeout} = 60s — overlay lifecycle upper bound. The overlay
 *       SHOULD be released before this timeout; the pipeline enforces it
 *       via try-with-resources.</li>
 *   <li>{@code iriSuffix} = empty → defaults to {@code "-overlay"} inside
 *       the implementation. The suffix is appended to the base ontology
 *       IRI to derive the overlay's IRI (debugging aid only).</li>
 * </ul>
 *
 * <p>v0.8.7 implements the single-call {@code createOverlay} interface.
 * A batch variant ({@code createOverlayBatch}) that lets multiple axiom
 * sets share one base overlay is deferred to a later version per D9 /
 * Open Question #2.</p>
 *
 * @param copyImportsClosure whether to copy the base ontology's imports
 *                           closure axioms into the overlay
 * @param reasoner           explicit reasoner name, or empty for auto-select
 * @param timeout            overlay lifecycle upper bound
 * @param iriSuffix          suffix appended to the overlay ontology IRI
 *                           (empty → "-overlay")
 */
public record OverlayOptions(
    boolean copyImportsClosure,
    Optional<String> reasoner,
    Duration timeout,
    Optional<String> iriSuffix
) {
    /** Default 60s timeout per design D9. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Default IRI suffix applied to overlay ontologies. */
    public static final String DEFAULT_IRI_SUFFIX = "-overlay";

    public OverlayOptions {
        if (timeout == null) timeout = DEFAULT_TIMEOUT;
        if (timeout.isNegative() || timeout.isZero()) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (reasoner == null) reasoner = Optional.empty();
        if (iriSuffix == null || iriSuffix.isEmpty()) {
            iriSuffix = Optional.empty();
        }
    }

    /**
     * Default options: imports closure copied, auto reasoner, 60s timeout,
     * {@code -overlay} IRI suffix.
     */
    public static OverlayOptions defaults() {
        return new OverlayOptions(true, Optional.empty(), DEFAULT_TIMEOUT, Optional.empty());
    }

    /**
     * Resolve the IRI suffix to apply, returning the default when unset.
     */
    public String resolvedIriSuffix() {
        return iriSuffix.orElse(DEFAULT_IRI_SUFFIX);
    }
}
