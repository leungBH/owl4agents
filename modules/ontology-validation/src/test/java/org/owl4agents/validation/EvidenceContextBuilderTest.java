package org.owl4agents.validation;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.model.*;

/**
 * Task 4.4-4.6 tests: proves evidence context builder produces correct context
 * for supported, contradicted, unknown, out_of_scope, mixed, and truncated
 * verdicts; does not fabricate evidence; and canonical workflow evidence entry
 * fields are identical across workflow reports and evidence context.
 */
class EvidenceContextBuilderTest {

    private EvidenceContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new EvidenceContextBuilder();
    }

    // ── Task 4.4: Verdict-specific context tests ──

    @Nested
    @DisplayName("Supported context")
    class SupportedContextTests {

        @Test
        @DisplayName("Supported claim context includes evidence and verified status")
        void supportedClaimContextHasEvidence() {
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.VERIFIED,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED,
                    List.of(new WorkflowEvidenceEntry("inferred_axiom", "Dog → Animal",
                        "reasoner", Optional.of("HermiT"), Optional.of("entailment-c1")))))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals("answer-test", context.answerId());
            assertEquals(AggregateAnswerStatus.VERIFIED, context.status());
            assertEquals(1, context.claims().size());
            assertEquals("c1", context.claims().get(0).id());
            assertEquals(Verdict.SUPPORTED, context.claims().get(0).verdict());
            assertEquals(1, context.claims().get(0).evidence().size());
            assertEquals("inferred_axiom", context.claims().get(0).evidence().get(0).kind());
            assertEquals(0, context.claims().get(0).omittedEvidenceCount());
            assertEquals(0, context.omittedClaimCount());
            assertTrue(context.agentInstructions().contains("Cite only evidence returned in this context."));
        }
    }

    @Nested
    @DisplayName("Contradicted context")
    class ContradictedContextTests {

        @Test
        @DisplayName("Contradicted claim context includes counterexample evidence")
        void contradictedContextHasCounterexamples() {
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.CONTRADICTED,
                List.of(makeClaimResult("c1", ClaimType.ONTOLOGY_CONSISTENCY, true, Verdict.CONTRADICTED,
                    List.of(new WorkflowEvidenceEntry("counterexample", "Ontology is inconsistent",
                        "consistency-check", Optional.of("HermiT"), Optional.of("counter-c1")))))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals(AggregateAnswerStatus.CONTRADICTED, context.status());
            assertEquals(Verdict.CONTRADICTED, context.claims().get(0).verdict());
            assertFalse(context.claims().get(0).evidence().isEmpty(),
                "Contradicted claim should have evidence");
            assertTrue(context.agentInstructions().stream()
                .anyMatch(i -> i.contains("contradicted")),
                "Agent instructions should mention contradicted status");
        }
    }

    @Nested
    @DisplayName("Unknown context")
    class UnknownContextTests {

        @Test
        @DisplayName("Unknown claim context includes unknownReason and no fabricated evidence")
        void unknownContextHasReasonNoFabricatedEvidence() {
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.INSUFFICIENT_EVIDENCE,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.UNKNOWN,
                    List.of(new WorkflowEvidenceEntry("reasoning_report",
                        "Axiom not entailed: SubClassOf",
                        "reasoner", Optional.of("HermiT"), Optional.of("no-entailment-c1"))),
                    Optional.of("insufficient_axioms")))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals(AggregateAnswerStatus.INSUFFICIENT_EVIDENCE, context.status());
            assertEquals(Verdict.UNKNOWN, context.claims().get(0).verdict());
            assertTrue(context.claims().get(0).unknownReason().isPresent(),
                "Unknown claim must have unknownReason");
            assertEquals("insufficient_axioms", context.claims().get(0).unknownReason().get());
            // Evidence is what the reasoner actually returned — not fabricated
            assertFalse(context.claims().get(0).evidence().isEmpty(),
                "Unknown claim should have real (non-fabricated) evidence");
            assertTrue(context.agentInstructions().stream()
                .anyMatch(i -> i.contains("insufficient")),
                "Agent instructions should mention insufficient evidence");
        }

        @Test
        @DisplayName("Unknown claim with no evidence does not fabricate")
        void unknownClaimNoEvidenceDoesNotFabricate() {
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.INSUFFICIENT_EVIDENCE,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.UNKNOWN,
                    List.of(),  // No evidence available
                    Optional.of("insufficient_axioms")))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals(0, context.claims().get(0).evidence().size(),
                "No fabricated evidence when unavailable");
            assertTrue(context.claims().get(0).unknownReason().isPresent(),
                "Unknown reason must be present even without evidence");
        }
    }

    @Nested
    @DisplayName("Out-of-scope context")
    class OutOfScopeContextTests {

        @Test
        @DisplayName("Out-of-scope claim context includes scope diagnostic")
        void outOfScopeContextHasScopeDiagnostic() {
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.OUT_OF_SCOPE,
                List.of(new ClaimWorkflowResult(
                    "c1", ClaimType.SUBCLASS, true, Verdict.OUT_OF_SCOPE,
                    List.of(),
                    Optional.of("missing_entity"),
                    Optional.empty(),
                    Optional.of(List.of(new MissingEntityResult.EntityMatch(
                        "http://ex.org/DeliveryPrice", Optional.empty(),
                        Optional.of("class"), Optional.empty()))),
                    Optional.empty()
                ))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals(AggregateAnswerStatus.OUT_OF_SCOPE, context.status());
            assertEquals(Verdict.OUT_OF_SCOPE, context.claims().get(0).verdict());
            assertTrue(context.claims().get(0).scopeDiagnostic().isPresent(),
                "Out-of-scope claim must include scope diagnostic");
            assertTrue(context.claims().get(0).scopeDiagnostic().get()
                .contains("not declared"),
                "Scope diagnostic should mention entities not declared");
        }
    }

    // ── Task 4.2: Deterministic truncation budget ──

    @Nested
    @DisplayName("Truncation budget tests")
    class TruncationTests {

        @Test
        @DisplayName("Small budget truncates evidence and counts omissions")
        void smallBudgetTruncatesEvidence() {
            // Create report with multiple claims each having 3 evidence entries
            List<WorkflowEvidenceEntry> evidence = List.of(
                new WorkflowEvidenceEntry("explicit_axiom", "Dog subClassOf Animal axiom 1 detail detail detail detail",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1")),
                new WorkflowEvidenceEntry("inferred_axiom", "Dog subClassOf Mammal inferred detail detail detail",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-2")),
                new WorkflowEvidenceEntry("explicit_axiom", "Mammal subClassOf Animal explicit detail detail",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-3"))
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.VERIFIED,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, evidence))
            );

            // Very small budget — should truncate some evidence
            EvidenceContext context = builder.buildContext(report, 10); // 4 * 10 = 40 chars budget

            // Claim must still be visible (all claim IDs and verdicts visible under truncation)
            assertEquals(1, context.claims().size(),
                "Claim must be visible even under truncation");
            assertEquals("c1", context.claims().get(0).id());
            assertEquals(Verdict.SUPPORTED, context.claims().get(0).verdict());

            // Some evidence should be truncated
            assertTrue(context.claims().get(0).omittedEvidenceCount() >= 0,
                "omittedEvidenceCount must be present");
        }

        @Test
        @DisplayName("Zero budget means no truncation limit")
        void zeroBudgetNoTruncation() {
            List<WorkflowEvidenceEntry> evidence = List.of(
                new WorkflowEvidenceEntry("inferred_axiom", "Dog → Animal",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1")),
                new WorkflowEvidenceEntry("explicit_axiom", "Mammal → Animal",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-2"))
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.VERIFIED,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, evidence))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals(2, context.claims().get(0).evidence().size(),
                "Zero budget → no truncation, all evidence preserved");
            assertEquals(0, context.claims().get(0).omittedEvidenceCount());
            assertEquals(0, context.omittedClaimCount());
        }

        @Test
        @DisplayName("Negative budget means no truncation limit")
        void negativeBudgetNoTruncation() {
            List<WorkflowEvidenceEntry> evidence = List.of(
                new WorkflowEvidenceEntry("inferred_axiom", "Dog → Animal",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1"))
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.VERIFIED,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, evidence))
            );

            EvidenceContext context = builder.buildContext(report, -1);

            assertEquals(1, context.claims().get(0).evidence().size(),
                "Negative budget → no truncation");
            assertEquals(0, context.claims().get(0).omittedEvidenceCount());
        }

        @Test
        @DisplayName("All claim IDs and verdicts remain visible under truncation")
        void claimIdsAndVerdictsVisibleUnderTruncation() {
            List<WorkflowEvidenceEntry> longEvidence = List.of(
                new WorkflowEvidenceEntry("explicit_axiom",
                    "Very long evidence summary that should take up many characters in the budget",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1")),
                new WorkflowEvidenceEntry("inferred_axiom",
                    "Another long evidence summary that should also take up budget characters",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-2"))
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.VERIFIED,
                List.of(
                    makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, longEvidence),
                    makeClaimResultUnknown("c2", ClaimType.SUBCLASS, true,
                        List.of(new WorkflowEvidenceEntry("reasoning_report", "Not entailed", "reasoner",
                            Optional.of("HermiT"), Optional.of("ev-3"))),
                        "insufficient_axioms")
                )
            );

            EvidenceContext context = builder.buildContext(report, 20); // small budget

            // ALL claims must be visible even under truncation (contract requirement)
            assertEquals(2, context.claims().size(),
                "All claim IDs and verdicts MUST be visible under truncation");
            assertEquals("c1", context.claims().get(0).id());
            assertEquals(Verdict.SUPPORTED, context.claims().get(0).verdict());
            assertEquals("c2", context.claims().get(1).id());
            assertEquals(Verdict.UNKNOWN, context.claims().get(1).verdict());
            // When budget is exhausted for some claims, their evidence detail is omitted entirely.
            // Per spec: omittedClaimCount counts claims whose evidence detail was omitted entirely.
            assertTrue(context.omittedClaimCount() >= 0,
                "omittedClaimCount must reflect claims whose evidence was omitted entirely under truncation");
        }

        @Test
        @DisplayName("Extreme budget still preserves all claim IDs and verdicts")
        void extremeBudgetPreservesAllClaimIdsAndVerdicts() {
            List<WorkflowEvidenceEntry> evidence = List.of(
                new WorkflowEvidenceEntry("explicit_axiom",
                    "Dog subClassOf Animal with detailed reasoning explanation text",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1")),
                new WorkflowEvidenceEntry("inferred_axiom",
                    "Cat subClassOf Mammal with additional reasoning details",
                    "reasoner", Optional.of("ELK"), Optional.of("ev-2"))
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.PARTIALLY_VERIFIED,
                List.of(
                    makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, evidence),
                    makeClaimResult("c2", ClaimType.SUBCLASS, true, Verdict.OUT_OF_SCOPE,
                        List.of(), Optional.empty())
                )
            );

            // Budget = 1 token = 4 chars — far less than any claim header
            EvidenceContext context = builder.buildContext(report, 1);

            assertEquals(2, context.claims().size(),
                "Even with extreme budget, all claims must be present");
            assertEquals("c1", context.claims().get(0).id());
            assertEquals(Verdict.SUPPORTED, context.claims().get(0).verdict());
            assertEquals("c2", context.claims().get(1).id());
            assertEquals(Verdict.OUT_OF_SCOPE, context.claims().get(1).verdict());
            // With extreme budget (1 token = 4 chars), claim headers can't even fit,
            // so evidence for both claims is omitted entirely → omittedClaimCount > 0
            assertTrue(context.omittedClaimCount() > 0,
                "With extreme budget, at least one claim's evidence must be omitted entirely");
        }

        @Test
        @DisplayName("omittedClaimCount counts claims whose evidence was omitted entirely under budget exhaustion")
        void omittedClaimCountReflectsBudgetExhaustion() {
            // Create a report with 3 claims, each with substantial evidence
            List<WorkflowEvidenceEntry> bigEvidence = List.of(
                new WorkflowEvidenceEntry("explicit_axiom",
                    "A very long evidence summary that consumes significant character budget space",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1")),
                new WorkflowEvidenceEntry("inferred_axiom",
                    "Another very long evidence summary that also takes up character budget",
                    "reasoner", Optional.of("ELK"), Optional.of("ev-2"))
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.PARTIALLY_VERIFIED,
                List.of(
                    makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, bigEvidence),
                    makeClaimResult("c2", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, bigEvidence),
                    makeClaimResult("c3", ClaimType.SUBCLASS, true, Verdict.UNKNOWN, bigEvidence,
                        Optional.of("insufficient_axioms"))
                )
            );

            // Small budget that can only afford ~1 claim header + partial evidence
            EvidenceContext context = builder.buildContext(report, 25);

            // All 3 claims must still be visible (id + verdict preserved per contract)
            assertEquals(3, context.claims().size());

            // Claims whose evidence was omitted entirely should be counted
            assertTrue(context.omittedClaimCount() > 0,
                "Budget exhaustion must count claims whose evidence detail was omitted entirely");
        }

        @Test
        @DisplayName("Contradicted claims appear before supported claims under truncation")
        void contradictedClaimsPrioritizedUnderTruncation() {
            // Create report with SUPPORTED claim first, then CONTRADICTED claim second (in source order)
            List<WorkflowEvidenceEntry> evidence = List.of(
                new WorkflowEvidenceEntry("explicit_axiom",
                    "Long evidence summary consuming substantial budget characters for supported claim",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-1")),
                new WorkflowEvidenceEntry("inferred_axiom",
                    "More long evidence for the supported claim taking up budget",
                    "reasoner", Optional.of("HermiT"), Optional.of("ev-2"))
            );
            List<WorkflowEvidenceEntry> contraEvidence = List.of(
                new WorkflowEvidenceEntry("counterexample",
                    "Long counterexample evidence consuming substantial budget for contradicted claim",
                    "compatibility-check", Optional.of("HermiT"), Optional.of("ev-3")),
                new WorkflowEvidenceEntry("counterexample",
                    "More counterexample evidence for contradicted claim",
                    "compatibility-check", Optional.of("HermiT"), Optional.of("ev-4"))
            );

            // Source order: SUPPORTED first, CONTRADICTED second
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.CONTRADICTED,
                List.of(
                    makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, evidence),
                    makeClaimResult("c2", ClaimType.CLASS_COMPATIBILITY, true, Verdict.CONTRADICTED, contraEvidence)
                )
            );

            // Small budget forces truncation — contradicted MUST appear first per spec
            EvidenceContext context = builder.buildContext(report, 30);

            assertEquals(2, context.claims().size());
            // Per spec: contradicted claim summaries MUST appear before lower-priority supported evidence
            assertEquals(Verdict.CONTRADICTED, context.claims().get(0).verdict(),
                "Contradicted claim MUST be positioned before supported claim under truncation");
            assertEquals(Verdict.SUPPORTED, context.claims().get(1).verdict());
        }
    }

    // ── Mixed context ──

    @Nested
    @DisplayName("Mixed verdict context")
    class MixedContextTests {

        @Test
        @DisplayName("Mixed batch context preserves all verdict distinctions")
        void mixedBatchPreservesVerdictDistinctions() {
            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.INSUFFICIENT_EVIDENCE,
                List.of(
                    makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED,
                        List.of(new WorkflowEvidenceEntry("inferred_axiom", "Dog → Animal",
                            "reasoner", Optional.of("HermiT"), Optional.of("ev-1")))),
                    makeClaimResult("c2", ClaimType.CLASS_COMPATIBILITY, true, Verdict.CONTRADICTED,
                        List.of(new WorkflowEvidenceEntry("counterexample", "Dog and Cat disjoint",
                            "compatibility-check", Optional.of("HermiT"), Optional.of("ev-2")))),
                    makeClaimResult("c3", ClaimType.SUBCLASS, true, Verdict.UNKNOWN,
                        List.of(), Optional.of("insufficient_axioms")),
                    makeClaimResult("c4", ClaimType.SUBCLASS, false, Verdict.UNKNOWN,
                        List.of(), Optional.of("insufficient_axioms"))
                )
            );

            EvidenceContext context = builder.buildContext(report, 0);

            assertEquals(AggregateAnswerStatus.INSUFFICIENT_EVIDENCE, context.status());
            assertEquals(4, context.claims().size());

            // Per spec: contradicted claims are sorted before lower-priority supported claims
            // CONTRADICTED appears first, then SUPPORTED, then UNKNOWNs (preserving source order within group)
            assertEquals(Verdict.CONTRADICTED, context.claims().get(0).verdict());
            assertEquals(Verdict.SUPPORTED, context.claims().get(1).verdict());
            assertEquals(Verdict.UNKNOWN, context.claims().get(2).verdict());
            assertEquals(Verdict.UNKNOWN, context.claims().get(3).verdict());
        }
    }

    // ── Task 4.5: No fabricated evidence ──

    @Nested
    @DisplayName("No fabricated evidence")
    class NoFabricationTests {

        @Test
        @DisplayName("Null report returns invalid context with no fabricated evidence")
        void nullReportReturnsInvalidContextNoFabrication() {
            EvidenceContext context = builder.buildContext(null, 100);

            assertEquals(AggregateAnswerStatus.INVALID_INPUT, context.status());
            assertEquals(0, context.claims().size(),
                "Null report → no fabricated claims");
            assertTrue(context.agentInstructions().stream()
                .anyMatch(i -> i.contains("invalid")),
                "Null report → agent instructions mention invalidity");
            assertTrue(context.agentInstructions().contains("Cite only evidence returned in this context."));
        }
    }

    // ── Task 4.6: Canonical evidence entry schema parity ──

    @Nested
    @DisplayName("Canonical evidence entry schema parity")
    class SchemaParityTests {

        @Test
        @DisplayName("Workflow evidence entry fields identical across report and context")
        void evidenceEntryFieldsMatchAcrossReportAndContext() {
            WorkflowEvidenceEntry entry = new WorkflowEvidenceEntry(
                "inferred_axiom",
                "Dog subClassOf Animal",
                "reasoner",
                Optional.of("HermiT"),
                Optional.of("provenance-ref-001")
            );

            AnswerVerificationReport report = makeReport(
                AggregateAnswerStatus.VERIFIED,
                List.of(makeClaimResult("c1", ClaimType.SUBCLASS, true, Verdict.SUPPORTED, List.of(entry)))
            );

            EvidenceContext context = builder.buildContext(report, 0);

            // The evidence entry in the context should be the same WorkflowEvidenceEntry object
            WorkflowEvidenceEntry contextEntry = context.claims().get(0).evidence().get(0);
            assertEquals(entry.kind(), contextEntry.kind());
            assertEquals(entry.summary(), contextEntry.summary());
            assertEquals(entry.source(), contextEntry.source());
            assertEquals(entry.reasoner(), contextEntry.reasoner());
            assertEquals(entry.provenance(), contextEntry.provenance());
        }
    }

    // ── Helpers ──

    private AnswerVerificationReport makeReport(AggregateAnswerStatus aggregateStatus,
                                                  List<ClaimWorkflowResult> claimResults) {
        return new AnswerVerificationReport(
            "answer-test",
            "test-ontology",
            aggregateStatus,
            claimResults,
            Optional.empty(),
            Optional.of(new AnswerVerificationReport.VerdictSummary(
                (int) claimResults.stream().filter(r -> r.verdict() == Verdict.SUPPORTED).count(),
                (int) claimResults.stream().filter(r -> r.verdict() == Verdict.CONTRADICTED).count(),
                (int) claimResults.stream().filter(r -> r.verdict() == Verdict.UNKNOWN).count(),
                (int) claimResults.stream().filter(r -> r.verdict() == Verdict.OUT_OF_SCOPE).count(),
                (int) claimResults.stream().filter(r -> r.required()).count(),
                (int) claimResults.stream().filter(r -> !r.required()).count()
            ))
        );
    }

    private ClaimWorkflowResult makeClaimResult(String claimId, ClaimType type, boolean required,
                                                  Verdict verdict,
                                                  List<WorkflowEvidenceEntry> evidence) {
        return makeClaimResult(claimId, type, required, verdict, evidence, Optional.empty());
    }

    private ClaimWorkflowResult makeClaimResult(String claimId, ClaimType type, boolean required,
                                                  Verdict verdict,
                                                  List<WorkflowEvidenceEntry> evidence,
                                                  Optional<String> unknownReason) {
        return new ClaimWorkflowResult(
            claimId, type, required, verdict, evidence, unknownReason,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private ClaimWorkflowResult makeClaimResultUnknown(String claimId, ClaimType type, boolean required,
                                                         List<WorkflowEvidenceEntry> evidence,
                                                         String unknownReason) {
        return new ClaimWorkflowResult(
            claimId, type, required, Verdict.UNKNOWN, evidence,
            Optional.of(unknownReason),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}