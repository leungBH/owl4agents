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
    ONTOLOGY_SCOPE("ontology_scope"),
    // v0.8.1: ISSUE-04 (DifferentIndividuals) + ISSUE-05 (SubObjectPropertyOf)
    DIFFERENT_INDIVIDUALS("different_individuals"),
    OBJECT_PROPERTY_SUBPROPERTY("object_property_subproperty");

    private final String jsonName;

    ClaimType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /**
     * v0.8.3 D7: Robust deserialization from JSON name.
     * Iterates all enum values matching the {@link #jsonName()} field, with a
     * fallback to {@link Enum#valueOf(String)} (uppercase) for backward
     * compatibility. Returns {@code null} if no match is found, so callers can
     * produce a structured error instead of propagating an exception.
     */
    public static ClaimType fromJsonName(String jsonName) {
        if (jsonName == null) return null;
        for (ClaimType t : values()) {
            if (t.jsonName.equals(jsonName)) return t;
        }
        try {
            return ClaimType.valueOf(jsonName.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}