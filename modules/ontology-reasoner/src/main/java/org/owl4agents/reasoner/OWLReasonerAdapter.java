package org.owl4agents.reasoner;

import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.Set;

/**
 * Unified adapter interface that all reasoner adapters (HermiT, ELK, Openllet) implement.
 * Each adapter wraps a specific OWL reasoner implementation and provides
 * standardized lifecycle operations.
 */
public interface OWLReasonerAdapter {

    /**
     * Get the name of this reasoner adapter (e.g., "HermiT", "ELK", "Openllet").
     */
    String getName();

    /**
     * Create and initialize the underlying reasoner for the given ontology.
     * Must be called before any other operation.
     */
    void initialize(OWLOntology ontology);

    /**
     * Classify the ontology: compute the inferred class hierarchy.
     * Requires initialization first.
     */
    ClassificationResult classify(String ontologyId);

    /**
     * Realize the ontology: compute inferred individual types.
     * Requires classification first.
     */
    RealizationResult realize(String ontologyId);

    /**
     * Check ontology consistency.
     * Requires initialization first.
     */
    ConsistencyResult checkConsistency(String ontologyId);

    /**
     * Get all unsatisfiable class IRIs.
     * Requires classification first.
     */
    Set<String> getUnsatClasses();

    /**
     * Explain inconsistency or unsatisfiability.
     * Returns conflicting axiom sets that cause the issue.
     * Not all reasoners support this; ELK will throw EXPLANATION_NOT_SUPPORTED.
     */
    InconsistencyExplanation explainInconsistency(String ontologyId);

    /**
     * Explain why a specific class is unsatisfiable.
     * Returns conflicting axiom sets that make the class unsatisfiable.
     */
    UnsatClassExplanation explainUnsatClass(String ontologyId, String classIRI);

    /**
     * Check whether this adapter supports explanation functionality.
     */
    boolean supportsExplanation();

    /**
     * Get the list of OWL profiles this adapter supports.
     */
    java.util.List<String> getSupportedProfiles();

    /**
     * Get the list of operations this adapter supports.
     */
    java.util.List<String> getSupportedOperations();

    /**
     * Shut down the reasoner and release all resources.
     * After shutdown, all other operations will throw REASONER_SHUTDOWN.
     */
    void shutdown();

    /**
     * Check whether the adapter has been initialized and is still active.
     */
    boolean isActive();
}