package org.owl4agents.core.model;

import org.owl4agents.core.OntologyId;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A catalog entry for an imported ontology.
 * Records the ontology ID, display name, source path, canonical path,
 * import timestamp, and metadata path.
 */
public record CatalogEntry(
    OntologyId ontologyId,
    String displayName,
    Path sourcePath,
    Path canonicalPath,
    Instant importTimestamp,
    Path metadataPath
) {}