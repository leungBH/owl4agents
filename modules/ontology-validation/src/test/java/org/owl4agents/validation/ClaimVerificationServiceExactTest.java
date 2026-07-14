package org.owl4agents.validation;

import java.util.Optional;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.*;

import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * v0.8.5 / Task 8.9: JUnit 5 tests for the 5-stage exact consistency
 * verification pipeline in {@link ClaimVerificationService}.
 *
 * <p>Covers all major paths through the pipeline (design D1):
 * <ul>
 *   <li>Stage 1 short-circuit: OUT_OF_SCOPE (external IRI)</li>
 *   <li>Stage 2 error: SOURCE_ONTOLOGY_INCONSISTENT</li>
 *   <li>Stage 3 short-circuit: ENTAILED → SUPPORTED (no exact check)</li>
 *   <li>Stage 3 error: CLAIM_AXIOM_BUILD_FAILED</li>
 *   <li>Stage 4 exact: CONSISTENT → UNKNOWN, INCONSISTENT → CONTRADICTED</li>
 *   <li>Stage 4 error: TIMEOUT → REASONER_TIMEOUT, ERROR → CLAIM_CONSISTENCY_CHECK_FAILED</li>
 *   <li>Special claim type bypass (design D9)</li>
 *   <li>Same-individual self-contradiction (DISJOINT_CLASSES with identical IRIs)</li>
 *   <li>PerStageTiming population for completed and errored results</li>
 *   <li>Evidence kind verification (CONSISTENCY_REPORT, REASONING_REPORT)</li>
 * </ul>
 */
@DisplayName("v0.8.5 5-stage exact consistency verification (task 8.9)")
class ClaimVerificationServiceExactTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String CHEESEY_PIZZA = PIZZA_NS + "CheeseyPizza";
    private static final String FRANCE = PIZZA_NS + "France";
    private static final String GERMANY = PIZZA_NS + "Germany";
    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private StubReasonerService stubReasoner;
    private ClaimVerificationService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService().withRealOntology(WORKSPACE);
        service = new ClaimVerificationService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), WORKSPACE),
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
    // Stage 1: OUT_OF_SCOPE short-circuit
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage 1: OUT_OF_SCOPE short-circuit")
    class OutOfScopeShortCircuit {

        @Test
        @DisplayName("External subject IRI returns OUT_OF_SCOPE before any reasoner call")
        void outOfScopeShortCircuitsBeforeAnyReasonerCall() {
            Claim claim = subclassClaim("oos-1",
                "http://example.org/external#Foo", PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.OUT_OF_SCOPE, data.verdict());
            assertTrue(data.unknownReason().isPresent());
            assertEquals(UnknownReason.MISSING_ENTITY, data.unknownReason().get());
            // No reasoner calls should have been made
            assertTrue(stubReasoner.getCallLog().stream()
                .noneMatch(c -> c.startsWith("checkSourceOntologyConsistency:")
                    || c.startsWith("checkAxiomEntailment:")
                    || c.startsWith("checkConsistencyAfterAdding:")),
                "No reasoner calls should be made when pre-check fails");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Stage 2: SOURCE_ONTOLOGY_INCONSISTENT
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage 2: source ontology consistency")
    class SourceConsistency {

        @Test
        @DisplayName("Inconsistent source ontology yields ERRORED result with SOURCE_ONTOLOGY_INCONSISTENT")
        void sourceInconsistentYieldsErroredResult() {
            stubReasoner.withSourceConsistent(false);
            Claim claim = subclassClaim("src-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(ExecutionStatus.ERROR, data.executionStatus());
            assertTrue(data.errorCode().isPresent());
            assertEquals(ErrorCode.SOURCE_ONTOLOGY_INCONSISTENT, data.errorCode().get());
            assertNull(data.verdict(), "Errored result must have null semanticVerdict");
            // Should NOT have reached entailment or exact check
            assertTrue(stubReasoner.getCallLog().stream()
                .noneMatch(c -> c.startsWith("checkAxiomEntailment:")
                    || c.startsWith("checkConsistencyAfterAdding:")),
                "Should not reach entailment or exact check when source is inconsistent");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Stage 3: ENTAILED → SUPPORTED (short-circuit)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage 3: ENTAILED short-circuits to SUPPORTED")
    class EntailedShortCircuit {

        @Test
        @DisplayName("ENTAILED yields SUPPORTED without calling checkConsistencyAfterAdding")
        void entailedShortCircuitsToSupported() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
            Claim claim = subclassClaim("ent-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.SUPPORTED, data.verdict());
            assertEquals(ExecutionStatus.COMPLETED, data.executionStatus());
            assertFalse(data.evidence().isEmpty(),
                "SUPPORTED verdict must have non-empty evidence");
            // checkConsistencyAfterAdding must NOT be called (short-circuit)
            assertTrue(stubReasoner.getCallLog().stream()
                .noneMatch(c -> c.startsWith("checkConsistencyAfterAdding:")),
                "checkConsistencyAfterAdding must not be called when ENTAILED");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Stage 3: CLAIM_AXIOM_BUILD_FAILED
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage 3: axiom build failure")
    class AxiomBuildFailure {

        @Test
        @DisplayName("Invalid entity kind yields CLAIM_AXIOM_BUILD_FAILED")
        void axiomBuildFailureYieldsErroredResult() {
            // DATA_PROPERTY_DOMAIN expects a data_property subject; using a class
            Claim claim = new Claim("build-err", ClaimType.DATA_PROPERTY_DOMAIN, "pizza",
                new ClaimEntity("class", PIZZA), null,
                new ClaimEntity("datatype", "http://www.w3.org/2001/XMLSchema#string"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(ExecutionStatus.ERROR, data.executionStatus());
            assertTrue(data.errorCode().isPresent());
            assertEquals(ErrorCode.CLAIM_AXIOM_BUILD_FAILED, data.errorCode().get());
            assertNull(data.verdict());
            // Should NOT have reached entailment or exact check
            assertTrue(stubReasoner.getCallLog().stream()
                .noneMatch(c -> c.startsWith("checkAxiomEntailment:")
                    || c.startsWith("checkConsistencyAfterAdding:")),
                "Should not reach entailment when axiom build fails");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Stage 4: exact consistency check → UNKNOWN / CONTRADICTED
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage 4: exact consistency verdict mapping")
    class ExactConsistencyVerdict {

        @Test
        @DisplayName("NOT_ENTAILED + CONSISTENT → UNKNOWN with INSUFFICIENT_AXIOMS")
        void notEntailedAndConsistentYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.CONSISTENT);
            Claim claim = subclassClaim("unk-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertEquals(ExecutionStatus.COMPLETED, data.executionStatus());
            assertTrue(data.unknownReason().isPresent());
            assertEquals(UnknownReason.INSUFFICIENT_AXIOMS, data.unknownReason().get());
            // checkConsistencyAfterAdding MUST have been called
            assertTrue(stubReasoner.getCallLog().stream()
                .anyMatch(c -> c.startsWith("checkConsistencyAfterAdding:")),
                "checkConsistencyAfterAdding must be called for non-entailed claims");
        }

        @Test
        @DisplayName("NOT_ENTAILED + INCONSISTENT → CONTRADICTED with CONSISTENCY_REPORT evidence")
        void notEntailedAndInconsistentYieldsContradicted() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.INCONSISTENT);
            Claim claim = subclassClaim("con-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            assertEquals(ExecutionStatus.COMPLETED, data.executionStatus());
            // Evidence must include at least one CONSISTENCY_REPORT item
            assertTrue(data.evidence().stream()
                .anyMatch(e -> e.kind() == EvidenceKind.CONSISTENCY_REPORT),
                "CONTRADICTED evidence must include CONSISTENCY_REPORT");
            // All evidence should be counter-evidence
            assertTrue(data.evidence().stream()
                .allMatch(e -> EvidenceItem.ROLE_COUNTER.equals(e.role())),
                "CONTRADICTED evidence should all be counter-evidence");
        }

        @Test
        @DisplayName("INCONSISTENT with explanation axioms yields INCONSISTENCY_JUSTIFICATION evidence")
        void inconsistentWithExplanationYieldsJustification() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.INCONSISTENT);
            stubReasoner.withConsistencyExplanationAxioms(
                java.util.List.of("DisjointClasses(Pizza PizzaTopping)"));
            Claim claim = subclassClaim("con-2", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            assertTrue(data.evidence().stream()
                .anyMatch(e -> e.kind() == EvidenceKind.INCONSISTENCY_JUSTIFICATION),
                "CONTRADICTED with explanation must include INCONSISTENCY_JUSTIFICATION");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Stage 4: TIMEOUT and ERROR
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stage 4: timeout and error paths")
    class TimeoutAndError {

        @Test
        @DisplayName("TIMEOUT yields ERRORED result with REASONER_TIMEOUT")
        void timeoutYieldsErroredResult() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.TIMEOUT);
            Claim claim = subclassClaim("to-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(ExecutionStatus.TIMEOUT, data.executionStatus());
            assertTrue(data.errorCode().isPresent());
            assertEquals(ErrorCode.REASONER_TIMEOUT, data.errorCode().get());
            assertNull(data.verdict(), "TIMEOUT result must have null semanticVerdict");
        }

        @Test
        @DisplayName("ERROR yields ERRORED result with CLAIM_CONSISTENCY_CHECK_FAILED")
        void errorYieldsErroredResult() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.ERROR);
            Claim claim = subclassClaim("err-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(ExecutionStatus.ERROR, data.executionStatus());
            assertTrue(data.errorCode().isPresent());
            assertEquals(ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED, data.errorCode().get());
            assertNull(data.verdict());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Special claim type bypass (design D9)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Special claim types bypass 5-stage flow (D9)")
    class SpecialClaimTypeBypass {

        @Test
        @DisplayName("ONTOLOGY_CONSISTENCY bypasses 5-stage flow (no checkSourceOntologyConsistency)")
        void ontologyConsistencyBypasses5Stage() {
            stubReasoner.withConsistent(true);
            Claim claim = new Claim("sp-1", ClaimType.ONTOLOGY_CONSISTENCY, "pizza",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.SUPPORTED, data.verdict());
            // 5-stage flow methods must NOT be called
            assertTrue(stubReasoner.getCallLog().stream()
                .noneMatch(c -> c.startsWith("checkSourceOntologyConsistency:")
                    || c.startsWith("checkAxiomEntailment:")
                    || c.startsWith("checkConsistencyAfterAdding:")),
                "ONTOLOGY_CONSISTENCY must bypass 5-stage flow");
        }

        @Test
        @DisplayName("LITERAL_VALIDITY bypasses 5-stage flow")
        void literalValidityBypasses5Stage() {
            Claim claim = new Claim("sp-2", ClaimType.LITERAL_VALIDITY, "pizza",
                new ClaimEntity("datatype", "http://www.w3.org/2001/XMLSchema#string"),
                null,
                new ClaimEntity("literal", "42"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = extract(service.verify(claim));

            assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict());
            assertTrue(stubReasoner.getCallLog().stream()
                .noneMatch(c -> c.startsWith("checkSourceOntologyConsistency:")
                    || c.startsWith("checkAxiomEntailment:")),
                "LITERAL_VALIDITY must bypass 5-stage flow");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Same-individual self-contradiction
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Same-individual self-contradiction (DISJOINT_CLASSES)")
    class SameIndividualSelfContradiction {

        @Test
        @DisplayName("DISJOINT_CLASSES with identical individual IRIs yields CONTRADICTED")
        void sameIndividualYieldsContradicted() {
            // DISJOINT_CLASSES with individual subjects where subject == object
            Claim claim = new Claim("self-1", ClaimType.DISJOINT_CLASSES, "pizza",
                new ClaimEntity("individual", FRANCE),
                "http://www.w3.org/2002/07/owl#differentFrom",
                new ClaimEntity("individual", FRANCE),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.CONTRADICTED, data.verdict());
            assertFalse(data.evidence().isEmpty(),
                "Self-contradiction must produce evidence");
            // Evidence should mention "Same individual"
            assertTrue(data.evidence().stream()
                .anyMatch(e -> e.value() != null
                    && e.value().contains("Same individual")),
                "Evidence should mention 'Same individual'");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PerStageTiming verification
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PerStageTiming population")
    class TimingVerification {

        @Test
        @DisplayName("SUPPORTED path: sourceConsistencyMs and entailmentMs are populated")
        void supportedPathTiming() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
            Claim claim = subclassClaim("tm-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            PerStageTiming timing = data.perStageTiming();
            assertNotNull(timing, "COMPLETED result must have PerStageTiming");
            assertNotNull(timing.sourceConsistencyMs(),
                "Stage 2 (source consistency) timing must be populated");
            assertNotNull(timing.entailmentMs(),
                "Stage 3b (entailment) timing must be populated");
            assertNotNull(timing.totalMs(),
                "Total timing must be populated");
            // Stage 4 fields should be null (short-circuit at stage 3)
            // Note: the stub returns empty timing, so exact check fields are null
        }

        @Test
        @DisplayName("CONTRADICTED path: all stage timings up to stage 4 are populated")
        void contradictedPathTiming() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.INCONSISTENT);
            Claim claim = subclassClaim("tm-2", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            PerStageTiming timing = data.perStageTiming();
            assertNotNull(timing);
            assertNotNull(timing.sourceConsistencyMs(),
                "Stage 2 timing must be populated");
            assertNotNull(timing.axiomBuildMs(),
                "Stage 3a (axiom build) timing must be populated");
            assertNotNull(timing.entailmentMs(),
                "Stage 3b (entailment) timing must be populated");
            assertNotNull(timing.totalMs(),
                "Total timing must be populated");
        }

        @Test
        @DisplayName("SOURCE_ONTOLOGY_INCONSISTENT: only sourceConsistencyMs is populated")
        void sourceInconsistentTiming() {
            stubReasoner.withSourceConsistent(false);
            Claim claim = subclassClaim("tm-3", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            PerStageTiming timing = data.perStageTiming();
            assertNotNull(timing);
            assertNotNull(timing.sourceConsistencyMs(),
                "Stage 2 timing must be populated even on error");
            assertNull(timing.axiomBuildMs(),
                "Stage 3a should not run when source is inconsistent");
            assertNull(timing.entailmentMs(),
                "Stage 3b should not run when source is inconsistent");
            assertNotNull(timing.totalMs(),
                "Total timing must be populated");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ExecutionStatus and semanticVerdict invariants
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ExecutionStatus and semanticVerdict invariants")
    class StatusInvariants {

        @Test
        @DisplayName("COMPLETED results have non-null semanticVerdict")
        void completedResultHasNonNullVerdict() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
            Claim claim = subclassClaim("inv-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(ExecutionStatus.COMPLETED, data.executionStatus());
            assertTrue(data.semanticVerdict().isPresent(),
                "COMPLETED result must have non-null semanticVerdict");
            assertTrue(data.errorCode().isEmpty(),
                "COMPLETED result must not have an error code");
        }

        @Test
        @DisplayName("ERRORED results have empty semanticVerdict and non-empty errorCode")
        void erroredResultHasEmptyVerdictAndErrorCode() {
            stubReasoner.withSourceConsistent(false);
            Claim claim = subclassClaim("inv-2", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertNotEquals(ExecutionStatus.COMPLETED, data.executionStatus());
            assertTrue(data.semanticVerdict().isEmpty(),
                "ERRORED result must have empty semanticVerdict");
            assertTrue(data.errorCode().isPresent(),
                "ERRORED result must have an error code");
            assertNull(data.verdict(),
                "verdict() backward-compat accessor must return null for errored results");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // UNSUPPORTED_AXIOM_TYPE → UNKNOWN with UNSUPPORTED_CLAIM_TYPE
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UNSUPPORTED_AXIOM_TYPE → UNKNOWN with UNSUPPORTED_CLAIM_TYPE")
    class UnsupportedAxiomType {

        @Test
        @DisplayName("UNSUPPORTED_AXIOM_TYPE + CONSISTENT → UNKNOWN with UNSUPPORTED_CLAIM_TYPE")
        void unsupportedAxiomYieldsUnknownWithCorrectReason() {
            stubReasoner.withEntailmentResult(EntailmentResult.UNSUPPORTED_AXIOM_TYPE);
            stubReasoner.withConsistencyAfterAdditionStatus(
                ConsistencyAfterAdditionStatus.CONSISTENT);
            Claim claim = subclassClaim("unsup-1", CHEESEY_PIZZA, PIZZA);

            ClaimVerificationResult data = extract(service.verify(claim));

            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertTrue(data.unknownReason().isPresent());
            assertEquals(UnknownReason.UNSUPPORTED_CLAIM_TYPE, data.unknownReason().get());
        }
    }
}
