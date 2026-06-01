package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Readonly class context including subclasses, superclasses,
 * equivalent classes, disjoint classes, and basic restrictions.
 * v0.2 adds inferred content fields when reasoning is available.
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
    List<RestrictionInfo> restrictions,
    // v0.2 inferred additions
    Optional<String> reasoningStatus,
    Optional<List<String>> inferredSuperclasses,
    Optional<List<String>> inferredSubclasses,
    Optional<List<String>> inferredEquivalentClasses,
    Optional<List<String>> inferredDisjointClasses
) implements EntityContext {

    /**
     * v0.1 factory: create ClassContext without inferred content.
     */
    public static ClassContext explicit(
        String iri, String prefixedName, String label, String comment,
        List<String> directSubclasses, List<String> directSuperclasses,
        List<String> equivalentClasses, List<String> disjointClasses,
        List<RestrictionInfo> restrictions
    ) {
        return new ClassContext(iri, prefixedName, label, comment,
            directSubclasses, directSuperclasses, equivalentClasses, disjointClasses, restrictions,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * v0.2 factory: create ClassContext with inferred content.
     */
    public static ClassContext withInferred(
        String iri, String prefixedName, String label, String comment,
        List<String> directSubclasses, List<String> directSuperclasses,
        List<String> equivalentClasses, List<String> disjointClasses,
        List<RestrictionInfo> restrictions,
        String reasoningStatus,
        List<String> inferredSuperclasses, List<String> inferredSubclasses,
        List<String> inferredEquivalentClasses, List<String> inferredDisjointClasses
    ) {
        return new ClassContext(iri, prefixedName, label, comment,
            directSubclasses, directSuperclasses, equivalentClasses, disjointClasses, restrictions,
            Optional.of(reasoningStatus),
            Optional.of(inferredSuperclasses), Optional.of(inferredSubclasses),
            Optional.of(inferredEquivalentClasses), Optional.of(inferredDisjointClasses));
    }
}