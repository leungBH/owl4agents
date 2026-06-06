package org.owl4agents.validation;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.owl4agents.reasoner.ReasonerService;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.storage.CatalogStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared claim verification service used by both CLI and MCP.
 * Verifies structured claims against an ontology by delegating to v0.2
 * entailment, membership, compatibility, consistency, scope, and literal
 * validation services, then converting results to v0.3 verdicts with
 * evidence items.
 *
 * Verdict resolution order: out_of_scope → supported → contradicted → unknown.
 * Lack of entailment alone yields UNKNOWN, never CONTRADICTED.
 */
public class ClaimVerificationService {

    private final ReasonerService reasonerService;
    private final ConsistencyAnalysisService consistencyService;
    private final SemanticDeepeningService deepeningService;
    private final CatalogStore catalogStore;
    private final WorkspaceId defaultWorkspaceId;

    public ClaimVerificationService(ReasonerService reasonerService,
                                     ConsistencyAnalysisService consistencyService,
                                     SemanticDeepeningService deepeningService,
                                     CatalogStore catalogStore,
                                     WorkspaceId workspaceId) {
        this.reasonerService = reasonerService;
        this.consistencyService = consistencyService;
        this.deepeningService = deepeningService;
        this.catalogStore = catalogStore;
        this.defaultWorkspaceId = workspaceId;
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

        // Check that the ontology exists in the catalog before proceeding
        ServiceResult<org.owl4agents.core.model.CatalogEntry> catalogResult =
            catalogStore.findEntry(defaultWorkspaceId, ontId);
        if (!catalogResult.isSuccess()) {
            ServiceError catalogError = ((ServiceResult.Error<org.owl4agents.core.model.CatalogEntry>) catalogResult).error();
            if (catalogError.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
                return ServiceResult.error(ServiceError.ontologyNotFound(ontId));
            }
            return mapError(catalogResult);
        }

        return switch (claim.type()) {
            case SUBCLASS, EQUIVALENT_CLASSES,
                 OBJECT_PROPERTY_DOMAIN, OBJECT_PROPERTY_RANGE,
                 DATA_PROPERTY_DOMAIN -> verifyEntailmentClaim(claim, ontId);
            case DISJOINT_CLASSES -> verifyDisjointClasses(claim, ontId);
            case INDIVIDUAL_MEMBERSHIP -> verifyIndividualMembership(claim, ontId);
            case OBJECT_PROPERTY_ASSERTION -> verifyObjectPropertyAssertion(claim, ontId);
            case DATA_PROPERTY_ASSERTION -> verifyDataPropertyAssertion(claim, ontId);
            case DATA_PROPERTY_RANGE -> verifyDataPropertyRange(claim, ontId);
            case LITERAL_VALIDITY -> verifyLiteralValidity(claim, ontId);
            case CLASS_COMPATIBILITY -> verifyClassCompatibility(claim, ontId);
            case ONTOLOGY_CONSISTENCY -> verifyOntologyConsistency(claim, ontId);
            case ONTOLOGY_SCOPE -> verifyOntologyScope(claim, ontId);
        };
    }

    // --- Entailment-based claims: SUBCLASS, EQUIVALENT_CLASSES, domain/range ---

    private ServiceResult<ClaimVerificationResult> verifyEntailmentClaim(Claim claim, OntologyId ontId) {
        String axiomType = entailmentAxiomType(claim.type());
        Map<String, String> params = buildEntailmentParams(claim);

        ServiceResult<EntailmentResult> result =
            reasonerService.checkEntailment(ontId, axiomType, params, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) result).data();
        Verdict verdict = mapEntailmentVerdict(entailment.result());
        List<EvidenceItem> evidence = buildEntailmentEvidence(claim, entailment, verdict);

        return buildResult(claim, ontId, verdict, evidence,
            entailment.result().equals(EntailmentResult.UNSUPPORTED_AXIOM_TYPE)
                ? Optional.of(UnknownReason.UNSUPPORTED_CLAIM_TYPE)
                : (verdict == Verdict.UNKNOWN ? Optional.of(UnknownReason.INSUFFICIENT_AXIOMS) : Optional.empty()),
            Optional.empty());
    }

    private String entailmentAxiomType(ClaimType type) {
        return switch (type) {
            case SUBCLASS -> "SubClassOf";
            case EQUIVALENT_CLASSES -> "EquivalentClasses";
            case OBJECT_PROPERTY_DOMAIN -> "ObjectPropertyDomain";
            case OBJECT_PROPERTY_RANGE -> "ObjectPropertyRange";
            case DATA_PROPERTY_DOMAIN -> "DataPropertyDomain";
            default -> throw new IllegalStateException("Unexpected entailment claim type: " + type);
        };
    }

    private Map<String, String> buildEntailmentParams(Claim claim) {
        Map<String, String> params = new LinkedHashMap<>();
        if (claim.subject() != null) {
            // Use axiom-type-specific parameter keys expected by ReasonerServiceImpl
            switch (claim.type()) {
                case SUBCLASS -> {
                    params.put("subclass", claim.subject().iri());
                    if (claim.object() != null) params.put("superclass", claim.object().iri());
                }
                case EQUIVALENT_CLASSES -> {
                    params.put("class1", claim.subject().iri());
                    if (claim.object() != null) params.put("class2", claim.object().iri());
                }
                case OBJECT_PROPERTY_DOMAIN -> {
                    params.put("propertyIRI", claim.subject().iri());
                    if (claim.object() != null) params.put("domainIRI", claim.object().iri());
                }
                case OBJECT_PROPERTY_RANGE -> {
                    params.put("propertyIRI", claim.subject().iri());
                    if (claim.object() != null) params.put("rangeIRI", claim.object().iri());
                }
                case DATA_PROPERTY_DOMAIN -> {
                    params.put("propertyIRI", claim.subject().iri());
                    if (claim.object() != null) params.put("domainIRI", claim.object().iri());
                }
                default -> {
                    params.put("subjectIRI", claim.subject().iri());
                    if (claim.object() != null) params.put("objectIRI", claim.object().iri());
                }
            }
        }
        if (claim.predicate() != null && !claim.predicate().isBlank() && !params.containsKey("propertyIRI")) {
            params.put("propertyIRI", claim.predicate());
        }
        return params;
    }

    private Verdict mapEntailmentVerdict(String entailmentResult) {
        if (EntailmentResult.ENTAILED.equals(entailmentResult)) {
            return Verdict.SUPPORTED;
        }
        if (EntailmentResult.NOT_ENTAILED.equals(entailmentResult)) {
            // Lack of entailment → unknown, not contradicted (task 3.9)
            return Verdict.UNKNOWN;
        }
        // UNSUPPORTED_AXIOM_TYPE → unknown with reason
        return Verdict.UNKNOWN;
    }

    private List<EvidenceItem> buildEntailmentEvidence(Claim claim, EntailmentResult entailment, Verdict verdict) {
        List<EvidenceItem> items = new ArrayList<>();

        if (verdict == Verdict.SUPPORTED) {
            items.add(new EvidenceItem(
                evidenceId("entailment", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                entailment.source() != null && entailment.source().contains("inferred")
                    ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
                entailment.axiomType() + ": " + claim.subject().iri() + " → " + claim.object().iri(),
                entailment.source() != null ? entailment.source() : "reasoner",
                entailment.reasonerName() != null ? entailment.reasonerName() : "default",
                "UNION",
                List.of(claim.subject().iri(), claim.object().iri()),
                EvidenceItem.CONFIDENCE_ENTAILED
            ));
        }

        if (verdict == Verdict.UNKNOWN && EntailmentResult.NOT_ENTAILED.equals(entailment.result())) {
            items.add(new EvidenceItem(
                evidenceId("no-entailment", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                "Axiom not entailed: " + entailment.axiomType(),
                entailment.source() != null ? entailment.source() : "reasoner",
                entailment.reasonerName() != null ? entailment.reasonerName() : "default",
                "UNION",
                List.of(claim.subject().iri(), claim.object().iri()),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }

        return items;
    }

    // --- DISJOINT_CLASSES via class compatibility ---

    private ServiceResult<ClaimVerificationResult> verifyDisjointClasses(Claim claim, OntologyId ontId) {
        ServiceResult<ClassCompatibilityResult> result =
            consistencyService.checkClassCompatibility(ontId, claim.subject().iri(), claim.object().iri());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        ClassCompatibilityResult compat = ((ServiceResult.Success<ClassCompatibilityResult>) result).data();
        // For disjoint class claims: "supported" if classes ARE disjoint,
        // "contradicted" if compatible, "unknown" if unknown result
        Verdict verdict;
        if (ClassCompatibilityResult.DISJOINT.equals(compat.compatibility())) {
            verdict = Verdict.SUPPORTED;
        } else if (ClassCompatibilityResult.COMPATIBLE.equals(compat.compatibility())) {
            verdict = Verdict.CONTRADICTED;
        } else if (ClassCompatibilityResult.UNSATISFIABLE_TOGETHER.equals(compat.compatibility())) {
            verdict = Verdict.SUPPORTED; // disjoint-like: unsatisfiable together implies disjoint
        } else {
            verdict = Verdict.UNKNOWN;
        }

        List<EvidenceItem> evidence = buildCompatibilityEvidence(claim, compat, verdict);
        return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
    }

    // --- CLASS_COMPATIBILITY via class compatibility ---

    private ServiceResult<ClaimVerificationResult> verifyClassCompatibility(Claim claim, OntologyId ontId) {
        ServiceResult<ClassCompatibilityResult> result =
            consistencyService.checkClassCompatibility(ontId, claim.subject().iri(), claim.object().iri());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        ClassCompatibilityResult compat = ((ServiceResult.Success<ClassCompatibilityResult>) result).data();
        // For compatibility claims: "supported" if compatible,
        // "contradicted" if disjoint/unsatisfiable
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

    // --- INDIVIDUAL_MEMBERSHIP via membership checks ---

    private ServiceResult<ClaimVerificationResult> verifyIndividualMembership(Claim claim, OntologyId ontId) {
        ServiceResult<MembershipResult> result =
            consistencyService.checkIndividualMembership(ontId, claim.subject().iri(), claim.object().iri(), claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        MembershipResult membership = ((ServiceResult.Success<MembershipResult>) result).data();
        Verdict verdict;
        if (membership.isMember()) {
            verdict = Verdict.SUPPORTED;
        } else {
            verdict = Verdict.UNKNOWN; // not a member ≠ contradicted
        }

        List<EvidenceItem> evidence = buildMembershipEvidence(claim, membership, verdict);
        return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
    }

    private List<EvidenceItem> buildMembershipEvidence(Claim claim, MembershipResult membership, Verdict verdict) {
        List<EvidenceItem> items = new ArrayList<>();

        if (verdict == Verdict.SUPPORTED) {
            EvidenceKind kind = MembershipResult.INFERRED.equals(membership.membershipType())
                || MembershipResult.BOTH.equals(membership.membershipType())
                ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM;
            items.add(new EvidenceItem(
                evidenceId("membership", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                kind,
                membership.individualIRI() + " ∈ " + membership.classIRI(),
                "membership-check",
                membership.reasonerName() != null ? membership.reasonerName() : "default",
                "UNION",
                List.of(membership.individualIRI(), membership.classIRI()),
                kind == EvidenceKind.INFERRED_AXIOM ? EvidenceItem.CONFIDENCE_INFERRED : EvidenceItem.CONFIDENCE_EXPLICIT
            ));
        }

        if (verdict == Verdict.UNKNOWN) {
            items.add(new EvidenceItem(
                evidenceId("no-membership", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                membership.individualIRI() + " is not a member of " + membership.classIRI(),
                "membership-check",
                membership.reasonerName() != null ? membership.reasonerName() : "default",
                "UNION",
                List.of(membership.individualIRI(), membership.classIRI()),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }

        return items;
    }

    // --- OBJECT_PROPERTY_ASSERTION via relation assertion checks ---

    private ServiceResult<ClaimVerificationResult> verifyObjectPropertyAssertion(Claim claim, OntologyId ontId) {
        String propertyIRI = claim.predicate() != null ? claim.predicate() : "";
        ServiceResult<RelationAssertionResult> result =
            consistencyService.checkRelationAssertion(ontId, claim.subject().iri(), propertyIRI, claim.object().iri(), claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        RelationAssertionResult assertion = ((ServiceResult.Success<RelationAssertionResult>) result).data();
        Verdict verdict;
        if (assertion.isAsserted()) {
            verdict = Verdict.SUPPORTED;
        } else {
            verdict = Verdict.UNKNOWN; // not asserted ≠ contradicted
        }

        List<EvidenceItem> evidence = buildRelationEvidence(claim, assertion, verdict);
        return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
    }

    private List<EvidenceItem> buildRelationEvidence(Claim claim, RelationAssertionResult assertion, Verdict verdict) {
        List<EvidenceItem> items = new ArrayList<>();

        if (verdict == Verdict.SUPPORTED) {
            EvidenceKind kind = RelationAssertionResult.INFERRED.equals(assertion.assertionType())
                || RelationAssertionResult.BOTH.equals(assertion.assertionType())
                ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM;
            items.add(new EvidenceItem(
                evidenceId("relation", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                kind,
                assertion.sourceIndividualIRI() + " → " + assertion.propertyIRI() + " → " + assertion.targetIndividualIRI(),
                "relation-assertion-check",
                assertion.reasonerName() != null ? assertion.reasonerName() : "default",
                "UNION",
                List.of(assertion.sourceIndividualIRI(), assertion.propertyIRI(), assertion.targetIndividualIRI()),
                kind == EvidenceKind.INFERRED_AXIOM ? EvidenceItem.CONFIDENCE_INFERRED : EvidenceItem.CONFIDENCE_EXPLICIT
            ));
        }

        if (verdict == Verdict.UNKNOWN) {
            items.add(new EvidenceItem(
                evidenceId("no-relation", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                assertion.sourceIndividualIRI() + " does not relate to " + assertion.targetIndividualIRI() + " via " + assertion.propertyIRI(),
                "relation-assertion-check",
                assertion.reasonerName() != null ? assertion.reasonerName() : "default",
                "UNION",
                List.of(assertion.sourceIndividualIRI(), assertion.propertyIRI(), assertion.targetIndividualIRI()),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }

        return items;
    }

    // --- DATA_PROPERTY_ASSERTION via entailment or data assertions ---

    private ServiceResult<ClaimVerificationResult> verifyDataPropertyAssertion(Claim claim, OntologyId ontId) {
        // Use entailment check for data property assertions
        Map<String, String> params = new LinkedHashMap<>();
        params.put("subjectIRI", claim.subject().iri());
        params.put("propertyIRI", claim.predicate() != null ? claim.predicate() : "");
        params.put("objectIRI", claim.object().iri());

        ServiceResult<EntailmentResult> result =
            reasonerService.checkEntailment(ontId, "DataPropertyAssertion", params, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) result).data();
        Verdict verdict = mapEntailmentVerdict(entailment.result());
        List<EvidenceItem> evidence = buildEntailmentEvidence(claim, entailment, verdict);

        Optional<UnknownReason> unknownReason = Optional.empty();
        if (EntailmentResult.UNSUPPORTED_AXIOM_TYPE.equals(entailment.result())) {
            unknownReason = Optional.of(UnknownReason.UNSUPPORTED_CLAIM_TYPE);
        }

        return buildResult(claim, ontId, verdict, evidence, unknownReason, Optional.empty());
    }

    // --- DATA_PROPERTY_RANGE via datatype constraints ---

    private ServiceResult<ClaimVerificationResult> verifyDataPropertyRange(Claim claim, OntologyId ontId) {
        ServiceResult<DatatypeConstraintsResult> result =
            deepeningService.getDatatypeConstraints(ontId, claim.object().iri());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        DatatypeConstraintsResult constraints = ((ServiceResult.Success<DatatypeConstraintsResult>) result).data();
        // If the property's range datatype has constraints and the claim matches → supported
        // Otherwise → unknown (we can't contradict without direct evidence)
        Verdict verdict = Verdict.UNKNOWN;

        // Check if the claim predicate matches a known range
        if (claim.predicate() != null && !claim.predicate().isBlank()) {
            // Use entailment for range assertion
            Map<String, String> params = new LinkedHashMap<>();
            params.put("propertyIRI", claim.predicate());
            params.put("objectIRI", claim.object().iri());

            ServiceResult<EntailmentResult> entailResult =
                reasonerService.checkEntailment(ontId, "DataPropertyRange", params, claim.reasoner());

            if (!entailResult.isSuccess()) {
                return mapError(entailResult);
            }

            EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) entailResult).data();
            verdict = mapEntailmentVerdict(entailment.result());
            List<EvidenceItem> evidence = new ArrayList<>();
            evidence.add(new EvidenceItem(
                evidenceId("datatype-range", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                verdict == Verdict.SUPPORTED ? EvidenceKind.EXPLICIT_AXIOM : EvidenceKind.REASONING_REPORT,
                "Range of " + claim.predicate() + " → " + claim.object().iri() + ": " + entailment.result(),
                entailment.source() != null ? entailment.source() : "reasoner",
                entailment.reasonerName() != null ? entailment.reasonerName() : "default",
                "UNION",
                List.of(claim.predicate(), claim.object().iri()),
                verdict == Verdict.SUPPORTED ? EvidenceItem.CONFIDENCE_ENTAILED : EvidenceItem.CONFIDENCE_INFERRED
            ));
            evidence.add(new EvidenceItem(
                evidenceId("datatype-constraints", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.EXPLICIT_AXIOM,
                constraints.datatypeIRI() + " facets: " + constraints.facets().stream()
                    .map(f -> f.facetType() + "=" + f.facetValue())
                    .collect(Collectors.joining(", ")),
                "datatype-constraints",
                "default",
                "EXPLICIT",
                List.of(constraints.datatypeIRI()),
                EvidenceItem.CONFIDENCE_EXPLICIT
            ));

            Optional<UnknownReason> unknownReason = Optional.empty();
            if (EntailmentResult.UNSUPPORTED_AXIOM_TYPE.equals(entailment.result())) {
                unknownReason = Optional.of(UnknownReason.UNSUPPORTED_CLAIM_TYPE);
            }

            return buildResult(claim, ontId, verdict, evidence, unknownReason, Optional.empty());
        }

        // No predicate specified — just report constraints
        List<EvidenceItem> evidence = List.of(new EvidenceItem(
            evidenceId("datatype-constraints", claim.claimId()),
            EvidenceItem.ROLE_SUPPORTING,
            EvidenceKind.EXPLICIT_AXIOM,
            constraints.datatypeIRI() + " facets: " + constraints.facets().stream()
                .map(f -> f.facetType() + "=" + f.facetValue())
                .collect(Collectors.joining(", ")),
            "datatype-constraints",
            "default",
            "EXPLICIT",
            List.of(constraints.datatypeIRI()),
            EvidenceItem.CONFIDENCE_EXPLICIT
        ));

        return buildResult(claim, ontId, verdict, evidence, Optional.of(UnknownReason.INSUFFICIENT_AXIOMS), Optional.empty());
    }

    // --- LITERAL_VALIDITY via literal validation ---

    private ServiceResult<ClaimVerificationResult> verifyLiteralValidity(Claim claim, OntologyId ontId) {
        // subject.iri() = datatypeIRI, object.iri() = literal value
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

    // --- ONTOLOGY_CONSISTENCY via reasoner consistency check ---

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

    private ServiceResult<ClaimVerificationResult> verifyOntologyScope(Claim claim, OntologyId ontId) {
        ServiceResult<ScopeDescription> result = consistencyService.getScope(ontId);

        if (!result.isSuccess()) {
            return mapError(result);
        }

        ScopeDescription scope = ((ServiceResult.Success<ScopeDescription>) result).data();
        List<EvidenceItem> evidence = new ArrayList<>();
        Verdict verdict;
        Optional<UnknownReason> unknownReason = Optional.empty();
        Optional<String> unknownExplanation = Optional.empty();

        // Determine if subject (and object, if present) actually belong to the ontology
        boolean subjectInScope = isEntityInOntology(claim.subject(), ontId);
        boolean objectInScope = claim.object() == null || isEntityInOntology(claim.object(), ontId);

        if (!subjectInScope || !objectInScope) {
            // At least one referenced entity is not part of the ontology → out_of_scope
            verdict = Verdict.OUT_OF_SCOPE;
            unknownReason = Optional.of(UnknownReason.MISSING_ENTITY);
            unknownExplanation = Optional.of("Subject or object is not declared in ontology '" + claim.ontologyId() + "'.");
        } else {
            // Both entities are known; report scope support
            verdict = Verdict.SUPPORTED;
        }

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

        return buildResult(claim, ontId, verdict, evidence, unknownReason, unknownExplanation);
    }

    private boolean isEntityInOntology(ClaimEntity entity, OntologyId ontId) {
        if (entity == null || entity.iri() == null || entity.iri().isBlank()) return false;
        if ("literal".equals(entity.kind())) return true;
        // Use consistency service to test if the entity IRI is declared in the ontology
        return consistencyService.isEntityDeclared(ontId, entity.iri(), entity.kind());
    }

    // --- Helpers ---

    private String evidenceId(String kind, String claimId) {
        return kind + "-" + claimId;
    }

    private ServiceResult<ClaimVerificationResult> buildResult(Claim claim, OntologyId ontId, Verdict verdict,
                                                                List<EvidenceItem> evidence,
                                                                Optional<UnknownReason> unknownReason,
                                                                Optional<String> unknownExplanation) {
        return ServiceResult.success(
            new ClaimVerificationResult(
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
                evidence.size()
            ),
            ResultMetadata.empty()
        );
    }

    @SuppressWarnings("unchecked")
    private <T> ServiceResult<ClaimVerificationResult> mapError(ServiceResult<T> errorResult) {
        ServiceError error = ((ServiceResult.Error<T>) errorResult).error();
        // Map v0.2 errors to v0.3 equivalents where needed
        if (error.code() == ErrorCode.ONTOLOGY_NOT_FOUND) {
            return ServiceResult.error(ServiceError.ontologyNotFound(new OntologyId(
                error.details() != null && error.details().get("ontologyId") != null
                    ? (String) error.details().get("ontologyId") : "")));
        }
        if (error.code() == ErrorCode.CLASS_NOT_FOUND
            || error.code() == ErrorCode.PROPERTY_NOT_FOUND
            || error.code() == ErrorCode.INDIVIDUAL_NOT_FOUND
            || error.code() == ErrorCode.DATATYPE_NOT_FOUND) {
            String entityIRI = error.details() != null && error.details().get("entityIRI") != null
                ? (String) error.details().get("entityIRI") : "";
            return ServiceResult.error(ServiceError.entityNotFound(
                new EntityId(entityIRI), new OntologyId("")));
        }
        return ServiceResult.error(error);
    }
}