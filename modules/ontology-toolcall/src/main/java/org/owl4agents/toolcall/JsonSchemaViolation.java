package org.owl4agents.toolcall;

import java.util.List;
import java.util.Optional;

/**
 * v0.8.7 TC-005: A single JSON Schema violation collected by
 * {@link JsonSchemaPreValidator} at pipeline stage 3.
 *
 * <p>The validator collects ALL structural violations (not just the first)
 * so the caller receives a complete {@code repairSpace}. Each violation
 * carries enough information for the LLM repair loop to reconstruct the
 * offending argument and the expected schema constraint.</p>
 *
 * <p>Five fields, JSON-serializable, MCP/CLI/Java API parity via
 * {@link ToolCallJsonSerializer#violationToMap(JsonSchemaViolation)}.</p>
 *
 * @param fieldPath       JSON pointer path to the offending instance
 *                        node (e.g. {@code "$"}, {@code "$.schedule.time"}).
 *                        For top-level violations the path is {@code "$"}.
 * @param violationType   short violation code derived from the JSON Schema
 *                        keyword that failed: {@code "required"},
 *                        {@code "type"}, {@code "enum"}, {@code "minimum"},
 *                        {@code "maximum"}, {@code "format"},
 *                        {@code "additionalProperties"}, {@code "items"},
 *                        {@code "properties"}, etc.
 * @param message         human-readable message describing the violation
 * @param expectedValue   the expected value(s) from the schema (e.g.
 *                        {@code "integer"} for a type mismatch,
 *                        {@code ["cool","heat","fan"]} for an enum
 *                        violation, {@code 0} for a minimum). May be
 *                        empty when the schema does not constrain the
 *                        value (e.g. {@code required} only checks presence).
 * @param actualValue     the offending value as it appeared in the tool
 *                        call arguments (e.g. {@code "turbo"} for an
 *                        enum violation). May be empty for {@code required}
 *                        violations (the value is absent).
 */
public record JsonSchemaViolation(
    String fieldPath,
    String violationType,
    String message,
    Optional<String> expectedValue,
    Optional<String> actualValue
) {
    public JsonSchemaViolation {
        if (fieldPath == null || fieldPath.isBlank()) {
            fieldPath = "$";
        }
        if (violationType == null || violationType.isBlank()) {
            violationType = "unknown";
        }
        if (message == null) message = "";
        if (expectedValue == null) expectedValue = Optional.empty();
        if (actualValue == null) actualValue = Optional.empty();
    }

    /**
     * Convenience constructor for a violation with a non-empty expected
     * value and an actual value.
     */
    public JsonSchemaViolation(String fieldPath, String violationType,
                                String message, String expectedValue,
                                String actualValue) {
        this(fieldPath, violationType, message,
            Optional.ofNullable(expectedValue).filter(s -> !s.isBlank()),
            Optional.ofNullable(actualValue).filter(s -> !s.isBlank()));
    }

    /**
     * Convenience constructor for a violation with no expected value
     * (e.g. {@code required} only checks presence).
     */
    public static JsonSchemaViolation ofRequired(String fieldPath,
                                                  String argumentName) {
        return new JsonSchemaViolation(
            fieldPath,
            "required",
            "Missing required argument: " + argumentName,
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Convenience constructor for an additionalProperties violation
     * naming the unknown argument.
     */
    public static JsonSchemaViolation ofUnknownArgument(String argumentName) {
        return new JsonSchemaViolation(
            "$." + argumentName,
            "additionalProperties",
            "Unknown argument not allowed by schema: " + argumentName,
            Optional.empty(),
            Optional.of(argumentName)
        );
    }

    /**
     * Convenience constructor for a list-backed expected value (e.g.
     * enum). The list is rendered as a comma-separated string.
     */
    public static JsonSchemaViolation ofEnum(String fieldPath,
                                              String actualValue,
                                              List<String> allowedValues) {
        String expected = allowedValues == null
            ? "" : String.join(", ", allowedValues);
        return new JsonSchemaViolation(
            fieldPath,
            "enum",
            "Value '" + actualValue + "' is not in the allowed enum values",
            Optional.of("[" + expected + "]"),
            Optional.of(actualValue)
        );
    }
}
