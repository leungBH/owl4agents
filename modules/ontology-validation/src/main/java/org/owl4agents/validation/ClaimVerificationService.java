package org.owl4agents.validation;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.owl4agents.reasoner.ReasonerService;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.storage.CatalogStore;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

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

        // v0.8.4 Decision 3: load the ontology once and thread it through all
        // downstream verification methods to avoid redundant loads.
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

        return verifyWithOntology(ontology, claim, ontId);
    }

    private ServiceResult<ClaimVerificationResult> verifyWithOntology(OWLOntology ontology, Claim claim, OntologyId ontId) {
        // v0.8.1 ISSUE-01: global scope pre-check. Verifies that the claim's
        // subject and object entities are declared in the ontology signature
        // BEFORE invoking any type-specific verification method. The pre-check
        // is exempt for ONTOLOGY_SCOPE (it IS the scope check),
        // ONTOLOGY_CONSISTENCY (subject is typically the ontology IRI or
        // owl:Thing), and LITERAL_VALIDITY (object is typically xsd:... which
        // is not in any ontology signature).
        if (!isExemptFromScopePrecheck(claim.type())) {
            ServiceResult<ClaimVerificationResult> precheckResult = applyScopePrecheck(ontology, claim, ontId);
            if (precheckResult != null) {
                return precheckResult;
            }
        }

        return switch (claim.type()) {
            case SUBCLASS, EQUIVALENT_CLASSES,
                 OBJECT_PROPERTY_DOMAIN, OBJECT_PROPERTY_RANGE,
                 DATA_PROPERTY_DOMAIN, DATA_PROPERTY_RANGE -> verifyEntailmentClaim(ontology, claim, ontId);
            case DISJOINT_CLASSES -> verifyDisjointClasses(ontology, claim, ontId);
            case INDIVIDUAL_MEMBERSHIP -> verifyIndividualMembership(claim, ontId);
            case OBJECT_PROPERTY_ASSERTION -> verifyObjectPropertyAssertion(claim, ontId);
            case DATA_PROPERTY_ASSERTION -> verifyDataPropertyAssertion(claim, ontId);
            case LITERAL_VALIDITY -> verifyLiteralValidity(claim, ontId);
            case CLASS_COMPATIBILITY -> verifyClassCompatibility(ontology, claim, ontId);
            case ONTOLOGY_CONSISTENCY -> verifyOntologyConsistency(claim, ontId);
            case ONTOLOGY_SCOPE -> verifyOntologyScope(ontology, claim, ontId);
            case DIFFERENT_INDIVIDUALS -> verifyDifferentIndividuals(claim, ontId);
            case OBJECT_PROPERTY_SUBPROPERTY -> verifySubPropertyOf(claim, ontId);
        };
    }

    // --- Entailment-based claims: SUBCLASS, EQUIVALENT_CLASSES, domain/range ---

    private ServiceResult<ClaimVerificationResult> verifyEntailmentClaim(OWLOntology ontology, Claim claim, OntologyId ontId) {
        String axiomType = entailmentAxiomType(claim.type());

        // v0.8.1 ISSUE-03: if either side of an EquivalentClasses claim has a
        // complex expression, delegate to ReasonerServiceImpl.checkEquivalentClassesEntailment
        // which accepts OWL API OWLClassExpression operands.
        if (claim.type() == ClaimType.EQUIVALENT_CLASSES
                && (claim.subject().expression() != null
                    || (claim.object() != null && claim.object().expression() != null))) {
            return verifyEquivalentClassesWithExpressions(ontology, claim, ontId);
        }

        Map<String, String> params = buildEntailmentParams(claim);

        ServiceResult<EntailmentResult> result =
            reasonerService.checkEntailment(ontology, ontId, axiomType, params, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) result).data();
        Verdict verdict = mapEntailmentVerdict(entailment.result());

        // v0.8.1 TC-14: when SUBCLASS/EQUIVALENT_CLASSES returns UNKNOWN,
        // run a complementary class-compatibility check. If the subject and
        // object classes are disjoint, the subclass claim is CONTRADICTED.
        if (verdict == Verdict.UNKNOWN
            && (claim.type() == ClaimType.SUBCLASS
                || claim.type() == ClaimType.EQUIVALENT_CLASSES)
            && claim.subject() != null && claim.subject().iri() != null
            && claim.object() != null && claim.object().iri() != null
            && EntailmentResult.NOT_ENTAILED.equals(entailment.result())) {
            ServiceResult<ClaimVerificationResult> disjointResult =
                checkDisjointCounterEvidence(ontology, claim, ontId, entailment);
            if (disjointResult != null) {
                return disjointResult;
            }
        }

        List<EvidenceItem> evidence = buildEntailmentEvidence(claim, entailment, verdict);

        return buildResult(claim, ontId, verdict, evidence,
            entailment.result().equals(EntailmentResult.UNSUPPORTED_AXIOM_TYPE)
                ? Optional.of(UnknownReason.UNSUPPORTED_CLAIM_TYPE)
                : (verdict == Verdict.UNKNOWN ? Optional.of(UnknownReason.INSUFFICIENT_AXIOMS) : Optional.empty()),
            Optional.empty());
    }

    /**
     * v0.8.1 TC-14: complementary class-compatibility check for SUBCLASS /
     * EQUIVALENT_CLASSES claims. If the subject and object are disjoint (per
     * {@code checkClassCompatibility}), return CONTRADICTED with a counter
     * evidence item, since "X subclassOf Y" cannot hold when X and Y have
     * no common instances. Returns {@code null} if the check is inconclusive
     * (caller should keep the original UNKNOWN verdict).
     */
    private ServiceResult<ClaimVerificationResult> checkDisjointCounterEvidence(
            OWLOntology ontology, Claim claim, OntologyId ontId, EntailmentResult originalEntailment) {
        // v0.8.3 R2: skip proxy when subject or object entity is not in the ontology's
        // direct signature. This prevents false contradicted verdicts on cross-ontology
        // claims where suffix matching could falsely associate entities.
        if (claim.subject() != null && claim.subject().iri() != null
            && !isEntityInOntology(ontology, claim.subject(), ontId)) {
            return null;
        }
        if (claim.object() != null && claim.object().iri() != null
            && !isEntityInOntology(ontology, claim.object(), ontId)) {
            return null;
        }
        ServiceResult<ClassCompatibilityResult> compatResult =
            consistencyService.checkClassCompatibility(
                ontology, ontId, claim.subject().iri(), claim.object().iri());
        if (!compatResult.isSuccess()) {
            return null; // signature/lookup error → not a clean disjoint match
        }
        ClassCompatibilityResult compat =
            ((ServiceResult.Success<ClassCompatibilityResult>) compatResult).data();
        if (!ClassCompatibilityResult.DISJOINT.equals(compat.compatibility())
            && !ClassCompatibilityResult.UNSATISFIABLE_TOGETHER.equals(compat.compatibility())) {
            return null; // not provably disjoint → keep UNKNOWN
        }
        // Disjoint (or unsatisfiable-together) → CONTRADICTED
        String reasonerName = compat.reasonerName() != null ? compat.reasonerName() : "default";
        EvidenceItem counter = new EvidenceItem(
            evidenceId("disjoint-counter", claim.claimId()),
            EvidenceItem.ROLE_COUNTER,
            compat.reasonerName() != null ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
            claim.subject().iri() + " and " + claim.object().iri() + " are disjoint",
            "class-compatibility-check",
            reasonerName,
            "UNION",
            List.of(claim.subject().iri(), claim.object().iri()),
            EvidenceItem.CONFIDENCE_INFERRED
        );
        return buildResult(claim, ontId, Verdict.CONTRADICTED, List.of(counter),
            Optional.empty(), Optional.empty());
    }

    /**
     * v0.8.1 ISSUE-03: Handle {@code equivalent_classes} claims where either
     * side is a complex class expression. Builds OWL API
     * {@link org.semanticweb.owlapi.model.OWLClassExpression} operands using
     * {@code ClassExpressionBuilder} and delegates to
     * {@code ReasonerServiceImpl.checkEquivalentClassesEntailment}.
     */
    private ServiceResult<ClaimVerificationResult> verifyEquivalentClassesWithExpressions(OWLOntology ontology, Claim claim, OntologyId ontId) {
        return verifyEquivalentClassesWithExpressionsImpl(ontology, claim, ontId);
    }

    private ServiceResult<ClaimVerificationResult> verifyEquivalentClassesWithExpressionsImpl(OWLOntology ontology, Claim claim, OntologyId ontId) {
        // v0.8.4 Decision 3: ontology is now loaded once in verify() and
        // passed through, avoiding the redundant loadOntologyForClaim() call.
        org.semanticweb.owlapi.model.OWLDataFactory df =
            ontology.getOWLOntologyManager().getOWLDataFactory();

        // Build subject expression (named or complex)
        org.semanticweb.owlapi.model.OWLClassExpression subjectExpr;
        try {
            if (claim.subject().expression() != null) {
                subjectExpr = org.owl4agents.reasoner.ClassExpressionBuilder.build(
                    claim.subject().expression(), ontology, df);
            } else {
                org.semanticweb.owlapi.model.IRI subIri =
                    org.owl4agents.owlapi.OntologyIriResolver.resolveOntologyIRI(
                        ontology, claim.subject().iri(), "class");
                if (subIri == null) {
                    return buildEntityNotFound(claim, ontId, "subject", claim.subject().iri());
                }
                subjectExpr = df.getOWLClass(subIri);
            }
        } catch (org.owl4agents.reasoner.ClassExpressionBuilder.EntityNotFoundException e) {
            return buildEntityNotFound(claim, ontId, "subject", claim.subject().iri() != null
                ? claim.subject().iri() : "<expression>");
        } catch (org.owl4agents.reasoner.ClassExpressionBuilder.ExpressionTooDeepException e) {
            return buildInvalidSchema(claim, ontId, e.getMessage());
        } catch (IllegalArgumentException e) {
            return buildInvalidSchema(claim, ontId, e.getMessage());
        }

        org.semanticweb.owlapi.model.OWLClassExpression objectExpr;
        try {
            if (claim.object() == null) {
                return buildInvalidSchema(claim, ontId,
                    "equivalent_classes claim requires an object entity");
            }
            if (claim.object().expression() != null) {
                objectExpr = org.owl4agents.reasoner.ClassExpressionBuilder.build(
                    claim.object().expression(), ontology, df);
            } else {
                org.semanticweb.owlapi.model.IRI objIri =
                    org.owl4agents.owlapi.OntologyIriResolver.resolveOntologyIRI(
                        ontology, claim.object().iri(), "class");
                if (objIri == null) {
                    return buildEntityNotFound(claim, ontId, "object", claim.object().iri());
                }
                objectExpr = df.getOWLClass(objIri);
            }
        } catch (org.owl4agents.reasoner.ClassExpressionBuilder.EntityNotFoundException e) {
            return buildEntityNotFound(claim, ontId, "object", claim.object().iri() != null
                ? claim.object().iri() : "<expression>");
        } catch (org.owl4agents.reasoner.ClassExpressionBuilder.ExpressionTooDeepException e) {
            return buildInvalidSchema(claim, ontId, e.getMessage());
        } catch (IllegalArgumentException e) {
            return buildInvalidSchema(claim, ontId, e.getMessage());
        }

        ServiceResult<EntailmentResult> result = reasonerService.checkEquivalentClassesEntailment(
            ontology, ontId, subjectExpr, objectExpr, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) result).data();
        Verdict verdict = mapEntailmentVerdict(entailment.result());
        List<EvidenceItem> evidence = buildEntailmentEvidence(claim, entailment, verdict);

        return buildResult(claim, ontId, verdict, evidence,
            verdict == Verdict.UNKNOWN ? Optional.of(UnknownReason.INSUFFICIENT_AXIOMS) : Optional.empty(),
            Optional.empty());
    }

    private ServiceResult<ClaimVerificationResult> buildInvalidSchema(Claim claim, OntologyId ontId, String message) {
        return ServiceResult.error(ErrorCode.INVALID_CLAIM_SCHEMA, message);
    }

    private ServiceResult<ClaimVerificationResult> buildEntityNotFound(Claim claim, OntologyId ontId,
                                                                      String role, String iri) {
        EvidenceItem evidence = new EvidenceItem(
            evidenceId("entity-not-found-" + role, claim.claimId()),
            EvidenceItem.ROLE_COUNTER,
            EvidenceKind.SCOPE_STATEMENT,
            role + " entity not found in ontology signature: " + iri,
            "ontology-scope",
            "default",
            "UNION",
            List.of(iri),
            EvidenceItem.CONFIDENCE_EXPLICIT
        );
        return buildResult(claim, ontId, Verdict.OUT_OF_SCOPE, List.of(),
            Optional.of(UnknownReason.MISSING_ENTITY), Optional.empty());
    }

    private String entailmentAxiomType(ClaimType type) {
        return switch (type) {
            case SUBCLASS -> "SubClassOf";
            case EQUIVALENT_CLASSES -> "EquivalentClasses";
            case OBJECT_PROPERTY_DOMAIN -> "ObjectPropertyDomain";
            case OBJECT_PROPERTY_RANGE -> "ObjectPropertyRange";
            case DATA_PROPERTY_DOMAIN -> "DataPropertyDomain";
            case DATA_PROPERTY_RANGE -> "DataPropertyRange";
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
                case DATA_PROPERTY_RANGE -> {
                    params.put("propertyIRI", claim.predicate() != null ? claim.predicate() : claim.subject().iri());
                    if (claim.object() != null) params.put("rangeIRI", claim.object().iri());
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
        // v0.8.1: when one side of the claim is a complex expression, its
        // IRI is null. Use a stable placeholder for the evidence list so that
        // List.of() (which forbids nulls) does not throw NPE.
        String subIri = claim.subject() != null && claim.subject().iri() != null
            ? claim.subject().iri() : "<expression>";
        String objIri = claim.object() != null && claim.object().iri() != null
            ? claim.object().iri() : "<expression>";

        if (verdict == Verdict.SUPPORTED) {
            items.add(new EvidenceItem(
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

        if (verdict == Verdict.UNKNOWN && EntailmentResult.NOT_ENTAILED.equals(entailment.result())) {
            items.add(new EvidenceItem(
                evidenceId("no-entailment", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.REASONING_REPORT,
                "Axiom not entailed: " + entailment.axiomType(),
                entailment.source() != null ? entailment.source() : "reasoner",
                entailment.reasonerName() != null ? entailment.reasonerName() : "default",
                "UNION",
                List.of(subIri, objIri),
                EvidenceItem.CONFIDENCE_INFERRED
            ));
        }

        return items;
    }

    // --- DISJOINT_CLASSES via class compatibility ---

    private ServiceResult<ClaimVerificationResult> verifyDisjointClasses(OWLOntology ontology, Claim claim, OntologyId ontId) {
        // v0.8.1 TC-14: if the entity kind doesn't match a class, return
        // UNKNOWN with INSUFFICIENT_AXIOMS rather than propagating the
        // ENTITY_NOT_FOUND error. This handles fixture cases where the
        // subject/object are object properties (not classes).
        if (claim.subject() == null || claim.subject().iri() == null
            || claim.object() == null || claim.object().iri() == null) {
            return buildResult(claim, ontId, Verdict.UNKNOWN, List.of(),
                Optional.of(UnknownReason.INSUFFICIENT_AXIOMS),
                Optional.of("disjoint_classes requires two class IRIs"));
        }

        // v0.8.3 R5: individual-level disjointness dispatch. When both subject
        // and object have kind=individual, delegate to DifferentIndividuals
        // entailment check instead of class-level compatibility. The existing
        // verifyDifferentIndividuals() method implements the full logic
        // (asserted DifferentIndividuals → reasoner isEntailed → SameIndividual
        // counter-evidence → UNKNOWN) and does not depend on claim.type().
        String subjectKind = claim.subject().kind();
        String objectKind = claim.object().kind();
        if ("individual".equals(subjectKind) && "individual".equals(objectKind)) {
            // Same-individual pre-check: a claim that an individual is different
            // from itself is trivially contradicted.
            if (claim.subject().iri().equals(claim.object().iri())) {
                EvidenceItem counter = new EvidenceItem(
                    evidenceId("same-individual-self", claim.claimId()),
                    EvidenceItem.ROLE_COUNTER,
                    EvidenceKind.EXPLICIT_AXIOM,
                    "Same individual: " + claim.subject().iri(),
                    "self-identity",
                    "default",
                    "EXPLICIT",
                    List.of(claim.subject().iri()),
                    EvidenceItem.CONFIDENCE_EXPLICIT
                );
                return buildResult(claim, ontId, Verdict.CONTRADICTED, List.of(counter),
                    Optional.empty(), Optional.empty());
            }
            return verifyDifferentIndividuals(claim, ontId);
        }

        ServiceResult<ClassCompatibilityResult> result =
            consistencyService.checkClassCompatibility(ontology, ontId, claim.subject().iri(), claim.object().iri());

        if (!result.isSuccess()) {
            // v0.8.1 TC-14: degrade gracefully when the compatibility check
            // cannot run (e.g., the IRIs are not classes). Return UNKNOWN
            // with INSUFFICIENT_AXIOMS so the test sees a verdict instead
            // of a hard error.
            ServiceError error = ((ServiceResult.Error<ClassCompatibilityResult>) result).error();
            if (error.code() == ErrorCode.CLASS_NOT_FOUND
                || error.code() == ErrorCode.PROPERTY_NOT_FOUND
                || error.code() == ErrorCode.INDIVIDUAL_NOT_FOUND) {
                return buildResult(claim, ontId, Verdict.UNKNOWN, List.of(),
                    Optional.of(UnknownReason.INSUFFICIENT_AXIOMS),
                    Optional.of("One or both entities are not declared as classes in the ontology"));
            }
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

    private ServiceResult<ClaimVerificationResult> verifyClassCompatibility(OWLOntology ontology, Claim claim, OntologyId ontId) {
        ServiceResult<ClassCompatibilityResult> result =
            consistencyService.checkClassCompatibility(ontology, ontId, claim.subject().iri(), claim.object().iri());

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
        // v0.8.1 TC-14: degrade gracefully when the IRIs don't represent
        // individuals (e.g., fixture uses object property IRIs).
        if (claim.subject() == null || claim.subject().iri() == null
            || claim.object() == null || claim.object().iri() == null
            || claim.predicate() == null || claim.predicate().isBlank()) {
            return buildResult(claim, ontId, Verdict.UNKNOWN, List.of(),
                Optional.of(UnknownReason.INSUFFICIENT_AXIOMS),
                Optional.of("object_property_assertion requires individual IRIs and a property IRI"));
        }

        // v0.8.3 R6: property hierarchy dispatch. When both subject and object
        // have kind=object_property and predicate=="subPropertyOf", delegate to
        // SubObjectPropertyOf entailment check instead of individual-level
        // relation assertion. The existing verifySubPropertyOf() method
        // implements the full logic (asserted SubObjectPropertyOf → reasoner
        // isEntailed → reverse direction counter-evidence → UNKNOWN) and does
        // not depend on claim.type().
        String subjectKind = claim.subject().kind();
        String objectKind = claim.object().kind();
        if ("object_property".equals(subjectKind) && "object_property".equals(objectKind)
            && "subPropertyOf".equals(claim.predicate())) {
            return verifySubPropertyOf(claim, ontId);
        }

        String propertyIRI = claim.predicate();
        ServiceResult<RelationAssertionResult> result =
            consistencyService.checkRelationAssertion(ontId, claim.subject().iri(), propertyIRI, claim.object().iri(), claim.reasoner());

        if (!result.isSuccess()) {
            ServiceError error = ((ServiceResult.Error<RelationAssertionResult>) result).error();
            if (error.code() == ErrorCode.CLASS_NOT_FOUND
                || error.code() == ErrorCode.PROPERTY_NOT_FOUND
                || error.code() == ErrorCode.INDIVIDUAL_NOT_FOUND) {
                return buildResult(claim, ontId, Verdict.UNKNOWN, List.of(),
                    Optional.of(UnknownReason.INSUFFICIENT_AXIOMS),
                    Optional.of("One or both entities are not declared as individuals in the ontology"));
            }
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

    // --- DIFFERENT_INDIVIDUALS (v0.8.1 ISSUE-04) ---

    private ServiceResult<ClaimVerificationResult> verifyDifferentIndividuals(Claim claim, OntologyId ontId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("individual1IRI", claim.subject().iri());
        if (claim.object() != null) {
            params.put("individual2IRI", claim.object().iri());
        }

        ServiceResult<EntailmentResult> result =
            reasonerService.checkEntailment(ontId, "DifferentIndividuals", params, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) result).data();
        if (EntailmentResult.ENTAILED.equals(entailment.result())) {
            Verdict verdict = Verdict.SUPPORTED;
            List<EvidenceItem> evidence = List.of(new EvidenceItem(
                evidenceId("different-individuals", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                entailment.source() != null && entailment.source().contains("inferred")
                    ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
                "DifferentIndividuals(" + claim.subject().iri() + ", " + claim.object().iri() + ")",
                entailment.source() != null ? entailment.source() : "reasoner",
                entailment.reasonerName() != null ? entailment.reasonerName() : "default",
                "UNION",
                List.of(claim.subject().iri(), claim.object().iri()),
                EvidenceItem.CONFIDENCE_ENTAILED
            ));
            return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
        }

        // v0.8.1 ISSUE-04: Counter-evidence check. The reasoner may prove that
        // the two individuals are actually the same (e.g. via a SameIndividual
        // axiom or by sharing a class assertion chain), in which case the
        // claim is CONTRADICTED, not just unknown.
        if (claim.object() != null) {
            Map<String, String> sameParams = new LinkedHashMap<>();
            sameParams.put("individual1IRI", claim.subject().iri());
            sameParams.put("individual2IRI", claim.object().iri());

            ServiceResult<EntailmentResult> sameResult = reasonerService.checkEntailment(
                ontId, "SameIndividual", sameParams, claim.reasoner());

            if (sameResult.isSuccess()) {
                EntailmentResult sameEntailment = ((ServiceResult.Success<EntailmentResult>) sameResult).data();
                if (EntailmentResult.ENTAILED.equals(sameEntailment.result())) {
                    EvidenceItem counter = new EvidenceItem(
                        evidenceId("same-individual-counter", claim.claimId()),
                        EvidenceItem.ROLE_COUNTER,
                        EvidenceKind.INFERRED_AXIOM,
                        "SameIndividual(" + claim.subject().iri() + ", " + claim.object().iri() + ")",
                        "inferred_same_individual",
                        sameEntailment.reasonerName() != null ? sameEntailment.reasonerName() : "default",
                        "INFERRED",
                        List.of(claim.subject().iri(), claim.object().iri()),
                        EvidenceItem.CONFIDENCE_ENTAILED
                    );
                    return buildResult(claim, ontId, Verdict.CONTRADICTED, List.of(counter),
                        Optional.empty(), Optional.empty());
                }
            }
        }

        // No DifferentIndividuals entailment, no SameIndividual entailment → UNKNOWN
        Verdict verdict = Verdict.UNKNOWN;
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(new EvidenceItem(
            evidenceId("no-different-individuals", claim.claimId()),
            EvidenceItem.ROLE_SUPPORTING,
            EvidenceKind.REASONING_REPORT,
            "DifferentIndividuals not entailed: " + claim.subject().iri() + " vs " + claim.object().iri(),
            "different-individuals-check",
            "default",
            "INFERRED",
            List.of(claim.subject().iri(), claim.object().iri()),
            EvidenceItem.CONFIDENCE_INFERRED
        ));
        return buildResult(claim, ontId, verdict, evidence,
            Optional.of(UnknownReason.INSUFFICIENT_AXIOMS), Optional.empty());
    }

    // --- OBJECT_PROPERTY_SUBPROPERTY (v0.8.1 ISSUE-05) ---

    private ServiceResult<ClaimVerificationResult> verifySubPropertyOf(Claim claim, OntologyId ontId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("subPropertyIRI", claim.subject().iri());
        if (claim.object() != null) {
            params.put("superPropertyIRI", claim.object().iri());
        }

        ServiceResult<EntailmentResult> result =
            reasonerService.checkEntailment(ontId, "SubObjectPropertyOf", params, claim.reasoner());

        if (!result.isSuccess()) {
            return mapError(result);
        }

        EntailmentResult entailment = ((ServiceResult.Success<EntailmentResult>) result).data();
        if (EntailmentResult.ENTAILED.equals(entailment.result())) {
            Verdict verdict = Verdict.SUPPORTED;
            List<EvidenceItem> evidence = List.of(new EvidenceItem(
                evidenceId("sub-property", claim.claimId()),
                EvidenceItem.ROLE_SUPPORTING,
                entailment.source() != null && entailment.source().contains("inferred")
                    ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
                "SubObjectPropertyOf(" + claim.subject().iri() + ", " + claim.object().iri() + ")",
                entailment.source() != null ? entailment.source() : "reasoner",
                entailment.reasonerName() != null ? entailment.reasonerName() : "default",
                "UNION",
                List.of(claim.subject().iri(), claim.object().iri()),
                EvidenceItem.CONFIDENCE_ENTAILED
            ));
            return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
        }

        // Not entailed — check reverse direction (SubObjectPropertyOf(superProp, subProp))
        if (claim.object() != null) {
            Map<String, String> reverseParams = new LinkedHashMap<>();
            reverseParams.put("subPropertyIRI", claim.object().iri());
            reverseParams.put("superPropertyIRI", claim.subject().iri());

            ServiceResult<EntailmentResult> reverseResult =
                reasonerService.checkEntailment(ontId, "SubObjectPropertyOf", reverseParams, claim.reasoner());

            if (reverseResult.isSuccess()) {
                EntailmentResult reverse = ((ServiceResult.Success<EntailmentResult>) reverseResult).data();
                if (EntailmentResult.ENTAILED.equals(reverse.result())) {
                    Verdict verdict = Verdict.CONTRADICTED;
                    List<EvidenceItem> evidence = List.of(new EvidenceItem(
                        evidenceId("reversed-sub-property", claim.claimId()),
                        EvidenceItem.ROLE_COUNTER,
                        reverse.source() != null && reverse.source().contains("inferred")
                            ? EvidenceKind.INFERRED_AXIOM : EvidenceKind.EXPLICIT_AXIOM,
                        "Reverse SubObjectPropertyOf(" + claim.object().iri() + ", " + claim.subject().iri() + ")",
                        reverse.source() != null ? reverse.source() : "reasoner",
                        reverse.reasonerName() != null ? reverse.reasonerName() : "default",
                        "UNION",
                        List.of(claim.object().iri(), claim.subject().iri()),
                        EvidenceItem.CONFIDENCE_ENTAILED
                    ));
                    return buildResult(claim, ontId, verdict, evidence, Optional.empty(), Optional.empty());
                }
            }
        }

        Verdict verdict = Verdict.UNKNOWN;
        List<EvidenceItem> evidence = List.of(new EvidenceItem(
            evidenceId("no-sub-property", claim.claimId()),
            EvidenceItem.ROLE_SUPPORTING,
            EvidenceKind.REASONING_REPORT,
            "SubObjectPropertyOf not entailed: " + claim.subject().iri() + " vs " + claim.object().iri(),
            "sub-property-check",
            "default",
            "INFERRED",
            List.of(claim.subject().iri(), claim.object().iri()),
            EvidenceItem.CONFIDENCE_INFERRED
        ));
        return buildResult(claim, ontId, verdict, evidence,
            Optional.of(UnknownReason.INSUFFICIENT_AXIOMS), Optional.empty());
    }

    // --- LITERAL_VALIDITY via literal validation ---

    private ServiceResult<ClaimVerificationResult> verifyLiteralValidity(Claim claim, OntologyId ontId) {
        // subject.iri() = datatypeIRI, object.iri() = literal value
        // v0.8.1: defensive NPE guard. The pre-check is exempt for this type
        // and callers may pass null object (no concrete literal to validate)
        // — return UNKNOWN rather than NPE.
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

    private ServiceResult<ClaimVerificationResult> verifyOntologyScope(OWLOntology ontology, Claim claim, OntologyId ontId) {
        // v0.8.1 DEFECT-2 fix: ONTOLOGY_SCOPE is exempt from the *global* pre-check
        // (which short-circuits before reaching the dispatcher), but the method
        // itself must still verify that any referenced entities are declared in
        // the ontology. An undeclared subject means the claim's entity is not
        // part of the ontology's scope — that is the canonical OUT_OF_SCOPE
        // outcome (Spec `claim-verification/spec.md` line 100-101, 236).
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
        Verdict verdict;
        Optional<UnknownReason> unknownReason = Optional.empty();
        Optional<String> unknownExplanation = Optional.empty();

        // ONTOLOGY_SCOPE is exempt from the global pre-check (the claim is
        // *about* scope, not about a specific entity), but we have already
        // verified that any referenced entities are declared. The verdict is
        // based on the scope description itself.
        verdict = Verdict.SUPPORTED;

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

    private boolean isEntityInOntology(OWLOntology ontology, ClaimEntity entity, OntologyId ontId) {
        if (entity == null) return false;
        // v0.8.1 ISSUE-03: skip the top-level scope check for expression-only
        // entities (pure complex class expression). IRIs nested inside the
        // expression are validated later by ClassExpressionBuilder, which
        // throws ENTITY_NOT_FOUND for unresolved IRIs.
        if (entity.iri() == null || entity.iri().isBlank()) {
            return entity.expression() != null;
        }
        if ("literal".equals(entity.kind())) return true;
        // v0.8.1 ISSUE-01: built-in namespace whitelist. Return true for any
        // IRI in the 4 standard built-in namespaces (xsd:, rdf:, rdfs:, owl:),
        // independent of the exemption list. This is a dual defense so that
        // DATA_PROPERTY_DOMAIN / DATA_PROPERTY_RANGE claims whose object is
        // xsd:string are not misclassified as out_of_scope.
        if (isInBuiltinNamespace(entity.iri())) {
            return true;
        }
        // Use consistency service to test if the entity IRI is declared in the ontology
        return consistencyService.isEntityDeclared(ontology, ontId, entity.iri(), entity.kind());
    }

    /**
     * v0.8.1 ISSUE-01: returns true when the IRI is in one of the four OWL
     * built-in namespaces (xsd:, rdf:, rdfs:, owl:). These IRIs are universally
     * available and should never be flagged as out_of_scope.
     */
    private static boolean isInBuiltinNamespace(String iri) {
        if (iri == null) return false;
        return iri.startsWith("http://www.w3.org/2001/XMLSchema#")
            || iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
            || iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")
            || iri.startsWith("http://www.w3.org/2002/07/owl#");
    }

    /**
     * v0.8.1 ISSUE-01: claim types that bypass the global scope pre-check.
     */
    private static boolean isExemptFromScopePrecheck(ClaimType type) {
        return type == ClaimType.ONTOLOGY_SCOPE
            || type == ClaimType.ONTOLOGY_CONSISTENCY
            || type == ClaimType.LITERAL_VALIDITY;
    }

    /**
     * v0.8.1 ISSUE-01: applies the global scope pre-check. Returns a non-null
     * {@code ServiceResult} when the claim is out of scope (caller should
     * return it directly), or {@code null} when the claim passes the pre-check
     * and the caller should proceed to the type-specific switch.
     */
    private ServiceResult<ClaimVerificationResult> applyScopePrecheck(OWLOntology ontology, Claim claim, OntologyId ontId) {
        if (claim.subject() == null) {
            return null; // no subject → no precheck to apply
        }
        boolean subjectInScope = isEntityInOntology(ontology, claim.subject(), ontId);
        boolean objectInScope = claim.object() == null || isEntityInOntology(ontology, claim.object(), ontId);
        if (subjectInScope && objectInScope) {
            return null;
        }

        // Build the out_of_scope response, naming the offending entity/entities
        StringBuilder sb = new StringBuilder("Subject or object is not declared in ontology '")
            .append(claim.ontologyId()).append("'.");
        if (!subjectInScope) {
            sb.append(" Offending subject: ").append(claim.subject().iri());
        }
        if (!objectInScope) {
            sb.append(" Offending object: ").append(claim.object().iri());
        }
        Verdict verdict = Verdict.OUT_OF_SCOPE;
        List<EvidenceItem> evidence = List.of();
        return buildResult(claim, ontId, verdict, evidence,
            Optional.of(UnknownReason.MISSING_ENTITY), Optional.of(sb.toString()));
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
            String ontIdStr = error.details() != null && error.details().get("ontologyId") != null
                ? (String) error.details().get("ontologyId") : null;
            // Guard: OntologyId must not be blank (DEFECT-024 fix)
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
            // Guard: avoid EntityId/OntologyId with blank values (DEFECT-024 fix)
            // Use ServiceError.of() with a descriptive message instead of entityNotFound()
            // which requires non-blank EntityId/OntologyId objects.
            if (entityIRI == null || entityIRI.isBlank()) {
                return ServiceResult.error(ServiceError.of(error.code(),
                    "Entity not found (IRI unavailable from error details)"));
            }
            // entityNotFound requires valid EntityId + OntologyId; use error code directly
            // to avoid potential OntologyId("") crash
            return ServiceResult.error(ServiceError.of(error.code(),
                "Entity not found: " + entityIRI));
        }
        return ServiceResult.error(error);
    }
}