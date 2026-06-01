package org.owl4agents.core.model;

import org.owl4agents.core.evidence.EvidenceMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Ontology-grounded QA context generated from a question.
 * v0.2 adds reasoning evidence and scope metadata sections.
 */
public record QaContext(
    String question,
    List<SearchMatch> matchedEntities,
    List<ClassContext> classContext,
    List<ObjectPropertyContext> propertyContext,
    List<IndividualContext> individualContext,
    List<SparqlEvidence> sparqlEvidence,
    String naturalLanguageContext,
    ContextBounds bounds,
    List<QaWarning> warnings,
    // v0.2 additions
    Optional<ReasoningEvidence> reasoningEvidence,
    Optional<ScopeDescription> scopeDescription
) {

    /**
     * v0.1 factory: create QaContext without reasoning or scope sections.
     */
    public static QaContext explicit(
        String question,
        List<SearchMatch> matchedEntities,
        List<ClassContext> classContext,
        List<ObjectPropertyContext> propertyContext,
        List<IndividualContext> individualContext,
        List<SparqlEvidence> sparqlEvidence,
        String naturalLanguageContext,
        ContextBounds bounds,
        List<QaWarning> warnings
    ) {
        return new QaContext(question, matchedEntities, classContext, propertyContext,
            individualContext, sparqlEvidence, naturalLanguageContext, bounds, warnings,
            Optional.empty(), Optional.empty());
    }

    /**
     * v0.2 factory: create QaContext with reasoning evidence and scope metadata.
     */
    public static QaContext withReasoningAndScope(
        String question,
        List<SearchMatch> matchedEntities,
        List<ClassContext> classContext,
        List<ObjectPropertyContext> propertyContext,
        List<IndividualContext> individualContext,
        List<SparqlEvidence> sparqlEvidence,
        String naturalLanguageContext,
        ContextBounds bounds,
        List<QaWarning> warnings,
        ReasoningEvidence reasoningEvidence,
        ScopeDescription scopeDescription
    ) {
        return new QaContext(question, matchedEntities, classContext, propertyContext,
            individualContext, sparqlEvidence, naturalLanguageContext, bounds, warnings,
            Optional.of(reasoningEvidence), Optional.of(scopeDescription));
    }
}