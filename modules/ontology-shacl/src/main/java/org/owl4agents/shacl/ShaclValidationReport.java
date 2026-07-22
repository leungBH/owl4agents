package org.owl4agents.shacl;

import java.util.List;
import java.util.Optional;

/**
 * v0.8.7 SH-003 / D6: SHACL validation report.
 *
 * <p>Carries the {@code conforms} flag plus the violations / warnings /
 * infos lists partitioned by severity. {@code conforms} is {@code true}
 * iff the violations list is empty (warnings and infos do not affect
 * conformance, per the SHACL spec).</p>
 *
 * <p>{@code shapeSetId} is populated only by
 * {@code validateRegisteredShapes} for audit purposes; the inline
 * {@code validate} path leaves it empty.</p>
 *
 * <p>{@code schemaVersion} is the report schema version ("1.0" in v0.8.7),
 * reserved for future evolution.</p>
 *
 * @param conforms       true iff no Violation-severity results were produced
 * @param violations     severity=Violation results
 * @param warnings       severity=Warning results
 * @param infos          severity=Info results
 * @param elapsedMs      wall-clock elapsed time in milliseconds
 * @param shapeSetId     shape set identifier used by validateRegisteredShapes
 *                       (empty for inline validate)
 * @param schemaVersion  report schema version, currently "1.0"
 */
public record ShaclValidationReport(
    boolean conforms,
    List<ShaclViolation> violations,
    List<ShaclViolation> warnings,
    List<ShaclViolation> infos,
    long elapsedMs,
    Optional<String> shapeSetId,
    String schemaVersion
) {
    public static final String SCHEMA_VERSION = "1.0";

    public ShaclValidationReport {
        if (violations == null) violations = List.of();
        else violations = List.copyOf(violations);
        if (warnings == null) warnings = List.of();
        else warnings = List.copyOf(warnings);
        if (infos == null) infos = List.of();
        else infos = List.copyOf(infos);
        if (shapeSetId == null) shapeSetId = Optional.empty();
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
    }

    /**
     * Build a conforming (empty) report. Useful for early-return paths
     * where no validation actually ran.
     */
    public static ShaclValidationReport empty(long elapsedMs) {
        return new ShaclValidationReport(
            true, List.of(), List.of(), List.of(), elapsedMs,
            Optional.empty(), SCHEMA_VERSION);
    }
}
