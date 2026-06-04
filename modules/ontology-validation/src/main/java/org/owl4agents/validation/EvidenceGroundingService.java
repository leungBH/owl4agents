package org.owl4agents.validation;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.owl4agents.reasoner.ReasonerService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evidence grounding service for v0.3 claim verification.
 * Assembles evidence paths, counterexamples, unknown explanations,
 * missing entity detection, and evidence truncation.
 *
 * Used by both CLI and MCP to provide detailed grounding after
 * ClaimVerificationService produces a verdict.
 */
public class EvidenceGroundingService {

    private final ReasonerService reasonerService;
    private final ConsistencyAnalysisService consistencyService;

    public EvidenceGroundingService(ReasonerService reasonerService,
                                     ConsistencyAnalysisService consistencyService) {
        this.reasonerService = reasonerService;
        this.consistencyService = consistencyService;
    }

    // ── Task 4.2: Evidence path assembly for supported claims ──

    /**
     * Assemble a full evidence path for a claim based on the verification result.
     * Enriches the basic evidence from ClaimVerificationResult with inferred facts
     * and reasoning report data from the reasoner.
     */
    public ServiceResult<EvidencePath> getEvidencePath(Claim claim, ClaimVerificationResult verificationResult) {
        if (claim == null || verificationResult == null) {
            return ServiceResult.error(ErrorCode.INVALID_CLAIM_SCHEMA, "Claim and verification result must not be null.");
        }

        // Evidence paths are only meaningful for supported and contradicted claims.
        // For unknown and out_of_scope verdicts, return EVIDENCE_NOT_AVAILABLE.
        if (verificationResult.verdict() == Verdict.UNKNOWN || verificationResult.verdict() == Verdict.OUT_OF_SCOPE) {
            return ServiceResult.error(ErrorCode.EVIDENCE_NOT_AVAILABLE,
                "Evidence paths are not available for " + verificationResult.verdict().jsonName()
                + " verdict claims. Verdict: " + verificationResult.verdict().jsonName());
        }

        OntologyId ontId = new OntologyId(claim.ontologyId());
        List<EvidenceItem> items = new ArrayList<>(verificationResult.evidence());

        // Enrich with entity-specific inferred facts for supported claims
        if (verificationResult.verdict() == Verdict.SUPPORTED) {
            enrichWithInferredFacts(claim, ontId, items);
        }

        // Add reasoning report as provenance evidence
        enrichWithReasoningReport(claim, ontId, items);

        // Task 4.6: Evidence truncation
        int totalAvailable = items.size();
        boolean truncated = false;
        int maxEvidence = getMaxEvidence(claim);
        if (maxEvidence > 0 && items.size() > maxEvidence) {
            items = items.subList(0, maxEvidence);
            truncated = true;
        }

        EvidencePath path = new EvidencePath(
            claim.claimId(),
            claim.ontologyId(),
            items,
            verificationResult.reasonerName(),
            verificationResult.graphScope(),
            truncated,
            totalAvailable
        );

        return ServiceResult.success(path, ResultMetadata.empty());
    }

    private void enrichWithInferredFacts(Claim claim, OntologyId ontId, List<EvidenceItem> items) {
        // Collect entity IRIs to query inferred facts for
        Set<String> entityIRIs = new LinkedHashSet<>();
        if (claim.subject() != null) {
            entityIRIs.add(claim.subject().iri());
        }
        if (claim.object() != null) {
            entityIRIs.add(claim.object().iri());
        }
        if (claim.predicate() != null && !claim.predicate().isBlank()) {
            entityIRIs.add(claim.predicate());
        }

        for (String entityIRI : entityIRIs) {
            ServiceResult<InferredFactsResult> factsResult =
                reasonerService.getInferredFacts(ontId, Optional.of(entityIRI));
            if (factsResult.isSuccess()) {
                InferredFactsResult factsData = ((ServiceResult.Success<InferredFactsResult>) factsResult).data();
                for (InferredFact fact : factsData.facts()) {
                    items.add(new EvidenceItem(
                        "inferred-fact-" + fact.subjectIRI() + "-" + fact.predicateIRI(),
                        EvidenceItem.ROLE_SUPPORTING,
                        EvidenceKind.INFERRED_TRIPLE,
                        fact.subjectIRI() + " " + fact.predicateIRI() + " " + (fact.objectIRI() != null ? fact.objectIRI() : fact.literalValue()),
                        fact.source() != null ? fact.source() : "reasoner",
                        fact.reasoner() != null ? fact.reasoner() : "default",
                        "INFERRED",
                        List.of(fact.subjectIRI(), fact.predicateIRI(), fact.objectIRI() != null ? fact.objectIRI() : ""),
                        EvidenceItem.CONFIDENCE_INFERRED
                    ));
                }
            }
        }
    }

    private void enrichWithReasoningReport(Claim claim, OntologyId ontId, List<EvidenceItem> items) {
        ServiceResult<ReasoningReport> reportResult = reasonerService.getReasoningReport(ontId);
        if (reportResult.isSuccess()) {
            ReasoningReport report = ((ServiceResult.Success<ReasoningReport>) reportResult).data();
            items.add(new EvidenceItem(
                "reasoning-report-" + claim.claimId(),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                "Reasoner: " + report.reasonerName() + ", profile: " + report.owlProfile()
                    + ", consistent: " + report.consistencyStatus()
                    + ", inferred axiom types: " + report.inferredAxiomCountsByType().keySet(),
                "reasoning-report",
                report.reasonerName(),
                "UNION",
                List.of(),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }
    }

    private int getMaxEvidence(Claim claim) {
        if (claim.options().isPresent()) {
            Object maxEvidence = claim.options().get().get(Claim.MAX_EVIDENCE);
            if (maxEvidence instanceof Number) {
                return ((Number) maxEvidence).intValue();
            }
        }
        return 0; // 0 means no truncation limit
    }

    // ── Task 4.3: Counterexample search for contradicted claims ──

    /**
     * Search for counterexamples that contradict the given claim.
     * Returns counterexample evidence items explaining why the claim is contradicted.
     */
    public ServiceResult<List<EvidenceItem>> findCounterexamples(Claim claim, ClaimVerificationResult verificationResult) {
        if (claim == null || verificationResult == null) {
            return ServiceResult.error(ErrorCode.INVALID_CLAIM_SCHEMA, "Claim and verification result must not be null.");
        }

        if (verificationResult.verdict() != Verdict.CONTRADICTED) {
            return ServiceResult.error(ErrorCode.EVIDENCE_NOT_AVAILABLE,
                "Counterexamples are only available for contradicted claims. Verdict: " + verificationResult.verdict().jsonName());
        }

        List<EvidenceItem> counterexamples = new ArrayList<>();

        // Collect counter-evidence items already present in the verification result
        for (EvidenceItem item : verificationResult.evidence()) {
            if (EvidenceItem.ROLE_COUNTER.equals(item.role())) {
                counterexamples.add(item);
            }
        }

        // For specific claim types, search for direct counterexamples
        OntologyId ontId = new OntologyId(claim.ontologyId());
        switch (claim.type()) {
            case DISJOINT_CLASSES, CLASS_COMPATIBILITY -> {
                // For contradicted compatibility/disjoint claims, find explicit evidence
                // that the classes are compatible (or disjoint, depending on claim direction)
                collectCompatibilityCounterexamples(claim, ontId, counterexamples);
            }
            case ONTOLOGY_CONSISTENCY -> {
                // For inconsistent ontology claims, find unsatisfiable classes
                collectInconsistencyCounterexamples(ontId, counterexamples);
            }
            case LITERAL_VALIDITY -> {
                // Literal validity violations are already captured as counter evidence
            }
            default -> {
                // Other claim types: counterexamples come from verification evidence
            }
        }

        // Apply truncation for counterexamples
        int maxEvidence = getMaxEvidence(claim);
        if (maxEvidence > 0 && counterexamples.size() > maxEvidence) {
            counterexamples = counterexamples.subList(0, maxEvidence);
        }

        if (counterexamples.isEmpty()) {
            return ServiceResult.error(ErrorCode.EVIDENCE_NOT_AVAILABLE,
                "No counterexamples could be assembled for this contradicted claim.");
        }

        return ServiceResult.success(counterexamples, ResultMetadata.empty());
    }

    private void collectCompatibilityCounterexamples(Claim claim, OntologyId ontId, List<EvidenceItem> counterexamples) {
        // For contradicted disjoint/compatibility claims, check what the actual relation is
        String class1IRI = claim.subject().iri();
        String class2IRI = claim.object().iri();

        ServiceResult<ClassCompatibilityResult> compatResult =
            consistencyService.checkClassCompatibility(ontId, class1IRI, class2IRI);

        if (compatResult.isSuccess()) {
            ClassCompatibilityResult compat = ((ServiceResult.Success<ClassCompatibilityResult>) compatResult).data();
            counterexamples.add(new EvidenceItem(
                "compatibility-counterexample-" + claim.claimId(),
                EvidenceItem.ROLE_COUNTER,
                EvidenceKind.COUNTEREXAMPLE,
                class1IRI + " and " + class2IRI + " are " + compat.compatibility()
                    + " (contradicts claim of " + claim.type().jsonName() + ")",
                "class-compatibility-check",
                compat.reasonerName() != null ? compat.reasonerName() : "default",
                "INFERRED",
                List.of(class1IRI, class2IRI),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }
    }

    private void collectInconsistencyCounterexamples(OntologyId ontId, List<EvidenceItem> counterexamples) {
        ServiceResult<List<String>> unsatResult = reasonerService.getUnsatClasses(ontId);
        if (unsatResult.isSuccess()) {
            List<String> unsatClasses = ((ServiceResult.Success<List<String>>) unsatResult).data();
            if (!unsatClasses.isEmpty()) {
                counterexamples.add(new EvidenceItem(
                    "unsat-classes-counterexample",
                    EvidenceItem.ROLE_COUNTER,
                    EvidenceKind.COUNTEREXAMPLE,
                    "Unsatisfiable classes found: " + unsatClasses.stream().collect(Collectors.joining(", ")),
                    "consistency-check",
                    "default",
                    "INFERRED",
                    unsatClasses,
                    EvidenceItem.CONFIDENCE_INFERRED
                ));
            }
        }
    }

    // ── Task 4.4: Unknown explanation with reason categories ──

    /**
     * Provide a detailed explanation for why a claim received an unknown verdict.
     * Returns an UnknownExplanation with reason category, relevant entities, and suggested actions.
     */
    public ServiceResult<UnknownExplanation> explainUnknown(Claim claim, ClaimVerificationResult verificationResult) {
        if (claim == null || verificationResult == null) {
            return ServiceResult.error(ErrorCode.INVALID_CLAIM_SCHEMA, "Claim and verification result must not be null.");
        }

        if (verificationResult.verdict() != Verdict.UNKNOWN) {
            return ServiceResult.error(ErrorCode.EVIDENCE_NOT_AVAILABLE,
                "Unknown explanations are only available for unknown verdicts. Verdict: " + verificationResult.verdict().jsonName());
        }

        UnknownReason reason = verificationResult.unknownReason().orElse(UnknownReason.INSUFFICIENT_AXIOMS);

        // Collect relevant entities from the claim
        List<String> relevantEntities = new ArrayList<>();
        if (claim.subject() != null) {
            relevantEntities.add(claim.subject().iri());
        }
        if (claim.object() != null) {
            relevantEntities.add(claim.object().iri());
        }
        if (claim.predicate() != null && !claim.predicate().isBlank()) {
            relevantEntities.add(claim.predicate());
        }

        // Build explanation text based on reason category
        String explanation = buildExplanationText(reason, claim);
        String suggestedAction = buildSuggestedAction(reason);

        UnknownExplanation unknownExplanation = new UnknownExplanation(
            claim.claimId(),
            claim.ontologyId(),
            reason,
            Optional.of(explanation),
            relevantEntities,
            Optional.of(suggestedAction)
        );

        return ServiceResult.success(unknownExplanation, ResultMetadata.empty());
    }

    private String buildExplanationText(UnknownReason reason, Claim claim) {
        return switch (reason) {
            case INSUFFICIENT_AXIOMS -> "The ontology does not contain sufficient axioms to verify the claim about "
                + entityDescription(claim) + ". No entailment was found, but absence of entailment does not constitute contradiction.";
            case MISSING_REASONING -> "Reasoning has not been run on the ontology. Run reasoning first to enable inferred axiom checks for "
                + entityDescription(claim) + ".";
            case UNSUPPORTED_PROFILE -> "The ontology's OWL profile is not supported by the available reasoner, preventing verification of "
                + entityDescription(claim) + ".";
            case UNSUPPORTED_CLAIM_TYPE -> "The claim type '" + claim.type().jsonName() + "' is not supported by the current verification pipeline.";
            case AMBIGUOUS_ENTITY -> "One or more entities in the claim match multiple ontology terms, making verification ambiguous for "
                + entityDescription(claim) + ".";
            case MISSING_ENTITY -> "One or more entities referenced in the claim do not exist in the ontology: "
                + entityDescription(claim) + ".";
            case SCOPE_UNAVAILABLE -> "The ontology scope description is unavailable, preventing scope-based verification.";
            case EVIDENCE_UNAVAILABLE -> "Evidence required for verification could not be assembled from the reasoner or ontology.";
        };
    }

    private String buildSuggestedAction(UnknownReason reason) {
        return switch (reason) {
            case INSUFFICIENT_AXIOMS -> "Add more axioms to the ontology or refine the claim to reference existing entities.";
            case MISSING_REASONING -> "Run the reasoner on this ontology before attempting claim verification.";
            case UNSUPPORTED_PROFILE -> "Use an ontology within a supported OWL profile (OWL 2 DL, EL, or QL).";
            case UNSUPPORTED_CLAIM_TYPE -> "Use one of the supported claim types: subclass, equivalent_classes, disjoint_classes, etc.";
            case AMBIGUOUS_ENTITY -> "Use the full IRI instead of a label or fragment to resolve entity ambiguity.";
            case MISSING_ENTITY -> "Verify entity IRIs exist in the ontology using missing-entities detection.";
            case SCOPE_UNAVAILABLE -> "Ensure the ontology is loaded and accessible before requesting scope verification.";
            case EVIDENCE_UNAVAILABLE -> "Re-run reasoning and try verification again.";
        };
    }

    private String entityDescription(Claim claim) {
        StringBuilder sb = new StringBuilder();
        if (claim.subject() != null) {
            sb.append(claim.subject().iri());
        }
        if (claim.predicate() != null && !claim.predicate().isBlank()) {
            sb.append(" ").append(claim.predicate());
        }
        if (claim.object() != null) {
            sb.append(" → ").append(claim.object().iri());
        }
        return sb.toString();
    }

    // ── Task 4.5: Missing entity detection ──

    /**
     * Detect missing, ambiguous, matched, and out-of-scope entities
     * referenced in a claim against the ontology.
     * Uses the existing MissingEntityResult model.
     */
    public ServiceResult<MissingEntityResult> detectMissingEntities(Claim claim) {
        if (claim == null) {
            return ServiceResult.error(ErrorCode.INVALID_CLAIM_SCHEMA, "Claim must not be null.");
        }

        OntologyId ontId = new OntologyId(claim.ontologyId());
        List<MissingEntityResult.EntityMatch> matched = new ArrayList<>();
        List<MissingEntityResult.EntityMatch> ambiguous = new ArrayList<>();
        List<MissingEntityResult.EntityMatch> missing = new ArrayList<>();
        List<MissingEntityResult.EntityMatch> outOfScope = new ArrayList<>();

        // Check each entity in the claim against the ontology
        checkEntity(claim.subject(), ontId, matched, ambiguous, missing, outOfScope);
        checkEntity(claim.object(), ontId, matched, ambiguous, missing, outOfScope);

        // Check predicate if it looks like an entity IRI (not a reserved keyword)
        if (claim.predicate() != null && !claim.predicate().isBlank()
            && !claim.predicate().startsWith("http://www.w3.org/2002/07/owl#")) {
            // Treat predicate as a property entity
            MissingEntityResult.EntityMatch predicateMatch = checkPropertyEntity(claim.predicate(), ontId);
            classifyMatch(predicateMatch, matched, ambiguous, missing, outOfScope);
        }

        MissingEntityResult result = new MissingEntityResult(
            claim.ontologyId(),
            matched,
            ambiguous,
            missing,
            outOfScope
        );

        return ServiceResult.success(result, ResultMetadata.empty());
    }

    private void checkEntity(ClaimEntity entity, OntologyId ontId,
                              List<MissingEntityResult.EntityMatch> matched,
                              List<MissingEntityResult.EntityMatch> ambiguous,
                              List<MissingEntityResult.EntityMatch> missing,
                              List<MissingEntityResult.EntityMatch> outOfScope) {
        if (entity == null) return;

        // Literal entities are always valid (no IRI matching needed)
        if ("literal".equals(entity.kind())) {
            matched.add(new MissingEntityResult.EntityMatch(
                entity.iri(), Optional.of(entity.iri()), Optional.of("literal"), Optional.empty()));
            return;
        }

        MissingEntityResult.EntityMatch entityMatch = matchEntityByKind(entity, ontId);
        classifyMatch(entityMatch, matched, ambiguous, missing, outOfScope);
    }

    private MissingEntityResult.EntityMatch matchEntityByKind(ClaimEntity entity, OntologyId ontId) {
        String searchTerm = entity.iri();
        String kind = entity.kind();

        // First, check whether the entity is declared in the ontology's signature
        // (via ConsistencyAnalysisService). This handles entities that exist but have
        // no inferred facts because no reasoning has been run yet.
        if (consistencyService.isEntityDeclared(ontId, searchTerm, kind)) {
            return new MissingEntityResult.EntityMatch(
                searchTerm, Optional.of(searchTerm), Optional.of(kind), Optional.empty());
        }

        // Fall back to reasoner inferred facts: an entity that participates in any
        // inferred triple must exist in the ontology.
        ServiceResult<InferredFactsResult> factsResult =
            reasonerService.getInferredFacts(ontId, Optional.of(searchTerm));

        if (factsResult.isSuccess()) {
            InferredFactsResult facts = ((ServiceResult.Success<InferredFactsResult>) factsResult).data();
            if (!facts.facts().isEmpty()) {
                // Entity has inferred facts → it exists in the ontology
                return new MissingEntityResult.EntityMatch(
                    searchTerm, Optional.of(searchTerm), Optional.of(kind), Optional.empty());
            }
        }

        // If facts result is an error (e.g., REASONING_NOT_RUN), classify accordingly
        if (!factsResult.isSuccess()) {
            ServiceError error = ((ServiceResult.Error<InferredFactsResult>) factsResult).error();
            if (error.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
                return new MissingEntityResult.EntityMatch(
                    searchTerm, Optional.empty(), Optional.of(kind), Optional.empty());
            }
        }

        // No facts found — could be missing or ambiguous
        // For now, classify as missing (simple classification)
        return new MissingEntityResult.EntityMatch(
            searchTerm, Optional.empty(), Optional.of(kind), Optional.empty());
    }

    private MissingEntityResult.EntityMatch checkPropertyEntity(String propertyIRI, OntologyId ontId) {
        // First check the ontology signature directly — covers properties that exist
        // but have not participated in any reasoning yet.
        if (consistencyService.isEntityDeclared(ontId, propertyIRI, "property")) {
            return new MissingEntityResult.EntityMatch(
                propertyIRI, Optional.of(propertyIRI), Optional.of("property"), Optional.empty());
        }

        ServiceResult<InferredFactsResult> factsResult =
            reasonerService.getInferredFacts(ontId, Optional.of(propertyIRI));

        if (factsResult.isSuccess()) {
            InferredFactsResult facts = ((ServiceResult.Success<InferredFactsResult>) factsResult).data();
            if (!facts.facts().isEmpty()) {
                return new MissingEntityResult.EntityMatch(
                    propertyIRI, Optional.of(propertyIRI), Optional.of("property"), Optional.empty());
            }
        }

        return new MissingEntityResult.EntityMatch(
            propertyIRI, Optional.empty(), Optional.of("property"), Optional.empty());
    }

    private void classifyMatch(MissingEntityResult.EntityMatch match,
                               List<MissingEntityResult.EntityMatch> matched,
                               List<MissingEntityResult.EntityMatch> ambiguous,
                               List<MissingEntityResult.EntityMatch> missing,
                               List<MissingEntityResult.EntityMatch> outOfScope) {
        if (match.matchedIRI().isPresent()) {
            matched.add(match);
        } else {
            // IRI not found in ontology — classify as missing
            missing.add(match);
        }
    }
}