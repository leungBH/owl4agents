package org.owl4agents.owlapi;

import org.owl4agents.core.OntologyId;

/**
 * Listener for ontology cache reload events.
 *
 * <p>Implemented by {@link org.owl4agents.reasoner.ReasonerLifecycleManager}
 * to receive notifications when an ontology is reloaded by
 * {@link OntologyCache}. On reload, the reasoner adapter bound to the
 * old {@link OWLOntology} instance must be shut down to release native
 * resources (HermiT/ELK/Openllet threads and memory) and to ensure the
 * next reasoning call builds a fresh adapter bound to the new ontology.</p>
 *
 * <p>Placed in the {@code ontology-owlapi} module to break the circular
 * dependency: {@code ontology-reasoner} depends on {@code ontology-owlapi},
 * not vice versa. By defining the interface here, {@code OntologyCache}
 * (in {@code ontology-owlapi}) can reference the listener without depending
 * on {@code ontology-reasoner}.</p>
 *
 * @see OntologyCache#addReloadListener(OntologyReloadListener)
 */
public interface OntologyReloadListener {

    /**
     * Called when a single ontology is reloaded (file mtime/size change
     * detected, or explicit {@link OntologyCache#invalidate(OntologyId)}
     * invoked).
     *
     * <p><b>Timing guarantee:</b> This callback is invoked BEFORE the new
     * cache entry becomes visible to other threads. This avoids a TOCTOU
     * window where a concurrent thread could obtain the new ontology
     * while still holding a stale reasoner adapter.</p>
     *
     * @param ontologyId the ontology that was reloaded
     */
    void onOntologyReloaded(OntologyId ontologyId);

    /**
     * Called when all ontologies are invalidated via
     * {@link OntologyCache#invalidateAll()}.
     */
    void onAllOntologiesReloaded();
}
