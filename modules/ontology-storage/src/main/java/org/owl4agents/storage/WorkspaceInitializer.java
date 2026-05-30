package org.owl4agents.storage;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Initializes the default workspace with config, catalog, ontology,
 * index, and log directories.
 */
public class WorkspaceInitializer {

    private final HomeDirectoryResolver homeResolver;

    public WorkspaceInitializer(HomeDirectoryResolver homeResolver) {
        this.homeResolver = homeResolver;
    }

    /**
     * Initialize a workspace. If it already exists, leave existing data intact
     * and report that it is already initialized.
     */
    public ServiceResult<Void> initialize(WorkspaceId workspaceId) {
        Path workspaceDir = homeResolver.resolveWorkspaceDirectory(workspaceId);

        // Check if workspace already exists
        if (Files.exists(workspaceDir.resolve("workspace.yaml"))) {
            return ServiceResult.error(
                org.owl4agents.core.ErrorCode.ONTOLOGY_NOT_FOUND,
                "Workspace '" + workspaceId.name() + "' is already initialized."
            );
        }

        try {
            // Create workspace directory structure
            Files.createDirectories(workspaceDir);
            Files.createDirectories(workspaceDir.resolve("ontologies"));
            Files.createDirectories(workspaceDir.resolve("logs"));

            // Create workspace.yaml
            String workspaceYaml = """
                workspaceId: %s
                createdAt: %s
                """.formatted(workspaceId.name(), java.time.Instant.now());
            Files.writeString(workspaceDir.resolve("workspace.yaml"), workspaceYaml);

            // Create empty catalog.json
            Files.writeString(workspaceDir.resolve("catalog.json"), """
                {
                  "entries": []
                }
                """);

            // Create home config if not present
            Path configPath = homeResolver.resolveConfigPath();
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath, """
                    # owl4agents configuration
                    defaultWorkspace: default
                    """);
            }

            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.explicit(
                    new OntologyId(workspaceId.name())));

        } catch (IOException e) {
            return ServiceResult.error(
                org.owl4agents.core.ErrorCode.IMPORT_FAILED,
                "Failed to initialize workspace: " + e.getMessage());
        }
    }

    /**
     * Initialize the default workspace idempotently.
     * If it already exists, returns success without modifying anything.
     */
    public ServiceResult<Void> initializeDefault() {
        return initializeIdempotent(WorkspaceId.DEFAULT);
    }

    /**
     * Initialize workspace idempotently — if it already exists, return success.
     */
    public ServiceResult<Void> initializeIdempotent(WorkspaceId workspaceId) {
        Path workspaceDir = homeResolver.resolveWorkspaceDirectory(workspaceId);

        if (Files.exists(workspaceDir.resolve("workspace.yaml"))) {
            // Already initialized — return success without modification
            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.explicit(
                    new OntologyId(workspaceId.name())));
        }

        return initialize(workspaceId);
    }
}