package org.owl4agents.core;

/**
 * Enum representing the types of ontology entities.
 */
public enum EntityType {
    CLASS("class"),
    OBJECT_PROPERTY("object_property"),
    DATA_PROPERTY("data_property"),
    ANNOTATION_PROPERTY("annotation_property"),
    INDIVIDUAL("individual"),
    DATATYPE("datatype");

    private final String jsonName;

    EntityType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}