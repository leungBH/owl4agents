package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link JsonSchemaViolation} (5 fields, convenience
 * helpers {@link JsonSchemaViolation#ofRequired},
 * {@link JsonSchemaViolation#ofUnknownArgument},
 * {@link JsonSchemaViolation#ofEnum}).
 */
@DisplayName("TC-007 JsonSchemaViolation record")
class JsonSchemaViolationTest {

    @Test
    @DisplayName("Record exposes exactly 5 fields per spec TC-005")
    void exactlyFiveFields() {
        assertEquals(5, JsonSchemaViolation.class.getRecordComponents().length,
            "JsonSchemaViolation must have exactly 5 fields per spec TC-005");
    }

    @Test
    @DisplayName("Canonical constructor preserves all 5 fields")
    void canonicalConstructorPreservesFields() {
        JsonSchemaViolation v = new JsonSchemaViolation(
            "$.schedule.time", "type",
            "expected integer, got string",
            "integer", "07:00");
        assertEquals("$.schedule.time", v.fieldPath());
        assertEquals("type", v.violationType());
        assertEquals("expected integer, got string", v.message());
        assertEquals(Optional.of("integer"), v.expectedValue());
        assertEquals(Optional.of("07:00"), v.actualValue());
    }

    @Test
    @DisplayName("Convenience constructor accepts nullable expected/actual")
    void convenienceConstructorHandlesNulls() {
        JsonSchemaViolation v = new JsonSchemaViolation(
            "$", "required", "missing field",
            Optional.empty(), Optional.empty());
        assertEquals(Optional.empty(), v.expectedValue());
        assertEquals(Optional.empty(), v.actualValue());
    }

    @Test
    @DisplayName("Blank fieldPath defaults to '$'")
    void blankFieldPathDefaultsToRoot() {
        JsonSchemaViolation v = new JsonSchemaViolation(
            "", "type", "msg",
            Optional.empty(), Optional.empty());
        assertEquals("$", v.fieldPath(),
            "Blank fieldPath must normalize to '$' per spec");
    }

    @Test
    @DisplayName("Blank violationType defaults to 'unknown'")
    void blankViolationTypeDefaultsToUnknown() {
        JsonSchemaViolation v = new JsonSchemaViolation(
            "$", "", "msg",
            Optional.empty(), Optional.empty());
        assertEquals("unknown", v.violationType());
    }

    @Test
    @DisplayName("ofRequired builds a 'required' violation for missing argument")
    void ofRequiredBuildsCorrectViolation() {
        JsonSchemaViolation v = JsonSchemaViolation.ofRequired(
            "$.schedule", "time");
        assertEquals("$.schedule", v.fieldPath());
        assertEquals("required", v.violationType());
        assertTrue(v.message().contains("time"),
            "Message should name the missing argument");
        assertEquals(Optional.empty(), v.expectedValue(),
            "required violation has no expected value (presence only)");
        assertEquals(Optional.empty(), v.actualValue(),
            "required violation has no actual value (field is absent)");
    }

    @Test
    @DisplayName("ofUnknownArgument builds 'additionalProperties' violation")
    void ofUnknownArgumentBuildsCorrectViolation() {
        JsonSchemaViolation v = JsonSchemaViolation.ofUnknownArgument("unknown_arg");
        assertEquals("$.unknown_arg", v.fieldPath(),
            "Field path must point to the unknown argument");
        assertEquals("additionalProperties", v.violationType());
        assertTrue(v.message().contains("unknown_arg"));
        assertEquals(Optional.of("unknown_arg"), v.actualValue());
    }

    @Test
    @DisplayName("ofEnum builds 'enum' violation listing allowed values")
    void ofEnumBuildsCorrectViolation() {
        JsonSchemaViolation v = JsonSchemaViolation.ofEnum(
            "$.mode", "turbo", List.of("cool", "heat", "fan"));
        assertEquals("$.mode", v.fieldPath());
        assertEquals("enum", v.violationType());
        assertEquals(Optional.of("turbo"), v.actualValue());
        assertTrue(v.expectedValue().isPresent(),
            "Enum violation must populate expectedValue with allowed values");
        String expected = v.expectedValue().get();
        assertTrue(expected.contains("cool") && expected.contains("heat")
                && expected.contains("fan"),
            "expectedValue must list all allowed values; got: " + expected);
    }

    @Test
    @DisplayName("ofEnum tolerates null allowedValues list")
    void ofEnumToleratesNullList() {
        JsonSchemaViolation v = JsonSchemaViolation.ofEnum(
            "$.mode", "turbo", null);
        assertEquals("enum", v.violationType());
        // Should not throw; expectedValue present but represents empty list.
        assertTrue(v.expectedValue().isPresent());
    }

    @Test
    @DisplayName("Serialization via ToolCallJsonSerializer preserves field order")
    void serializerPreservesFieldOrder() {
        JsonSchemaViolation v = new JsonSchemaViolation(
            "$.x", "type", "msg", "int", "str");
        java.util.Map<String, Object> m =
            ToolCallJsonSerializer.violationToMap(v);
        // 5 fields, in fixed order
        assertEquals(5, m.size());
        java.util.List<String> keys = new java.util.ArrayList<>(m.keySet());
        assertEquals(java.util.List.of(
            "fieldPath", "violationType", "message",
            "expectedValue", "actualValue"), keys,
            "Serializer must preserve field order for parity");
    }

    @Test
    @DisplayName("Nullable fields are emitted as null (not omitted)")
    void nullableFieldsEmittedAsNull() {
        JsonSchemaViolation v = new JsonSchemaViolation(
            "$", "required", "missing",
            Optional.empty(), Optional.empty());
        java.util.Map<String, Object> m =
            ToolCallJsonSerializer.violationToMap(v);
        // Nullable fields must be present with null value (not omitted)
        assertTrue(m.containsKey("expectedValue"));
        assertNull(m.get("expectedValue"));
        assertTrue(m.containsKey("actualValue"));
        assertNull(m.get("actualValue"));
    }
}
