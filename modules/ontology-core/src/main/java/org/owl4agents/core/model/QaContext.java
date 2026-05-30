package org.owl4agents.core.model;

import org.owl4agents.core.evidence.EvidenceMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Ontology-grounded QA context generated from a question.
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
    List<QaWarning> warnings
) {}