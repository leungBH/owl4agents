package org.owl4agents.shacl;

import java.nio.file.Path;

/**
 * v0.8.7 SH-004 / D7: Registered ShapeSet metadata.
 *
 * <p>Each record captures a single trusted SHACL shapes file registered via
 * the {@code shacl-register} CLI or Java API. The MCP {@code ontology_validate_shacl}
 * tool references a ShapeSet exclusively by {@code id}; inline shapes are
 * rejected per the "Agent Cannot Upload Arbitrary SHACL-SPARQL" requirement.</p>
 *
 * @param id                globally unique shape set identifier
 *                          (e.g. {@code "smart-home-core"})
 * @param version           semantic version (e.g. {@code "1.0.0"})
 * @param domain            domain tag (e.g. {@code "smart-home"})
 * @param sourcePath        absolute path to the original shapes file on disk
 * @param checksum          SHA256 of file bytes (64 lowercase hex chars)
 * @param enabled           whether the ShapeSet is currently enabled
 * @param trusted           always {@code true} after registration
 * @param requiresInference whether the ShapeSet's {@code sh:sparql} constraints
 *                          require inferred facts (pipeline runs SHACL on the
 *                          inferred overlay when {@code true})
 */
public record ShapeSet(
    String id,
    String version,
    String domain,
    Path sourcePath,
    String checksum,
    boolean enabled,
    boolean trusted,
    boolean requiresInference
) {
    public ShapeSet {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ShapeSet.id must not be blank");
        }
        if (sourcePath == null) {
            throw new IllegalArgumentException("ShapeSet.sourcePath must not be null");
        }
        if (version == null || version.isBlank()) version = "1.0.0";
        if (domain == null || domain.isBlank()) domain = "default";
        if (checksum == null) checksum = "";
    }
}
