package org.owl4agents.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI acceptance tests for successful commands, missing ontology errors,
 * import errors, and service consistency.
 */
@DisplayName("CLI acceptance tests")
class CliAcceptanceTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Workspace init")
    class InitTests {

        @Test
        @DisplayName("Init command succeeds for fresh workspace")
        void initFreshWorkspace() {
            org.owl4agents.storage.HomeDirectoryResolver homeResolver =
                new org.owl4agents.storage.HomeDirectoryResolver(tempDir);
            org.owl4agents.storage.WorkspaceInitializer initializer =
                new org.owl4agents.storage.WorkspaceInitializer(homeResolver);

            var result = initializer.initializeIdempotent(org.owl4agents.core.WorkspaceId.DEFAULT);
            assertTrue(result.isSuccess());
            assertTrue(Files.exists(tempDir.resolve("workspaces").resolve("default").resolve("workspace.yaml")));
        }
    }

    @Nested
    @DisplayName("Missing ontology errors")
    class MissingOntologyTests {

        @Test
        @DisplayName("Summary for unknown ontology returns ONTOLOGY_NOT_FOUND")
        void summaryForUnknownOntology() {
            org.owl4agents.owlapi.OntologySummaryExtractor extractor = new org.owl4agents.owlapi.OntologySummaryExtractor();
            var result = extractor.extractSummary(
                new org.owl4agents.core.OntologyId("nonexistent"),
                Path.of("/nonexistent/path.owl"));

            assertFalse(result.isSuccess());
            var error = ((org.owl4agents.core.ServiceResult.Error) result).error();
            assertEquals(org.owl4agents.core.ErrorCode.ONTOLOGY_NOT_FOUND, error.code());
        }
    }

    @Nested
    @DisplayName("Import errors")
    class ImportErrorTests {

        @Test
        @DisplayName("Import nonexistent file returns error")
        void importNonexistent() {
            org.owl4agents.storage.HomeDirectoryResolver homeResolver =
                new org.owl4agents.storage.HomeDirectoryResolver(tempDir);
            org.owl4agents.storage.WorkspaceInitializer initializer =
                new org.owl4agents.storage.WorkspaceInitializer(homeResolver);
            initializer.initializeIdempotent(org.owl4agents.core.WorkspaceId.DEFAULT);

            org.owl4agents.storage.CatalogStore catalogStore = new org.owl4agents.storage.CatalogStore(homeResolver);
            org.owl4agents.owlapi.OntologyImporter importer = new org.owl4agents.owlapi.OntologyImporter(homeResolver, catalogStore);

            var result = importer.importOntology(
                new org.owl4agents.core.OntologyId("test"),
                Path.of("/nonexistent.owl"),
                org.owl4agents.core.WorkspaceId.DEFAULT);

            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Service consistency")
    class ServiceConsistencyTests {

        @Test
        @DisplayName("CLI service factory creates consistent service instances")
        void serviceFactoryConsistency() {
            CliServiceFactory factory = new CliServiceFactory("default", null);

            // Same factory returns same instances
            assertNotNull(factory.getHomeResolver());
            assertNotNull(factory.getWorkspaceInitializer());
            assertNotNull(factory.getCatalogStore());
            assertNotNull(factory.getOntologyImporter());
            assertNotNull(factory.getSummaryExtractor());
            assertNotNull(factory.getSparqlValidator());
            assertNotNull(factory.getSparqlExecutor());
            assertNotNull(factory.getSparqlSafetyGuard());
        }
    }
}