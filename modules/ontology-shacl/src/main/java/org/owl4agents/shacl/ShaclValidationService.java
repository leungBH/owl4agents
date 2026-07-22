package org.owl4agents.shacl;

import org.owl4agents.core.ServiceResult;
import org.apache.jena.rdf.model.Model;

/**
 * v0.8.7 SH-002 / D6: SHACL validation service interface.
 *
 * <p>Two methods:</p>
 * <ul>
 *   <li>{@link #validate(Model, Model, ShaclValidationOptions)} — inline shapes
 *       (used by the {@code shacl-validate} CLI when --shapes is a file path)</li>
 *   <li>{@link #validateRegisteredShapes(String, Model, ShaclValidationOptions)} —
 *       registered shapes (used by the {@code ontology_validate_shacl} MCP tool
 *       and the CLI when --shapes is a shape_set_id)</li>
 * </ul>
 *
 * <p>Implementations MUST delegate to Apache Jena SHACL
 * ({@code org.apache.jena.shacl.ShaclValidator}) at the same Jena version
 * used by {@code ontology-query} (5.3.0), and MUST NOT expose any Jena
 * {@code ValidationReport} object directly to the caller.</p>
 */
public interface ShaclValidationService {

    /**
     * Validate a data graph against inline SHACL shapes.
     *
     * @param dataGraph   the data graph to validate
     * @param shapesGraph the shapes graph (inline SHACL)
     * @param options     validation options (severity filter, timeout, etc.)
     * @return success containing a {@link ShaclValidationReport}, or error
     *         with one of: {@code SHACL_SHAPES_MALFORMED} (shapes parse failure),
     *         {@code SHACL_TIMEOUT} (SPARQL constraint timeout)
     */
    ServiceResult<ShaclValidationReport> validate(
        Model dataGraph,
        Model shapesGraph,
        ShaclValidationOptions options
    );

    /**
     * Validate a data graph against a registered ShapeSet.
     *
     * @param shapeSetId the registered ShapeSet identifier
     * @param dataGraph  the data graph to validate
     * @param options    validation options (severity filter, timeout, etc.)
     * @return success containing a {@link ShaclValidationReport} with the
     *         {@code shapeSetId} field populated, or error with one of:
     *         {@code SHAPE_SET_NOT_FOUND}, {@code SHACL_SHAPES_LOAD_FAILED},
     *         {@code SHACL_TIMEOUT}
     */
    ServiceResult<ShaclValidationReport> validateRegisteredShapes(
        String shapeSetId,
        Model dataGraph,
        ShaclValidationOptions options
    );
}
