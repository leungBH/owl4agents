package org.owl4agents.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.owl4agents.storage.CatalogStore;

/**
 * Orchestrates v0.5 answer-level claim verification workflow.
 * For each claim in a batch, delegates to the existing v0.3 ClaimVerificationService,
 * then computes aggregate status from required-claim verdicts per the priority truth table.
 *
 * This service is readonly — it does not mutate ontology state.
 */
public class ClaimWorkflowService {

    private final ClaimVerificationService claimVerificationService;
    private final EvidenceGroundingService evidenceGroundingService;
    private final CatalogStore catalogStore;
    private final WorkspaceId defaultWorkspaceId;

    public ClaimWorkflowService(ClaimVerificationService claimVerificationService,
                                EvidenceGroundingService evidenceGroundingService,
                                CatalogStore catalogStore,
                                WorkspaceId workspaceId) {
        this.claimVerificationService = claimVerificationService;
        this.evidenceGroundingService = evidenceGroundingService;
        this.catalogStore = catalogStore;
        this.defaultWorkspaceId = workspaceId;
    }

    /**
     * Verify a batch of claims against the given ontology and produce an
     * AnswerVerificationReport with aggregate status and per-claim results.
     *
     * @param batch  Validated ClaimBatchInput (from ClaimBatchValidator)
     * @param ontologyId  Ontology ID to verify against
     * @return ServiceResult containing the report or a structured error
     */
    public ServiceResult<AnswerVerificationReport> verifyBatch(ClaimBatchInput batch, String ontologyId) {
        if (batch == null) {
            return ServiceResult.error(ErrorCode.INVALID_CLAIM_SCHEMA,
                "Claim batch must not be null.");
        }

        // Check ontology exists
        OntologyId ontId = new OntologyId(ontologyId);
        ServiceResult<CatalogEntry> catalogResult = catalogStore.findEntry(defaultWorkspaceId, ontId);
        if (!catalogResult.isSuccess()) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
        }

        // Resolve workflow options (reasoner override available for future use)
        batch.options()
            .flatMap(WorkflowOptions::reasoner)
            .orElse(null);

        // Verify each claim and build per-claim results
        List<ClaimWorkflowResult> claimResults = new ArrayList<>();
        List<String> outOfScopeEntities = new ArrayList<>();

        int supportedCount = 0;
        int contradictedCount = 0;
        int unknownCount = 0;
        int outOfScopeCount = 0;
        int requiredCount = 0;
        int optionalCount = 0;

        for (ClaimBatchInput.BatchClaim batchClaim : batch.claims()) {
            // Convert BatchClaim to v0.3 Claim for delegation
            Claim v03Claim = batchClaimToV03Claim(batchClaim, ontologyId);

            ServiceResult<ClaimVerificationResult> verifyResult =
                claimVerificationService.verify(v03Claim);

            if (!verifyResult.isSuccess()) {
                // Downstream verification error → treat as unknown for this claim
                ClaimWorkflowResult errorResult = new ClaimWorkflowResult(
                    batchClaim.id(),
                    batchClaim.type(),
                    batchClaim.required(),
                    Verdict.UNKNOWN,
                    List.of(),
                    Optional.of(UnknownReason.INSUFFICIENT_AXIOMS.jsonName()),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("Verification service error: " + errorFrom(verifyResult))
                );
                claimResults.add(errorResult);
                unknownCount++;
                if (batchClaim.required()) requiredCount++;
                else optionalCount++;
                continue;
            }

            ClaimVerificationResult v03Result = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();
            Verdict verdict = v03Result.verdict();

            // Convert v0.3 evidence to workflow evidence entries
            List<WorkflowEvidenceEntry> workflowEvidence = convertEvidence(v03Result.evidence());

            // Build per-claim workflow result
            Optional<String> unknownReason = v03Result.unknownReason()
                .map(UnknownReason::jsonName);

            Optional<List<WorkflowEvidenceEntry>> counterexamples = Optional.empty();
            if (verdict == Verdict.CONTRADICTED) {
                // Delegate counterexample search to EvidenceGroundingService
                ServiceResult<List<EvidenceItem>> counterResult =
                    evidenceGroundingService.findCounterexamples(v03Claim, v03Result);
                if (counterResult.isSuccess()) {
                    List<EvidenceItem> counterItems = ((ServiceResult.Success<List<EvidenceItem>>) counterResult).data();
                    counterexamples = Optional.of(convertEvidence(counterItems));
                }
            }

            Optional<List<MissingEntityResult.EntityMatch>> missingEntities = Optional.empty();
            if (verdict == Verdict.OUT_OF_SCOPE || verdict == Verdict.UNKNOWN) {
                ServiceResult<MissingEntityResult> missingResult =
                    evidenceGroundingService.detectMissingEntities(v03Claim);
                if (missingResult.isSuccess()) {
                    MissingEntityResult missingData = ((ServiceResult.Success<MissingEntityResult>) missingResult).data();
                    if (!missingData.missing().isEmpty() || !missingData.outOfScope().isEmpty()) {
                        List<MissingEntityResult.EntityMatch> relevant = new ArrayList<>();
                        relevant.addAll(missingData.missing());
                        relevant.addAll(missingData.outOfScope());
                        missingEntities = Optional.of(relevant);
                    }
                    // Collect out-of-scope entity IRIs for scope diagnostic.
                    // Only include terms that look like entity IRIs (start with http:// or similar),
                    // not predicate keyword strings like "subClassOf" that are relation names.
                    for (MissingEntityResult.EntityMatch match : missingData.outOfScope()) {
                        if (match.matchedIRI().isEmpty() && isEntityIRI(match.searchTerm())) {
                            outOfScopeEntities.add(match.searchTerm());
                        }
                    }
                    for (MissingEntityResult.EntityMatch match : missingData.missing()) {
                        if (match.matchedIRI().isEmpty() && isEntityIRI(match.searchTerm())) {
                            outOfScopeEntities.add(match.searchTerm());
                        }
                    }
                    // Upgrade verdict: when subject/object entity IRIs are genuinely
                    // missing or out-of-scope (not predicate keyword strings),
                    // a claim previously judged UNKNOWN should become OUT_OF_SCOPE.
                    // This is required by the v0.5 aggregate status truth table
                    // so that out_of_scope and partially_verified are reachable.
                    boolean hasGenuineMissingEntities = outOfScopeEntities.size() > 0;
                    if (verdict == Verdict.UNKNOWN && hasGenuineMissingEntities) {
                        verdict = Verdict.OUT_OF_SCOPE;
                        unknownReason = Optional.of("missing_or_out_of_scope_entities");
                    }
                }
            }

            Optional<String> diagnostics = v03Result.unknownExplanation();
            // When verdict was upgraded to OUT_OF_SCOPE, override diagnostics
            // to reflect scope issue rather than unknown explanation
            if (verdict == Verdict.OUT_OF_SCOPE
                && v03Result.verdict() == Verdict.UNKNOWN) {
                diagnostics = Optional.of("Claim references entities not declared in ontology '"
                    + ontologyId + "'. Verdict upgraded from UNKNOWN to OUT_OF_SCOPE.");
            }

            ClaimWorkflowResult claimResult = new ClaimWorkflowResult(
                batchClaim.id(),
                batchClaim.type(),
                batchClaim.required(),
                verdict,
                workflowEvidence,
                unknownReason,
                counterexamples,
                missingEntities,
                diagnostics
            );
            claimResults.add(claimResult);

            // Update counts
            switch (verdict) {
                case SUPPORTED -> supportedCount++;
                case CONTRADICTED -> contradictedCount++;
                case UNKNOWN -> unknownCount++;
                case OUT_OF_SCOPE -> outOfScopeCount++;
            }
            if (batchClaim.required()) requiredCount++;
            else optionalCount++;
        }

        // Compute aggregate status from required claims only
        AggregateAnswerStatus aggregateStatus = computeAggregateStatus(claimResults);

        // Build scope diagnostic if applicable
        Optional<ScopeDiagnostic> scopeDiagnostic = Optional.empty();
        if (aggregateStatus == AggregateAnswerStatus.OUT_OF_SCOPE
            || aggregateStatus == AggregateAnswerStatus.PARTIALLY_VERIFIED) {
            scopeDiagnostic = Optional.of(new ScopeDiagnostic(
                outOfScopeEntities,
                Optional.of("Some referenced entities are not declared in ontology '" + ontologyId + "'."),
                Optional.of(outOfScopeEntities.size() + " entities outside ontology scope.")
            ));
        }

        // Build summary
        AnswerVerificationReport.VerdictSummary summary = new AnswerVerificationReport.VerdictSummary(
            supportedCount, contradictedCount, unknownCount, outOfScopeCount,
            requiredCount, optionalCount
        );

        AnswerVerificationReport report = new AnswerVerificationReport(
            batch.answerId(),
            ontologyId,
            aggregateStatus,
            claimResults,
            scopeDiagnostic,
            Optional.of(summary)
        );

        return ServiceResult.success(report, ResultMetadata.empty());
    }

    /**
     * Compute aggregate answer status from required-claim verdicts using
     * the priority truth table:
     *   invalid_input > contradicted > insufficient_evidence > out_of_scope
     *   > partially_verified > verified
     *
     * Optional claim failures do not override the aggregate status.
     */
    private AggregateAnswerStatus computeAggregateStatus(List<ClaimWorkflowResult> claimResults) {
        // Filter to required claims only for aggregate status computation
        List<ClaimWorkflowResult> requiredClaims = claimResults.stream()
            .filter(ClaimWorkflowResult::required)
            .toList();

        if (requiredClaims.isEmpty()) {
            // All claims are optional — aggregate is verified if all optional claims pass
            // But per contract, required defaults to true, so this should not happen
            // in normal operation. Handle edge case: treat as verified if no required failures.
            boolean allOptionalSupported = claimResults.stream()
                .allMatch(r -> r.verdict() == Verdict.SUPPORTED);
            return allOptionalSupported ? AggregateAnswerStatus.VERIFIED : AggregateAnswerStatus.INSUFFICIENT_EVIDENCE;
        }

        // Priority 1: contradicted — any required claim contradicted
        boolean anyContradicted = requiredClaims.stream()
            .anyMatch(r -> r.verdict() == Verdict.CONTRADICTED);
        if (anyContradicted) {
            return AggregateAnswerStatus.CONTRADICTED;
        }

        // Priority 2: insufficient_evidence — any required claim unknown (and no contradicted)
        boolean anyUnknown = requiredClaims.stream()
            .anyMatch(r -> r.verdict() == Verdict.UNKNOWN);
        if (anyUnknown) {
            return AggregateAnswerStatus.INSUFFICIENT_EVIDENCE;
        }

        // Priority 3: out_of_scope — all required claims out_of_scope
        boolean allOutOfScope = requiredClaims.stream()
            .allMatch(r -> r.verdict() == Verdict.OUT_OF_SCOPE);
        if (allOutOfScope) {
            return AggregateAnswerStatus.OUT_OF_SCOPE;
        }

        // Priority 4: partially_verified — at least one supported + at least one not supported
        // (and no contradicted or unknown, which were checked above)
        boolean anySupported = requiredClaims.stream()
            .anyMatch(r -> r.verdict() == Verdict.SUPPORTED);
        boolean anyNotSupported = requiredClaims.stream()
            .anyMatch(r -> r.verdict() != Verdict.SUPPORTED);
        if (anySupported && anyNotSupported) {
            // Since contradicted and unknown are excluded, "not supported" means out_of_scope
            return AggregateAnswerStatus.PARTIALLY_VERIFIED;
        }

        // Priority 5: verified — all required claims supported
        boolean allSupported = requiredClaims.stream()
            .allMatch(r -> r.verdict() == Verdict.SUPPORTED);
        if (allSupported) {
            return AggregateAnswerStatus.VERIFIED;
        }

        // Fallback: should not reach here with valid verdicts
        return AggregateAnswerStatus.INSUFFICIENT_EVIDENCE;
    }

    /**
     * Convert a v0.5 BatchClaim to a v0.3 Claim for delegation to
     * ClaimVerificationService. Preserves v0.3 claim field semantics.
     */
    private Claim batchClaimToV03Claim(ClaimBatchInput.BatchClaim batchClaim, String ontologyId) {
        return new Claim(
            batchClaim.id(),
            batchClaim.type(),
            ontologyId,
            batchClaim.subject().orElse(null),
            batchClaim.predicate().orElse(null),
            batchClaim.object().orElse(null),
            batchClaim.reasoner(),
            batchClaim.graphScope(),
            batchClaim.options()
        );
    }

    /**
     * Convert v0.3 EvidenceItem list to v0.5 WorkflowEvidenceEntry list.
     * Projects the v0.3 schema onto the compact workflow schema:
     *   (evidenceId, role, kind, value, source, reasoner, graphScope, entities, confidence)
     *   → (kind, summary, source, reasoner, provenance)
     */
    private List<WorkflowEvidenceEntry> convertEvidence(List<EvidenceItem> v03Items) {
        List<WorkflowEvidenceEntry> entries = new ArrayList<>();
        for (EvidenceItem item : v03Items) {
            String provenance = item.evidenceId();
            entries.add(new WorkflowEvidenceEntry(
                item.kind().jsonName(),
                item.value(),
                item.source(),
                Optional.ofNullable(item.reasoner()),
                Optional.of(provenance)
            ));
        }
        return entries;
    }

    private String errorFrom(ServiceResult<?> result) {
        if (result instanceof ServiceResult.Error) {
            ServiceError error = ((ServiceResult.Error<?>) result).error();
            return error.code().code() + ": " + error.message();
        }
        return "unknown error";
    }

    /**
     * Check whether a search term looks like an entity IRI (starts with a URI scheme)
     * rather than a predicate keyword string (e.g., "subClassOf", "class_assertion").
     * Predicate keywords are relation/axiom type names, not ontology entity references.
     */
    private boolean isEntityIRI(String searchTerm) {
        if (searchTerm == null || searchTerm.isEmpty()) return false;
        // Entity IRIs start with a URI scheme like http://, https://, urn:, etc.
        // Predicate keywords like "subClassOf" or "class_assertion" are bare names.
        return searchTerm.startsWith("http://")
            || searchTerm.startsWith("https://")
            || searchTerm.startsWith("urn:")
            || searchTerm.contains("#")
            || searchTerm.contains("/");
    }
}