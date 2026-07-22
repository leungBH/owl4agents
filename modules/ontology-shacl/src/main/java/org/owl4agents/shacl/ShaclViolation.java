package org.owl4agents.shacl;

import java.util.List;

/**
 * v0.8.7 SH-003 / D8: Unified SHACL violation model.
 *
 * <p>A JSON-serializable record with EXACTLY 10 fields. Nullable fields
 * ({@code resultPath}, {@code value}) MUST be serialized as JSON {@code null}
 * (not omitted) to keep the schema stable across MCP, CLI, and Java API
 * outputs.</p>
 *
 * <p>The {@code evidenceTriples} list is bounded to at most 10 entries,
 * each formatted as {@code "subject predicate object"}.</p>
 *
 * <p>Conversion from Jena's {@code ValidationReport} happens in
 * {@code JenaShaclValidationService}; the Jena object is never exposed to
 * the caller.</p>
 *
 * @param violationId             UUIDv4 string identifying this violation
 * @param sourceShape             Shape IRI that produced the violation
 * @param sourceConstraintComponent constraint component IRI
 *                                 (e.g. {@code "sh:minCount"}, {@code "sh:sparql"})
 * @param focusNode               IRI of the validated focus node
 * @param resultPath              property IRI, or {@code null} for node shapes
 * @param value                   violating value, or {@code null} when none recorded
 * @param severity                {@link Severity#Violation}, {@link Severity#Warning},
 *                                 or {@link Severity#Info}
 * @param message                 human-readable message from the SHACL engine
 * @param evidenceTriples         list of {@code "subject predicate object"} strings
 *                                 (max 10 entries)
 * @param repairHint              human-readable suggestion for fixing the violation
 */
public record ShaclViolation(
    String violationId,
    String sourceShape,
    String sourceConstraintComponent,
    String focusNode,
    String resultPath,
    String value,
    Severity severity,
    String message,
    List<String> evidenceTriples,
    String repairHint
) {
    /**
     * Compact canonical constructor: enforce non-null invariants and bound
     * the {@code evidenceTriples} list to 10 entries.
     */
    public ShaclViolation {
        if (violationId == null) {
            violationId = java.util.UUID.randomUUID().toString();
        }
        if (sourceShape == null) sourceShape = "";
        if (sourceConstraintComponent == null) sourceConstraintComponent = "";
        if (focusNode == null) focusNode = "";
        if (severity == null) severity = Severity.Violation;
        if (message == null) message = "";
        if (evidenceTriples == null) {
            evidenceTriples = List.of();
        } else if (evidenceTriples.size() > 10) {
            evidenceTriples = List.copyOf(evidenceTriples.subList(0, 10));
        } else {
            evidenceTriples = List.copyOf(evidenceTriples);
        }
        if (repairHint == null) repairHint = "";
    }
}
