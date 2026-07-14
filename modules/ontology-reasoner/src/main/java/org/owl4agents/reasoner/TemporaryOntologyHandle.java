package org.owl4agents.reasoner;

import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Handle to a temporary ontology created by {@link TemporaryOntologyFactory}.
 *
 * <p>Implements {@link AutoCloseable} — callers SHOULD use try-with-resources
 * to ensure the temporary ontology manager is released after the exact
 * consistency check completes:
 *
 * <pre>{@code
 * try (TemporaryOntologyHandle handle = factory.create(source, axiom, opts)) {
 *     OWLOntology temp = handle.ontology();
 *     // ... run reasoner on temp ...
 * }
 * }</pre>
 *
 * <p>The underlying {@link OWLOntologyManager} is independent from the main
 * manager; when {@link #release()} is called, the manager is cleared and
 * the temporary ontology becomes eligible for garbage collection.
 */
public final class TemporaryOntologyHandle implements AutoCloseable {

    private final OWLOntology ontology;
    private final org.semanticweb.owlapi.model.OWLOntologyManager manager;
    private volatile boolean released = false;

    TemporaryOntologyHandle(OWLOntology ontology,
                            org.semanticweb.owlapi.model.OWLOntologyManager manager) {
        this.ontology = ontology;
        this.manager = manager;
    }

    /**
     * The temporary ontology containing the source's imports closure
     * (if requested) plus the claim axiom.
     */
    public OWLOntology ontology() {
        return ontology;
    }

    /**
     * Release the temporary ontology manager. Safe to call multiple times.
     * After release, the ontology reference is still valid (it will be
     * garbage-collected when no longer referenced), but the manager's
     * internal caches are cleared by removing all ontologies it manages.
     */
    public void release() {
        if (!released) {
            released = true;
            try {
                // OWLOntologyManager has no clear() in OWL API 5.x;
                // iterate over a snapshot of managed ontologies and remove each.
                for (OWLOntology ont : java.util.Set.copyOf(manager.getOntologies())) {
                    try {
                        manager.removeOntology(ont);
                    } catch (Exception ignored) {
                        // best-effort per-ontology removal
                    }
                }
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Override
    public void close() {
        release();
    }
}
