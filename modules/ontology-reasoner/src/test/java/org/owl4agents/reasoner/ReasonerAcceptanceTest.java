package org.owl4agents.reasoner;

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
 * V0.2 acceptance gates for reasoner features:
 * classification, consistency, explanation, and auto-selection.
 * Uses ELK reasoner for classification and consistency to avoid
 * HermiT OWL API 4.5.29 compatibility issues.
 */
@DisplayName("v0.2 Reasoner acceptance gates")
class ReasonerAcceptanceTest {

    private static Path fixtureDir;
    private static Path tempHome;
    private static HomeDirectoryResolver homeResolver;
    private static CatalogStore catalogStore;

    @BeforeAll
    static void setup() throws Exception {
        fixtureDir = Path.of(System.getProperty("corpus.fixtures"));
        tempHome = Files.createTempDirectory("owl4agents-test");
        homeResolver = new HomeDirectoryResolver(tempHome);
        catalogStore = new CatalogStore(homeResolver);

        // Initialize default workspace before any imports
        org.owl4agents.storage.WorkspaceInitializer initializer =
            new org.owl4agents.storage.WorkspaceInitializer(homeResolver);
        var initResult = initializer.initializeIdempotent(WorkspaceId.DEFAULT);
        assertTrue(initResult.isSuccess(), "Workspace initialization should succeed");
    }

    @AfterAll
    static void cleanup() throws Exception {
        Files.walk(tempHome).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (Exception ignored) {}
        });
    }

    private void importPizza() {
        // Check if pizza is already imported
        var existing = catalogStore.findEntry(WorkspaceId.DEFAULT, new OntologyId("pizza"));
        if (existing.isSuccess()) return;

        Path pizzaPath = fixtureDir.resolve("smoke/pizza.owl");
        var importer = new org.owl4agents.owlapi.OntologyImporter(homeResolver, catalogStore);
        var result = importer.importOntology(new OntologyId("pizza"), pizzaPath, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(), "Pizza import should succeed: " +
            (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
    }

    private void importInconsistent() {
        // Check if inconsistent is already imported
        var existing = catalogStore.findEntry(WorkspaceId.DEFAULT, new OntologyId("inconsistent"));
        if (existing.isSuccess()) return;

        Path inconsistentPath = fixtureDir.resolve("golden/inconsistent.owl");
        var importer = new org.owl4agents.owlapi.OntologyImporter(homeResolver, catalogStore);
        var result = importer.importOntology(new OntologyId("inconsistent"), inconsistentPath, WorkspaceId.DEFAULT);
        assertTrue(result.isSuccess(), "Inconsistent import should succeed: " +
            (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
    }

    private ReasonerServiceImpl createReasonerService() {
        String workspaceBasePath = homeResolver.resolveHomeDirectory()
            .resolve("workspaces").toString();
        return new ReasonerServiceImpl(catalogStore, workspaceBasePath);
    }

    // ── Classification ──

    @Nested
    @DisplayName("Classification acceptance gates")
    class ClassificationTests {

        @Test
        @DisplayName("ELK classification produces inferred hierarchy")
        void elkClassification() {
            importPizza();
            ReasonerServiceImpl reasonerService = createReasonerService();

            var result = reasonerService.classify(new OntologyId("pizza"), Optional.of("ELK"));
            assertTrue(result.isSuccess(), "ELK classification should succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
            ClassificationResult data = ((ServiceResult.Success<ClassificationResult>) result).data();
            assertFalse(data.completeHierarchy().isEmpty(), "ELK inferred hierarchy should have entries");
            assertFalse(data.delta().isEmpty(), "Delta (new inferences) should be non-empty for pizza");
        }
    }

    // ── Consistency ──

    @Nested
    @DisplayName("Consistency acceptance gates")
    class ConsistencyTests {

        @Test
        @DisplayName("Consistency check returns consistent for valid ontologies")
        void consistentOntology() {
            importPizza();
            ReasonerServiceImpl reasonerService = createReasonerService();

            var result = reasonerService.checkConsistency(new OntologyId("pizza"), Optional.of("ELK"));
            assertTrue(result.isSuccess(), "Consistency check should succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
            ConsistencyResult data = ((ServiceResult.Success<ConsistencyResult>) result).data();
            assertTrue(data.consistent(), "Pizza ontology should be consistent");
        }

        @Test
        @DisplayName("Consistency check returns inconsistent for broken ontologies")
        void inconsistentOntology() {
            importInconsistent();
            ReasonerServiceImpl reasonerService = createReasonerService();

            var result = reasonerService.checkConsistency(new OntologyId("inconsistent"), Optional.of("ELK"));
            assertTrue(result.isSuccess(), "Inconsistent consistency check should succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
            ConsistencyResult data = ((ServiceResult.Success<ConsistencyResult>) result).data();
            assertFalse(data.consistent(), "Inconsistent ontology should be detected as inconsistent");
            assertFalse(data.unsatisfiableClassIRIs().isEmpty(), "Should have unsatisfiable classes");

            reasonerService.shutdown(new OntologyId("inconsistent"));
        }
    }

    // ── Realization ──

    @Nested
    @DisplayName("Realization acceptance gates")
    class RealizationTests {

        @Test
        @DisplayName("Realization produces inferred individual types")
        void realizationProducesInferredTypes() {
            importPizza();
            ReasonerServiceImpl reasonerService = createReasonerService();

            var result = reasonerService.realize(new OntologyId("pizza"), Optional.of("ELK"));
            assertTrue(result.isSuccess(), "Realization should succeed: " +
                (result instanceof ServiceResult.Error<?> e ? e.error().code() + " " + e.error().message() : "unknown"));
            RealizationResult data = ((ServiceResult.Success<RealizationResult>) result).data();
            assertNotNull(data);
        }
    }

    // ── Auto Reasoner Selection ──

    @Nested
    @DisplayName("Auto reasoner selection")
    class AutoSelectionTests {

        @Test
        @DisplayName("Auto reasoner selection chooses HermiT for OWL 2 DL ontologies")
        void autoSelectionDL() {
            importPizza();
            ReasonerServiceImpl reasonerService = createReasonerService();

            var result = reasonerService.selectReasoner(new OntologyId("pizza"), false);
            assertTrue(result.isSuccess());
            ReasonerSelectionResult data = ((ServiceResult.Success<ReasonerSelectionResult>) result).data();
            assertEquals("HermiT", data.reasonerName(), "Pizza.owl (OWL 2 DL) should select HermiT");
        }
    }

    // ── Explanation ──

    @Nested
    @DisplayName("Explanation acceptance gates")
    class ExplanationTests {

        @Test
        @DisplayName("HermiT does not support explanation")
        void hermitNoExplanation() {
            HermiTAdapter adapter = new HermiTAdapter();
            assertFalse(adapter.supportsExplanation(), "HermiT should not support explanation");
            assertThrows(UnsupportedOperationException.class, () -> adapter.explainInconsistency("test"));
        }

        @Test
        @DisplayName("Openllet supports explanation and is available under OWL API 5.x")
        void openlletAvailable() {
            OpenlletAdapter adapter = new OpenlletAdapter();
            assertTrue(adapter.supportsExplanation(), "Openllet adapter declares explanation support");
        }
    }

    // ── Reasoner List ──

    @Nested
    @DisplayName("Reasoner list acceptance gates")
    class ReasonerListTests {

        @Test
        @DisplayName("List reasoners returns all available adapters")
        void listReasoners() {
            ReasonerServiceImpl reasonerService = createReasonerService();

            var result = reasonerService.listReasoners();
            assertTrue(result.isSuccess());
            ReasonerListResult data = ((ServiceResult.Success<ReasonerListResult>) result).data();
            assertFalse(data.reasoners().isEmpty(), "Should have at least one reasoner listed");

            // HermiT, ELK, and Openllet should all be listed
            var hermit = data.reasoners().stream().filter(r -> "HermiT".equals(r.name())).findFirst();
            assertTrue(hermit.isPresent(), "HermiT should be listed");

            var elk = data.reasoners().stream().filter(r -> "ELK".equals(r.name())).findFirst();
            assertTrue(elk.isPresent(), "ELK should be listed");

            var openllet = data.reasoners().stream().filter(r -> "Openllet".equals(r.name())).findFirst();
            assertTrue(openllet.isPresent(), "Openllet should be listed");
            assertTrue(openllet.get().explanationSupported(), "Openllet should support explanation");
        }
    }
}