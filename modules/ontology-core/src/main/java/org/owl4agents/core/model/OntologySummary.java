package org.owl4agents.core.model;

import org.owl4agents.core.OntologyId;

import java.util.List;
import java.util.Optional;

/**
 * Ontology summary containing ontology IRI, version IRI, imports,
 * profile information, entity counts, and v0.2 recommended reasoner.
 */
public record OntologySummary(
    OntologyId ontologyId,
    String ontologyIri,
    String versionIri,
    List<String> imports,
    ProfileInfo profile,
    EntityCounts entityCounts,
    // v0.2 addition: recommended reasoner based on detected profile
    Optional<String> recommendedReasoner
) {

    /**
     * v0.1 factory: create OntologySummary without recommended reasoner.
     */
    public static OntologySummary withoutReasoner(
        OntologyId ontologyId, String ontologyIri, String versionIri,
        List<String> imports, ProfileInfo profile, EntityCounts entityCounts
    ) {
        return new OntologySummary(ontologyId, ontologyIri, versionIri,
            imports, profile, entityCounts, Optional.empty());
    }

    /**
     * v0.2 factory: create OntologySummary with recommended reasoner.
     */
    public static OntologySummary withReasoner(
        OntologyId ontologyId, String ontologyIri, String versionIri,
        List<String> imports, ProfileInfo profile, EntityCounts entityCounts,
        String recommendedReasoner
    ) {
        return new OntologySummary(ontologyId, ontologyIri, versionIri,
            imports, profile, entityCounts, Optional.of(recommendedReasoner));
    }
}