package org.owl4agents.validation;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;
import org.owl4agents.storage.CatalogStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/**
 * Hand-written stub for CatalogStore.
 * By default returns a success result with a dummy CatalogEntry
 * for any ontology ID. Can be configured to return NOT_FOUND
 * for specific ontology IDs.
 */
class StubCatalogStore extends CatalogStore {

    private Set<String> notFoundIds = Set.of();

    StubCatalogStore() {
        super(null); // HomeDirectoryResolver not needed for stub behavior
    }

    /**
     * Configure specific ontology IDs that should return ONTOLOGY_NOT_FOUND.
     */
    StubCatalogStore withNotFound(String... ids) {
        this.notFoundIds = Set.of(ids);
        return this;
    }

    @Override
    public ServiceResult<CatalogEntry> findEntry(WorkspaceId workspaceId, OntologyId ontologyId) {
        if (notFoundIds.contains(ontologyId.id())) {
            return ServiceResult.error(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND,
                "No ontology with ID '" + ontologyId.id() + "' found in the workspace catalog."));
        }

        CatalogEntry entry = new CatalogEntry(
            ontologyId,
            "stub-ontology",
            Path.of("stub/source"),
            Path.of("stub/canonical"),
            Instant.now(),
            Path.of("stub/metadata")
        );
        return ServiceResult.success(entry, org.owl4agents.core.ResultMetadata.empty());
    }
}