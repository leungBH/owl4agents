package org.owl4agents.core.model;

import java.util.List;

/**
 * Readonly individual context including explicit types,
 * object property assertions, and data property assertions.
 */
public record IndividualContext(
    String iri,
    String prefixedName,
    String label,
    String comment,
    List<String> explicitTypes,
    List<ObjectPropertyAssertion> objectPropertyAssertions,
    List<DataPropertyAssertion> dataPropertyAssertions
) implements EntityContext {}