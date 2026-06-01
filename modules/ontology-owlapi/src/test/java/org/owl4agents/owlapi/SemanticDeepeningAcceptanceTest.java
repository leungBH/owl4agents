package org.owl4agents.owlapi;

import org.junit.jupiter.api.*;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.*;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V0.2 acceptance gates for semantic-deepening features.
 */
@DisplayName("v0.2 Semantic-deepening acceptance gates")
class SemanticDeepeningAcceptanceTest {

    private static Path fixtureDir;
    private static Path tempHome;
    private static HomeDirectoryResolver homeResolver;
    private static CatalogStore catalogStore;
    private static SemanticDeepeningService service;

    @BeforeAll
    static void setup() throws Exception {
        fixtureDir = Path.of(System.getProperty("corpus.fixtures"));
        tempHome = Files.createTempDirectory("owl4agents-deepening-test");
        homeResolver = new HomeDirectoryResolver(tempHome);
        catalogStore = new CatalogStore(homeResolver);

        // Import fixtures
        var importer = new OntologyImporter(homeResolver, catalogStore);
        importer.importOntology(new OntologyId("pizza"), fixtureDir.resolve("smoke/pizza.owl"), WorkspaceId.DEFAULT);
        importer.importOntology(new OntologyId("datatype"), fixtureDir.resolve("golden/datatype-constraints.owl"), WorkspaceId.DEFAULT);
        importer.importOntology(new OntologyId("disjoint-props"), fixtureDir.resolve("golden/disjoint-properties.owl"), WorkspaceId.DEFAULT);
        importer.importOntology(new OntologyId("individuals"), fixtureDir.resolve("golden/individual-assertions.owl"), WorkspaceId.DEFAULT);
        importer.importOntology(new OntologyId("relations"), fixtureDir.resolve("golden/relation-assertions.owl"), WorkspaceId.DEFAULT);

        // SemanticDeepeningService resolves paths as workspaceBasePath/default/ontologies/{id}/...
        // The importer stores ontologies at homeDir/workspaces/default/ontologies/{id}/...
        // So we pass homeDir/workspaces as the base path so the "default" segment aligns.
        String workspaceBasePath = homeResolver.resolveHomeDirectory()
            .resolve("workspaces").toString();
        service = new SemanticDeepeningService(workspaceBasePath);
    }

    @AfterAll
    static void cleanup() throws Exception {
        Files.walk(tempHome).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (Exception ignored) {}
        });
    }

    // ── Datatype Constraints ──

    @Nested
    @DisplayName("Datatype constraints acceptance gates")
    class DatatypeConstraintsTests {

        @Test
        @DisplayName("Get datatype constraints returns facets for custom datatypes")
        void getDatatypeConstraints() {
            var result = service.getDatatypeConstraints(
                new OntologyId("datatype"),
                "http://example.org/datatype-constraints#AgeType"
            );
            assertTrue(result.isSuccess(), "Should find AgeType datatype constraints");
            DatatypeConstraintsResult data = ((ServiceResult.Success<DatatypeConstraintsResult>) result).data();
            assertNotNull(data.baseDatatypeIRI(), "Should have base datatype IRI");
            assertFalse(data.facets().isEmpty(), "Should have facet constraints");
        }

        @Test
        @DisplayName("Validate literal returns valid for correct values")
        void validateLiteralValid() {
            var result = service.validateLiteral(
                new OntologyId("datatype"),
                "25",
                "http://example.org/datatype-constraints#AgeType",
                Optional.empty()
            );
            assertTrue(result.isSuccess());
            LiteralValidationResult data = ((ServiceResult.Success<LiteralValidationResult>) result).data();
            assertTrue(data.valid(), "Age 25 should be valid for AgeType");
        }

        @Test
        @DisplayName("Validate literal returns invalid for out-of-range values")
        void validateLiteralInvalid() {
            var result = service.validateLiteral(
                new OntologyId("datatype"),
                "200",
                "http://example.org/datatype-constraints#AgeType",
                Optional.empty()
            );
            assertTrue(result.isSuccess());
            LiteralValidationResult data = ((ServiceResult.Success<LiteralValidationResult>) result).data();
            assertFalse(data.valid(), "Age 200 should be invalid for AgeType (0-150)");
        }
    }

    // ── Property Characteristics ──

    @Nested
    @DisplayName("Property characteristics acceptance gates")
    class PropertyCharacteristicsTests {

        @Test
        @DisplayName("Get property characteristics returns flags")
        void getPropertyCharacteristics() {
            var result = service.getPropertyCharacteristics(
                new OntologyId("disjoint-props"),
                "http://example.org/disjoint-properties#hasSpouse",
                false
            );
            assertTrue(result.isSuccess(), "Should find hasSpouse property");
            PropertyCharacteristicsResult data = ((ServiceResult.Success<PropertyCharacteristicsResult>) result).data();
            assertTrue(data.functional(), "hasSpouse should be functional");
            assertTrue(data.symmetric(), "hasSpouse should be symmetric");
        }
    }

    // ── Disjoint Properties ──

    @Nested
    @DisplayName("Disjoint properties acceptance gates")
    class DisjointPropertiesTests {

        @Test
        @DisplayName("Get disjoint properties returns disjoint axioms")
        void getDisjointProperties() {
            var result = service.getDisjointProperties(
                new OntologyId("disjoint-props"),
                "http://example.org/disjoint-properties#hasParent",
                false
            );
            assertTrue(result.isSuccess(), "Should find hasParent disjoint axioms");
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            assertFalse(data.relatedPropertyIRIs().isEmpty(), "hasParent should have disjoint properties");
        }
    }

    // ── Same/Different Individuals ──

    @Nested
    @DisplayName("Individual assertion acceptance gates")
    class IndividualAssertionTests {

        @Test
        @DisplayName("Get same individuals returns owl:sameAs individuals")
        void getSameIndividuals() {
            var result = service.getSameIndividuals(
                new OntologyId("individuals"),
                "http://example.org/v2-individual-assertions#alice",
                false
            );
            assertTrue(result.isSuccess(), "Should find alice's sameAs individuals: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            assertFalse(data.relatedPropertyIRIs().isEmpty(), "alice should have sameAs individuals");
        }

        @Test
        @DisplayName("Get different individuals returns owl:differentFrom")
        void getDifferentIndividuals() {
            var result = service.getDifferentIndividuals(
                new OntologyId("individuals"),
                "http://example.org/v2-individual-assertions#bob",
                false
            );
            assertTrue(result.isSuccess(), "Should find bob's differentFrom individuals: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            assertFalse(data.relatedPropertyIRIs().isEmpty(), "bob should have differentFrom individuals");
        }
    }

    // ── Relations ──

    @Nested
    @DisplayName("Relations acceptance gates")
    class RelationsTests {

        @Test
        @DisplayName("Find relations between entities returns object properties")
        void findRelations() {
            var result = service.findRelationsBetweenEntities(
                new OntologyId("relations"),
                "http://example.org/relation-assertions#alice",
                "http://example.org/relation-assertions#paris",
                false
            );
            assertTrue(result.isSuccess(), "Should find relations between alice and paris");
            PropertyAxiomsResult data = ((ServiceResult.Success<PropertyAxiomsResult>) result).data();
            assertFalse(data.relatedPropertyIRIs().isEmpty(), "alice and paris should have at least one relation");
        }
    }

    // ── Recommended Reasoner in Summary ──

    @Nested
    @DisplayName("Recommended reasoner acceptance gates")
    class RecommendedReasonerTests {

        @Test
        @DisplayName("Ontology summary includes recommended reasoner field")
        void summaryIncludesRecommendedReasoner() {
            OntologySummaryExtractor extractor = new OntologySummaryExtractor();
            // Use the imported canonical copy from the workspace
            var catalogResult = catalogStore.findEntry(WorkspaceId.DEFAULT, new OntologyId("pizza"));
            assertTrue(catalogResult.isSuccess(), "Pizza ontology should be in catalog");

            if (catalogResult instanceof ServiceResult.Success<CatalogEntry> s) {
                CatalogEntry entry = s.data();
                var result = extractor.extractSummary(new OntologyId("pizza"), entry.canonicalPath());
                assertTrue(result.isSuccess(), "Extract summary should succeed: " +
                    (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
                OntologySummary summary = ((ServiceResult.Success<OntologySummary>) result).data();
                assertTrue(summary.recommendedReasoner().isPresent(), "Summary should include recommendedReasoner");
                assertEquals("hermit", summary.recommendedReasoner().get(), "Pizza.owl (OWL 2 DL) should recommend hermit");
            }
        }
    }
}