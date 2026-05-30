package org.owl4agents.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for QA context generation, bounded context,
 * no-match handling, and evidence metadata.
 */
@DisplayName("QA context acceptance tests")
class QaContextAcceptanceTest {

    private EntityIndex entityIndex;
    private OntologyId ontologyId;
    private String corpusFixturesPath;

    @BeforeEach
    void setup() {
        entityIndex = new EntityIndex();
        ontologyId = new OntologyId("test");
        corpusFixturesPath = System.getProperty("corpus.fixtures", "../test/corpus");
    }

    private Path resolveFixture(String relativePath) {
        Path basePath = Path.of(corpusFixturesPath);
        Path fixturePath = basePath.resolve(relativePath);
        if (Files.exists(fixturePath)) return fixturePath;
        return Path.of("test/corpus").resolve(relativePath);
    }

    private OWLOntology loadOntology(Path path) throws OWLOntologyCreationException {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(path.toFile());
    }

    @Nested
    @DisplayName("Successful context generation")
    class SuccessfulContextTests {

        @Test
        @DisplayName("QA context includes matched entities and class context")
        void qaContextWithMatchedEntities() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/06-individual-assertions.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            QaContextService service = new QaContextService(entityIndex, ontologyId, ontology);
            ServiceResult<QaContext> result = service.generateContext(
                "Who does Alice study with?", Optional.empty(), Optional.empty());

            assertTrue(result.isSuccess());
            QaContext context = ((ServiceResult.Success<QaContext>) result).data();
            assertFalse(context.matchedEntities().isEmpty());
            assertNotNull(context.naturalLanguageContext());
            assertTrue(context.warnings().isEmpty());
        }

        @Test
        @DisplayName("QA context with SPARQL safety fixture returns city data")
        void qaContextWithCities() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/07-sparql-safety.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            QaContextService service = new QaContextService(entityIndex, ontologyId, ontology);
            ServiceResult<QaContext> result = service.generateContext(
                "What cities are in France?", Optional.empty(), Optional.empty());

            assertTrue(result.isSuccess());
            QaContext context = ((ServiceResult.Success<QaContext>) result).data();
            assertFalse(context.matchedEntities().isEmpty());
        }
    }

    @Nested
    @DisplayName("Bounded context")
    class BoundedContextTests {

        @Test
        @DisplayName("QA context respects max entities limit")
        void boundedMaxEntities() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/02-subclass-transitive.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            QaContextService service = new QaContextService(entityIndex, ontologyId, ontology);
            ServiceResult<QaContext> result = service.generateContext(
                "animals", Optional.of(2), Optional.of(1));

            assertTrue(result.isSuccess());
            QaContext context = ((ServiceResult.Success<QaContext>) result).data();
            assertEquals(2, context.bounds().maxEntities());
            assertEquals(1, context.bounds().maxDepth());
            assertTrue(context.matchedEntities().size() <= 2);
        }
    }

    @Nested
    @DisplayName("No-match handling")
    class NoMatchTests {

        @Test
        @DisplayName("No-match question produces warning")
        void noMatchWarning() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/01-minimal.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            QaContextService service = new QaContextService(entityIndex, ontologyId, ontology);
            ServiceResult<QaContext> result = service.generateContext(
                "quantum entanglement", Optional.empty(), Optional.empty());

            assertTrue(result.isSuccess());
            QaContext context = ((ServiceResult.Success<QaContext>) result).data();
            assertTrue(context.matchedEntities().isEmpty());
            assertFalse(context.warnings().isEmpty());
            assertTrue(context.warnings().stream()
                .anyMatch(w -> w.type().equals(QaWarning.TYPE_NO_MATCH)));
            assertTrue(context.warnings().stream()
                .anyMatch(w -> w.severity().equals(QaWarning.SEVERITY_LOW_CONFIDENCE)));
        }
    }

    @Nested
    @DisplayName("Evidence metadata")
    class EvidenceMetadataTests {

        @Test
        @DisplayName("QA context includes evidence metadata in results")
        void evidenceMetadataPresent() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/06-individual-assertions.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            QaContextService service = new QaContextService(entityIndex, ontologyId, ontology);
            ServiceResult<QaContext> result = service.generateContext(
                "Alice", Optional.empty(), Optional.empty());

            assertTrue(result.isSuccess());
            QaContext context = ((ServiceResult.Success<QaContext>) result).data();

            // Check that matched entities have evidence metadata
            if (!context.matchedEntities().isEmpty()) {
                SearchMatch firstMatch = context.matchedEntities().get(0);
                assertNotNull(firstMatch.evidence());
                assertEquals(ontologyId, firstMatch.evidence().ontologyId());
                assertEquals("explicit", firstMatch.evidence().extractionStatus());
            }
        }
    }
}