package org.owl4agents.validation;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.*;
import org.owl4agents.core.model.ClassCompatibilityResult;

import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Task 9.5: Evidence upgrade tests for the v0.8.5 exact consistency
 * verification pipeline.
 *
 * <p>Verifies the four evidence scenarios introduced by design D10:
 * <ul>
 *   <li>CONSISTENCY_REPORT on CONTRADICTED (task 9.1)</li>
 *   <li>INCONSISTENCY_JUSTIFICATION with Openllet explanation axioms (task 9.2)</li>
 *   <li>STRUCTURAL_CONFLICT_HINT on UNKNOWN with proxy hint (task 9.3)</li>
 *   <li>UNKNOWN evidence content: "not entailed" + "preserves consistency" (task 9.4)</li>
 * </ul>
 */
@DisplayName("Task 9.5: Evidence upgrade (D10)")
class EvidenceUpgradeTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String CHEESEY_PIZZA = PIZZA_NS + "CheeseyPizza";
    private static final String PIZZA_TOPPING = PIZZA_NS + "PizzaTopping";
    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private StubReasonerService stubReasoner;
    private ConfigurableConsistencyService stubConsistency;
    private ClaimVerificationService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService().withRealOntology(WORKSPACE);
        stubConsistency = new ConfigurableConsistencyService();
        service = new ClaimVerificationService(
            stubReasoner,
            stubConsistency,
            new SemanticDeepeningService(WORKSPACE),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    private Claim subclassClaim(String id, String subIri, String objIri) {
        return new Claim(id, ClaimType.SUBCLASS, "pizza",
            new ClaimEntity("class", subIri),
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            new ClaimEntity("class", objIri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private ClaimVerificationResult extract(ServiceResult<ClaimVerificationResult> result) {
        assertTrue(result.isSuccess(), "ServiceResult should be success");
        return ((ServiceResult.Success<ClaimVerificationResult>) result).data();
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 9.1: CONSISTENCY_REPORT on CONTRADICTED
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9.1: CONSISTENCY_REPORT on CONTRADICTED")
    class ConsistencyReportOnContradicted {

        @Test
        @DisplayName("CONTRADICTED evidence includes CONSISTENCY_REPORT with inconsistency summary")
        void contradictedIncludesConsistencyReport() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.INCONSISTENT);
            Claim claim = subclassClaim("cr-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            EvidenceItem report = data.evidence().stream()
                .filter(e -> e.kind() == EvidenceKind.CONSISTENCY_REPORT)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "CONSISTENCY_REPORT evidence must be present on CONTRADICTED"));
            assertEquals(EvidenceItem.ROLE_COUNTER, report.role(),
                "CONSISTENCY_REPORT must be counter-evidence");
            assertNotNull(report.value(), "CONSISTENCY_REPORT must have a non-null value");
            assertTrue(report.value().contains("inconsistent"),
                "CONSISTENCY_REPORT value must mention inconsistency, got: " + report.value());
            assertNotNull(report.reasoner(), "CONSISTENCY_REPORT must have reasoner name");
            assertFalse(report.reasoner().isBlank(),
                "CONSISTENCY_REPORT reasoner name must not be blank");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 9.2: INCONSISTENCY_JUSTIFICATION with Openllet explanation
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9.2: INCONSISTENCY_JUSTIFICATION with explanation axioms")
    class InconsistencyJustification {

        @Test
        @DisplayName("CONTRADICTED with explanation axioms yields INCONSISTENCY_JUSTIFICATION evidence")
        void contradictedWithExplanationYieldsJustification() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.INCONSISTENT);
            stubReasoner.withConsistencyExplanationAxioms(
                List.of("DisjointClasses(Pizza PizzaTopping)",
                        "SubClassOf(CheeseyPizza Pizza)"));
            Claim claim = subclassClaim("ij-1", CHEESEY_PIZZA, PIZZA_TOPPING);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            List<EvidenceItem> justifications = data.evidence().stream()
                .filter(e -> e.kind() == EvidenceKind.INCONSISTENCY_JUSTIFICATION)
                .toList();
            assertFalse(justifications.isEmpty(),
                "INCONSISTENCY_JUSTIFICATION evidence must be present when explanation axioms available");
            assertEquals(2, justifications.size(),
                "Each explanation axiom should produce one INCONSISTENCY_JUSTIFICATION item");
            for (EvidenceItem j : justifications) {
                assertEquals(EvidenceItem.ROLE_COUNTER, j.role(),
                    "INCONSISTENCY_JUSTIFICATION must be counter-evidence");
                assertNotNull(j.value(),
                    "INCONSISTENCY_JUSTIFICATION value must be the Manchester syntax axiom");
            }
        }

        @Test
        @DisplayName("CONTRADICTED without explanation axioms has no INCONSISTENCY_JUSTIFICATION")
        void contradictedWithoutExplanationHasNoJustification() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.INCONSISTENT);
            stubReasoner.withConsistencyExplanationAxioms(List.of());
            Claim claim = subclassClaim("ij-2", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            assertTrue(data.evidence().stream()
                .noneMatch(e -> e.kind() == EvidenceKind.INCONSISTENCY_JUSTIFICATION),
                "No INCONSISTENCY_JUSTIFICATION when explanation axioms are empty");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 9.3: STRUCTURAL_CONFLICT_HINT on UNKNOWN with proxy
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9.3: STRUCTURAL_CONFLICT_HINT on UNKNOWN with proxy")
    class StructuralConflictHintOnUnknown {

        @Test
        @DisplayName("UNKNOWN with disjoint proxy hint yields STRUCTURAL_CONFLICT_HINT evidence")
        void unknownWithDisjointProxyYieldsHint() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.CONSISTENT);
            // Configure proxy to report DISJOINT — simulates a structural
            // proxy detecting a potential conflict that the exact check
            // (using a different reasoner) did not confirm.
            stubConsistency.withCompatibility(PIZZA, PIZZA_TOPPING,
                ClassCompatibilityResult.DISJOINT);
            Claim claim = subclassClaim("sch-1", PIZZA, PIZZA_TOPPING);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.UNKNOWN, data.verdict(),
                "Exact check CONSISTENT must yield UNKNOWN even with proxy hint");
            EvidenceItem hint = data.evidence().stream()
                .filter(e -> e.kind() == EvidenceKind.STRUCTURAL_CONFLICT_HINT)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "STRUCTURAL_CONFLICT_HINT must be present when proxy detects conflict"));
            assertEquals(EvidenceItem.ROLE_SUPPORTING, hint.role(),
                "STRUCTURAL_CONFLICT_HINT is supporting evidence (not counter)");
            assertNotNull(hint.value());
            assertTrue(hint.value().contains("structurally"),
                "STRUCTURAL_CONFLICT_HINT must mention 'structurally'");
            assertTrue(hint.value().contains("hint"),
                "STRUCTURAL_CONFLICT_HINT must clarify it is a hint, not a formal contradiction");
        }

        @Test
        @DisplayName("UNKNOWN with compatible proxy has no STRUCTURAL_CONFLICT_HINT")
        void unknownWithCompatibleProxyHasNoHint() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.CONSISTENT);
            stubConsistency.withCompatibility(CHEESEY_PIZZA, PIZZA,
                ClassCompatibilityResult.COMPATIBLE);
            Claim claim = subclassClaim("sch-2", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertTrue(data.evidence().stream()
                .noneMatch(e -> e.kind() == EvidenceKind.STRUCTURAL_CONFLICT_HINT),
                "No STRUCTURAL_CONFLICT_HINT when proxy says COMPATIBLE");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Task 9.4: UNKNOWN evidence content
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9.4: UNKNOWN evidence content")
    class UnknownEvidenceContent {

        @Test
        @DisplayName("UNKNOWN evidence states 'not entailed' and 'preserves consistency'")
        void unknownEvidenceContentIsCorrect() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.CONSISTENT);
            stubConsistency.withCompatibility(CHEESEY_PIZZA, PIZZA,
                ClassCompatibilityResult.COMPATIBLE);
            Claim claim = subclassClaim("ue-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.UNKNOWN, data.verdict());
            EvidenceItem report = data.evidence().stream()
                .filter(e -> e.kind() == EvidenceKind.REASONING_REPORT)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "UNKNOWN must have REASONING_REPORT evidence"));
            String value = report.value();
            assertNotNull(value);
            assertTrue(value.contains("not entailed"),
                "UNKNOWN evidence must state 'not entailed', got: " + value);
            assertTrue(value.contains("preserves consistency") || value.contains("consistent"),
                "UNKNOWN evidence must state consistency is preserved, got: " + value);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Configurable stub ConsistencyAnalysisService
    // ════════════════════════════════════════════════════════════════════

    /**
     * Stub {@link ConsistencyAnalysisService} that allows configuring
     * class compatibility results per IRI pair. Used to test the
     * STRUCTURAL_CONFLICT_HINT path (task 9.3) deterministically
     * without depending on a real reasoner's class compatibility verdict.
     */
    private static class ConfigurableConsistencyService extends ConsistencyAnalysisService {

        private final java.util.Map<String, String> compatibilityMap = new java.util.HashMap<>();

        ConfigurableConsistencyService() {
            super(new ReasonerLifecycleManager(), "dummy-path");
        }

        ConfigurableConsistencyService withCompatibility(String iri1, String iri2, String compatibility) {
            compatibilityMap.put(key(iri1, iri2), compatibility);
            return this;
        }

        private String key(String iri1, String iri2) {
            return iri1 + "||" + iri2;
        }

        @Override
        public ServiceResult<ClassCompatibilityResult> checkClassCompatibility(
                OntologyId ontologyId, String class1IRI, String class2IRI) {
            return checkCompat(ontologyId, class1IRI, class2IRI);
        }

        @Override
        public ServiceResult<ClassCompatibilityResult> checkClassCompatibility(
                OWLOntology ontology, OntologyId ontologyId,
                String class1IRI, String class2IRI) {
            return checkCompat(ontologyId, class1IRI, class2IRI);
        }

        private ServiceResult<ClassCompatibilityResult> checkCompat(
                OntologyId ontologyId, String class1IRI, String class2IRI) {
            String compat = compatibilityMap.getOrDefault(key(class1IRI, class2IRI),
                ClassCompatibilityResult.COMPATIBLE);
            ClassCompatibilityResult result = new ClassCompatibilityResult(
                ontologyId.id(), class1IRI, class2IRI, compat, "stub");
            return ServiceResult.success(result, org.owl4agents.core.ResultMetadata.empty());
        }

        @Override
        public boolean isEntityDeclared(OntologyId ontologyId, String entityIRI, String kind) {
            return true;
        }

        @Override
        public boolean isEntityDeclared(OWLOntology ontology, OntologyId ontologyId,
                                         String entityIRI, String kind) {
            return true;
        }

        @Override
        public ServiceResult<ScopeDescription> getScope(OntologyId ontologyId) {
            ScopeDescription scope = new ScopeDescription(
                ontologyId.id(), List.of("test"), List.of(), List.of(), List.of());
            return ServiceResult.success(scope, org.owl4agents.core.ResultMetadata.empty());
        }
    }
}
