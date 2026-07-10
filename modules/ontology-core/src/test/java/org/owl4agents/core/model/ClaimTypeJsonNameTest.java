package org.owl4agents.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.3 D7: ClaimType.fromJsonName() deserialization tests.
 */
@DisplayName("TC-D7: ClaimType.fromJsonName() deserialization")
class ClaimTypeJsonNameTest {

    @Test
    @DisplayName("TC-D7-01: fromJsonName(\"disjoint_classes\") returns DISJOINT_CLASSES")
    void disjointClassesJsonName() {
        assertEquals(ClaimType.DISJOINT_CLASSES, ClaimType.fromJsonName("disjoint_classes"));
    }

    @Test
    @DisplayName("TC-D7-02: fromJsonName(\"object_property_assertion\") returns OBJECT_PROPERTY_ASSERTION")
    void objectPropertyAssertionJsonName() {
        assertEquals(ClaimType.OBJECT_PROPERTY_ASSERTION, ClaimType.fromJsonName("object_property_assertion"));
    }

    @Test
    @DisplayName("TC-D7-03: fromJsonName(\"subclass\") returns SUBCLASS")
    void subclassJsonName() {
        assertEquals(ClaimType.SUBCLASS, ClaimType.fromJsonName("subclass"));
    }

    @Test
    @DisplayName("TC-D7-04: fromJsonName(\"equivalent_classes\") returns EQUIVALENT_CLASSES")
    void equivalentClassesJsonName() {
        assertEquals(ClaimType.EQUIVALENT_CLASSES, ClaimType.fromJsonName("equivalent_classes"));
    }

    @Test
    @DisplayName("TC-D7-05: fromJsonName(\"different_individuals\") returns DIFFERENT_INDIVIDUALS")
    void differentIndividualsJsonName() {
        assertEquals(ClaimType.DIFFERENT_INDIVIDUALS, ClaimType.fromJsonName("different_individuals"));
    }

    @Test
    @DisplayName("TC-D7-06: fromJsonName(\"object_property_subproperty\") returns OBJECT_PROPERTY_SUBPROPERTY")
    void objectPropertySubpropertyJsonName() {
        assertEquals(ClaimType.OBJECT_PROPERTY_SUBPROPERTY, ClaimType.fromJsonName("object_property_subproperty"));
    }

    @Test
    @DisplayName("TC-D7-07: fromJsonName(\"not_a_type\") returns null")
    void unknownJsonNameReturnsNull() {
        assertNull(ClaimType.fromJsonName("not_a_type"));
    }

    @Test
    @DisplayName("TC-D7-08: fromJsonName(null) returns null")
    void nullJsonNameReturnsNull() {
        assertNull(ClaimType.fromJsonName(null));
    }

    @Test
    @DisplayName("TC-D7-09: fromJsonName(\"\") returns null")
    void emptyJsonNameReturnsNull() {
        assertNull(ClaimType.fromJsonName(""));
    }

    @Test
    @DisplayName("TC-D7-10: fallback to valueOf(uppercase) for backward compatibility")
    void fallbackToUppercase() {
        assertEquals(ClaimType.SUBCLASS, ClaimType.fromJsonName("SUBCLASS"));
        assertEquals(ClaimType.DISJOINT_CLASSES, ClaimType.fromJsonName("DISJOINT_CLASSES"));
    }
}
