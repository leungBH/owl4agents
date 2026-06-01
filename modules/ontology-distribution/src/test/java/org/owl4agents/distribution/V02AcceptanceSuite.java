package org.owl4agents.distribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.mcp.McpToolRegistry;
import org.owl4agents.mcp.McpServerAdapter;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.OntologySummaryExtractor;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.AutoReasonerSelector;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.query.SparqlSafetyGuard;
import org.owl4agents.storage.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.2 End-to-end acceptance suite against golden fixtures.
 * Verifies: reasoner selection, classification, consistency (consistent + inconsistent),
 * entity context enrichment, recommended reasoner, SPARQL scope, semantic deepening,
 * readonly policy for v0.2 reasoning tools, and v0.2 data model / error codes.
 *
 * This suite lives in the distribution module because it requires
 * dependencies from all other modules.
 */
@DisplayName("v0.2 End-to-end acceptance suite")
class V02AcceptanceSuite {

    @TempDir
    Path tempDir;

    private HomeDirectoryResolver homeResolver;
    private WorkspaceInitializer initializer;
    private CatalogStore catalogStore;
    private OntologyImporter importer;
    private OntologySummaryExtractor summaryExtractor;

    private String corpusFixturesPath;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        homeResolver = new HomeDirectoryResolver(tempDir);
        initializer = new WorkspaceInitializer(homeResolver);
        catalogStore = new CatalogStore(homeResolver);
        importer = new OntologyImporter(homeResolver, catalogStore);
        summaryExtractor = new OntologySummaryExtractor();

        corpusFixturesPath = System.getProperty("corpus.fixtures", "../test/corpus");
        initializer.initializeIdempotent(WorkspaceId.DEFAULT);
    }

    private Path resolveFixture(String relativePath) {
        Path basePath = Path.of(corpusFixturesPath);
        Path fixturePath = basePath.resolve(relativePath);
        if (Files.exists(fixturePath)) return fixturePath;
        return Path.of("test/corpus").resolve(relativePath);
    }

    private void importFixture(String ontologyId, String relativePath) {
        Path fixturePath = resolveFixture(relativePath);
        if (!Files.exists(fixturePath)) fail("Required fixture not found: " + fixturePath);

        var importResult = importer.importOntology(
            new OntologyId(ontologyId), fixturePath, WorkspaceId.DEFAULT);
        assertTrue(importResult.isSuccess(),
            "Import of " + relativePath + " must succeed: " +
            (importResult instanceof ServiceResult.Error<?> e ? e.error().message() : ""));
    }

    private ReasonerServiceImpl createReasonerService() {
        // ReasonerServiceImpl resolves ontology paths as workspaceBasePath/default/ontologies/{id}/...
        // The importer stores ontologies at homeDir/workspaces/default/ontologies/{id}/...
        // So we pass homeDir/workspaces as the base path so the "default" segment aligns.
        String workspaceBasePath = homeResolver.resolveHomeDirectory()
            .resolve("workspaces").toString();
        return new ReasonerServiceImpl(catalogStore, workspaceBasePath);
    }

    private SemanticDeepeningService createDeepeningService() {
        // Same path resolution logic as ReasonerServiceImpl
        String workspaceBasePath = homeResolver.resolveHomeDirectory()
            .resolve("workspaces").toString();
        return new SemanticDeepeningService(workspaceBasePath);
    }

    // ─── 21.1 Reasoner selection ───

    @Nested
    @DisplayName("21.1 Reasoner selection")
    class ReasonerSelectionTests {

        @Test
        @DisplayName("Auto reasoner selection chooses HermiT for Pizza.owl (OWL 2 DL)")
        void autoSelectsHermiTForPizzaOwl() {
            importFixture("pizza", "smoke/pizza.owl");

            ReasonerServiceImpl reasonerService = createReasonerService();

            ServiceResult<ReasonerSelectionResult> result =
                reasonerService.selectReasoner(new OntologyId("pizza"), false);

            assertTrue(result.isSuccess(),
                "Reasoner selection must succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            ReasonerSelectionResult selection =
                ((ServiceResult.Success<ReasonerSelectionResult>) result).data();

            assertEquals("HermiT", selection.reasonerName(),
                "Pizza.owl is OWL 2 DL; auto selection should choose HermiT");
            assertNotNull(selection.detectedProfile());
            assertNotNull(selection.selectionRationale());
        }

        @Test
        @DisplayName("AutoReasonerSelector selects HermiT for OWL 2 DL profile")
        void autoSelectorHermiTForDLProfile() {
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult result = selector.select("OWL 2 DL", false);

            assertEquals("HermiT", result.reasonerName());
            assertEquals("OWL 2 DL", result.detectedProfile());
            assertTrue(result.selectionRationale().contains("HermiT"));
        }

        @Test
        @DisplayName("AutoReasonerSelector selects ELK for OWL 2 EL profile")
        void autoSelectorELKForELProfile() {
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult result = selector.select("OWL 2 EL", false);

            assertEquals("ELK", result.reasonerName());
            assertEquals("OWL 2 EL", result.detectedProfile());
        }

        @Test
        @DisplayName("AutoReasonerSelector selects Openllet when explanation is requested")
        void autoSelectorOpenlletForExplanation() {
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult result = selector.select("OWL 2 DL", true);

            assertEquals("Openllet", result.reasonerName());
            assertTrue(result.selectionRationale().contains("Openllet"));
        }
    }

    // ─── 21.2 Classification ───

    @Nested
    @DisplayName("21.2 Classification")
    class ClassificationTests {

        @Test
        @DisplayName("Classification produces inferred hierarchy with at least some entries")
        void classificationProducesHierarchy() {
            importFixture("pizza", "smoke/pizza.owl");

            ReasonerServiceImpl reasonerService = createReasonerService();

            // Use ELK reasoner explicitly to avoid HermiT OWL API 4.5.29 compatibility issue
            ServiceResult<ClassificationResult> result =
                reasonerService.classify(new OntologyId("pizza"), Optional.of("ELK"));

            assertTrue(result.isSuccess(),
                "Classification must succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            ClassificationResult classification =
                ((ServiceResult.Success<ClassificationResult>) result).data();

            assertNotNull(classification.completeHierarchy());
            assertFalse(classification.completeHierarchy().isEmpty(),
                "Classification must produce at least some inferred hierarchy entries");
            assertNotNull(classification.reasonerName());
            assertEquals("pizza", classification.ontologyId());
        }
    }

    // ─── 21.3 Consistency ───

    @Nested
    @DisplayName("21.3 Consistency")
    class ConsistencyTests {

        @Test
        @DisplayName("Consistency check returns consistent for Pizza.owl")
        void consistentForPizzaOwl() {
            importFixture("pizza", "smoke/pizza.owl");

            ReasonerServiceImpl reasonerService = createReasonerService();

            // Use ELK reasoner explicitly to avoid HermiT OWL API 4.5.29 compatibility issue
            ServiceResult<ConsistencyResult> result =
                reasonerService.checkConsistency(new OntologyId("pizza"), Optional.of("ELK"));

            assertTrue(result.isSuccess(),
                "Consistency check must succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            ConsistencyResult consistency =
                ((ServiceResult.Success<ConsistencyResult>) result).data();

            assertTrue(consistency.consistent(),
                "Pizza.owl must be reported as consistent");
            assertNotNull(consistency.reasonerName());
        }

        @Test
        @DisplayName("Consistency check returns inconsistent for inconsistent.owl fixture")
        void inconsistentForInconsistentOwl() {
            importFixture("inconsistent", "golden/inconsistent.owl");

            ReasonerServiceImpl reasonerService = createReasonerService();

            ServiceResult<ConsistencyResult> result =
                reasonerService.checkConsistency(new OntologyId("inconsistent"), Optional.of("ELK"));

            assertTrue(result.isSuccess(),
                "Consistency check must succeed even for inconsistent ontology: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            ConsistencyResult consistency =
                ((ServiceResult.Success<ConsistencyResult>) result).data();

            assertFalse(consistency.consistent(),
                "inconsistent.owl must be reported as inconsistent");
            assertNotNull(consistency.unsatisfiableClassIRIs());
        }
    }

    // ─── 21.4 Entity context enrichment ───

    @Nested
    @DisplayName("21.4 Entity context enrichment")
    class EntityContextEnrichmentTests {

        @Test
        @DisplayName("ClassContext includes inferred fields when reasoning is available")
        void classContextWithInferredFields() {
            ClassContext enrichedContext = ClassContext.withInferred(
                "http://example.org/C", "ex:C", "C", "Test class",
                List.of("http://example.org/SubC"),
                List.of("http://example.org/SuperC"),
                List.of(), List.of(),
                List.of(),
                "HermiT",
                List.of("http://example.org/InferredSuperC"),
                List.of("http://example.org/InferredSubC"),
                List.of(), List.of()
            );

            assertTrue(enrichedContext.reasoningStatus().isPresent(),
                "Enriched ClassContext must have reasoningStatus");
            assertEquals("HermiT", enrichedContext.reasoningStatus().get());
            assertTrue(enrichedContext.inferredSuperclasses().isPresent(),
                "Enriched ClassContext must have inferredSuperclasses");
            assertFalse(enrichedContext.inferredSuperclasses().get().isEmpty(),
                "inferredSuperclasses should contain entries");
            assertTrue(enrichedContext.inferredSubclasses().isPresent(),
                "Enriched ClassContext must have inferredSubclasses");
        }

        @Test
        @DisplayName("ClassContext explicit factory does not include inferred fields")
        void classContextExplicitWithoutInferred() {
            ClassContext explicitContext = ClassContext.explicit(
                "http://example.org/C", "ex:C", "C", "Test class",
                List.of("http://example.org/SubC"),
                List.of("http://example.org/SuperC"),
                List.of(), List.of(),
                List.of()
            );

            assertTrue(explicitContext.reasoningStatus().isEmpty(),
                "Explicit ClassContext must not have reasoningStatus");
            assertTrue(explicitContext.inferredSuperclasses().isEmpty(),
                "Explicit ClassContext must not have inferredSuperclasses");
            assertTrue(explicitContext.inferredSubclasses().isEmpty(),
                "Explicit ClassContext must not have inferredSubclasses");
        }
    }

    // ─── 21.5 Recommended reasoner ───

    @Nested
    @DisplayName("21.5 Recommended reasoner")
    class RecommendedReasonerTests {

        @Test
        @DisplayName("OntologySummary includes recommendedReasoner field")
        void summaryIncludesRecommendedReasoner() {
            importFixture("pizza", "smoke/pizza.owl");

            var catalogResult = catalogStore.findEntry(WorkspaceId.DEFAULT, new OntologyId("pizza"));
            assertTrue(catalogResult.isSuccess(),
                "Pizza ontology must be in catalog after import");

            var entry = ((ServiceResult.Success<CatalogEntry>) catalogResult).data();
            var summaryResult = summaryExtractor.extractSummary(
                new OntologyId("pizza"), entry.canonicalPath());

            assertTrue(summaryResult.isSuccess(),
                "Summary extraction must succeed: " +
                (summaryResult instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            OntologySummary summary =
                ((ServiceResult.Success<OntologySummary>) summaryResult).data();

            assertTrue(summary.recommendedReasoner().isPresent(),
                "OntologySummary must include recommendedReasoner field");
            String recommended = summary.recommendedReasoner().get();
            assertFalse(recommended.isEmpty(),
                "recommendedReasoner must not be empty");
            assertTrue(recommended.equalsIgnoreCase("hermit"),
                "For OWL 2 DL ontologies, recommended reasoner should be hermit: got " + recommended);
        }

        @Test
        @DisplayName("OntologySummary withoutReasoner factory omits recommendedReasoner")
        void summaryWithoutReasonerOmitsField() {
            OntologySummary summary = OntologySummary.withoutReasoner(
                new OntologyId("test"), "http://example.org/test", null,
                List.of(), new ProfileInfo(List.of(), List.of()),
                new EntityCounts(1, 0, 0, 0, 0, 0)
            );

            assertTrue(summary.recommendedReasoner().isEmpty(),
                "withoutReasoner factory must produce empty recommendedReasoner");
        }

        @Test
        @DisplayName("OntologySummary withReasoner factory populates recommendedReasoner")
        void summaryWithReasonerPopulatesField() {
            OntologySummary summary = OntologySummary.withReasoner(
                new OntologyId("test"), "http://example.org/test", null,
                List.of(), new ProfileInfo(List.of("OWL 2 DL"), List.of()),
                new EntityCounts(1, 0, 0, 0, 0, 0),
                "hermit"
            );

            assertTrue(summary.recommendedReasoner().isPresent());
            assertEquals("hermit", summary.recommendedReasoner().get());
        }
    }

    // ─── 21.6 SPARQL scope ───

    @Nested
    @DisplayName("21.6 SPARQL scope")
    class SparqlScopeTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("ontology_list_graphs returns explicit, inferred, and union")
        void listGraphsReturnsThreeScopes() {
            McpServerAdapter mcpAdapter = new McpServerAdapter(Map.of(), tempDir.toString());

            var result = mcpAdapter.handleToolCall("ontology_list_graphs",
                Map.of("ontology_id", "pizza"));

            assertEquals("success", result.get("status"));
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            List<String> scopes = (List<String>) data.get("scopes");

            assertTrue(scopes.contains("explicit"),
                "SPARQL scopes must include 'explicit'");
            assertTrue(scopes.contains("inferred"),
                "SPARQL scopes must include 'inferred'");
            assertTrue(scopes.contains("union"),
                "SPARQL scopes must include 'union'");
            assertEquals(3, scopes.size(),
                "SPARQL scopes must have exactly 3 entries");
        }
    }

    // ─── 21.7 Semantic deepening ───

    @Nested
    @DisplayName("21.7 Semantic deepening")
    class SemanticDeepeningTests {

        @Test
        @DisplayName("getClassRestrictions returns restrictions for a class")
        void classRestrictionsForPizzaClass() {
            importFixture("pizza", "smoke/pizza.owl");

            SemanticDeepeningService service = createDeepeningService();

            ServiceResult<ClassRestrictionsResult> result =
                service.getClassRestrictions(new OntologyId("pizza"),
                    "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza",
                    false);

            assertTrue(result.isSuccess(),
                "getClassRestrictions must succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            ClassRestrictionsResult restrictionsResult =
                ((ServiceResult.Success<ClassRestrictionsResult>) result).data();

            assertEquals("pizza", restrictionsResult.ontologyId());
            assertNotNull(restrictionsResult.restrictions());
            assertFalse(restrictionsResult.restrictions().isEmpty(),
                "Pizza class must have at least one restriction (e.g. someValuesFrom on hasBase)");
        }

        @Test
        @DisplayName("SemanticDeepeningService provides import closure")
        void importClosureForPizza() {
            importFixture("pizza", "smoke/pizza.owl");

            SemanticDeepeningService service = createDeepeningService();

            ServiceResult<ImportClosureResult> result =
                service.getImportClosure(new OntologyId("pizza"));

            assertTrue(result.isSuccess(),
                "Import closure must succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().message() : ""));

            ImportClosureResult importClosure =
                ((ServiceResult.Success<ImportClosureResult>) result).data();

            assertEquals("pizza", importClosure.ontologyId());
            assertNotNull(importClosure.imports());
        }
    }

    // ─── 21.8 Readonly policy ───

    @Nested
    @DisplayName("21.8 Readonly policy verification for v0.2")
    class ReadonlyPolicyTests {

        @Test
        @DisplayName("v0.2 reasoning tools are still readonly")
        void v02ReasoningToolsAreReadonly() {
            McpToolRegistry registry = new McpToolRegistry();

            assertTrue(registry.isReadonlyTool("ontology_list_reasoners"));
            assertTrue(registry.isReadonlyTool("ontology_run_reasoner"));
            assertTrue(registry.isReadonlyTool("ontology_classify"));
            assertTrue(registry.isReadonlyTool("ontology_realize_instances"));
            assertTrue(registry.isReadonlyTool("ontology_check_consistency"));
            assertTrue(registry.isReadonlyTool("ontology_explain_inconsistency"));
            assertTrue(registry.isReadonlyTool("ontology_explain_unsat_class"));
            assertTrue(registry.isReadonlyTool("ontology_get_unsat_classes"));
            assertTrue(registry.isReadonlyTool("ontology_get_reasoning_report"));
            assertTrue(registry.isReadonlyTool("ontology_get_inferred_facts"));
            assertTrue(registry.isReadonlyTool("ontology_check_entailment"));
        }

        @Test
        @DisplayName("v0.2 consistency-analysis tools are readonly")
        void v02ConsistencyAnalysisToolsAreReadonly() {
            McpToolRegistry registry = new McpToolRegistry();

            assertTrue(registry.isReadonlyTool("ontology_check_class_compatibility"));
            assertTrue(registry.isReadonlyTool("ontology_check_individual_membership"));
            assertTrue(registry.isReadonlyTool("ontology_check_relation_assertion"));
            assertTrue(registry.isReadonlyTool("ontology_get_scope"));
        }

        @Test
        @DisplayName("v0.2 semantic-deepening tools are readonly")
        void v02SemanticDeepeningToolsAreReadonly() {
            McpToolRegistry registry = new McpToolRegistry();

            assertTrue(registry.isReadonlyTool("ontology_get_imports"));
            assertTrue(registry.isReadonlyTool("ontology_get_class_restrictions"));
            assertTrue(registry.isReadonlyTool("ontology_get_property_characteristics"));
            assertTrue(registry.isReadonlyTool("ontology_get_equivalent_properties"));
            assertTrue(registry.isReadonlyTool("ontology_get_disjoint_properties"));
            assertTrue(registry.isReadonlyTool("ontology_get_datatype_constraints"));
            assertTrue(registry.isReadonlyTool("ontology_validate_literal"));
            assertTrue(registry.isReadonlyTool("ontology_find_relations_between_entities"));
            assertTrue(registry.isReadonlyTool("ontology_get_object_property_assertions"));
            assertTrue(registry.isReadonlyTool("ontology_get_data_property_assertions"));
            assertTrue(registry.isReadonlyTool("ontology_get_same_individuals"));
            assertTrue(registry.isReadonlyTool("ontology_get_different_individuals"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("MCP write-style tool call is rejected for v0.2")
        void mcpWriteToolRejected() {
            McpServerAdapter mcpAdapter = new McpServerAdapter(Map.of(), tempDir.toString());

            var result = mcpAdapter.handleToolCall("ontology_edit_entity", Map.of());
            assertTrue(result.containsKey("error"));
            assertEquals("READONLY_VIOLATION",
                ((Map<String, Object>) result.get("error")).get("code"));
        }

        @Test
        @DisplayName("SPARQL INSERT is still rejected under v0.2")
        void sparqlInsertStillRejected() {
            SparqlSafetyGuard guard = new SparqlSafetyGuard();
            var result = guard.checkReadonly("INSERT DATA { <x> a <y> }");
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.READONLY_VIOLATION,
                ((ServiceResult.Error<Void>) result).error().code());
        }
    }

    // ─── 21.9 v0.2 error codes ───

    @Nested
    @DisplayName("21.9 v0.2 error code acceptance")
    class ErrorCodeTests {

        @Test
        @DisplayName("All v0.2 reasoning error codes are defined")
        void reasoningErrorCodesDefined() {
            assertNotNull(ErrorCode.REASONING_NOT_RUN);
            assertNotNull(ErrorCode.REASONER_NOT_AVAILABLE);
            assertNotNull(ErrorCode.PROFILE_NOT_SUPPORTED);
            assertNotNull(ErrorCode.EXPLANATION_NOT_SUPPORTED);
            assertNotNull(ErrorCode.ONTOLOGY_INCONSISTENT);
            assertNotNull(ErrorCode.ONTOLOGY_CONSISTENT);
            assertNotNull(ErrorCode.CLASSIFICATION_FAILED);
            assertNotNull(ErrorCode.EXPLANATION_FAILED);
            assertNotNull(ErrorCode.REASONER_SHUTDOWN);
        }

        @Test
        @DisplayName("All v0.2 entity and axiom error codes are defined")
        void entityAxiomErrorCodesDefined() {
            assertNotNull(ErrorCode.CLASS_NOT_FOUND);
            assertNotNull(ErrorCode.PROPERTY_NOT_FOUND);
            assertNotNull(ErrorCode.DATATYPE_NOT_FOUND);
            assertNotNull(ErrorCode.PROPERTY_RANGE_NOT_FOUND);
            assertNotNull(ErrorCode.INDIVIDUAL_NOT_FOUND);
            assertNotNull(ErrorCode.INVALID_AXIOM_PARAMETERS);
            assertNotNull(ErrorCode.INVALID_AXIOM_STRUCTURE);
            assertNotNull(ErrorCode.INVALID_SPARQL_SCOPE);
            assertNotNull(ErrorCode.SCOPE_ANALYSIS_FAILED);
        }

        @Test
        @DisplayName("All v0.2 semantic-deepening status codes are defined")
        void semanticDeepeningStatusCodesDefined() {
            assertNotNull(ErrorCode.IMPORTS_EMPTY);
            assertNotNull(ErrorCode.CLASS_NO_RESTRICTIONS);
            assertNotNull(ErrorCode.PROPERTY_NO_EQUIVALENTS);
            assertNotNull(ErrorCode.PROPERTY_NO_DISJOINTS);
            assertNotNull(ErrorCode.DATATYPE_NO_CONSTRAINTS);
            assertNotNull(ErrorCode.NO_RELATIONS_FOUND);
        }
    }

    // ─── 21.10 v0.2 data model acceptance ───

    @Nested
    @DisplayName("21.10 v0.2 data model acceptance")
    class DataModelTests {

        @Test
        @DisplayName("v0.2 result records are defined with required fields")
        void v02ResultRecordsDefined() {
            // ClassificationResult
            ClassificationResult classification = new ClassificationResult(
                "test", "HermiT", List.of(), List.of());
            assertEquals("test", classification.ontologyId());
            assertEquals("HermiT", classification.reasonerName());

            // ConsistencyResult
            ConsistencyResult consistency = new ConsistencyResult(
                "test", "HermiT", true, List.of());
            assertTrue(consistency.consistent());

            // ReasonerSelectionResult
            ReasonerSelectionResult selection = new ReasonerSelectionResult(
                "HermiT", "OWL 2 DL", "HermiT is the reference OWL 2 DL reasoner");
            assertEquals("HermiT", selection.reasonerName());

            // ReasonerListResult
            ReasonerListResult list = new ReasonerListResult(List.of());
            assertNotNull(list.reasoners());

            // ReasonerCapability
            ReasonerCapability cap = new ReasonerCapability(
                "HermiT", List.of("OWL 2 DL"), List.of("classify"), false);
            assertEquals("HermiT", cap.name());

            // ClassRestrictionsResult
            ClassRestrictionsResult restrictions = new ClassRestrictionsResult(
                "test", "http://example.org/C", List.of());
            assertEquals("http://example.org/C", restrictions.classIRI());

            // ScopeDescription
            ScopeDescription scope = new ScopeDescription(
                "test", List.of(), List.of(), List.of(), List.of());
            assertEquals("test", scope.ontologyId());
        }

        @Test
        @DisplayName("ReasoningReport structure supports v0.2 fields")
        void reasoningReportStructure() {
            ReasoningReport.TimingBreakdown timing =
                new ReasoningReport.TimingBreakdown(100, 200, 300, 600);
            assertEquals(600, timing.totalTimeMs());

            ReasoningReport report = new ReasoningReport(
                "test", "HermiT", "OWL 2 DL",
                true, true, true, timing, 0,
                Map.of("SubClassOf", 5), null);
            assertTrue(report.classificationStatus());
            assertTrue(report.consistencyStatus());
            assertEquals(5, report.inferredAxiomCountsByType().get("SubClassOf"));
        }
    }
}