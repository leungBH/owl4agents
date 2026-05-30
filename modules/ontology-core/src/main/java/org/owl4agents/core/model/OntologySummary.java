package org.owl4agents.core.model;

import org.owl4agents.core.OntologyId;

import java.util.List;

/**
 * Ontology summary containing ontology IRI, version IRI, imports,
 * profile information, and entity counts.
 */
public record OntologySummary(
    OntologyId ontologyId,
    String ontologyIri,
    String versionIri,
    List<String> imports,
    ProfileInfo profile,
    EntityCounts entityCounts
) {}