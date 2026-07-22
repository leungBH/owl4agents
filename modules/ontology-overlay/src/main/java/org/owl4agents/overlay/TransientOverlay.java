package org.owl4agents.overlay;

import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import java.time.Instant;
import java.util.Collection;

/**
 * v0.8.7 OV-002 / D9: Handle to a transient overlay ontology created by
 * {@link TransientOntologyOverlayService}.
 *
 * <p>Implements {@link AutoCloseable} — callers SHOULD use
 * try-with-resources to ensure the overlay is released after pipeline
 * stages 6-8 complete (or short-circuit):</p>
 *
 * <pre>{@code
 * try (TransientOverlay overlay = service.createOverlay(base, axioms, opts).data()) {
 *     OWLOntology ont = overlay.ontology();
 *     // ... run reasoner + SHACL on ont ...
 * }
 * }</pre>
 *
 * <p>The underlying {@link OWLOntology} lives in an isolated
 * {@code OWLOntologyManager} (provided by the v0.8.5
 * {@code TemporaryOntologyFactory}); when {@link #release()} is called,
 * the manager is cleared and the overlay ontology becomes eligible for
 * garbage collection. The pipeline MUST NOT cache overlays across
 * calls (OV-002 / D16).</p>
 */
public interface TransientOverlay extends AutoCloseable {

    /** Unique identifier of this overlay instance (UUID). */
    String overlayId();

    /** The base ontology ID the overlay was built from. */
    OntologyId baseOntologyId();

    /** The dynamic axioms added on top of the base ontology. */
    Collection<OWLAxiom> dynamicAxioms();

    /** The snapshot identifier of the dynamic state used to build the overlay. */
    String snapshotId();

    /** When the overlay was created (UTC). */
    Instant createdAt();

    /** The isolated overlay ontology (base + dynamic axioms). */
    OWLOntology ontology();

    /** The environment snapshot associated with the overlay. */
    EnvironmentSnapshot snapshot();

    /**
     * Release the overlay's isolated {@code OWLOntologyManager}. Safe to
     * call multiple times. After release, the overlay ontology is no
     * longer managed and becomes eligible for GC.
     */
    void release();

    @Override
    default void close() {
        release();
    }
}
