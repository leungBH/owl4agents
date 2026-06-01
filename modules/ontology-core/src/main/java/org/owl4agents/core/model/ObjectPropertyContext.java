package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Readonly object property context including domain, range,
 * inverse properties, hierarchy, and characteristics.
 * v0.2 adds inferred content fields when reasoning is available.
 */
public record ObjectPropertyContext(
    String iri,
    String prefixedName,
    String label,
    String comment,
    List<String> domain,
    List<String> range,
    List<String> inverseProperties,
    List<String> superProperties,
    List<String> subProperties,
    List<String> characteristics,
    // v0.2 inferred additions
    Optional<String> reasoningStatus,
    Optional<List<String>> inferredDomain,
    Optional<List<String>> inferredRange,
    Optional<List<String>> inferredSuperProperties
) implements EntityContext {

    /**
     * v0.1 factory: create ObjectPropertyContext without inferred content.
     */
    public static ObjectPropertyContext explicit(
        String iri, String prefixedName, String label, String comment,
        List<String> domain, List<String> range,
        List<String> inverseProperties, List<String> superProperties,
        List<String> subProperties, List<String> characteristics
    ) {
        return new ObjectPropertyContext(iri, prefixedName, label, comment,
            domain, range, inverseProperties, superProperties, subProperties, characteristics,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}