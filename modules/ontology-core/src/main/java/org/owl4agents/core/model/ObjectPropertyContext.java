package org.owl4agents.core.model;

import java.util.List;

/**
 * Readonly object property context including domain, range,
 * inverse properties, hierarchy, and characteristics.
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
    List<String> characteristics
) implements EntityContext {}