package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Readonly data property context including domain, range,
 * datatype, and hierarchy.
 * v0.2 adds inferred content fields when reasoning is available.
 */
public record DataPropertyContext(
    String iri,
    String prefixedName,
    String label,
    String comment,
    List<String> domain,
    List<String> range,
    String datatype,
    List<String> superProperties,
    List<String> subProperties,
    // v0.2 inferred additions
    Optional<String> reasoningStatus,
    Optional<List<String>> inferredDomain,
    Optional<List<String>> inferredRange
) implements EntityContext {

    /**
     * v0.1 factory: create DataPropertyContext without inferred content.
     */
    public static DataPropertyContext explicit(
        String iri, String prefixedName, String label, String comment,
        List<String> domain, List<String> range,
        String datatype, List<String> superProperties,
        List<String> subProperties
    ) {
        return new DataPropertyContext(iri, prefixedName, label, comment,
            domain, range, datatype, superProperties, subProperties,
            Optional.empty(), Optional.empty(), Optional.empty());
    }
}