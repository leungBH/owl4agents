package org.owl4agents.toolcall;

import org.owl4agents.overlay.ToolCallCandidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * v0.8.7 TC-005 / TC-006: JSON Schema pre-validator for pipeline stage 3.
 *
 * <p>Validates {@link ToolCallCandidate#arguments()} against
 * {@link ToolContract#inputSchema()} and collects ALL structural
 * violations (not just the first) into a {@link JsonSchemaViolation}
 * list. On any violation the pipeline short-circuits: stages 4-9 are
 * skipped and the reasoner is never invoked
 * ({@code perStageTiming.owlBatchMs == 0}).</p>
 *
 * <p>The validator covers the 8+1 checks required by the
 * toolcall-model spec "JSON Schema Pre-Validation":</p>
 * <ol>
 *   <li>tool name existence (caller-supplied; the pipeline stage 2
 *       loads the contract, returning TOOL_CONTRACT_NOT_FOUND when the
 *       name has no contract — this validator assumes the contract is
 *       already loaded and only validates the {@code inputSchema})</li>
 *   <li>required arguments present ({@code "required"} violation)</li>
 *   <li>argument types ({@code "type"} violation)</li>
 *   <li>enum values ({@code "enum"} violation)</li>
 *   <li>numeric ranges ({@code "minimum"} / {@code "maximum"} /
 *       {@code "exclusiveMinimum"} / {@code "exclusiveMaximum"})</li>
 *   <li>string formats ({@code "format"} violation)</li>
 *   <li>unknown arguments ({@code "additionalProperties"} violation
 *       when the schema declares {@code additionalProperties: false})</li>
 *   <li>nested object validation ({@code "properties"} /
 *       {@code "required"} on nested objects)</li>
 *   <li>array item validation ({@code "items"} / {@code "minItems"} /
 *       {@code "maxItems"} / {@code "uniqueItems"})</li>
 * </ol>
 *
 * <p>Delegation to {@code com.networknt:json-schema-validator:1.5.2}
 * implements the full JSON Schema 2020-12 specification. This class
 * is a thin adapter that converts each {@link ValidationMessage} into
 * a {@link JsonSchemaViolation} record so the pipeline never sees
 * library-specific types.</p>
 *
 * <p>Argument value conversion: {@link ToolCallCandidate#arguments()}
 * is a {@code Map<String, String>} where each value is a JSON-encoded
 * literal (per the overlay module's {@code ToolCallCandidate}
 * javadoc). Each string is parsed as a JSON value: {@code "123"}
 * becomes an integer node, {@code "true"} a boolean node,
 * {@code "{\"foo\":1}"} an object node, {@code "[1,2,3]"} an array
 * node, and any unparseable string (e.g. {@code "hello"}) is wrapped
 * as a JSON text node.</p>
 */
public final class JsonSchemaPreValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;

    /**
     * Default constructor using JSON Schema 2020-12 spec version.
     */
    public JsonSchemaPreValidator() {
        this(SpecVersion.VersionFlag.V202012);
    }

    /**
     * Constructor allowing a custom spec version (used by tests).
     */
    public JsonSchemaPreValidator(SpecVersion.VersionFlag specVersion) {
        this.factory = JsonSchemaFactory.getInstance(specVersion);
        this.config = new SchemaValidatorsConfig();
        // Surface all violations, not just the first.
        this.config.setFailFast(false);
        // Per JSON Schema 2020-12, `format` is an annotation by default;
        // enable it as an assertion so the spec-mandated Check 5 (string
        // formats) produces violations for invalid date-time/uri/etc.
        this.config.setFormatAssertionsEnabled(true);
    }

    /**
     * Validate the candidate's arguments against the contract's
     * inputSchema. Returns the list of violations (empty on success).
     *
     * @param contract  the loaded ToolContract (must not be null)
     * @param candidate the tool call candidate (must not be null)
     * @return non-null list of {@link JsonSchemaViolation}; empty when
     *         the arguments satisfy the schema
     */
    public List<JsonSchemaViolation> validate(ToolContract contract,
                                              ToolCallCandidate candidate) {
        if (contract == null) {
            return List.of();
        }
        if (candidate == null) {
            return List.of();
        }
        Map<String, Object> schemaMap = contract.inputSchema();
        if (schemaMap == null || schemaMap.isEmpty()) {
            // No schema declared -> ?no structural checks possible.
            return List.of();
        }

        JsonNode schemaNode;
        try {
            schemaNode = MAPPER.valueToTree(schemaMap);
        } catch (RuntimeException e) {
            // Malformed schema -> ?emit a single 'schema' violation.
            return List.of(new JsonSchemaViolation(
                "$", "schema",
                "ToolContract.inputSchema is not a valid JSON Schema: "
                    + e.getMessage(),
                Optional.empty(), Optional.empty()
            ));
        }

        JsonNode instanceNode = argumentsToJsonNode(candidate.arguments());

        JsonSchema schema;
        try {
            schema = factory.getSchema(schemaNode, config);
        } catch (RuntimeException e) {
            return List.of(new JsonSchemaViolation(
                "$", "schema",
                "Failed to compile ToolContract.inputSchema: "
                    + e.getMessage(),
                Optional.empty(), Optional.empty()
            ));
        }

        Set<ValidationMessage> errors;
        try {
            errors = schema.validate(instanceNode);
        } catch (RuntimeException e) {
            return List.of(new JsonSchemaViolation(
                "$", "schema",
                "JSON Schema validation threw an exception: "
                    + e.getMessage(),
                Optional.empty(), Optional.empty()
            ));
        }

        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        // Preserve insertion order for deterministic output.
        List<JsonSchemaViolation> violations = new ArrayList<>(errors.size());
        for (ValidationMessage msg : errors) {
            violations.add(toViolation(msg));
        }
        return violations;
    }

    /**
     * TC-006: Suggest the short-circuit decision for a violation list.
     *
     * <p>Returns {@link ValidationDecision#AUTO_REPAIR} when at least one
     * violation is repairable by the LLM (missing required, type
     * mismatch, numeric range, format, items, properties). Returns
     * {@link ValidationDecision#CLARIFY} when only unknown-argument
     * ({@code additionalProperties}) violations are present — the
     * caller must disambiguate before any repair.</p>
     *
     * <p>Per design D12 / D14, the pipeline overrides this suggestion
     * to {@link ValidationDecision#REQUEST_CONFIRMATION} when the
     * contract is high-risk. This method does NOT apply that override
     * (it is the pipeline's responsibility).</p>
     */
    public ValidationDecision suggestShortCircuitDecision(
        List<JsonSchemaViolation> violations
    ) {
        if (violations == null || violations.isEmpty()) {
            return ValidationDecision.EXECUTE;
        }
        for (JsonSchemaViolation v : violations) {
            // Any non-additionalProperties violation -> ?AUTO_REPAIR.
            if (!"additionalProperties".equals(v.violationType())) {
                return ValidationDecision.AUTO_REPAIR;
            }
        }
        // Only unknown-argument violations -> ?CLARIFY.
        return ValidationDecision.CLARIFY;
    }

    // ── Helpers ──

    /**
     * Convert a {@code Map<String, String>} of JSON-encoded argument
     * values into a Jackson {@link ObjectNode} suitable for JSON Schema
     * validation. Each string value is parsed as a JSON literal;
     * unparseable values are wrapped as JSON text nodes.
     */
    static JsonNode argumentsToJsonNode(Map<String, String> arguments) {
        ObjectNode root = MAPPER.createObjectNode();
        if (arguments == null) return root;
        for (Map.Entry<String, String> e : arguments.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value == null) {
                root.putNull(key);
                continue;
            }
            JsonNode parsed = tryParseJson(value);
            root.set(key, parsed);
        }
        return root;
    }

    /**
     * Parse a string as a JSON literal. Falls back to a text node when
     * the string is not a valid JSON literal (so plain-word strings are
     * treated as their string value, not rejected).
     */
    private static JsonNode tryParseJson(String value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node != null && node.isValueNode()) {
                return node;
            }
            if (node != null && (node.isObject() || node.isArray())) {
                return node;
            }
        } catch (Exception ignored) {
            // fall through to text node
        }
        return MAPPER.getNodeFactory().textNode(value);
    }

    /**
     * Convert a networknt {@link ValidationMessage} into a
     * {@link JsonSchemaViolation} record.
     *
     * <p>Path normalization: networknt uses {@code "$.field"} for the
     * root and {@code "$.a.b"} for nested paths. We keep this form so
     * the spec scenario "instancePath points to {@code schedule.time}"
     * is satisfied literally (the {@code $} prefix is the JSON pointer
     * convention).</p>
     */
    private static JsonSchemaViolation toViolation(ValidationMessage msg) {
        // networknt 1.5.x: getPath() was removed in favor of
        // getInstanceLocation() which returns a JsonNodePath. Its
        // toString() renders as "$.field.subfield" (JSON pointer form).
        String path;
        try {
            path = msg.getInstanceLocation() != null
                ? msg.getInstanceLocation().toString() : "$";
        } catch (RuntimeException e) {
            path = "$";
        }
        if (path == null || path.isBlank()) {
            path = "$";
        }
        String code = msg.getType();
        if (code == null || code.isBlank()) {
            code = "unknown";
        }
        String message = msg.getMessage() != null ? msg.getMessage() : "";
        Optional<String> expected = extractExpected(msg, code);
        Optional<String> actual = extractActual(msg, code);
        return new JsonSchemaViolation(path, code, message, expected, actual);
    }

    /**
     * Extract the expected value(s) from the ValidationMessage.
     *
     * <p>networknt populates {@code getArguments()} with the schema-side
     * values: for {@code type} it's the expected type, for {@code enum}
     * it's the allowed values, for {@code minimum}/{@code maximum} it's
     * the bound. We render the array as a single string for the
     * {@code expectedValue} field.</p>
     */
    private static Optional<String> extractExpected(ValidationMessage msg,
                                                     String code) {
        // networknt 1.5.x: getArguments() returns Object[] (not String[])
        // because the schema-side values may be non-string JSON literals
        // (e.g. integers for minimum/maximum). We render each via
        // String.valueOf() so the caller always sees a stable string.
        Object[] args = msg.getArguments();
        if (args == null || args.length == 0) {
            // Try the schema node as a fallback.
            return Optional.empty();
        }
        if (args.length == 1) {
            return Optional.of(String.valueOf(args[0]));
        }
        // Multi-value (e.g. enum): render as a JSON-array-style string.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.valueOf(args[i]));
        }
        sb.append("]");
        return Optional.of(sb.toString());
    }

    /**
     * Extract the actual offending value from the ValidationMessage.
     *
     * <p>networknt 1.5.x: {@code getInstanceNode()} returns the Jackson
     * node that violated the schema. We serialize scalar values; complex
     * nodes (objects/arrays) are summarized as their compact JSON form.</p>
     */
    private static Optional<String> extractActual(ValidationMessage msg,
                                                   String code) {
        // For 'required' there is no instance node (the field is absent).
        if ("required".equals(code)) {
            return Optional.empty();
        }
        try {
            JsonNode instanceNode = msg.getInstanceNode();
            if (instanceNode == null) return Optional.empty();
            if (instanceNode.isTextual()) {
                return Optional.of(instanceNode.asText());
            }
            if (instanceNode.isValueNode()) {
                return Optional.of(instanceNode.toString());
            }
            // Object/array -> ?compact JSON.
            return Optional.of(instanceNode.toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Build a deterministic, ordered set from a violation list (for
     * pipeline-level de-duplication). Preserves insertion order.
     */
    public static List<JsonSchemaViolation> deduplicate(
        List<JsonSchemaViolation> violations
    ) {
        if (violations == null || violations.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<JsonSchemaViolation> out = new ArrayList<>(violations.size());
        for (JsonSchemaViolation v : violations) {
            String key = v.fieldPath() + "|" + v.violationType()
                + "|" + v.message();
            if (seen.add(key)) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * Convenience: build a violation list summary as a single string
     * (used by repairSpace and decision logging).
     */
    public static String summarize(List<JsonSchemaViolation> violations) {
        if (violations == null || violations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append("; ");
            JsonSchemaViolation v = violations.get(i);
            sb.append(v.violationType()).append(" at ").append(v.fieldPath());
            if (v.actualValue().isPresent()) {
                sb.append(" (got ").append(v.actualValue().get()).append(")");
            }
            if (v.expectedValue().isPresent()) {
                sb.append(" expected ").append(v.expectedValue().get());
            }
        }
        return sb.toString();
    }
}
