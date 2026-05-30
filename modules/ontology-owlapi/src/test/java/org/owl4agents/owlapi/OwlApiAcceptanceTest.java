package org.owl4agents.owlapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.OntologySummary;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.owl4agents.storage.WorkspaceInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.CopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for OWL API import and summary operations.
 * Tests import of valid fixtures, invalid fixtures, and summary extraction.
 */
@DisplayName("OWL API import and summary acceptance tests")
class OwlApiAcceptanceTest {

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private WorkspaceInitializer initializer;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private OntologySummaryExtractor summaryExtractor;
    private ImportErrorHandler errorHandler;

    // Fixture paths — these will be resolved from the corpus.fixtures system property
    // or from relative paths for local development
    private String corpusFixturesPath;

    @BeforeEach
    void setup() {
        homeResolver = new HomeDirectoryResolver(tempDir);
        initializer = new WorkspaceInitializer(homeResolver);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        summaryExtractor = new OntologySummaryExtractor();
        errorHandler = new ImportErrorHandler();

        corpusFixturesPath = System.getProperty("corpus.fixtures",
            "../test/corpus");

        // Initialize workspace first
        initializer.initializeIdempotent(WorkspaceId.DEFAULT);
    }

    private Path resolveFixture(String relativePath) {
        Path basePath = Path.of(corpusFixturesPath);
        Path fixturePath = basePath.resolve(relativePath);
        if (Files.exists(fixturePath)) {
            return fixturePath;
        }
        // Try relative from project root
        return Path.of("test/corpus").resolve(relativePath);
    }

    @Nested
    @DisplayName("Import valid ontologies")
    class ImportValidTests {

        @Test
        @DisplayName("Import minimal ontology fixture")
        void importMinimal() {
            Path fixturePath = resolveFixture("golden/01-minimal.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            ServiceResult<Void> result = importer.importOntology(
                new OntologyId("minimal"), fixturePath, WorkspaceId.DEFAULT);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Import subclass transitive fixture")
        void importSubclassTransitive() {
            Path fixturePath = resolveFixture("golden/02-subclass-transitive.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            ServiceResult<Void> result = importer.importOntology(
                new OntologyId("subclass-transitive"), fixturePath, WorkspaceId.DEFAULT);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Import property axioms fixture")
        void importPropertyAxioms() {
            Path fixturePath = resolveFixture("golden/05-property-axioms.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            ServiceResult<Void> result = importer.importOntology(
                new OntologyId("property-axioms"), fixturePath, WorkspaceId.DEFAULT);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Import individual assertions fixture")
        void importIndividualAssertions() {
            Path fixturePath = resolveFixture("golden/06-individual-assertions.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            ServiceResult<Void> result = importer.importOntology(
                new OntologyId("individual-assertions"), fixturePath, WorkspaceId.DEFAULT);

            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Import invalid ontologies")
    class ImportInvalidTests {

        @Test
        @DisplayName("Import invalid text file returns structured error")
        void importInvalidTextFile() throws java.io.IOException {
            // Create a non-OWL file for testing
            Path invalidFile = tempDir.resolve("invalid.txt");
            Files.writeString(invalidFile, "This is not an OWL ontology.");

            ServiceResult<Void> result = importer.importOntology(
                new OntologyId("invalid-test"), invalidFile, WorkspaceId.DEFAULT);

            assertFalse(result.isSuccess());
            ServiceResult.Error<Void> error = (ServiceResult.Error<Void>) result;
            assertEquals(org.owl4agents.core.ErrorCode.IMPORT_FAILED, error.error().code());
        }

        @Test
        @DisplayName("Import nonexistent file returns error")
        void importNonexistentFile() {
            Path nonexistent = Path.of("/nonexistent/path.owl");

            ServiceResult<Void> result = importer.importOntology(
                new OntologyId("nonexistent"), nonexistent, WorkspaceId.DEFAULT);

            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Summary extraction")
    class SummaryTests {

        @Test
        @DisplayName("Extract summary from minimal ontology")
        void extractMinimalSummary() {
            Path fixturePath = resolveFixture("golden/01-minimal.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            ServiceResult<OntologySummary> result = summaryExtractor.extractSummary(
                new OntologyId("minimal"), fixturePath);

            assertTrue(result.isSuccess());
            OntologySummary summary = ((ServiceResult.Success<OntologySummary>) result).data();
            assertEquals("http://example.org/minimal", summary.ontologyIri());
            assertNotNull(summary.entityCounts());
        }
    }

    @Nested
    @DisplayName("Import error handler")
    class ErrorHandlerTests {

        @Test
        @DisplayName("Non-ontology file produces IMPORT_FAILED error")
        void nonOntologyFileError() throws java.io.IOException {
            Path textFile = tempDir.resolve("text.txt");
            Files.writeString(textFile, "plain text content");

            ServiceResult<Void> result = errorHandler.handleNonOntologyFile(textFile);

            assertFalse(result.isSuccess());
            ServiceResult.Error<Void> error = (ServiceResult.Error<Void>) result;
            assertEquals(org.owl4agents.core.ErrorCode.IMPORT_FAILED, error.error().code());
            assertTrue(error.error().message().contains("not a valid OWL/RDF ontology"));
        }
    }
}