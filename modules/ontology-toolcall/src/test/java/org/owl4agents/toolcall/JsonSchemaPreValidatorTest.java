package org.owl4agents.toolcall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.overlay.ToolCallCandidate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-007 unit tests for {@link JsonSchemaPreValidator} (8 spec-mandated
 * checks, all-violations-collected behavior, short-circuit suggestion).
 *
 * <p>Each test exercises one of the 8 JSON Schema checks required by spec
 * "JSON Schema Pre-Validation" using a self-contained {@link ToolContract}
 * + {@link ToolCallCandidate} pair.</p>
 */
@DisplayName("TC-007 JsonSchemaPreValidator (8 checks + short-circuit)")
class JsonSchemaPreValidatorTest {

    private final JsonSchemaPreValidator validator = new JsonSchemaPreValidator();

    private ToolCallCandidate candidate(String toolName, Map<String, String> args) {
        return new ToolCallCandidate(
            "call-1", java.util.Optional.empty(), java.util.Optional.empty(),
            toolName, java.util.Optional.empty(),
            args, java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty());
    }

    private ToolContract contract(String toolName, Map<String, Object> schema) {
        return new ToolContract(toolName, schema);
    }

    private Map<String, Object> schema(Map<String, Object> properties,
                                       List<String> required,
                                       boolean additionalPropertiesFalse) {
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            s.put("required", required);
        }
        if (additionalPropertiesFalse) {
            s.put("additionalProperties", false);
        }
        return s;
    }

    // ── Check 1: required arguments present ──

    @Test
    @DisplayName("Check 1: missing required argument -> 'required' violation")
    void missingRequiredArgument() {
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"), false));
        ToolCallCandidate cand = candidate("set_temperature", Map.of());

        List<JsonSchemaViolation> violations = validator.validate(c, cand);

        assertFalse(violations.isEmpty(),
            "Missing required argument must produce a violation");
        JsonSchemaViolation v = violations.get(0);
        assertEquals("required", v.violationType(),
            "Missing required argument must produce 'required' violation");
        // Per spec "Required argument missing"
        assertTrue(v.message().contains("targetTemperature")
                || v.message().toLowerCase().contains("targettemperature"),
            "Message should reference the missing argument name");
    }

    // ── Check 2: argument types ──

    @Test
    @DisplayName("Check 2: type mismatch -> 'type' violation with expected+actual")
    void typeMismatch() {
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"), false));
        // Pass a string for an integer argument.
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "\"hot\""));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);

        assertFalse(violations.isEmpty(),
            "Type mismatch must produce a violation");
        JsonSchemaViolation v = violations.get(0);
        assertEquals("type", v.violationType(),
            "Type mismatch must produce 'type' violation");
        // Per spec "Type mismatch collected"
        assertTrue(v.expectedValue().isPresent(),
            "Type violation must include expected type");
        assertTrue(v.actualValue().isPresent(),
            "Type violation must include actual offending value");
    }

    @Test
    @DisplayName("Check 2 pass: correct type produces no violations")
    void correctTypeNoViolations() {
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"), false));
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "22"));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertTrue(violations.isEmpty(),
            "Valid arguments must produce no violations");
    }

    // ── Check 3: enum values ──

    @Test
    @DisplayName("Check 3: enum violation lists allowed values")
    void enumViolation() {
        // Per spec "Enum violation": mode="turbo" not in ["cool","heat","fan"]
        ToolContract c = contract("set_mode", schema(
            Map.of("mode", Map.of(
                "type", "string",
                "enum", List.of("cool", "heat", "fan"))),
            List.of("mode"), false));
        ToolCallCandidate cand = candidate("set_mode",
            Map.of("mode", "\"turbo\""));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);

        assertFalse(violations.isEmpty(),
            "Enum violation must be detected");
        JsonSchemaViolation v = violations.get(0);
        assertEquals("enum", v.violationType());
        assertTrue(v.expectedValue().isPresent(),
            "Enum violation must list allowed values in expectedValue");
        String expected = v.expectedValue().get();
        assertTrue(expected.contains("cool") && expected.contains("heat")
                && expected.contains("fan"),
            "Expected value must list all allowed enum values; got: " + expected);
    }

    // ── Check 4: numeric ranges ──

    @Test
    @DisplayName("Check 4: minimum violation")
    void minimumViolation() {
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of(
                "type", "integer", "minimum", 16)),
            List.of("targetTemperature"), false));
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "10")); // 10 < 16

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertFalse(violations.isEmpty(),
            "Value below minimum must be flagged");
        // networknt reports this as 'minimum'
        assertEquals("minimum", violations.get(0).violationType());
    }

    @Test
    @DisplayName("Check 4: maximum violation")
    void maximumViolation() {
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of(
                "type", "integer", "maximum", 32)),
            List.of("targetTemperature"), false));
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "40")); // 40 > 32

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertFalse(violations.isEmpty());
        assertEquals("maximum", violations.get(0).violationType());
    }

    // ── Check 5: string formats ──

    @Test
    @DisplayName("Check 5: format violation (date-time)")
    void formatViolation() {
        ToolContract c = contract("schedule_action", schema(
            Map.of("when", Map.of(
                "type", "string", "format", "date-time")),
            List.of("when"), false));
        ToolCallCandidate cand = candidate("schedule_action",
            Map.of("when", "\"not-a-datetime\""));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        // networknt may report this as 'format'
        assertFalse(violations.isEmpty(),
            "Invalid format must produce a violation");
        // The violation type is 'format' (networknt convention)
        assertEquals("format", violations.get(0).violationType());
    }

    // ── Check 6: unknown arguments (additionalProperties=false) ──

    @Test
    @DisplayName("Check 6: unknown argument rejected when additionalProperties=false")
    void unknownArgumentRejected() {
        // Per spec "Unknown argument rejected"
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"),
            true)); // additionalProperties: false
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "22", "unknown_arg", "\"x\""));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertFalse(violations.isEmpty(),
            "Unknown argument must be flagged when additionalProperties=false");
        // networknt reports this as 'additionalProperties' or 'unevaluatedProperties'
        boolean hasAdditionalPropsViolation = violations.stream()
            .anyMatch(v -> v.violationType().toLowerCase()
                .contains("additionalproperties")
                || v.violationType().toLowerCase().contains("properties"));
        assertTrue(hasAdditionalPropsViolation,
            "Must produce additionalProperties-type violation; got types: "
                + violations.stream().map(JsonSchemaViolation::violationType)
                    .toList());
    }

    @Test
    @DisplayName("Check 6: unknown argument allowed when additionalProperties omitted")
    void unknownArgumentAllowedByDefault() {
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"),
            false)); // additionalProperties not set (default true)
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "22", "extra", "\"x\""));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        // Per JSON Schema spec, additionalProperties defaults to true
        // (no violation unless explicitly set to false)
        assertTrue(violations.isEmpty(),
            "Unknown argument should be allowed by default; got: " + violations);
    }

    // ── Check 7: nested object validation ──

    @Test
    @DisplayName("Check 7: nested object inner field type validated")
    void nestedObjectValidation() {
        // Per spec "Nested object validation": schedule.time with wrong type
        Map<String, Object> scheduleSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "time", Map.of("type", "integer")),
            "required", List.of("time"));
        ToolContract c = contract("schedule_action", schema(
            Map.of("schedule", scheduleSchema),
            List.of("schedule"), false));
        // Pass schedule.time as a string instead of integer.
        ToolCallCandidate cand = candidate("schedule_action",
            Map.of("schedule", "{\"time\":\"07:00\"}"));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertFalse(violations.isEmpty(),
            "Nested object type violation must be detected");
        // Per spec "Nested object validation": instancePath points to schedule.time
        JsonSchemaViolation v = violations.get(0);
        assertTrue(v.fieldPath().contains("time"),
            "Violation path must point to nested field 'schedule.time'; got: "
                + v.fieldPath());
    }

    // ── Check 8: array item validation ──

    @Test
    @DisplayName("Check 8: array items type validated")
    void arrayItemsValidated() {
        Map<String, Object> arraySchema = Map.of(
            "type", "array",
            "items", Map.of("type", "integer"),
            "minItems", 1);
        ToolContract c = contract("set_temperatures", schema(
            Map.of("temperatures", arraySchema),
            List.of("temperatures"), false));
        // Pass array with a non-integer item.
        ToolCallCandidate cand = candidate("set_temperatures",
            Map.of("temperatures", "[22, \"hot\", 24]"));

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertFalse(violations.isEmpty(),
            "Array item type violation must be detected");
        // Path should reference an array index
        JsonSchemaViolation v = violations.get(0);
        assertTrue(v.fieldPath().contains("temperatures")
                || v.fieldPath().contains("/1"),
            "Violation path should reference array index; got: " + v.fieldPath());
    }

    // ── All violations collected ──

    @Test
    @DisplayName("All structural errors collected before short-circuit")
    void allViolationsCollected() {
        // Per spec "All structural errors collected before short-circuit":
        // missing required + type mismatch + unknown argument in one call
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"),
            true)); // additionalProperties: false
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of(
                "targetTemperature", "\"hot\"",  // type violation
                "unknown_arg", "\"x\""));        // additionalProperties violation
        // Note: targetTemperature is present (just wrong type), and required
        // is satisfied, so we expect type + additionalProperties violations.

        List<JsonSchemaViolation> violations = validator.validate(c, cand);
        assertTrue(violations.size() >= 2,
            "Multiple violations must be collected in a single call; got: "
                + violations.size());
    }

    // ── suggestShortCircuitDecision ──

    @Test
    @DisplayName("suggestShortCircuitDecision returns AUTO_REPAIR for repairable violations")
    void suggestAutoRepairForRepairable() {
        // Type/required/enum violations -> AUTO_REPAIR
        List<JsonSchemaViolation> repairable = List.of(
            new JsonSchemaViolation("$", "type", "msg", "int", "str"),
            new JsonSchemaViolation("$", "required", "missing",
                Optional.empty(), Optional.empty()));
        assertEquals(ValidationDecision.AUTO_REPAIR,
            validator.suggestShortCircuitDecision(repairable));
    }

    @Test
    @DisplayName("suggestShortCircuitDecision returns CLARIFY for unknown-argument only")
    void suggestClarifyForUnknownArgs() {
        // Only additionalProperties violations -> CLARIFY (per spec)
        List<JsonSchemaViolation> unknownOnly = List.of(
            JsonSchemaViolation.ofUnknownArgument("arg1"),
            JsonSchemaViolation.ofUnknownArgument("arg2"));
        assertEquals(ValidationDecision.CLARIFY,
            validator.suggestShortCircuitDecision(unknownOnly));
    }

    @Test
    @DisplayName("suggestShortCircuitDecision returns EXECUTE for empty list")
    void suggestExecuteForEmpty() {
        assertEquals(ValidationDecision.EXECUTE,
            validator.suggestShortCircuitDecision(List.of()));
        assertEquals(ValidationDecision.EXECUTE,
            validator.suggestShortCircuitDecision(null));
    }

    // ── Defensive null handling ──

    @Test
    @DisplayName("Null contract or candidate returns empty list (defensive)")
    void nullInputsReturnEmpty() {
        assertTrue(validator.validate(null, candidate("x", Map.of())).isEmpty());
        assertTrue(validator.validate(contract("x", Map.of()), null).isEmpty());
    }

    @Test
    @DisplayName("Contract with empty inputSchema returns empty list")
    void emptySchemaReturnsEmpty() {
        ToolContract c = new ToolContract("noop", Map.of());
        ToolCallCandidate cand = candidate("noop", Map.of("x", "1"));
        assertTrue(validator.validate(c, cand).isEmpty(),
            "Empty inputSchema must skip JSON Schema validation");
    }

    // ── Argument value conversion ──

    @Test
    @DisplayName("String-encoded JSON values are parsed for validation")
    void stringJsonValuesAreParsed() {
        // ToolCallCandidate.arguments is Map<String, String> where each value
        // is a JSON-encoded literal. The validator must parse them.
        ToolContract c = contract("set_temperature", schema(
            Map.of("targetTemperature", Map.of("type", "integer")),
            List.of("targetTemperature"), false));
        // The string "22" must be parsed as JSON integer 22 (not "22" string)
        ToolCallCandidate cand = candidate("set_temperature",
            Map.of("targetTemperature", "22"));
        assertTrue(validator.validate(c, cand).isEmpty(),
            "String-encoded JSON integer must validate against integer schema");
    }

    @Test
    @DisplayName("Non-JSON string values are treated as text nodes")
    void nonJsonStringsAreTextNodes() {
        ToolContract c = contract("set_name", schema(
            Map.of("name", Map.of("type", "string")),
            List.of("name"), false));
        // "Alice" (without quotes) is not valid JSON; validator wraps it as
        // a text node so it validates as a string.
        ToolCallCandidate cand = candidate("set_name",
            Map.of("name", "Alice"));
        assertTrue(validator.validate(c, cand).isEmpty(),
            "Non-JSON string must be treated as text node (string type)");
    }

    @Test
    @DisplayName("Object and array string values are parsed as JSON nodes")
    void objectAndArrayStringValuesAreParsed() {
        Map<String, Object> scheduleSchema = Map.of(
            "type", "object",
            "properties", Map.of("time", Map.of("type", "integer")),
            "required", List.of("time"));
        ToolContract c = contract("schedule", schema(
            Map.of("schedule", scheduleSchema),
            List.of("schedule"), false));
        // JSON object encoded as a string
        ToolCallCandidate cand = candidate("schedule",
            Map.of("schedule", "{\"time\":7}"));
        assertTrue(validator.validate(c, cand).isEmpty(),
            "JSON object encoded as string must be parsed and validated");
    }
}
