package org.owl4agents.validation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.core.model.EvidenceItem;
import org.owl4agents.core.model.EvidenceKind;
import org.owl4agents.core.model.EvidencePath;
import org.owl4agents.core.model.InferredFact;
import org.owl4agents.core.model.InferredFactsResult;
import org.owl4agents.core.model.MissingEntityResult;
import org.owl4agents.core.model.UnknownExplanation;
import org.owl4agents.core.model.UnknownReason;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * Task 4.7 tests: evidence grounding service.
 * Proves evidence payloads are non-empty where required, truncation works,
 * counterexamples are assembled, unknown explanations have reason categories,
 * and missing entity detection classifies entities correctly.
 */
class EvidenceGroundingServiceTest {

    private StubReasonerService stubReasoner;
    private EvidenceGroundingService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        service = new EvidenceGroundingService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path")
        );
    }

    private OntologyId ontId() {
        return new OntologyId("test-ontology");
    }

    private Claim subclassClaim(String claimId) {
        return new Claim(claimId, ClaimType.SUBCLASS, "test-ontology",
            new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
            new ClaimEntity("class", "http://ex.org/B"),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Claim subclassClaimWithOptions(String claimId, Map<String, Object> options) {
        return new Claim(claimId, ClaimType.SUBCLASS, "test-ontology",
            new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
            new ClaimEntity("class", "http://ex.org/B"),
            Optional.empty(), Optional.empty(), Optional.of(options));
    }

    private ClaimVerificationResult supportedResult(Claim claim) {
        return new ClaimVerificationResult(
            claim.claimId(), claim.ontologyId(), claim.type(),
            Verdict.SUPPORTED,
            List.of(new EvidenceItem(
                "entailment-" + claim.claimId(),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.INFERRED_AXIOM,
                "SubClassOf: http://ex.org/A → http://ex.org/B",
                "reasoner", "HermiT", "UNION",
                List.of("http://ex.org/A", "http://ex.org/B"),
                EvidenceItem.CONFIDENCE_ENTAILED
            )),
            Optional.empty(), Optional.empty(),
            Optional.of("HermiT"), Optional.empty(),
            false, 1
        );
    }

    private ClaimVerificationResult unknownResult(Claim claim, UnknownReason reason) {
        return new ClaimVerificationResult(
            claim.claimId(), claim.ontologyId(), claim.type(),
            Verdict.UNKNOWN,
            List.of(new EvidenceItem(
                "no-entailment-" + claim.claimId(),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                "Axiom not entailed: SubClassOf",
                "reasoner", "HermiT", "UNION",
                List.of("http://ex.org/A", "http://ex.org/B"),
                EvidenceItem.CONFIDENCE_INFERRED
            )),
            Optional.of(reason), Optional.empty(),
            Optional.of("HermiT"), Optional.empty(),
            false, 1
        );
    }

    private ClaimVerificationResult contradictedResult(Claim claim) {
        return new ClaimVerificationResult(
            claim.claimId(), claim.ontologyId(), claim.type(),
            Verdict.CONTRADICTED,
            List.of(new EvidenceItem(
                "compatibility-" + claim.claimId(),
                EvidenceItem.ROLE_COUNTER,
                EvidenceKind.INFERRED_AXIOM,
                "http://ex.org/A and http://ex.org/B → compatible",
                "class-compatibility-check", "HermiT", "UNION",
                List.of("http://ex.org/A", "http://ex.org/B"),
                EvidenceItem.CONFIDENCE_INFERRED
            )),
            Optional.empty(), Optional.empty(),
            Optional.of("HermiT"), Optional.empty(),
            false, 1
        );
    }

    // ── Task 4.2: Evidence path assembly ──

    @Nested
    @DisplayName("4.2 Evidence path assembly for supported claims")
    class EvidencePathAssemblyTests {

        @Test
        @DisplayName("Supported claim returns non-empty evidence path")
        void supportedClaimReturnsNonEmptyPath() {
            Claim claim = subclassClaim("c1");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertFalse(path.items().isEmpty(), "Evidence path must not be empty for supported claims");
        }

        @Test
        @DisplayName("Evidence path includes reasoning report when available")
        void evidencePathIncludesReasoningReport() {
            stubReasoner.withReasoningReport(true);
            Claim claim = subclassClaim("c2");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertTrue(path.items().stream()
                .anyMatch(i -> i.kind() == EvidenceKind.REASONING_REPORT),
                "Evidence path must include reasoning report when available");
        }

        @Test
        @DisplayName("Evidence path enriched with inferred facts")
        void evidencePathEnrichedWithInferredFacts() {
            stubReasoner.withInferredFacts(List.of(
                new InferredFact("test-ontology", "http://ex.org/A",
                    "http://ex.org/subClassOf", "http://ex.org/B",
                    null, "SubClassOf", "inferred", "HermiT")
            ));
            stubReasoner.withReasoningReport(true);
            Claim claim = subclassClaim("c3");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertTrue(path.items().stream()
                .anyMatch(i -> i.kind() == EvidenceKind.INFERRED_TRIPLE),
                "Evidence path must include inferred triples when available");
        }

        @Test
        @DisplayName("Null claim returns error")
        void nullClaimReturnsError() {
            ClaimVerificationResult verification = supportedResult(subclassClaim("c4"));
            ServiceResult<EvidencePath> result = service.getEvidencePath(null, verification);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Null verification result returns error")
        void nullVerificationReturnsError() {
            Claim claim = subclassClaim("c5");
            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, null);
            assertFalse(result.isSuccess());
        }
    }

    // ── Task 4.3: Counterexample search ──

    @Nested
    @DisplayName("4.3 Counterexample search for contradicted claims")
    class CounterexampleSearchTests {

        @Test
        @DisplayName("Contradicted claim returns counterexamples")
        void contradictedClaimReturnsCounterexamples() {
            Claim claim = new Claim("c6", ClaimType.DISJOINT_CLASSES, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), null,
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult verification = contradictedResult(claim);

            ServiceResult<List<EvidenceItem>> result = service.findCounterexamples(claim, verification);
            assertTrue(result.isSuccess());
            List<EvidenceItem> counterexamples = ((ServiceResult.Success<List<EvidenceItem>>) result).data();
            assertFalse(counterexamples.isEmpty(), "Counterexamples must not be empty for contradicted claims");
            assertTrue(counterexamples.stream()
                .allMatch(i -> EvidenceItem.ROLE_COUNTER.equals(i.role())),
                "All counterexamples must have counter role");
        }

        @Test
        @DisplayName("Supported claim cannot have counterexamples")
        void supportedClaimCannotHaveCounterexamples() {
            Claim claim = subclassClaim("c7");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<List<EvidenceItem>> result = service.findCounterexamples(claim, verification);
            assertFalse(result.isSuccess(), "Counterexamples should not be available for supported claims");
        }

        @Test
        @DisplayName("Unknown claim cannot have counterexamples")
        void unknownClaimCannotHaveCounterexamples() {
            Claim claim = subclassClaim("c8");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.INSUFFICIENT_AXIOMS);

            ServiceResult<List<EvidenceItem>> result = service.findCounterexamples(claim, verification);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Contradicted ontology consistency includes unsatisfiable classes")
        void contradictedConsistencyIncludesUnsatClasses() {
            stubReasoner.withConsistent(false);
            stubReasoner.withUnsatClasses(List.of("http://ex.org/C1", "http://ex.org/C2"));
            Claim claim = new Claim("c9", ClaimType.ONTOLOGY_CONSISTENCY, "test-ontology",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult verification = new ClaimVerificationResult(
                "c9", "test-ontology", ClaimType.ONTOLOGY_CONSISTENCY,
                Verdict.CONTRADICTED,
                List.of(new EvidenceItem(
                    "consistency-c9", EvidenceItem.ROLE_COUNTER,
                    EvidenceKind.REASONING_REPORT,
                    "Ontology consistent=false",
                    "consistency-check", "HermiT", "INFERRED",
                    List.of(), EvidenceItem.CONFIDENCE_INFERRED
                )),
                Optional.empty(), Optional.empty(),
                Optional.of("HermiT"), Optional.empty(),
                false, 1
            );

            ServiceResult<List<EvidenceItem>> result = service.findCounterexamples(claim, verification);
            assertTrue(result.isSuccess());
            List<EvidenceItem> counterexamples = ((ServiceResult.Success<List<EvidenceItem>>) result).data();
            assertTrue(counterexamples.stream()
                .anyMatch(i -> i.kind() == EvidenceKind.COUNTEREXAMPLE),
                "Inconsistent ontology counterexamples must include COUNTEREXAMPLE kind");
        }
    }

    // ── Task 4.4: Unknown explanation ──

    @Nested
    @DisplayName("4.4 Unknown explanation with reason categories")
    class UnknownExplanationTests {

        @Test
        @DisplayName("INSUFFICIENT_AXIOMS unknown returns explanation with reason")
        void insufficientAxiomsReturnsExplanation() {
            Claim claim = subclassClaim("c10");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.INSUFFICIENT_AXIOMS);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertTrue(result.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertEquals(UnknownReason.INSUFFICIENT_AXIOMS, explanation.reason());
            assertTrue(explanation.explanation().isPresent());
            assertFalse(explanation.explanation().get().isEmpty());
        }

        @Test
        @DisplayName("UNSUPPORTED_CLAIM_TYPE unknown returns explanation")
        void unsupportedClaimTypeReturnsExplanation() {
            Claim claim = subclassClaim("c11");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.UNSUPPORTED_CLAIM_TYPE);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertTrue(result.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertEquals(UnknownReason.UNSUPPORTED_CLAIM_TYPE, explanation.reason());
            assertTrue(explanation.explanation().isPresent());
            assertTrue(explanation.suggestedAction().isPresent());
        }

        @Test
        @DisplayName("Explanation includes relevant entities")
        void explanationIncludesRelevantEntities() {
            Claim claim = subclassClaim("c12");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.INSUFFICIENT_AXIOMS);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertTrue(result.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertFalse(explanation.relevantEntities().isEmpty());
            assertTrue(explanation.relevantEntities().contains("http://ex.org/A"));
            assertTrue(explanation.relevantEntities().contains("http://ex.org/B"));
        }

        @Test
        @DisplayName("Explanation includes suggested action")
        void explanationIncludesSuggestedAction() {
            Claim claim = subclassClaim("c13");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.INSUFFICIENT_AXIOMS);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertTrue(result.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertTrue(explanation.suggestedAction().isPresent());
            assertFalse(explanation.suggestedAction().get().isEmpty());
        }

        @Test
        @DisplayName("Supported claim cannot have unknown explanation")
        void supportedClaimCannotHaveExplanation() {
            Claim claim = subclassClaim("c14");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("MISSING_REASONING reason category returns correct explanation")
        void missingReasoningReturnsExplanation() {
            Claim claim = subclassClaim("c15");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.MISSING_REASONING);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertTrue(result.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertEquals(UnknownReason.MISSING_REASONING, explanation.reason());
            assertTrue(explanation.explanation().get().contains("Reasoning has not been run"));
        }
    }

    // ── Task 4.5: Missing entity detection ──

    @Nested
    @DisplayName("4.5 Missing entity detection")
    class MissingEntityDetectionTests {

        @Test
        @DisplayName("Entity with inferred facts is classified as matched")
        void entityWithInferredFactsIsMatched() {
            stubReasoner.withInferredFacts(List.of(
                new InferredFact("test-ontology", "http://ex.org/A",
                    "http://ex.org/subClassOf", "http://ex.org/B",
                    null, "SubClassOf", "inferred", "HermiT")
            ));
            Claim claim = subclassClaim("c16");

            ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
            assertTrue(result.isSuccess());
            MissingEntityResult missingResult = ((ServiceResult.Success<MissingEntityResult>) result).data();
            assertFalse(missingResult.matched().isEmpty(), "Entity with inferred facts must be matched");
        }

        @Test
        @DisplayName("Entity without inferred facts is classified as missing")
        void entityWithoutInferredFactsIsMissing() {
            stubReasoner.withInferredFacts(List.of()); // empty facts
            Claim claim = subclassClaim("c17");

            ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
            assertTrue(result.isSuccess());
            MissingEntityResult missingResult = ((ServiceResult.Success<MissingEntityResult>) result).data();
            assertFalse(missingResult.missing().isEmpty(), "Entity without inferred facts must be missing");
        }

        @Test
        @DisplayName("Literal entity is always classified as matched")
        void literalEntityAlwaysMatched() {
            Claim claim = new Claim("c18", ClaimType.DATA_PROPERTY_ASSERTION, "test-ontology",
                new ClaimEntity("individual", "http://ex.org/i"), "http://ex.org/age",
                new ClaimEntity("literal", "42"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
            assertTrue(result.isSuccess());
            MissingEntityResult missingResult = ((ServiceResult.Success<MissingEntityResult>) result).data();
            // Literal should be in matched
            assertTrue(missingResult.matched().stream()
                .anyMatch(m -> m.matchedIRI().isPresent() && "literal".equals(m.kind().orElse(null))),
                "Literal entity must be in matched list");
        }

        @Test
        @DisplayName("Null claim returns error")
        void nullClaimReturnsError() {
            ServiceResult<MissingEntityResult> result = service.detectMissingEntities(null);
            assertFalse(result.isSuccess());
        }
    }

    // ── Task 4.6: Evidence truncation ──

    @Nested
    @DisplayName("4.6 Evidence truncation using maxEvidence")
    class EvidenceTruncationTests {

        @Test
        @DisplayName("maxEvidence truncates items and sets truncated=true")
        void maxEvidenceTruncatesItems() {
            stubReasoner.withInferredFacts(List.of(
                new InferredFact("test-ontology", "http://ex.org/A",
                    "http://ex.org/subClassOf", "http://ex.org/B",
                    null, "SubClassOf", "inferred", "HermiT"),
                new InferredFact("test-ontology", "http://ex.org/B",
                    "http://ex.org/subClassOf", "http://ex.org/C",
                    null, "SubClassOf", "inferred", "HermiT"),
                new InferredFact("test-ontology", "http://ex.org/C",
                    "http://ex.org/subClassOf", "http://ex.org/D",
                    null, "SubClassOf", "inferred", "HermiT")
            ));
            stubReasoner.withReasoningReport(true);
            Claim claim = subclassClaimWithOptions("c19", Map.of(Claim.MAX_EVIDENCE, 2));
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertEquals(2, path.items().size(), "Items must be truncated to maxEvidence limit");
            assertTrue(path.truncated(), "Truncated flag must be true when items are truncated");
            assertTrue(path.totalAvailable() > 2, "totalAvailable must reflect original count");
        }

        @Test
        @DisplayName("maxEvidence=0 means no truncation")
        void maxEvidenceZeroMeansNoTruncation() {
            stubReasoner.withReasoningReport(true);
            Claim claim = subclassClaimWithOptions("c20", Map.of(Claim.MAX_EVIDENCE, 0));
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertFalse(path.truncated(), "Truncated must be false when maxEvidence=0 (no limit)");
        }

        @Test
        @DisplayName("No options means no truncation")
        void noOptionsMeansNoTruncation() {
            Claim claim = subclassClaim("c21");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertFalse(path.truncated(), "Truncated must be false when no options set");
        }

        @Test
        @DisplayName("totalAvailable reflects total before truncation")
        void totalAvailableReflectsOriginalCount() {
            stubReasoner.withInferredFacts(List.of(
                new InferredFact("test-ontology", "http://ex.org/A",
                    "http://ex.org/subClassOf", "http://ex.org/B",
                    null, "SubClassOf", "inferred", "HermiT")
            ));
            stubReasoner.withReasoningReport(true);
            Claim claim = subclassClaimWithOptions("c22", Map.of(Claim.MAX_EVIDENCE, 1));
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            // original count: 1 (verification) + inferred facts per entity + reasoning report
            assertTrue(path.totalAvailable() > path.items().size(),
                "totalAvailable must be greater than items.size() when truncated");
        }
    }

    // ── Task 4.7: Non-empty evidence payloads ──

    @Nested
    @DisplayName("4.7 Evidence payloads must be non-empty where required")
    class NonEmptyPayloadTests {

        @Test
        @DisplayName("Supported claim evidence path has non-empty items")
        void supportedPathHasNonEmptyItems() {
            Claim claim = subclassClaim("c23");
            ClaimVerificationResult verification = supportedResult(claim);

            ServiceResult<EvidencePath> result = service.getEvidencePath(claim, verification);
            assertTrue(result.isSuccess());
            EvidencePath path = ((ServiceResult.Success<EvidencePath>) result).data();
            assertFalse(path.items().isEmpty(), "Supported claim must have non-empty evidence");
            // Verify each item has non-placeholder content
            for (EvidenceItem item : path.items()) {
                assertFalse(item.evidenceId().isBlank(), "evidenceId must not be blank");
                assertFalse(item.value().isBlank(), "value must not be blank/placeholder");
                assertNotNull(item.kind(), "kind must not be null");
            }
        }

        @Test
        @DisplayName("Contradicted claim counterexamples have non-empty content")
        void contradictedCounterexamplesHaveContent() {
            Claim claim = new Claim("c24", ClaimType.DISJOINT_CLASSES, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), null,
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ClaimVerificationResult verification = contradictedResult(claim);

            ServiceResult<List<EvidenceItem>> result = service.findCounterexamples(claim, verification);
            assertTrue(result.isSuccess());
            List<EvidenceItem> counterexamples = ((ServiceResult.Success<List<EvidenceItem>>) result).data();
            assertFalse(counterexamples.isEmpty());
            for (EvidenceItem item : counterexamples) {
                assertFalse(item.value().isBlank(), "counterexample value must not be blank");
                assertNotNull(item.kind(), "counterexample kind must not be null");
            }
        }

        @Test
        @DisplayName("Unknown explanation has non-empty explanation text")
        void unknownExplanationHasContent() {
            Claim claim = subclassClaim("c25");
            ClaimVerificationResult verification = unknownResult(claim, UnknownReason.INSUFFICIENT_AXIOMS);

            ServiceResult<UnknownExplanation> result = service.explainUnknown(claim, verification);
            assertTrue(result.isSuccess());
            UnknownExplanation explanation = ((ServiceResult.Success<UnknownExplanation>) result).data();
            assertTrue(explanation.explanation().isPresent());
            assertFalse(explanation.explanation().get().isBlank(),
                "Explanation text must not be blank or placeholder");
        }
    }
}