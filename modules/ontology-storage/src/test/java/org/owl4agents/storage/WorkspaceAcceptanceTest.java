package org.owl4agents.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for local workspace and catalog operations.
 * Covers initialization, idempotent initialization, catalog persistence,
 * and path rejection.
 */
@DisplayName("Workspace and catalog acceptance tests")
class WorkspaceAcceptanceTest {

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private WorkspaceInitializer initializer;
    private CatalogStore catalogStore;

    @BeforeEach
    void setup() {
        homeResolver = new HomeDirectoryResolver(tempDir);
        initializer = new WorkspaceInitializer(homeResolver);
        catalogStore = new CatalogStore(homeResolver);
    }

    @Nested
    @DisplayName("Workspace initialization")
    class InitializationTests {

        @Test
        @DisplayName("Fresh workspace can be initialized")
        void freshWorkspaceInitialization() {
            ServiceResult<Void> result = initializer.initialize(WorkspaceId.DEFAULT);

            assertTrue(result.isSuccess());
            Path workspaceDir = homeResolver.resolveWorkspaceDirectory(WorkspaceId.DEFAULT);
            assertTrue(Files.exists(workspaceDir.resolve("workspace.yaml")));
            assertTrue(Files.exists(workspaceDir.resolve("catalog.json")));
            assertTrue(Files.exists(workspaceDir.resolve("ontologies")));
            assertTrue(Files.exists(workspaceDir.resolve("logs")));
        }

        @Test
        @DisplayName("Idempotent initialization leaves existing data intact")
        void idempotentInitialization() throws java.io.IOException {
            // First initialization
            initializer.initialize(WorkspaceId.DEFAULT);
            Path workspaceYaml = homeResolver.resolveWorkspaceDirectory(WorkspaceId.DEFAULT)
                .resolve("workspace.yaml");
            String originalContent = Files.readString(workspaceYaml);

            // Second initialization (idempotent)
            ServiceResult<Void> result = initializer.initializeIdempotent(WorkspaceId.DEFAULT);
            assertTrue(result.isSuccess());

            // Data unchanged
            String afterContent = Files.readString(workspaceYaml);
            assertEquals(originalContent, afterContent);
        }

        @Test
        @DisplayName("Re-initializing existing workspace returns error")
        void reInitializationError() {
            initializer.initialize(WorkspaceId.DEFAULT);
            ServiceResult<Void> result = initializer.initialize(WorkspaceId.DEFAULT);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Named workspace can be initialized")
        void namedWorkspaceInitialization() {
            WorkspaceId custom = new WorkspaceId("research");
            ServiceResult<Void> result = initializer.initialize(custom);

            assertTrue(result.isSuccess());
            Path workspaceDir = homeResolver.resolveWorkspaceDirectory(custom);
            assertTrue(Files.exists(workspaceDir.resolve("workspace.yaml")));
        }
    }

    @Nested
    @DisplayName("Catalog persistence")
    class CatalogTests {

        @Test
        @DisplayName("Empty catalog is readable after initialization")
        void emptyCatalogReadable() {
            initializer.initialize(WorkspaceId.DEFAULT);

            ServiceResult<java.util.List<org.owl4agents.core.model.CatalogEntry>> result =
                catalogStore.readCatalog(WorkspaceId.DEFAULT);

            assertTrue(result.isSuccess());
            assertTrue(((ServiceResult.Success<java.util.List<org.owl4agents.core.model.CatalogEntry>>) result).data().isEmpty());
        }

        @Test
        @DisplayName("Catalog entry can be added")
        void catalogEntryCanBeAdded() {
            initializer.initialize(WorkspaceId.DEFAULT);

            org.owl4agents.core.model.CatalogEntry entry = new org.owl4agents.core.model.CatalogEntry(
                new OntologyId("pizza"),
                "Pizza Ontology",
                Path.of("/test/pizza.owl"),
                Path.of("/workspace/ontologies/pizza/canonical/ontology.owl"),
                java.time.Instant.now(),
                Path.of("/workspace/ontologies/pizza/metadata.json")
            );

            ServiceResult<Void> result = catalogStore.addEntry(WorkspaceId.DEFAULT, entry);
            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Path rejection")
    class PathRejectionTests {

        @Test
        @DisplayName("Non-cataloged path is rejected for read access")
        void nonCatalogedPathRejected() {
            initializer.initialize(WorkspaceId.DEFAULT);

            WorkspaceSafety safety = new WorkspaceSafety(catalogStore);
            ServiceResult<Void> result = safety.checkReadAccess(
                WorkspaceId.DEFAULT, Path.of("/etc/passwd"));

            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Home directory resolution")
    class HomeDirectoryTests {

        @Test
        @DisplayName("Test override takes priority")
        void testOverridePriority() {
            HomeDirectoryResolver resolver = new HomeDirectoryResolver(tempDir);
            assertEquals(tempDir, resolver.resolveHomeDirectory());
        }

        @Test
        @DisplayName("System property override works")
        void systemPropertyOverride() {
            String customPath = tempDir.resolve("custom-home").toString();
            System.setProperty("owl4agents.home", customPath);

            HomeDirectoryResolver resolver = new HomeDirectoryResolver();
            Path resolved = resolver.resolveHomeDirectory();
            assertEquals(Path.of(customPath), resolved);

            System.clearProperty("owl4agents.home");
        }

        @Test
        @DisplayName("Default home directory fallback")
        void defaultHomeFallback() {
            HomeDirectoryResolver resolver = new HomeDirectoryResolver();
            Path resolved = resolver.resolveHomeDirectory();
            assertTrue(resolved.toString().contains(".owl4agents"));
        }
    }
}
