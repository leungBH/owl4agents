package org.owl4agents.core.model;

import java.util.List;

/**
 * Readonly class context including subclasses, superclasses,
 * equivalent classes, disjoint classes, and basic restrictions.
 */
public record ClassContext(
    String iri,
    String prefixedName,
    String label,
    String comment,
    List<String> directSubclasses,
    List<String> directSuperclasses,
    List<String> equivalentClasses,
    List<String> disjointClasses,
    List<RestrictionInfo> restrictions
) implements EntityContext {}