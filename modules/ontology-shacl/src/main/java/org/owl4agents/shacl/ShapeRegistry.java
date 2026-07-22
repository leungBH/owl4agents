package org.owl4agents.shacl;

import org.owl4agents.core.ServiceResult;
import org.apache.jena.rdf.model.Model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * v0.8.7 SH-004 / D7: Shape Registry interface.
 *
 * <p>Manages trusted ShapeSet registration and provides cached resolution
 * of the parsed Jena {@link Model}. Registration is only possible via
 * {@link #register(String, Path, String, boolean, boolean)} (CLI
 * {@code shacl-register} or Java API); the MCP tool MUST NOT accept inline
 * shapes.</p>
 *
 * <p>Implementations MUST:</p>
 * <ul>
 *   <li>cache the parsed Model in a Caffeine cache ({@code maximumSize=50})</li>
 *   <li>on every {@link #resolve(String)}, verify the source file's mtime and
 *       SHA256 checksum against the cached metadata; reload on mismatch</li>
 *   <li>persist the ShapeSet registry to {@code ~/.owl4agents/shapes/registry.json}</li>
 *   <li>enforce SHAPE_SET_ID_CONFLICT unless {@code force=true} on re-registration</li>
 * </ul>
 */
public interface ShapeRegistry {

    /**
     * Register a new ShapeSet or overwrite an existing one.
     *
     * @param id                shape set identifier
     * @param shapesFile        path to the shapes file (Turtle, RDF/XML, JSON-LD, etc.)
     * @param domain            domain tag (may be blank — defaults to "default")
     * @param force             when true, overwrite an existing entry with a different
     *                          checksum; when false, return SHAPE_SET_ID_CONFLICT
     * @param requiresInference whether the ShapeSet's sh:sparql constraints require
     *                          inferred facts (persisted with the ShapeSet)
     * @return success containing the registered {@link ShapeSet}, or error with:
     *         SHACL_SHAPES_MALFORMED, SHAPE_SET_ID_CONFLICT, IMPORT_FAILED (I/O)
     */
    ServiceResult<ShapeSet> register(String id, Path shapesFile, String domain,
                                     boolean force, boolean requiresInference);

    /**
     * Resolve a ShapeSet to its parsed Jena Model, reloading from disk if the
     * source file's mtime or SHA256 checksum has changed since the last load.
     *
     * @param shapeSetId the registered shape set identifier
     * @return success containing the parsed Model, or error with:
     *         SHAPE_SET_NOT_FOUND, SHACL_SHAPES_LOAD_FAILED, SHACL_SHAPES_MALFORMED
     */
    ServiceResult<Model> resolve(String shapeSetId);

    /**
     * Forcibly remove the cache entry for a ShapeSet. The next
     * {@link #resolve(String)} call will reload the file from disk.
     *
     * @param shapeSetId the registered shape set identifier
     * @return success (void) or error with SHAPE_SET_NOT_FOUND
     */
    ServiceResult<Void> invalidate(String shapeSetId);

    /**
     * List all registered ShapeSets (metadata only, no Models).
     */
    List<ShapeSet> list();

    /**
     * Get a single ShapeSet's metadata by id.
     */
    Optional<ShapeSet> get(String shapeSetId);
}
