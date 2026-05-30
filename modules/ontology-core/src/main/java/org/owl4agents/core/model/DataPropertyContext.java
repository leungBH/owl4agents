package org.owl4agents.core.model;

import java.util.List;

/**
 * Readonly data property context including domain, range,
 * datatype, and hierarchy.
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
    List<String> subProperties
) implements EntityContext {}