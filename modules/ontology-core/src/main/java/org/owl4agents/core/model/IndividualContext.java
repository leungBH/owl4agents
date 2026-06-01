package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Readonly individual context including explicit types,
 * object property assertions, and data property assertions.
 * v0.2 adds inferred types when reasoning is available.
 */
public record IndividualContext(
    String iri,
    String prefixedName,
    String label,
    String comment,
    List<String> explicitTypes,
    List<ObjectPropertyAssertion> objectPropertyAssertions,
    List<DataPropertyAssertion> dataPropertyAssertions,
    // v0.2 inferred additions
    Optional<String> reasoningStatus,
    Optional<List<String>> inferredTypes
) implements EntityContext {

    /**
     * v0.1 factory: create IndividualContext without inferred content.
     */
    public static IndividualContext explicit(
        String iri, String prefixedName, String label, String comment,
        List<String> explicitTypes,
        List<ObjectPropertyAssertion> objectPropertyAssertions,
        List<DataPropertyAssertion> dataPropertyAssertions
    ) {
        return new IndividualContext(iri, prefixedName, label, comment,
            explicitTypes, objectPropertyAssertions, dataPropertyAssertions,
            Optional.empty(), Optional.empty());
    }

    /**
     * v0.2 factory: create IndividualContext with inferred types.
     */
    public static IndividualContext withInferred(
        String iri, String prefixedName, String label, String comment,
        List<String> explicitTypes,
        List<ObjectPropertyAssertion> objectPropertyAssertions,
        List<DataPropertyAssertion> dataPropertyAssertions,
        String reasoningStatus,
        List<String> inferredTypes
    ) {
        return new IndividualContext(iri, prefixedName, label, comment,
            explicitTypes, objectPropertyAssertions, dataPropertyAssertions,
            Optional.of(reasoningStatus), Optional.of(inferredTypes));
    }
}