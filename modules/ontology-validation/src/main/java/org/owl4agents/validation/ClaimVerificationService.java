package org.owl4agents.validation;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.owl4agents.reasoner.ReasonerService;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.storage.CatalogStore;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.time.Duration;
import java.util.*;

/**
 * Shared claim verification service used by both CLI and MCP.
 *
 * <p>v0.8.5: Refactored to a 5-stage verification pipeline for axiom-backed
 * claim types (design D1): (1) scope check → (2) source ontology consistency →
 * (3) entailment → (4) exact consistency after adding claim axiom → (5) final
 * verdict. Structural proxies are demoted to STRUCTURAL_CONFLICT_HINT evidence
 * and never determine the final verdict. Timeout and error cases return
 * {@code semanticVerdict = null} with explicit error codes.
 *
 * <p>Special claim types (ONTOLOGY_CONSISTENCY, CLASS_COMPATIBILITY,
 * ONTOLOGY_SCOPE, LITERAL_VALIDITY) bypass the 5-stage flow with custom
 * verification logic (design D9).
 *
 * <p>Verdict resolution: OUT_OF_SCOPE at stage 1, SOURCE_ONTOLOGY_INCONSISTENT
 * at stage 2, SUPPORTED at stage 3 (entailed), CONTRADICTED at stage 4
 * (O ∪ {α} inconsistent), UNKNOWN at stage 4 (O ∪ {α} consistent).
 */
public class ClaimVerificationService {

    private final ReasonerService reasonerService;
    private final ConsistencyAnalysisService consistencyService;
    private final SemanticDeepeningService deepeningService;
    private final CatalogStore catalogStore;
    private final WorkspaceId defaultWorkspaceId;
    private final ClaimAxiomBuilder claimAxiomBuilder;
    private final Duration defaultExactCheckTimeout;

    public ClaimVerificationService(ReasonerService reasonerService,
                                     ConsistencyAnalysisService consistencyService,
                                     SemanticDeepeningService deepeningService,
                                     CatalogStore catalogStore,
                                     WorkspaceId workspaceId) {
        this(reasonerService, consistencyService, deepeningService, catalogStore,
             workspaceId, new ClaimAxiomBuilder(), Duration.ofSeconds(60));
    }

    public ClaimVerificationService(ReasonerService reasonerService,
                                     ConsistencyAnalysisService consistencyService,
                                     SemanticDeepeningService deepeningService,
                                     CatalogStore catalogStore,
                                     WorkspaceId workspaceId,
                                     ClaimAxiomBuilder claimAxiomBuilder,
                                     Duration defaultExactCheckTimeout) {
        this.reasonerService = reasonerService;
        this.consistencyService = consistencyService;
        this.deepeningService = deepeningService;
        this.catalogStore = catalogStore;
        this.defaultWorkspaceId = workspaceId;
        this.claimAxiomBuilder = claimAxiomBuilder;
        this.defaultExactCheckTimeout = defaultExactCheckTimeout;
    }

    /**
     * Verify a structured claim against the ontology.
     * Returns a ClaimVerificationResult with verdict, evidence, and metadata.
     */
    public ServiceResult<ClaimVerificationResult> verify(Claim claim) {
        if (claim == null) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("Claim must not be null."));
        }

        OntologyId ontId = new OntologyId(claim.ontologyId());

        ServiceResult<org.owl4agents.core.model.CatalogEntry> catalogResult =
            catalogStore.findEntry(defaultWorkspaceId, ontId);
        if (!catalogResult.isSuccess()) {
            ServiceError catalogError = ((ServiceResult.Error<org.owl4agents.core.model.CatalogEntry>) catalogResult).error();
            if (catalogError.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
                return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
            }
            return mapError(catalogResult);
        }

        OWLOntology ontology;
        try {
            ontology = reasonerService.loadOntologyForClaim(ontId);
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
        }

        return verifyWithOntology(ontology, claim, ontId);
    }

    /**
     * v0.8.4: Overload that accepts a pre-loaded {@link OWLOntology}, avoiding
     * redundant ontology loading in batch verification ({@code ClaimWorkflowService.verifyBatch}).
     * The caller is responsible for ensuring the ontology corresponds to
     * {@code claim.ontologyId()}.
     */
    public ServiceResult<ClaimVerificationResult> verify(OWLOntology ontology, Claim claim) {
        if (claim == null) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("Claim must not be null."));
        }

        OntologyId ontId = new OntologyId(claim.ontologyId());

        ServiceResult<org.owl4agents.core.model.CatalogEntry> catalogResult =
            catalogStore.findEntry(defaultWorkspaceId, ontId);
        if (!catalogResult.isSuccess()) {
            ServiceError catalogError = ((ServiceResult.Error<org.owl4agents.core.model.CatalogEntry>) catalogResult).error();
            if (catalogError.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
                return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
            }
            return mapError(catalogResult);
        }

        return verifyWithOntology(ontology, claim, ontId);
    }

    /**
     * v0.8.5: Overload that accepts a caller-supplied exact-check timeout
     * (task 10.8). Used by the CLI {@code --timeout} flag to override the
     * service default on a per-call basis. When {@code timeout} is null,
     * falls back to {@link #defaultExactCheckTimeout}.
     */
    public ServiceResult<ClaimVerificationResult> verify(Claim claim, Duration timeout) {
        if (claim == null) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("Claim must not be null."));
        }

        OntologyId ontId = new OntologyId(claim.ontologyId());

        ServiceResult<org.owl4agents.core.model.CatalogEntry> catalogResult =
            catalogStore.findEntry(defaultWorkspaceId, ontId);
        if (!catalogResult.isSuccess()) {
            ServiceError catalogError = ((ServiceResult.Error<org.owl4agents.core.model.CatalogEntry>) catalogResult).error();
            if (catalogError.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
                return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
            }
            return mapError(catalogResult);
        }

        OWLOntology ontology;
        try {
            ontology = reasonerService.loadOntologyForClaim(ontId);
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
        }

        Duration effectiveTimeout = timeout != null ? timeout : defaultExactCheckTimeout;
        return verifyWithOntology(ontology, claim, ontId, effectiveTimeout);
    }

    // ════════════════════════════════════════════════════════════════════
    // 5-STAGE VERIFICATION PIPELINE (design D1)
    // ════════════════════════════════════════════════════════════════════

    private ServiceResult<ClaimVerificationResult> verifyWithOntology(OWLOntology ontology, Claim claim, OntologyId ontId) {
        return verifyWithOntology(ontology, claim, ontId, defaultExactCheckTimeout);
    }

    /**
     * v0.8.5: 5-stage pipeline with caller-supplied exact-check timeout.
     * Used by the CLI {@code --timeout} flag (task 10.8) to override the
     * default timeout on a per-call basis without mutating service state.
     */
    private ServiceResult<ClaimVerificationResult> verifyWithOntology(OWLOntology ontology, Claim claim, OntologyId ontId, Duration exactCheckTimeout) {
        // Stage 1: global scope pre-check. Exempt for ONTOLOGY_SCOPE (it IS
        // the scope check), ONTOLOGY_CONSISTENCY (subject is typically the
        // ontology IRI or owl:Thing), and LITERAL_VALIDITY (object is typically
        // xsd:... which is not in any ontology signature).
        if (!isExemptFromScopePrecheck(claim.type())) {
            ServiceResult<ClaimVerificationResult> precheckResult = applyScopePrecheck(ontology, claim, ontId);
            if (precheckResult != null) {
                return precheckResult;
            }
        }

        // Kind-based pre-dispatch for backward compatibility (v0.8.3 R5, R6):
        // - DISJOINT_CLASSES with individual subjects → DifferentIndividuals flow
        // - OBJECT_PROPERTY_ASSERTION with object_property subjects + subPropertyOf
        //   predicate → SubObjectPropertyOf flow
        ClaimType effectiveType = resolveEffectiveType(claim);

        return switch (claim.type()) {
            // 12 axiom-backed types → unified 5-stage flow
            case SUBCLASS, EQUIVALENT_CLASSES, DISJOINT_CLASSES,
                 INDIVIDUAL_MEMBERSHIP, OBJECT_PROPERTY_ASSERTION, DATA_PROPERTY_ASSERTION,
                 OBJECT_PROPERTY_DOMAIN, OBJECT_PROPERTY_RANGE,
                 DATA_PROPERTY_DOMAIN, DATA_PROPERTY_RANGE,
                 DIFFERENT_INDIVIDUALS, OBJECT_PROPERTY_SUBPROPERTY ->
                verifyWith5StageFlow(ontology, claim, ontId, effectiveType, exactCheckTimeout);
            // 4 special types → custom dispatch (design D9)
            case LITERAL_VALIDITY -> verifyLiteralValidity(claim, ontId);
            case CLASS_COMPATIBILITY -> verifyClassCompatibility(ontology, claim, ontId);
            case ONTOLOGY_CONSISTENCY -> verifyOntologyConsistency(claim, ontId);
            case ONTOLOGY_SCOPE -> verifyOntologyScope(ontology, claim, ontId);
        };
    }

    /**
     * Resolve the effective claim type for axiom building, accounting for
     * kind-based dispatch (v0.8.3 R5: individual-level DISJOINT_CLASSES →
     * DifferentIndividuals; v0.8.3 R6: OBJECT_PROPERTY_ASSERTION with
     * subPropertyOf predicate → OBJECT_PROPERTY_SUBPROPERTY).
     */
    private ClaimType resolveEffectiveType(Claim claim) {
        if (claim.type() == ClaimType.DISJOINT_CLASSES
                && claim.subject() != null && claim.object() != null
                && "individual".equals(claim.subject().kind())
                && "individual".equals(claim.object().kind())) {
            return ClaimType.DIFFERENT_INDIVIDUALS;
        }
        if (claim.type() == ClaimType.OBJECT_PROPERTY_ASSERTION
                && claim.subject() != null && claim.object() != null
                && "object_property".equals(claim.subject().kind())
                && "object_property".equals(claim.object().kind())
                && "subPropertyOf".equals(claim.predicate())) {
            return ClaimType.OBJECT_PROPERTY_SUBPROPERTY;
        }
        return claim.type();
    }

    /**
     * 5-stage verification pipeline for axiom-backed claim types (design D1).
     *
     * Stage 1: scope check (already done in verifyWithOntology)
     * Stage 2: source ontology consistency → SOURCE_ONTOLOGY_INCONSISTENT if inconsistent
     * Stage 3: build claim axiom + entailment check → SUPPORTED if entailed
     * Stage 4: exact consistency after adding claim axiom → CONTRADICTED if inconsistent
     * Stage 5: final verdict mapping → UNKNOWN if consistent
     */
    private ServiceResult<ClaimVerificationResult> verifyWith5StageFlow(
            OWLOntology ontology, Claim claim, OntologyId ontId, ClaimType effectiveType,
            Duration exactCheckTimeout) {

        // Same-individual self-contradiction for individual-level DISJOINT_CLASSES
        if (effectiveType == ClaimType.DIFFERENT_INDIVIDUALS
                && claim.type() == ClaimType.DISJOINT_CLASSES
                && claim.subject() != null && claim.object() != null
                && claim.subject().iri() != null
                && claim.subject().iri().equals(claim.object().iri())) {
            EvidenceItem counter = new EvidenceItem(
                evidenceId("same-individual-self", claim.claimId()),
                EvidenceItem.ROLE_COUNTER,
                EvidenceKind.EXPLICIT_AXIOM,
                "Same individual: " + claim.subject().iri(),
                "self-identity", "default", "EXPLICIT",
                List.of(claim.subject().iri()),
                EvidenceItem.CONFIDENCE_EXPLICIT
            );
            // Stage 1 short-circuit → metadata = null (no reasoner call)
            return buildResult(claim, ontId, Verdict.CONTRADICTED, List.of(counter),
                Optional.empty(), Optional.empty(), null);
        }

        long pipelineStart = System.nanoTime();
        Long axiomBuildMs = null;
        Long sourceConsistencyMs = null;
        Long entailmentMs = null;

        // v0.8.6 task 3.10b: Track metadata from each stage's reasoner call.
        // Priority: Stage 4 > Stage 3 > Stage 2; null until a stage produces non-null.
        ReasonerCallMetadata stage2Meta = null;
        ReasonerCallMetadata stage3Meta = null;
        ReasonerCallMetadata stage4Meta = null;

        // ── Stage 2: source ontology consistency ──
        long stage2Start = System.nanoTime();
        ServiceResult<Boolean> sourceResult =
            reasonerService.checkSourceOntologyConsistency(ontId, claim.reasoner());
        sourceConsistencyMs = msSince(stage2Start);

        // Extract Stage 2 metadata (populated by ReasonerCallWrapper)
        if (sourceResult instanceof ServiceResult.Success<Boolean> s2) {
            stage2Meta = s2.reasonerMetadata();
        } else if (sourceResult instanceof ServiceResult.Error<Boolean> e2) {
            stage2Meta = e2.reasonerMetadata();
        }

        if (!sourceResult.isSuccess()) {
            return buildErroredResult(claim, ontId, ExecutionStatus.ERROR,
                extractErrorCode(sourceResult),
                new PerStageTiming(null, sourceConsistencyMs, null, null, null, null, null,
                    sourceConsistencyMs),
                stage2Meta);
        }
        Boolean sourceConsistent = ((ServiceResult.Success<Boolean>) sourceResult).data();
        if (Boolean.FALSE.equals(sourceConsistent)) {
            return buildErroredResult(claim, ontId, ExecutionStatus.ERROR,
                ErrorCode.SOURCE_ONTOLOGY_INCONSISTENT,
                new PerStageTiming(null, sourceConsistencyMs, null, null, null, null, null,
                    sourceConsistencyMs),
                stage2Meta);
        }

        // ── Stage 3a: build claim axiom (design D2: single axiom for both checks) ──
        long axiomStart = System.nanoTime();
        ServiceResult<OWLAxiom> axiomResult = claimAxiomBuilder.build(ontology, claim, effectiveType);
        axiomBuildMs = msSince(axiomStart);

        if (!axiomResult.isSuccess()) {
            ErrorCode code = extractErrorCode(axiomResult);
            String errorMsg = ((ServiceResult.Error<OWLAxiom>) axiomResult).error().message();
            // v0.8.5: entity-not-found during axiom building (e.g., an
            // expression references an IRI not in the ontology signature)
            // → OUT_OF_SCOPE, not a hard error.
            if (errorMsg != null && errorMsg.contains("Entity not found")) {
                // Axiom building doesn't invoke the reasoner → preserve Stage 2 metadata
                return buildResult(claim, ontId, Verdict.OUT_OF_SCOPE, List.of(),
                    Optional.of(UnknownReason.MISSING_ENTITY), Optional.of(errorMsg), stage2Meta);
            }
            long total = sumMs(sourceConsistencyMs, axiomBuildMs);
            return buildErroredResult(claim, ontId, ExecutionStatus.ERROR, code,
                new PerStageTiming(axiomBuildMs, sourceConsistencyMs, null, null, null, null, null, total),
                stage2Meta);
        }
        OWLAxiom claimAxiom = ((ServiceResult.Success<OWLAxiom>) axiomResult).data();

        // ── Stage 3b: entailment check ──
        long entailStart = System.nanoTime();
        ServiceResult<EntailmentResult> entailResult =
            reasonerService.checkAxiomEntailment(ontology, ontId, claimAxiom, claim.reasoner());
        entailmentMs = msSince(entailStart);

        // Extract Stage 3 metadata (null when asserted-axiom fast-path is taken)
        if (entailResult instanceof ServiceResult.Success<EntailmentResult> s3) {
            stage3Meta = s3.reasonerMetadata();
        } else if (entailResult instanceof ServiceResult.Error<EntailmentResult> e3) {
            stage3Meta = e3.reasonerMetadata();
        }
        // "Last non-null wins": Stage 3 > Stage 2
        ReasonerCallMetadata metaAfterStage3 = stage3Meta != null ? stage3Meta : stage2Meta;

        if (!entailResult.isSuccess()) {
            ErrorCode code = extractErrorCode(entailResult);
            long total = sumMs(sourceConsistencyMs, axiomBuildMs, entailmentMs);
            return buildErroredResult(claim, ontId, ExecutionStatus.ERROR, code,
                new PerStageTiming(axiomBuildMs, sourceConsistencyMs, entailmentMs, null, null, null, null, total),
                metaAfterStage3);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) entailResult).data();

        // Stage 3 short-circuit: ENTAILED → SUPPORTED
        if (EntailmentResult.ENTAILED.equals(entailment.result())) {
            List<EvidenceItem> evidence = buildEntailedEvidence(claim, entailment);
            long total = sumMs(sourceConsistencyMs, axiomBuildMs, entailmentMs);
            // For asserted-axiom fast-path, stage3Meta is null → Stage 2's metadata preserved
            return buildCompletedResult(claim, ontId, Verdict.SUPPORTED, evidence,
                Optional.empty(), Optional.empty(),
                new PerStageTiming(axiomBuildMs, sourceConsistencyMs, entailmentMs, null, null, null, null, total),
                metaAfterStage3);
        }

        // ── Stage 4: exact consistency check (only for non-entailed claims) ──
        long exactStart = System.nanoTime();
        ServiceResult<ConsistencyAfterAdditionResult> exactResult =
            reasonerService.checkConsistencyAfterAdding(
                ontology, ontId, claim.claimId(), claimAxiom,
                claim.reasoner(), exactCheckTimeout);
        long exactElapsed = msSince(exactStart);

        if (!exactResult.isSuccess()) {
            ErrorCode code = extractErrorCode(exactResult);
            long total = sumMs(sourceConsistencyMs, axiomBuildMs, entailmentMs, exactElapsed);
            return buildErroredResult(claim, ontId, ExecutionStatus.ERROR, code,
                new PerStageTiming(axiomBuildMs, sourceConsistencyMs, entailmentMs, null, null, null, null, total),
                metaAfterStage3);
        }

        ConsistencyAfterAdditionResult exact =
            ((ServiceResult.Success<ConsistencyAfterAdditionResult>) exactResult).data();

        // v0.8.6: Extract Stage 4 metadata from ConsistencyAfterAdditionResult.metadata
        // (populated by ReasonerCallWrapper in checkConsistencyAfterAdding).
        stage4Meta = exact.metadata();
        // "Last non-null wins": Stage 4 > Stage 3 > Stage 2
        ReasonerCallMetadata metaAfterStage4 = stage4Meta != null ? stage4Meta : metaAfterStage3;

        // Propagate per-stage timing from the exact check result
        PerStageTiming exactTiming = exact.perStageTiming();
        Long temporaryCopyMs = exactTiming != null ? exactTiming.temporaryCopyMs() : null;
        Long reasonerInitMs = exactTiming != null ? exactTiming.reasonerInitMs() : null;
        Long consistencyCheckMs = exactTiming != null ? exactTiming.consistencyCheckMs() : null;
        Long explanationMs = exactTiming != null ? exactTiming.explanationMs() : null;

        long totalMs = sumMs(sourceConsistencyMs, axiomBuildMs, entailmentMs, exactElapsed);
        PerStageTiming finalTiming = new PerStageTiming(
            axiomBuildMs, sourceConsistencyMs, entailmentMs,
            temporaryCopyMs, reasonerInitMs, consistencyCheckMs, explanationMs, totalMs);

        // ── Stage 5: final verdict mapping ──
        switch (exact.status()) {
            case INCONSISTENT:
                // not-entailed + inconsistent → CONTRADICTED
                List<EvidenceItem> contradictedEvidence =
                    buildContradictedEvidence(claim, exact, entailment);
                return buildCompletedResult(claim, ontId, Verdict.CONTRADICTED,
                    contradictedEvidence, Optional.empty(), Optional.empty(), finalTiming, metaAfterStage4);

            case CONSISTENT:
                // not-entailed + consistent → UNKNOWN
                List<EvidenceItem> unknownEvidence =
                    buildUnknownEvidence(claim, exact, entailment, ontology, ontId);
                Optional<UnknownReason> reason = EntailmentResult.UNSUPPORTED_AXIOM_TYPE.equals(entailment.result())
                    ? Optional.of(UnknownReason.UNSUPPORTED_CLAIM_TYPE)
                    : Optional.of(UnknownReason.INSUFFICIENT_AXIOMS);
                return buildCompletedResult(claim, ontId, Verdict.UNKNOWN,
                    unknownEvidence, reason, Optional.empty(), finalTiming, metaAfterStage4);

            case TIMEOUT:
                return buildErroredResult(claim, ontId, ExecutionStatus.TIMEOUT,
                    ErrorCode.REASONER_TIMEOUT, finalTiming, metaAfterStage4);

            case ERROR:
            default:
                return buildErroredResult(claim, ontId, ExecutionStatus.ERROR,
                    ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED, finalTiming, metaAfterStage4);
        }
    }

    // ── Evidence builders for 5-stage flow ──

    private List<EvidenceItem> buildEntailedEvidence(Claim claim, EntailmentResult entailment) {
        String subIri = claim.subject() != null && claim.subject().iri() != null
            ? claim.subject().iri() : "<expression>";
        String objIri = claim.object() != null && claim.object().iri() != null
            ? claim.object().iri() : "<expression>";
        return List.of(new EvidenceItem(
            evidenceId("entailment", claim.claimId()),
            EvidenceItem.ROLE_SUPPORTING,
            entailment.source() != null && entailment.source().contains("inferred")
                ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
            entailment.axiomType() + ": " + subIri + " → " + objIri,
            entailment.source() != null ? entailment.source() : "reasoner",
            entailment.reasonerName() != null ? entailment.reasonerName() : "default",
            "UNION",
            List.of(subIri, objIri),
            EvidenceItem.CONFIDENCE_ENTAILED
        ));
    }

    /**
     * Build evidence for CONTRADICTED verdict (stage 4: O ∪ {α} inconsistent).
     * Includes CONSISTENCY_REPORT and optionally INCONSISTENCY_JUSTIFICATION
     * when Openllet provides explanation axioms.
     */
    private List<EvidenceItem> buildContradictedEvidence(Claim claim,
                                                          ConsistencyAfterAdditionResult exact,
                                                          EntailmentResult entailment) {
        List<EvidenceItem> items = new ArrayList<>();
        String reasonerName = exact.reasonerName() != null ? exact.reasonerName() : "default";

        // CONSISTENCY_REPORT: summary of the inconsistency
        items.add(new EvidenceItem(
            evidenceId("consistency-report", claim.claimId()),
            EvidenceItem.ROLE_COUNTER,
            EvidenceKind.CONSISTENCY_REPORT,
            "Adding the claim axiom makes the ontology inconsistent.",
            "exact-consistency-check",
            reasonerName,
            "UNION",
            List.of(),
            EvidenceItem.CONFIDENCE_INFERRED
        ));

        // INCONSISTENCY_JUSTIFICATION: Openllet explanation axioms (if available)
        if (exact.explanationAxioms() != null && !exact.explanationAxioms().isEmpty()) {
            for (String axiomStr : exact.explanationAxioms()) {
                items.add(new EvidenceItem(
                    evidenceId("inconsistency-justification", claim.claimId()),
                    EvidenceItem.ROLE_COUNTER,
                    EvidenceKind.INCONSISTENCY_JUSTIFICATION,
                    axiomStr,
                    "openllet-explanation",
                    reasonerName,
                    "UNION",
                    List.of(),
                    EvidenceItem.CONFIDENCE_INFERRED
                ));
            }
        }
        return items;
    }

    /**
     * Build evidence for UNKNOWN verdict (stage 4: O ∪ {α} consistent).
     * States "claim not entailed" AND "adding claim preserves consistency".
     * Adds STRUCTURAL_CONFLICT_HINT evidence when a structural proxy detects
     * a potential conflict (design D10: proxy is hint only, never a verdict).
     */
    private List<EvidenceItem> buildUnknownEvidence(Claim claim,
                                                     ConsistencyAfterAdditionResult exact,
                                                     EntailmentResult entailment,
                                                     OWLOntology ontology, OntologyId ontId) {
        List<EvidenceItem> items = new ArrayList<>();
        String reasonerName = exact.reasonerName() != null ? exact.reasonerName() : "default";

        // Primary UNKNOWN evidence: not entailed + consistent
        items.add(new EvidenceItem(
            evidenceId("no-entailment", claim.claimId()),
            EvidenceItem.ROLE_SUPPORTING,
            EvidenceKind.REASONING_REPORT,
            "Claim not entailed; adding claim preserves consistency.",
            "exact-consistency-check",
            reasonerName,
            "UNION",
            List.of(),
            EvidenceItem.CONFIDENCE_INFERRED
        ));

        // STRUCTURAL_CONFLICT_HINT: proxy hint (NOT a formal contradiction)
        EvidenceItem proxyHint = buildStructuralConflictHint(ontology, claim, ontId);
        if (proxyHint != null) {
            items.add(proxyHint);
        }
        return items;
    }

    /**
     * Build a STRUCTURAL_CONFLICT_HINT evidence item when a structural proxy
     * (class compatibility / disjointness check) detects a potential conflict.
     * This is explicitly NOT a formal contradiction verdict — it is a hint
     * that the claim may be contradicted, but the exact consistency check
     * did not confirm it (design D10).
     *
     * Only applies to SUBCLASS and EQUIVALENT_CLASSES claims where the
     * old proxy (checkClassCompatibility) would have returned CONTRADICTED.
     */
    private EvidenceItem buildStructuralConflictHint(OWLOntology ontology, Claim claim, OntologyId ontId) {
        if (claim.type() != ClaimType.SUBCLASS && claim.type() != ClaimType.EQUIVALENT_CLASSES) {
            return null;
        }
        if (claim.subject() == null || claim.subject().iri() == null
            || claim.object() == null || claim.object().iri() == null) {
            return null;
        }
        // Skip proxy when subject or object entity is not in the ontology's
        // direct signature (prevents false hints on cross-ontology claims).
        if (!isEntityInOntology(ontology, claim.subject(), ontId)
            || !isEntityInOntology(ontology, claim.object(), ontId)) {
            return null;
        }
        try {
            ServiceResult<ClassCompatibilityResult> compatResult =
                consistencyService.checkClassCompatibility(
                    ontology, ontId, claim.subject().iri(), claim.object().iri());
            if (!compatResult.isSuccess()) {
                return null;
            }
            ClassCompatibilityResult compat =
                ((ServiceResult.Success<ClassCompatibilityResult>) compatResult).data();
            if (!ClassCompatibilityResult.DISJOINT.equals(compat.compatibility())
                && !ClassCompatibilityResult.UNSATISFIABLE_TOGETHER.equals(compat.compatibility())) {
                return null;
            }
            String reasonerName = compat.reasonerName() != null ? compat.reasonerName() : "default";
            return new EvidenceItem(
                evidenceId("structural-conflict-hint", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.STRUCTURAL_CONFLICT_HINT,
                claim.subject().iri() + " and " + claim.object().iri()
                    + " are structurally " + compat.compatibility()
                    + " — proxy hint only, not a formal contradiction.",
                "class-compatibility-proxy",
                reasonerName,
                "UNION",
                List.of(claim.subject().iri(), claim.object().iri()),
                EvidenceItem.CONFIDENCE_INFERRED
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── Result builders ──

    private ServiceResult<ClaimVerificationResult> buildResult(Claim claim, OntologyId ontId, Verdict verdict,
                                                                List<EvidenceItem> evidence,
                                                                Optional<UnknownReason> unknownReason,
                                                                Optional<String> unknownExplanation) {
        return buildResult(claim, ontId, verdict, evidence, unknownReason, unknownExplanation, null);
    }

    /**
     * v0.8.6 task 3.10b: Overload that accepts reasoner call metadata.
     * Used by {@code verifyWith5StageFlow} to propagate the LAST stage's
     * metadata into {@code ClaimVerificationResult.metadata}.
     */
    private ServiceResult<ClaimVerificationResult> buildResult(Claim claim, OntologyId ontId, Verdict verdict,
                                                                List<EvidenceItem> evidence,
                                                                Optional<UnknownReason> unknownReason,
                                                                Optional<String> unknownExplanation,
                                                                ReasonerCallMetadata metadata) {
        return ServiceResult.success(
            ClaimVerificationResult.completed(
                claim.claimId(),
                claim.ontologyId(),
                claim.type(),
                verdict,
                evidence,
                unknownReason,
                unknownExplanation,
                claim.reasoner(),
                claim.graphScope(),
                false,
                evidence.size(),
                metadata
            ),
            ResultMetadata.empty()
        );
    }

    private ServiceResult<ClaimVerificationResult> buildCompletedResult(
            Claim claim, OntologyId ontId, Verdict verdict,
            List<EvidenceItem> evidence,
            Optional<UnknownReason> unknownReason,
            Optional<String> unknownExplanation,
            PerStageTiming perStageTiming) {
        return buildCompletedResult(claim, ontId, verdict, evidence, unknownReason, unknownExplanation,
            perStageTiming, null);
    }

    /**
     * v0.8.6 task 3.10b: Overload that accepts reasoner call metadata.
     */
    private ServiceResult<ClaimVerificationResult> buildCompletedResult(
            Claim claim, OntologyId ontId, Verdict verdict,
            List<EvidenceItem> evidence,
            Optional<UnknownReason> unknownReason,
            Optional<String> unknownExplanation,
            PerStageTiming perStageTiming,
            ReasonerCallMetadata metadata) {
        return ServiceResult.success(
            new ClaimVerificationResult(
                claim.claimId(),
                claim.ontologyId(),
                claim.type(),
                Optional.of(verdict),
                evidence,
                unknownReason,
                unknownExplanation,
                claim.reasoner(),
                claim.graphScope(),
                false,
                evidence.size(),
                ExecutionStatus.COMPLETED,
                Optional.empty(),
                perStageTiming,
                metadata
            ),
            ResultMetadata.empty()
        );
    }

    private ServiceResult<ClaimVerificationResult> buildErroredResult(
            Claim claim, OntologyId ontId,
            ExecutionStatus executionStatus, ErrorCode errorCode,
            PerStageTiming perStageTiming) {
        return buildErroredResult(claim, ontId, executionStatus, errorCode, perStageTiming, null);
    }

    /**
     * v0.8.6 task 3.10b: Overload that accepts reasoner call metadata.
     */
    private ServiceResult<ClaimVerificationResult> buildErroredResult(
            Claim claim, OntologyId ontId,
            ExecutionStatus executionStatus, ErrorCode errorCode,
            PerStageTiming perStageTiming,
            ReasonerCallMetadata metadata) {
        return ServiceResult.success(
            ClaimVerificationResult.errored(
                claim.claimId(),
                claim.ontologyId(),
                claim.type(),
                executionStatus,
                errorCode,
                claim.reasoner(),
                claim.graphScope(),
                perStageTiming,
                metadata
            ),
            ResultMetadata.empty()
        );
    }

    // ── Timing helpers ──

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static long sumMs(Long... values) {
        long sum = 0;
        for (Long v : values) {
            if (v != null) sum += v;
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    private <T> ErrorCode extractErrorCode(ServiceResult<T> result) {
        if (result.isSuccess()) {
            return ErrorCode.INVALID_CLAIM_SCHEMA;
        }
        ServiceError error = ((ServiceResult.Error<T>) result).error();
        return error.code();
    }

    // ════════════════════════════════════════════════════════════════════
    // SPECIAL CLAIM TYPE HANDLERS (design D9)
    // ════════════════════════════════════════════════════════════════════

    // --- CLASS_COMPATIBILITY via class compatibility (isSatisfiable) ---

    private ServiceResult<ClaimVerificationResult> verifyClassCompatibility(OWLOntology ontology, Claim claim, OntologyId ontId) {
        ServiceResult<ClassCompatibilityResult> result =
            consistencyService.checkClassCompatibility(ontology, ontId, claim.subject().iri(), claim.object().iri());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        ClassCompatibilityResult compat = ((ServiceResult.Success<ClassCompatibilityResult>) result).data();
        Verdict verdict;
        if (ClassCompatibilityResult.COMPATIBLE.equals(compat.compatibility())) {
            verdict = Verdict.SUPPORTED;
        } else if (ClassCompatibilityResult.DISJOINT.equals(compat.compatibility())) {
            verdict = Verdict.CONTRADICTED;
        } else if (ClassCompatibilityResult.UNSATISFIABLE_TOGETHER.equals(compat.compatibility())) {
            verdict = Verdict.CONTRADICTED;
        } else {
            verdict = Verdict.UNKNOWN;
        }

        List<EvidenceItem> evidence = buildCompatibilityEvidence(claim, compat, verdict);
        return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
    }

    private List<EvidenceItem> buildCompatibilityEvidence(Claim claim, ClassCompatibilityResult compat, Verdict verdict) {
        List<EvidenceItem> items = new ArrayList<>();

        if (verdict == Verdict.SUPPORTED || verdict == Verdict.CONTRADICTED) {
            items.add(new EvidenceItem(
                evidenceId("compatibility", claim.claimId()),
                verdict == Verdict.SUPPORTED ? EvidenceItem.ROLE_SUPPORTING : EvidenceItem.ROLE_COUNTER,
                compat.reasonerName() != null ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
                compat.class1IRI() + " and " + compat.class2IRI() + " → " + compat.compatibility(),
                "class-compatibility-check",
                compat.reasonerName() != null ? compat.reasonerName() : "default",
                "UNION",
                List.of(compat.class1IRI(), compat.class2IRI()),
                verdict == Verdict.SUPPORTED ? EvidenceItem.CONFIDENCE_ENTAILED : EvidenceItem.CONFIDENCE_INFERRED
            ));
        }

        if (verdict == Verdict.UNKNOWN) {
            items.add(new EvidenceItem(
                evidenceId("compatibility-unknown", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                "Class compatibility result: " + compat.compatibility(),
                "class-compatibility-check",
                compat.reasonerName() != null ? compat.reasonerName() : "default",
                "UNION",
                List.of(compat.class1IRI(), compat.class2IRI()),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }

        return items;
    }

    // --- LITERAL_VALIDITY via literal validation ---

    private ServiceResult<ClaimVerificationResult> verifyLiteralValidity(Claim claim, OntologyId ontId) {
        if (claim.subject() == null || claim.object() == null) {
            return buildResult(claim, ontId, Verdict.UNKNOWN, List.of(),
                Optional.of(UnknownReason.INSUFFICIENT_AXIOMS),
                Optional.of("literal_validity requires both a datatype (subject) and a literal (object)"));
        }
        Optional<String> propertyIRI = claim.predicate() != null && !claim.predicate().isBlank()
            ? Optional.of(claim.predicate()) : Optional.empty();

        ServiceResult<LiteralValidationResult> result =
            deepeningService.validateLiteral(ontId, claim.object().iri(), claim.subject().iri(), propertyIRI);

        if (!result.isSuccess()) {
            return mapError(result);
        }

        LiteralValidationResult validation = ((ServiceResult.Success<LiteralValidationResult>) result).data();
        Verdict verdict;
        if (validation.valid()) {
            verdict = Verdict.SUPPORTED;
        } else {
            verdict = Verdict.CONTRADICTED;
        }

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(new EvidenceItem(
            evidenceId("literal-validation", claim.claimId()),
            verdict == Verdict.SUPPORTED ? EvidenceItem.ROLE_SUPPORTING : EvidenceItem.ROLE_COUNTER,
            EvidenceKind.LITERAL_VALIDATION,
            validation.literalValue() + " against " + validation.datatypeIRI() + ": valid=" + validation.valid(),
            "literal-validation",
            "default",
            "EXPLICIT",
            List.of(validation.datatypeIRI()),
            EvidenceItem.CONFIDENCE_EXPLICIT
        ));

        if (!validation.valid() && !validation.violations().isEmpty()) {
            for (String violation : validation.violations()) {
                evidence.add(new EvidenceItem(
                    evidenceId("literal-violation", claim.claimId()),
                    EvidenceItem.ROLE_COUNTER,
                    EvidenceKind.LITERAL_VALIDATION,
                    violation,
                    "literal-validation",
                    "default",
                    "EXPLICIT",
                    List.of(validation.datatypeIRI()),
                    EvidenceItem.CONFIDENCE_EXPLICIT
                ));
            }
        }

        return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
    }

    // --- ONTOLOGY_CONSISTENCY via reasoner consistency check (bypasses stage 2) ---

    private ServiceResult<ClaimVerificationResult> verifyOntologyConsistency(Claim claim, OntologyId ontId) {
        ServiceResult<ConsistencyResult> result =
            reasonerService.checkConsistency(ontId, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        ConsistencyResult consistency = ((ServiceResult.Success<ConsistencyResult>) result).data();
        Verdict verdict;
        if (consistency.consistent()) {
            verdict = Verdict.SUPPORTED;
        } else {
            verdict = Verdict.CONTRADICTED;
        }

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(new EvidenceItem(
            evidenceId("consistency", claim.claimId()),
            verdict == Verdict.SUPPORTED ? EvidenceItem.ROLE_SUPPORTING : EvidenceItem.ROLE_COUNTER,
            EvidenceKind.REASONING_REPORT,
            "Ontology consistent=" + consistency.consistent()
                + (consistency.unsatisfiableClassIRIs() != null && !consistency.unsatisfiableClassIRIs().isEmpty()
                    ? ", unsatisfiable: " + consistency.unsatisfiableClassIRIs() : ""),
            "consistency-check",
            consistency.reasonerName() != null ? consistency.reasonerName() : "default",
            "INFERRED",
            consistency.unsatisfiableClassIRIs() != null ? consistency.unsatisfiableClassIRIs() : List.of(),
            EvidenceItem.CONFIDENCE_INFERRED
        ));

        return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
    }

    // --- ONTOLOGY_SCOPE via scope description ---

    private ServiceResult<ClaimVerificationResult> verifyOntologyScope(OWLOntology ontology, Claim claim, OntologyId ontId) {
        if (claim.subject() != null && claim.subject().iri() != null
            && !isEntityInOntology(ontology, claim.subject(), ontId)) {
            return buildResult(claim, ontId, Verdict.OUT_OF_SCOPE, List.of(),
                Optional.of(UnknownReason.MISSING_ENTITY),
                Optional.of("Subject entity " + claim.subject().iri()
                    + " is not declared in ontology '" + claim.ontologyId() + "'"));
        }
        if (claim.object() != null && claim.object().iri() != null
            && !isEntityInOntology(ontology, claim.object(), ontId)) {
            return buildResult(claim, ontId, Verdict.OUT_OF_SCOPE, List.of(),
                Optional.of(UnknownReason.MISSING_ENTITY),
                Optional.of("Object entity " + claim.object().iri()
                    + " is not declared in ontology '" + claim.ontologyId() + "'"));
        }

        ServiceResult<ScopeDescription> result = consistencyService.getScope(ontId);

        if (!result.isSuccess()) {
            return mapError(result);
        }

        ScopeDescription scope = ((ServiceResult.Success<ScopeDescription>) result).data();
        List<EvidenceItem> evidence = new ArrayList<>();

        evidence.add(new EvidenceItem(
            evidenceId("scope", claim.claimId()),
            EvidenceItem.ROLE_SUPPORTING,
            EvidenceKind.SCOPE_STATEMENT,
            "Domains: " + scope.coveredDomains()
                + ", gaps: " + scope.knownGaps()
                + ", limitations: " + scope.profileLimitations(),
            "scope-description",
            "default",
            "EXPLICIT",
            List.of(),
            EvidenceItem.CONFIDENCE_EXPLICIT
        ));

        return buildResult(claim, ontId, Verdict.SUPPORTED, evidence, Optional.empty(), Optional.empty());
    }

    // ════════════════════════════════════════════════════════════════════
    // SCOPE PRE-CHECK (Stage 1)
    // ════════════════════════════════════════════════════════════════════

    private boolean isEntityInOntology(OWLOntology ontology, ClaimEntity entity, OntologyId ontId) {
        if (entity == null) return false;
        if (entity.iri() == null || entity.iri().isBlank()) {
            return entity.expression() != null;
        }
        if ("literal".equals(entity.kind())) return true;
        if (isInBuiltinNamespace(entity.iri())) {
            return true;
        }
        return consistencyService.isEntityDeclared(ontology, ontId, entity.iri(), entity.kind());
    }

    private static boolean isInBuiltinNamespace(String iri) {
        if (iri == null) return false;
        return iri.startsWith("http://www.w3.org/2001/XMLSchema#")
            || iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
            || iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")
            || iri.startsWith("http://www.w3.org/2002/07/owl#");
    }

    private static boolean isExemptFromScopePrecheck(ClaimType type) {
        return type == ClaimType.ONTOLOGY_SCOPE
            || type == ClaimType.ONTOLOGY_CONSISTENCY
            || type == ClaimType.LITERAL_VALIDITY;
    }

    private ServiceResult<ClaimVerificationResult> applyScopePrecheck(OWLOntology ontology, Claim claim, OntologyId ontId) {
        if (claim.subject() == null) {
            return null;
        }
        boolean subjectInScope = isEntityInOntology(ontology, claim.subject(), ontId);
        boolean objectInScope = claim.object() == null || isEntityInOntology(ontology, claim.object(), ontId);
        if (subjectInScope && objectInScope) {
            return null;
        }

        StringBuilder sb = new StringBuilder("Subject or object is not declared in ontology '")
            .append(claim.ontologyId()).append("'.");
        if (!subjectInScope) {
            sb.append(" Offending subject: ").append(claim.subject().iri());
        }
        if (!objectInScope) {
            sb.append(" Offending object: ").append(claim.object().iri());
        }
        return buildResult(claim, ontId, Verdict.OUT_OF_SCOPE, List.of(),
            Optional.of(UnknownReason.MISSING_ENTITY), Optional.of(sb.toString()));
    }

    // ════════════════════════════════════════════════════════════════════
    // MISC HELPERS
    // ════════════════════════════════════════════════════════════════════

    private String evidenceId(String kind, String claimId) {
        return kind + "-" + claimId;
    }

    @SuppressWarnings("unchecked")
    private <T> ServiceResult<ClaimVerificationResult> mapError(ServiceResult<T> errorResult) {
        ServiceError error = ((ServiceResult.Error<T>) errorResult).error();
        if (error.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
            String ontIdStr = error.details() != null && error.details().get("ontologyId") != null
                ? (String) error.details().get("ontologyId") : null;
            if (ontIdStr == null || ontIdStr.isBlank()) {
                return ServiceResult.error(ServiceError.of(ErrorCode.ONTOLOGY_NOT_FOUND,
                    "Ontology not found (ID unavailable from error details)"));
            }
            return ServiceResult.error(ServiceError.ontologyNotFound(new OntologyId(ontIdStr)));
        }
        if (error.code() == ErrorCode.CLASS_NOT_FOUND
            || error.code() == ErrorCode.PROPERTY_NOT_FOUND
            || error.code() == ErrorCode.INDIVIDUAL_NOT_FOUND
            || error.code() == ErrorCode.DATATYPE_NOT_FOUND) {
            String entityIRI = error.details() != null && error.details().get("entityIRI") != null
                ? (String) error.details().get("entityIRI") : null;
            if (entityIRI == null || entityIRI.isBlank()) {
                return ServiceResult.error(ServiceError.of(error.code(),
                    "Entity not found (IRI unavailable from error details)"));
            }
            return ServiceResult.error(ServiceError.of(error.code(),
                "Entity not found: " + entityIRI));
        }
        return ServiceResult.error(error);
    }
}
