package org.owl4agents.storage;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;

import java.nio.file.Path;

/**
 * Workspace safety checks for readonly mode.
 * Restricts read operations to cataloged ontology resources
 * and explicitly provided import paths.
 */
public class WorkspaceSafety {

    private final CatalogStore catalogStore;

    public WorkspaceSafety(CatalogStore catalogStore) {
        this.catalogStore = catalogStore;
    }

    /**
     * Check if a file path is safe to read.
     * Only cataloged resources and explicit import paths are allowed.
     */
    public ServiceResult<Void> checkReadAccess(WorkspaceId workspaceId, Path filePath) {
        // Allow if the file is cataloged (source or canonical)
        if (catalogStore.isCatalogedPath(workspaceId, filePath)) {
            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.explicit(new OntologyId(workspaceId.name())));
        }

        // Reject arbitrary file reads
        return ServiceResult.error(ServiceError.fileAccessDenied(
            filePath.toString(), "not_cataloged"));
    }

    /**
     * Check if a write-style operation is allowed.
     * In readonly mode, all write-style operations are rejected except
     * workspace initialization and ontology import (catalog setup).
     */
    public ServiceResult<Void> checkWriteAllowed(String operationType) {
        return ServiceResult.error(ServiceError.readonlyViolation(operationType));
    }
}