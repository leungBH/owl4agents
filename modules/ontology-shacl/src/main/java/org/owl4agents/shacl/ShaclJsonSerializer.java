package org.owl4agents.shacl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * v0.8.7 SH-003 / D8: Canonical JSON-serializable Map builders for
 * {@link ShaclValidationReport}, {@link ShaclViolation}, and {@link ShapeSet}.
 *
 * <p>Both the CLI ({@code --output json}) and the MCP tools
 * ({@code ontology_validate_shacl}, {@code ontology_list_shape_sets},
 * {@code ontology_get_shape_set}) use these helpers to produce byte-for-byte
 * identical output for the same fixtures, satisfying the
 * "MCP/CLI/Java parity" requirement.</p>
 *
 * <p>Nullable fields ({@code resultPath}, {@code value}) are explicitly
 * serialized as {@code null} (not omitted) to keep the schema stable.</p>
 */
public final class ShaclJsonSerializer {

    private ShaclJsonSerializer() {}

    /**
     * Serialize a {@link ShaclValidationReport} to a Map (ready for Gson).
     * Field order is fixed for byte-for-byte parity.
     */
    public static Map<String, Object> reportToMap(ShaclValidationReport report) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", report.schemaVersion());
        m.put("conforms", report.conforms());
        m.put("violations", report.violations().stream()
            .map(ShaclJsonSerializer::violationToMap)
            .collect(Collectors.toList()));
        m.put("warnings", report.warnings().stream()
            .map(ShaclJsonSerializer::violationToMap)
            .collect(Collectors.toList()));
        m.put("infos", report.infos().stream()
            .map(ShaclJsonSerializer::violationToMap)
            .collect(Collectors.toList()));
        m.put("elapsedMs", report.elapsedMs());
        m.put("shapeSetId", report.shapeSetId().orElse(null));
        return m;
    }

    /**
     * Serialize a {@link ShaclViolation} to a Map with EXACTLY 10 fields,
     * nullable fields emitted as {@code null} (not omitted).
     */
    public static Map<String, Object> violationToMap(ShaclViolation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("violationId", v.violationId());
        m.put("sourceShape", v.sourceShape());
        m.put("sourceConstraintComponent", v.sourceConstraintComponent());
        m.put("focusNode", v.focusNode());
        m.put("resultPath", v.resultPath());      // nullable -> JSON null
        m.put("value", v.value());                 // nullable -> JSON null
        m.put("severity", v.severity().jsonName());
        m.put("message", v.message());
        m.put("evidenceTriples", v.evidenceTriples());
        m.put("repairHint", v.repairHint());
        return m;
    }

    /**
     * Serialize a {@link ShapeSet} metadata to a Map for the
     * {@code ontology_list_shape_sets} and {@code ontology_get_shape_set}
     * MCP tools. Per spec, the raw shapes Model content is NEVER included
     * (only metadata).
     */
    public static Map<String, Object> shapeSetToMap(ShapeSet ss) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ss.id());
        m.put("version", ss.version());
        m.put("domain", ss.domain());
        m.put("checksum", ss.checksum());
        m.put("enabled", ss.enabled());
        m.put("trusted", ss.trusted());
        m.put("requiresInference", ss.requiresInference());
        // sourcePath is intentionally OMITTED from MCP responses to avoid
        // leaking internal filesystem paths (spec: "list shape sets returns
        // metadata" / "get shape set returns metadata only").
        return m;
    }

    /**
     * Variant that includes sourcePath (used by the CLI which is operator-
     * controlled and may legitimately need to inspect the file path).
     */
    public static Map<String, Object> shapeSetToMapWithSourcePath(ShapeSet ss) {
        Map<String, Object> m = shapeSetToMap(ss);
        m.put("sourcePath", ss.sourcePath().toString());
        return m;
    }
}
