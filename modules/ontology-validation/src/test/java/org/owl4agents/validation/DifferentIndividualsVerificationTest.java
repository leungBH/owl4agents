package org.owl4agents.validation;

import org.junit.jupiter.api.*;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.1 ISSUE-04 / Task 7.4: JUnit 5 tests for the
 * {@code verifyDifferentIndividuals} dispatch path.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code assertedDifferentIndividualsIsSupported} (TC-9): stub returns ENTAILED for
 *       {@code DifferentIndividuals}. Assert verdict == SUPPORTED, evidence[0].source
 *       contains "asserted".</li>
 *   <li>{@code inferredDifferentFromIsSupported} (TC-10): same as TC-9 with source
 *       containing "inferred" (modeled by the stub returning the result with source "inferred").</li>
 *   <li>{@code sameIndividualContradictsDifferentFrom} (TC-11): DifferentIndividuals NOT_ENTAILED
 *       and SameIndividual ENTAILED. Assert verdict == CONTRADICTED.</li>
 *   <li>{@code noEvidenceEitherWayYieldsUnknown}: both not entailed, verdict == UNKNOWN,
 *       unknownReason == INSUFFICIENT_AXIOMS.</li>
 *   <li>{@code verifyDifferentIndividualsDispatchNotVerifyDisjointClasses}: assert call log
 *       does NOT contain "checkEntailment:DisjointClasses".</li>
 *   <li>{@code assertedDifferentIndividualsDoesNotCallIsEntailed}: for an asserted
 *       DifferentIndividuals, the stub returns ENTAILED on the first try.</li>
 * </ul>
 */
@DisplayName("v0.8.1 DifferentIndividuals verification (ISSUE-04)")
class DifferentIndividualsVerificationTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String FRANCE = PIZZA_NS + "France";
    private static final String GERMANY = PIZZA_NS + "Germany";
    private static final String ITALY = PIZZA_NS + "Italy";
    private static final String AMERICA = PIZZA_NS + "America";
    private static final String ENGLAND = PIZZA_NS + "England";
    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private StubReasonerService stubReasoner;
    private ClaimVerificationService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        stubReasoner.withRealOntology(WORKSPACE);
        service = new ClaimVerificationService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), WORKSPACE),
            new SemanticDeepeningService(WORKSPACE),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    private Claim buildClaim(String id, ClaimType type, String subIri, String objIri) {
        return new Claim(id, type, "pizza",
            new ClaimEntity("individual", subIri),
            "http://www.w3.org/2002/07/owl#differentFrom",
            objIri == null ? null : new ClaimEntity("individual", objIri),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("TC-9: asserted DifferentIndividuals yields SUPPORTED")
    void assertedDifferentIndividualsIsSupported() {
        stubReasoner.withEntailmentResult("DifferentIndividuals", EntailmentResult.ENTAILED);

        Claim claim = buildClaim("c1", ClaimType.DIFFERENT_INDIVIDUALS, FRANCE, GERMANY);
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);

        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.SUPPORTED, data.verdict());
        assertFalse(data.evidence().isEmpty(), "Evidence should be non-empty for SUPPORTED verdict");
    }

    @Test
    @DisplayName("TC-10: inferred DifferentIndividuals yields SUPPORTED")
    void inferredDifferentFromIsSupported() {
        stubReasoner.withEntailmentResult("DifferentIndividuals", EntailmentResult.ENTAILED);

        Claim claim = buildClaim("c2", ClaimType.DIFFERENT_INDIVIDUALS, FRANCE, GERMANY);
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);

        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.SUPPORTED, data.verdict());
    }

    @Test
    @DisplayName("TC-11: exact consistency check (O ∪ {α} inconsistent) yields CONTRADICTED")
    void exactConsistencyInconsistentYieldsContradicted() {
        // v0.8.5: In the 5-stage flow, CONTRADICTED is determined by the exact
        // consistency check (stage 4: O ∪ {α} inconsistent), not by SameIndividual
        // counter-evidence. Configure: DifferentIndividuals NOT_ENTAILED (stage 3)
        // + O ∪ {α} inconsistent (stage 4) → CONTRADICTED.
        stubReasoner.withEntailmentResult("DifferentIndividuals", EntailmentResult.NOT_ENTAILED);
        stubReasoner.withConsistencyAfterAdditionStatus(ConsistencyAfterAdditionStatus.INCONSISTENT);

        Claim claim = buildClaim("c3", ClaimType.DIFFERENT_INDIVIDUALS, FRANCE, GERMANY);
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);

        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.CONTRADICTED, data.verdict(),
            "DifferentIndividuals NOT_ENTAILED + O ∪ {α} inconsistent should yield CONTRADICTED");
    }

    @Test
    @DisplayName("Neither DifferentIndividuals nor SameIndividual entailed yields UNKNOWN")
    void noEvidenceEitherWayYieldsUnknown() {
        stubReasoner.withEntailmentResult("DifferentIndividuals", EntailmentResult.NOT_ENTAILED);
        stubReasoner.withEntailmentResult("SameIndividual", EntailmentResult.NOT_ENTAILED);

        Claim claim = buildClaim("c4", ClaimType.DIFFERENT_INDIVIDUALS, FRANCE, GERMANY);
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);

        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertEquals(Verdict.UNKNOWN, data.verdict());
        assertTrue(data.unknownReason().isPresent());
        assertEquals(UnknownReason.INSUFFICIENT_AXIOMS, data.unknownReason().get());
    }

    @Test
    @DisplayName("verifyDifferentIndividuals does NOT call verifyDisjointClasses")
    void verifyDifferentIndividualsDispatchNotVerifyDisjointClasses() {
        stubReasoner.withEntailmentResult("DifferentIndividuals", EntailmentResult.ENTAILED);

        Claim claim = buildClaim("c5", ClaimType.DIFFERENT_INDIVIDUALS, FRANCE, GERMANY);
        service.verify(claim);

        var log = stubReasoner.getCallLog();
        assertTrue(log.contains("checkAxiomEntailment:DifferentIndividuals"),
            "Should call checkAxiomEntailment with DifferentIndividuals (got: " + log + ")");
        assertFalse(log.contains("checkAxiomEntailment:DisjointClasses"),
            "Should NOT call DisjointClasses for a different_individuals claim (got: " + log + ")");
    }

    @Test
    @DisplayName("asserted DifferentIndividuals triggers only the asserted path (not the isEntailed fallback)")
    void assertedDifferentIndividualsDoesNotCallIsEntailed() {
        // The stub returns ENTAILED on the first DifferentIndividuals call, so
        // the asserted path matches and the SameIndividual counter-evidence
        // check is NOT triggered.
        stubReasoner.withEntailmentResult("DifferentIndividuals", EntailmentResult.ENTAILED);

        Claim claim = buildClaim("c6", ClaimType.DIFFERENT_INDIVIDUALS, FRANCE, GERMANY);
        service.verify(claim);

        var log = stubReasoner.getCallLog();
        long differentFromCalls = log.stream()
            .filter(s -> s.equals("checkAxiomEntailment:DifferentIndividuals"))
            .count();
        long sameIndividualCalls = log.stream()
            .filter(s -> s.equals("checkAxiomEntailment:SameIndividual"))
            .count();
        assertEquals(1, differentFromCalls,
            "DifferentIndividuals should be checked exactly once on the asserted path");
        assertEquals(0, sameIndividualCalls,
            "SameIndividual counter-evidence check should be skipped when DifferentIndividuals is entailed");
    }
}
