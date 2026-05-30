package org.owl4agents.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.owl4agents.core.EntityId;
import org.owl4agents.core.EntityType;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for entity search and class/property/individual context contracts.
 */
@DisplayName("Entity search and context acceptance tests")
class EntityContextAcceptanceTest {

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
        if (Files.exists(fixturePath)) {
            return fixturePath;
        }
        return Path.of("test/corpus").resolve(relativePath);
    }

    private OWLOntology loadOntology(Path path) throws OWLOntologyCreationException {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(path.toFile());
    }

    @Nested
    @DisplayName("Entity search")
    class SearchTests {

        @Test
        @DisplayName("Search by label finds matching entities")
        void searchByLabel() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/02-subclass-transitive.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            EntitySearchService searchService = new EntitySearchService(entityIndex, ontologyId);
            ServiceResult<SearchResult> result = searchService.search("Dog");

            assertTrue(result.isSuccess());
            SearchResult data = ((ServiceResult.Success<SearchResult>) result).data();
            assertTrue(data.totalResults() > 0);
            assertTrue(data.results().stream()
                .anyMatch(m -> m.iri().contains("Dog") && m.type() == EntityType.CLASS));
        }

        @Test
        @DisplayName("Search for object property finds matching property")
        void searchForObjectProperty() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/05-property-axioms.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            EntitySearchService searchService = new EntitySearchService(entityIndex, ontologyId);
            ServiceResult<SearchResult> result = searchService.search("works for");

            assertTrue(result.isSuccess());
            SearchResult data = ((ServiceResult.Success<SearchResult>) result).data();
            assertTrue(data.results().stream()
                .anyMatch(m -> m.type() == EntityType.OBJECT_PROPERTY));
        }

        @Test
        @DisplayName("Search for individual finds matching individual")
        void searchForIndividual() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/06-individual-assertions.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            EntitySearchService searchService = new EntitySearchService(entityIndex, ontologyId);
            ServiceResult<SearchResult> result = searchService.search("Alice");

            assertTrue(result.isSuccess());
            SearchResult data = ((ServiceResult.Success<SearchResult>) result).data();
            assertTrue(data.results().stream()
                .anyMatch(m -> m.type() == EntityType.INDIVIDUAL));
        }
    }

    @Nested
    @DisplayName("Class context")
    class ClassContextTests {

        @Test
        @DisplayName("Class context includes subclasses and superclasses")
        void classContextHierarchy() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/02-subclass-transitive.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            ClassContextService service = new ClassContextService(entityIndex, ontologyId, ontology);
            ServiceResult<ClassContext> result = service.getClassContext(
                new EntityId("http://example.org/subclass-transitive#Mammal"));

            assertTrue(result.isSuccess());
            ClassContext context = ((ServiceResult.Success<ClassContext>) result).data();
            assertTrue(context.directSuperclasses().stream()
                .anyMatch(iri -> iri.contains("Animal")));
            assertTrue(context.directSubclasses().stream()
                .anyMatch(iri -> iri.contains("Dog") || iri.contains("Cat")));
        }

        @Test
        @DisplayName("Class context includes equivalent classes")
        void classContextEquivalent() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/03-equivalent-classes.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            ClassContextService service = new ClassContextService(entityIndex, ontologyId, ontology);
            ServiceResult<ClassContext> result = service.getClassContext(
                new EntityId("http://example.org/equivalent-classes#Person"));

            assertTrue(result.isSuccess());
            ClassContext context = ((ServiceResult.Success<ClassContext>) result).data();
            assertTrue(context.equivalentClasses().stream()
                .anyMatch(iri -> iri.contains("Human")));
        }

        @Test
        @DisplayName("Class context includes disjoint classes")
        void classContextDisjoint() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/04-disjoint-classes.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            ClassContextService service = new ClassContextService(entityIndex, ontologyId, ontology);
            ServiceResult<ClassContext> result = service.getClassContext(
                new EntityId("http://example.org/disjoint-classes#Cat"));

            assertTrue(result.isSuccess());
            ClassContext context = ((ServiceResult.Success<ClassContext>) result).data();
            assertTrue(context.disjointClasses().stream()
                .anyMatch(iri -> iri.contains("Dog")));
        }
    }

    @Nested
    @DisplayName("Property context")
    class PropertyContextTests {

        @Test
        @DisplayName("Object property context includes domain, range, and inverse")
        void objectPropertyContext() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/05-property-axioms.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            ObjectPropertyContextService service = new ObjectPropertyContextService(entityIndex, ontologyId, ontology);
            ServiceResult<ObjectPropertyContext> result = service.getObjectPropertyContext(
                new EntityId("http://example.org/property-axioms#worksFor"));

            assertTrue(result.isSuccess());
            ObjectPropertyContext context = ((ServiceResult.Success<ObjectPropertyContext>) result).data();
            assertTrue(context.domain().stream().anyMatch(iri -> iri.contains("Person")));
            assertTrue(context.range().stream().anyMatch(iri -> iri.contains("Organization")));
            assertTrue(context.inverseProperties().stream().anyMatch(iri -> iri.contains("employs")));
        }

        @Test
        @DisplayName("Data property context includes domain, range, and datatype")
        void dataPropertyContext() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/05-property-axioms.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            DataPropertyContextService service = new DataPropertyContextService(entityIndex, ontologyId, ontology);
            ServiceResult<DataPropertyContext> result = service.getDataPropertyContext(
                new EntityId("http://example.org/property-axioms#hasAge"));

            assertTrue(result.isSuccess());
            DataPropertyContext context = ((ServiceResult.Success<DataPropertyContext>) result).data();
            assertTrue(context.domain().stream().anyMatch(iri -> iri.contains("Person")));
            assertNotNull(context.datatype());
        }
    }

    @Nested
    @DisplayName("Individual context")
    class IndividualContextTests {

        @Test
        @DisplayName("Individual context includes types and assertions")
        void individualContextAssertions() throws OWLOntologyCreationException {
            Path fixturePath = resolveFixture("golden/06-individual-assertions.owl");
            if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

            OWLOntology ontology = loadOntology(fixturePath);
            entityIndex.buildFromOntology(ontology);

            IndividualContextService service = new IndividualContextService(entityIndex, ontologyId, ontology);
            ServiceResult<IndividualContext> result = service.getIndividualContext(
                new EntityId("http://example.org/individual-assertions#bob"));

            assertTrue(result.isSuccess());
            IndividualContext context = ((ServiceResult.Success<IndividualContext>) result).data();
            assertTrue(context.explicitTypes().stream().anyMatch(iri -> iri.contains("Professor")));
            assertTrue(context.objectPropertyAssertions().stream()
                .anyMatch(a -> a.propertyIri().contains("worksFor")));
        }
    }
}