package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link ToolContract} (9 fields, defensive copies,
 * minimal convenience constructor, {@link ToolContract#requiresShacl()},
 * {@link ToolContract#targetsEntity()}, JSON serialization round-trip).
 */
@DisplayName("TC-007 ToolContract record")
class ToolContractTest {

    @Test
    @DisplayName("Record exposes exactly 9 fields per spec TC-002")
    void exactlyNineFields() {
        assertEquals(9, ToolContract.class.getRecordComponents().length,
            "ToolContract must have exactly 9 fields per spec TC-002");
    }

    @Test
    @DisplayName("All 9 spec-mandated fields are present")
    void allSpecFieldsPresent() {
        // Verify field names match spec exactly
        java.util.Set<String> fieldNames = java.util.Arrays.stream(
                ToolContract.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(fieldNames.containsAll(java.util.List.of(
            "toolName", "inputSchema", "targetClass", "requiredCapabilities",
            "requiredStates", "effects", "riskLevel", "requiredPermission",
            "shapeSetIds")));
    }

    @Test
    @DisplayName("Canonical constructor defensive-copies lists and maps")
    void defensiveCopies() {
        Map<String, Object> schema = new java.util.HashMap<>(Map.of("type", "object"));
        List<String> capabilities = new java.util.ArrayList<>(List.of("cap1"));
        List<String> states = new java.util.ArrayList<>(List.of("state1"));
        List<String> effects = new java.util.ArrayList<>(List.of("effect1"));
        List<String> shapeSetIds = new java.util.ArrayList<>(List.of("ss1"));

        ToolContract c = new ToolContract(
            "set_temperature", schema, Optional.of("http://ex#Thermostat"),
            capabilities, states, effects, RiskLevel.HIGH,
            Optional.of("http://ex#Admin"), shapeSetIds);

        // Mutate the originals -> ?the contract must be unaffected.
        schema.put("extra", "leaked");
        capabilities.add("cap2");
        states.add("state2");
        effects.add("effect2");
        shapeSetIds.add("ss2");

        assertFalse(c.inputSchema().containsKey("extra"),
            "inputSchema must be defensively copied");
        assertEquals(1, c.requiredCapabilities().size(),
            "requiredCapabilities must be defensively copied");
        assertEquals(1, c.requiredStates().size());
        assertEquals(1, c.effects().size());
        assertEquals(1, c.shapeSetIds().size());
    }

    @Test
    @DisplayName("Defensive copies are unmodifiable")
    void copiesAreUnmodifiable() {
        ToolContract c = new ToolContract(
            "t", Map.of(), Optional.empty(),
            List.of("cap"), List.of("s"), List.of("e"),
            RiskLevel.LOW, Optional.empty(), List.of("ss"));
        assertThrows(UnsupportedOperationException.class,
            () -> c.requiredCapabilities().add("x"));
        assertThrows(UnsupportedOperationException.class,
            () -> c.shapeSetIds().add("x"));
        assertThrows(UnsupportedOperationException.class,
            () -> c.effects().add("x"));
    }

    @Test
    @DisplayName("Blank toolName is rejected")
    void blankToolNameRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new ToolContract("", Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ToolContract(null, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ToolContract("   ", Map.of()));
    }

    @Test
    @DisplayName("Minimal constructor defaults to LOW risk, no target, no shapes")
    void minimalConstructorDefaults() {
        ToolContract c = new ToolContract("list_devices", Map.of("type", "object"));
        assertEquals("list_devices", c.toolName());
        assertEquals(Map.of("type", "object"), c.inputSchema());
        assertEquals(Optional.empty(), c.targetClass());
        assertTrue(c.requiredCapabilities().isEmpty());
        assertTrue(c.requiredStates().isEmpty());
        assertTrue(c.effects().isEmpty());
        assertEquals(RiskLevel.LOW, c.riskLevel(),
            "Minimal contract must default to LOW risk");
        assertEquals(Optional.empty(), c.requiredPermission());
        assertTrue(c.shapeSetIds().isEmpty());
    }

    @Test
    @DisplayName("Null lists/maps in canonical constructor are tolerated")
    void nullCollectionsTolerated() {
        ToolContract c = new ToolContract(
            "t", null, null, null, null, null,
            null, null, null);
        assertEquals("t", c.toolName());
        assertTrue(c.inputSchema().isEmpty());
        assertEquals(Optional.empty(), c.targetClass());
        assertTrue(c.requiredCapabilities().isEmpty());
        assertTrue(c.requiredStates().isEmpty());
        assertTrue(c.effects().isEmpty());
        assertEquals(RiskLevel.LOW, c.riskLevel(),
            "Null riskLevel must default to LOW (not crash)");
        assertEquals(Optional.empty(), c.requiredPermission());
        assertTrue(c.shapeSetIds().isEmpty());
    }

    @Test
    @DisplayName("requiresShacl() is true only when shapeSetIds is non-empty")
    void requiresShaclLogic() {
        assertFalse(new ToolContract("t", Map.of()).requiresShacl(),
            "Contract with no shapeSetIds must not require SHACL");
        assertTrue(new ToolContract("t", Map.of(), Optional.empty(),
            List.of(), List.of(), List.of(),
            RiskLevel.LOW, Optional.empty(), List.of("ss1")).requiresShacl(),
            "Contract with shapeSetIds must require SHACL");
    }

    @Test
    @DisplayName("targetsEntity() detects entity-targeting contracts")
    void targetsEntityLogic() {
        // Per spec "Contract with no target class" scenario: when targetClass,
        // requiredCapabilities, requiredStates are all empty, the pipeline
        // skips target-class claim decomposition.
        ToolContract noTarget = new ToolContract(
            "list_devices", Map.of(), Optional.empty(),
            List.of(), List.of(), List.of(),
            RiskLevel.LOW, Optional.empty(), List.of());
        assertFalse(noTarget.targetsEntity(),
            "list_devices must not target an entity");

        ToolContract withTargetClass = new ToolContract(
            "set_temperature", Map.of(),
            Optional.of("http://ex#Thermostat"),
            List.of(), List.of(), List.of(),
            RiskLevel.LOW, Optional.empty(), List.of());
        assertTrue(withTargetClass.targetsEntity());

        ToolContract withCapabilities = new ToolContract(
            "set_temperature", Map.of(), Optional.empty(),
            List.of("http://ex#TemperatureControl"),
            List.of(), List.of(),
            RiskLevel.LOW, Optional.empty(), List.of());
        assertTrue(withCapabilities.targetsEntity(),
            "requiredCapabilities must trigger targetsEntity");
    }

    @Test
    @DisplayName("JSON serialization round-trips all 9 fields")
    void jsonSerializationRoundTrip() {
        ToolContract c = new ToolContract(
            "unlock_door", Map.of("type", "object", "properties",
                Map.of("targetTemperature", Map.of("type", "integer"))),
            Optional.of("http://ex#Door"),
            List.of("http://ex#UnlockCapability"),
            List.of("door(closed)"),
            List.of("changesState(door, unlocked)"),
            RiskLevel.HIGH,
            Optional.of("http://ex#AdminPermission"),
            List.of("smart-home-shapes-v1"));

        Map<String, Object> m = ToolCallJsonSerializer.contractToMap(c);

        // All 9 fields present per spec "Contract with high risk" scenario
        assertEquals(9, m.size(), "All 9 fields must be serialized");
        assertEquals("unlock_door", m.get("toolName"));
        assertTrue(m.get("inputSchema") instanceof Map);
        assertEquals("http://ex#Door", m.get("targetClass"));
        assertEquals(List.of("http://ex#UnlockCapability"),
            m.get("requiredCapabilities"));
        assertEquals(List.of("door(closed)"), m.get("requiredStates"));
        assertEquals(List.of("changesState(door, unlocked)"), m.get("effects"));
        assertEquals("high", m.get("riskLevel"),
            "riskLevel must serialize to lowercase jsonName");
        assertEquals("http://ex#AdminPermission", m.get("requiredPermission"));
        assertEquals(List.of("smart-home-shapes-v1"), m.get("shapeSetIds"));
    }

    @Test
    @DisplayName("Nullable fields are emitted as null (not omitted) for parity")
    void nullableFieldsEmittedAsNull() {
        ToolContract c = new ToolContract("list_devices", Map.of());
        Map<String, Object> m = ToolCallJsonSerializer.contractToMap(c);
        // Spec "targetEntity nullable" + ToolContract parity: nullable fields
        // must be present in JSON output as null.
        assertTrue(m.containsKey("targetClass"));
        assertNull(m.get("targetClass"));
        assertTrue(m.containsKey("requiredPermission"));
        assertNull(m.get("requiredPermission"));
    }

    @Test
    @DisplayName("Serializer preserves field order for byte-for-byte parity")
    void serializerPreservesFieldOrder() {
        ToolContract c = new ToolContract("t", Map.of());
        Map<String, Object> m = ToolCallJsonSerializer.contractToMap(c);
        java.util.List<String> keys = new java.util.ArrayList<>(m.keySet());
        assertEquals(java.util.List.of(
            "toolName", "inputSchema", "targetClass", "requiredCapabilities",
            "requiredStates", "effects", "riskLevel", "requiredPermission",
            "shapeSetIds"), keys,
            "Serializer must preserve spec field order for MCP/CLI/Java parity");
    }
}
