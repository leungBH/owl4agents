package org.owl4agents.reasoner;

import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

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
     * v0.8.5: Check whether this adapter supports consistency checking
     * via {@link #checkConsistency(String)} on an arbitrary ontology.
     * All current adapters return {@code true}.
     */
    default boolean supportsConsistency() {
        return true;
    }

    /**
     * v0.8.5: Check whether this adapter can be initialized against an
     * arbitrary in-memory {@link OWLOntology} (i.e., a temporary ontology
     * created by {@code TemporaryOntologyFactory}) without catalog
     * registration. All current adapters return {@code true} since
     * {@link #initialize(OWLOntology)} accepts any OWLOntology.
     */
    default boolean supportsTemporaryOntology() {
        return true;
    }

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

    /**
     * v0.8.8: Check whether the given class expression is satisfiable
     * (i.e., can have instances) under the current ontology state.
     *
     * <p>Implementations SHALL call {@code reasoner.flush()} before querying
     * to ensure pending ontology changes (axiom add/remove) are processed.
     * This is consistent with the v0.8.5 P1 fix for {@code checkConsistency}.
     *
     * <p>Used by Stage 4 satisfiability check (D2) to detect classes that
     * became unsatisfiable after adding a claim axiom, even when the ontology
     * remains consistent (OWL 2 DL: class unsatisfiable != ontology inconsistent).
     *
     * @param expr the class expression to check
     * @return {@code true} if the class expression is satisfiable
     * @throws IllegalStateException if the adapter has been shut down
     */
    boolean isSatisfiable(OWLClassExpression expr);

    /**
     * v0.8.1 ISSUE-02: returns the raw underlying {@link OWLReasoner} instance
     * for advanced operations like {@code isEntailed(axiom)} and
     * {@code getSuperClasses(cls, true)}. Required by
     * {@code ReasonerServiceImpl.checkAxiomEntailment} to replace the
     * v0.8.0 private {@code getOWLReasonerFromAdapter(adapter)} bridge
     * (which returned {@code null}) that caused the SubClassOf inferred path
     * to silently fall through. v0.8.1 callers MUST ensure the adapter is
     * {@link #isActive() active} before invoking this method.
     */
    OWLReasoner getUnderlyingReasoner();
}