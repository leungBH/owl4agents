package org.owl4agents.core.model;

/**
 * Supported v0.3 claim types for structured claim verification.
 * Each type maps to a specific verification strategy using v0.2 reasoning primitives.
 */
public enum ClaimType {
    SUBCLASS("subclass"),
    EQUIVALENT_CLASSES("equivalent_classes"),
    DISJOINT_CLASSES("disjoint_classes"),
    INDIVIDUAL_MEMBERSHIP("individual_membership"),
    OBJECT_PROPERTY_ASSERTION("object_property_assertion"),
    DATA_PROPERTY_ASSERTION("data_property_assertion"),
    OBJECT_PROPERTY_DOMAIN("object_property_domain"),
    OBJECT_PROPERTY_RANGE("object_property_range"),
    DATA_PROPERTY_DOMAIN("data_property_domain"),
    DATA_PROPERTY_RANGE("data_property_range"),
    LITERAL_VALIDITY("literal_validity"),
    CLASS_COMPATIBILITY("class_compatibility"),
    ONTOLOGY_CONSISTENCY("ontology_consistency"),
    ONTOLOGY_SCOPE("ontology_scope");

    private final String jsonName;

    ClaimType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}