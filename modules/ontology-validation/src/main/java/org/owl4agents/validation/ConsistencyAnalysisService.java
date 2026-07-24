package org.owl4agents.validation;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.EntitySignatureCache;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.stream.*;
import java.nio.file.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Consistency-analysis service implementation.
 * Provides class compatibility checking, individual membership checks,
 * relation assertion checks, and ontology scope description.
 */
public class ConsistencyAnalysisService {

    private static final Logger LOG = Logger.getLogger(ConsistencyAnalysisService.class.getName());

    private final ReasonerLifecycleManager reasonerLifecycle;
    private final String workspaceBasePath;
    private final OntologyCache ontologyCache;
    private final EntitySignatureCacheManager entitySignatureCacheManager;

    /**
     * @deprecated Use the 3-arg constructor with shared {@link OntologyCache}
     *             for cross-service cache sharing and workspaceName fix.
     *             This constructor creates a standalone cache with hardcoded
     *             {@code "default"} workspace (original buggy behavior).
     */
    @Deprecated
    public ConsistencyAnalysisService(ReasonerLifecycleManager reasonerLifecycle, String workspaceBasePath) {
        this.reasonerLifecycle = reasonerLifecycle;
        this.workspaceBasePath = workspaceBasePath;
        this.ontologyCache = new OntologyCache(workspaceBasePath, "default");
        this.entitySignatureCacheManager = null;
    }

    /**
     * @deprecated Use the 4-arg constructor with {@link EntitySignatureCacheManager}
     *             for O(1) entity signature lookups.
     */
    @Deprecated
    public ConsistencyAnalysisService(ReasonerLifecycleManager reasonerLifecycle,
                                       String workspaceBasePath,
                                       OntologyCache ontologyCache) {
        this.reasonerLifecycle = reasonerLifecycle;
        this.workspaceBasePath = workspaceBasePath;
        this.ontologyCache = ontologyCache;
        this.entitySignatureCacheManager = null;
    }

    public ConsistencyAnalysisService(ReasonerLifecycleManager reasonerLifecycle,
                                       String workspaceBasePath,
                                       OntologyCache ontologyCache,
                                       EntitySignatureCacheManager entitySignatureCacheManager) {
        this.reasonerLifecycle = reasonerLifecycle;
        this.workspaceBasePath = workspaceBasePath;
        this.ontologyCache = ontologyCache;
        this.entitySignatureCacheManager = entitySignatureCacheManager;
    }

    // ── Class Compatibility ──

    public ServiceResult<ClassCompatibilityResult> checkClassCompatibility(OntologyId ontologyId, String class1IRI, String class2IRI) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            return checkClassCompatibilityImpl(ontology, ontologyId, class1IRI, class2IRI);
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    /**
     * v0.8.4: Overload that accepts a pre-loaded {@link OWLOntology}, avoiding
     * redundant ontology loading in the claim verification hot path.
     */
    public ServiceResult<ClassCompatibilityResult> checkClassCompatibility(OWLOntology ontology, OntologyId ontologyId,
                                                                           String class1IRI, String class2IRI) {
        try {
            return checkClassCompatibilityImpl(ontology, ontologyId, class1IRI, class2IRI);
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    private ServiceResult<ClassCompatibilityResult> checkClassCompatibilityImpl(OWLOntology ontology, OntologyId ontologyId,
                                                                                String class1IRI, String class2IRI) {
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            // Check both classes exist
            boolean c1Found = ontology.getClassesInSignature(Imports.INCLUDED)
                .stream().anyMatch(c -> c.getIRI().toString().equals(class1IRI));
            boolean c2Found = ontology.getClassesInSignature(Imports.INCLUDED)
                .stream().anyMatch(c -> c.getIRI().toString().equals(class2IRI));

            if (!c1Found) {
                return ServiceResult.error(ErrorCode.CLASS_NOT_FOUND, "Class URI not found: " + class1IRI);
            }
            if (!c2Found) {
                return ServiceResult.error(ErrorCode.CLASS_NOT_FOUND, "Class URI not found: " + class2IRI);
            }

            // owl:Thing is compatible with any non-empty class
            if (class1IRI.equals("http://www.w3.org/2002/07/owl#Thing") ||
                class2IRI.equals("http://www.w3.org/2002/07/owl#Thing")) {
                return ServiceResult.success(
                    new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                        ClassCompatibilityResult.COMPATIBLE, null),
                    ResultMetadata.empty());
            }

            // Check explicit disjointness
            // v0.8.4 Decision 5: check EntitySignatureCache index first (O(1) lookup)
            if (entitySignatureCacheManager != null) {
                try {
                    EntitySignatureCache esc = entitySignatureCacheManager.getOrCreate(ontologyId, ontology);
                    if (esc.getDisjointClasses(class1IRI).contains(class2IRI) ||
                        esc.getDisjointClasses(class2IRI).contains(class1IRI)) {
                        return ServiceResult.success(
                            new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                                ClassCompatibilityResult.DISJOINT, null),
                            ResultMetadata.empty());
                    }
                } catch (Exception ignored) {
                }
            }

            boolean disjoint = ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)
                .stream().anyMatch(ax -> {
                    Set<OWLClass> classes = ax.getClassExpressionsAsList()
                        .stream().filter(OWLClassExpression::isNamed)
                        .map(ce -> ce.asOWLClass()).collect(Collectors.toSet());
                    String c1 = class1IRI;
                    String c2 = class2IRI;
                    return classes.stream().anyMatch(c -> c.getIRI().toString().equals(c1)) &&
                           classes.stream().anyMatch(c -> c.getIRI().toString().equals(c2));
                });

            if (disjoint) {
                return ServiceResult.success(
                    new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                        ClassCompatibilityResult.DISJOINT, null),
                    ResultMetadata.empty());
            }

            // Check if one is a subclass of the other (compatible)
            OWLClass class1 = df.getOWLClass(IRI.create(class1IRI));
            OWLClass class2 = df.getOWLClass(IRI.create(class2IRI));

            // Use reasoner if available
            Optional<OWLReasonerAdapter> adapter = reasonerLifecycle.getActiveReasoner(ontologyId);
            if (adapter.isPresent() && adapter.get().isActive()) {
                // v0.8.1: use adapter.getUnderlyingReasoner() instead of the
                // v0.8.0 null-casting bridge so the reasoner-driven path
                // actually runs against a live OWLReasoner.
                try {
                    org.semanticweb.owlapi.reasoner.OWLReasoner owlReasoner =
                        adapter.get().getUnderlyingReasoner();
                    if (owlReasoner != null) {
                        // v0.8.1: ensure classification has run before
                        // asking the reasoner entailment questions.
                        if (!reasonerLifecycle.isClassified(ontologyId)) {
                            try {
                                owlReasoner.precomputeInferences(
                                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY);
                            } catch (Exception ignore) { }
                            reasonerLifecycle.markClassified(ontologyId);
                        }

                        // v0.8.1 TC-14: if the reasoner explicitly entails
                        // DisjointClasses(class1, class2), return DISJOINT.
                        // This catches both asserted (covered above) and
                        // inferred disjointness from AllDisjointClasses
                        // groups, equivalent-class chains, etc.
                        try {
                            if (owlReasoner.isEntailed(
                                    df.getOWLDisjointClassesAxiom(class1, class2))) {
                                return ServiceResult.success(
                                    new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                                        ClassCompatibilityResult.DISJOINT, adapter.get().getName()),
                                    ResultMetadata.empty());
                            }
                        } catch (Exception ignore) { /* fall through */ }

                        if (owlReasoner.isEntailed(df.getOWLSubClassOfAxiom(class1, class2)) ||
                            owlReasoner.isEntailed(df.getOWLSubClassOfAxiom(class2, class1))) {
                            return ServiceResult.success(
                                new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                                    ClassCompatibilityResult.COMPATIBLE, adapter.get().getName()),
                                ResultMetadata.empty());
                        }

                        // v0.8.1 TC-14: check unsatisfiability of the
                        // intersection. If class1 ⊓ class2 is unsatisfiable,
                        // the two classes are effectively disjoint.
                        try {
                            OWLObjectIntersectionOf intersectExpr =
                                df.getOWLObjectIntersectionOf(class1, class2);
                            if (!owlReasoner.isSatisfiable(intersectExpr)) {
                                return ServiceResult.success(
                                    new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                                        ClassCompatibilityResult.UNSATISFIABLE_TOGETHER,
                                        adapter.get().getName()),
                                    ResultMetadata.empty());
                            }
                        } catch (Exception ignore) { /* fall through */ }

                        return ServiceResult.success(
                            new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                                ClassCompatibilityResult.UNKNOWN, adapter.get().getName()),
                            ResultMetadata.empty());
                    }
                } catch (Exception e) {
                    // Fall back to explicit checks
                }
            }

            // Without reasoner, check explicit subclass hierarchy
            boolean explicitSubclass = ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)
                .stream().anyMatch(ax -> {
                    if (ax.getSubClass().isNamed() && ax.getSuperClass().isNamed()) {
                        String sub = ax.getSubClass().asOWLClass().getIRI().toString();
                        String sup = ax.getSuperClass().asOWLClass().getIRI().toString();
                        return (sub.equals(class1IRI) && sup.equals(class2IRI)) ||
                               (sub.equals(class2IRI) && sup.equals(class1IRI));
                    }
                    return false;
                });

            if (explicitSubclass) {
                return ServiceResult.success(
                    new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                        ClassCompatibilityResult.COMPATIBLE, null),
                    ResultMetadata.empty());
            }

            // Default: unknown
            return ServiceResult.success(
                new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                    ClassCompatibilityResult.UNKNOWN, null),
                ResultMetadata.empty());
    }

    // ── Individual Membership ──

    public ServiceResult<MembershipResult> checkIndividualMembership(OntologyId ontologyId, String individualIRI, String classIRI, Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            // Check individual exists
            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(individualIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Individual URI not found: " + individualIRI);
            }

            // Check class exists
            if (!ontology.getClassesInSignature(Imports.INCLUDED).stream().anyMatch(c -> c.getIRI().toString().equals(classIRI))) {
                return ServiceResult.error(ErrorCode.CLASS_NOT_FOUND, "Class URI not found: " + classIRI);
            }

            // Check explicit membership
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(individualIRI));
            OWLClass cls = df.getOWLClass(IRI.create(classIRI));

            boolean explicit = ontology.getAxioms(AxiomType.CLASS_ASSERTION, Imports.INCLUDED)
                .stream().anyMatch(ax -> ax.getIndividual().equals(ind) &&
                               ax.getClassExpression().equals(cls));

            // Check inferred membership via reasoner
            Optional<OWLReasonerAdapter> adapter = reasonerLifecycle.getActiveReasoner(ontologyId);
            boolean inferred = false;

            if (adapter.isPresent() && adapter.get().isActive()) {
                try {
                    org.semanticweb.owlapi.reasoner.OWLReasoner owlReasoner =
                        adapter.get().getUnderlyingReasoner();
                    if (owlReasoner != null) {
                        if (!reasonerLifecycle.isClassified(ontologyId)) {
                            try {
                                owlReasoner.precomputeInferences(
                                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY);
                            } catch (Exception ignore) { }
                            reasonerLifecycle.markClassified(ontologyId);
                        }
                        NodeSet<OWLClass> types = owlReasoner.getTypes(ind, false);
                        inferred = types.getFlattened().contains(cls);
                    }
                } catch (Exception e) {
                    inferred = false;
                }
            }

            String membershipType;
            if (explicit && inferred) {
                membershipType = MembershipResult.BOTH;
            } else if (explicit) {
                membershipType = MembershipResult.EXPLICIT;
            } else if (inferred) {
                membershipType = MembershipResult.INFERRED;
            } else {
                membershipType = null;
            }

            return ServiceResult.success(
                new MembershipResult(ontologyId.id(), individualIRI, classIRI,
                    explicit || inferred, membershipType,
                    adapter.map(a -> a.getName()).orElse(null)),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Relation Assertion ──

    public ServiceResult<RelationAssertionResult> checkRelationAssertion(OntologyId ontologyId, String sourceIRI, String propertyIRI, String targetIRI, Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            // Check entities exist
            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(sourceIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Source individual not found: " + sourceIRI);
            }
            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(targetIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Target individual not found: " + targetIRI);
            }
            if (!ontology.getObjectPropertiesInSignature(Imports.INCLUDED).stream().anyMatch(p -> p.getIRI().toString().equals(propertyIRI))) {
                return ServiceResult.error(ErrorCode.PROPERTY_NOT_FOUND, "Property URI not found: " + propertyIRI);
            }

            OWLNamedIndividual source = df.getOWLNamedIndividual(IRI.create(sourceIRI));
            OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propertyIRI));
            OWLNamedIndividual target = df.getOWLNamedIndividual(IRI.create(targetIRI));

            // Check explicit assertion
            boolean explicit = ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION, Imports.INCLUDED)
                .stream().anyMatch(ax -> ax.getSubject().equals(source) &&
                               ax.getProperty().equals(prop) &&
                               ax.getObject().equals(target));

            // Check inferred via reasoner
            Optional<OWLReasonerAdapter> adapter = reasonerLifecycle.getActiveReasoner(ontologyId);
            boolean inferred = false;

            if (adapter.isPresent() && adapter.get().isActive()) {
                try {
                    org.semanticweb.owlapi.reasoner.OWLReasoner owlReasoner =
                        adapter.get().getUnderlyingReasoner();
                    if (owlReasoner != null) {
                        if (!reasonerLifecycle.isClassified(ontologyId)) {
                            try {
                                owlReasoner.precomputeInferences(
                                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY);
                            } catch (Exception ignore) { }
                            reasonerLifecycle.markClassified(ontologyId);
                        }
                        NodeSet<OWLNamedIndividual> values = owlReasoner.getObjectPropertyValues(source, prop);
                        inferred = values.getFlattened().contains(target);
                    }
                } catch (Exception e) {
                    inferred = false;
                }
            }

            String assertionType;
            if (explicit && inferred) {
                assertionType = RelationAssertionResult.BOTH;
            } else if (explicit) {
                assertionType = RelationAssertionResult.EXPLICIT;
            } else if (inferred) {
                assertionType = RelationAssertionResult.INFERRED;
            } else {
                assertionType = null;
            }

            return ServiceResult.success(
                new RelationAssertionResult(ontologyId.id(), sourceIRI, propertyIRI, targetIRI,
                    explicit || inferred, assertionType,
                    adapter.map(a -> a.getName()).orElse(null)),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Scope Description ──

    public ServiceResult<ScopeDescription> getScope(OntologyId ontologyId) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);

            // Derive covered domains from top-level classes and namespace
            List<String> coveredDomains = new ArrayList<>();
            String ontologyIRI = "";
            if (ontology.getOntologyID().getOntologyIRI().isPresent()) {
                ontologyIRI = ontology.getOntologyID().getOntologyIRI().get().toString();
            }

            // Get top-level classes (those with only owl:Thing as superclass)
            for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                // Check if this is a top-level class (only has owl:Thing as explicit superclass)
                boolean topLevel = ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)
                    .stream().filter(ax -> ax.getSubClass().equals(cls) && ax.getSuperClass().isNamed())
                    .noneMatch(ax -> !ax.getSuperClass().asOWLClass().isOWLThing());

                if (topLevel) {
                    // Get label for domain naming
                    String label = getLabel(cls, ontology);
                    coveredDomains.add(label != null ? label : cls.getIRI().getFragment());
                }
            }

            // Derive known gaps
            List<String> knownGaps = new ArrayList<>();
            // Check if ontology lacks key properties
            boolean hasObjectProperties = ontology.getObjectPropertiesInSignature(Imports.INCLUDED).stream().findAny().isPresent();
            boolean hasDataProperties = ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream().findAny().isPresent();

            if (!hasObjectProperties) {
                knownGaps.add("No object properties linking class hierarchy nodes");
            }
            if (!hasDataProperties) {
                knownGaps.add("No data properties for attribute descriptions");
            }

            // Derive profile limitations
            List<String> profileLimitations = new ArrayList<>();
            String profile = detectProfile(ontology);
            if ("OWL 2 EL".equals(profile)) {
                profileLimitations.add("No disjointness axioms support");
                profileLimitations.add("No union of class expressions");
                profileLimitations.add("No cardinality restrictions (except max 1)");
            } else if ("OWL 2 QL".equals(profile)) {
                profileLimitations.add("Limited class expression nesting");
            }

            // Unsupported feature types
            List<String> unsupportedFeatureTypes = new ArrayList<>();
            // Check what the ontology doesn't use
            boolean hasRestrictions = ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)
                .stream().anyMatch(ax -> ax.getSuperClass() instanceof OWLRestriction);
            boolean hasIndividuals = ontology.getIndividualsInSignature(Imports.INCLUDED).stream().findAny().isPresent();
            boolean hasSWRL = ontology.getAxioms(AxiomType.SWRL_RULE, Imports.INCLUDED).stream().findAny().isPresent();

            if (!hasRestrictions) {
                unsupportedFeatureTypes.add("Class restrictions (someValuesFrom, allValuesFrom, hasValue, cardinality)");
            }
            if (!hasIndividuals) {
                unsupportedFeatureTypes.add("Individual assertions and fact checking");
            }
            unsupportedFeatureTypes.add("SWRL rules and rule-based inference");

            return ServiceResult.success(
                new ScopeDescription(ontologyId.id(), coveredDomains, knownGaps, profileLimitations, unsupportedFeatureTypes),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.SCOPE_ANALYSIS_FAILED, e.getMessage());
        }
    }

    // ── Entity existence check (used by v0.3 scope verification & missing-entities) ──

    /**
     * Check whether an entity IRI is declared in the ontology's signature.
     * The {@code kind} parameter narrows the lookup to a specific entity type
     * ("class", "objectProperty", "dataProperty", "individual"); when {@code null}
     * or unknown, all four signatures are searched.
     */
    public boolean isEntityDeclared(OntologyId ontologyId, String entityIRI, String kind) {
        if (entityIRI == null || entityIRI.isBlank()) return false;
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            return isEntityDeclared(ontology, ontologyId, entityIRI, kind);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Overload that accepts a pre-loaded {@link OWLOntology}, avoiding redundant
     * ontology loading when the caller already has the ontology in hand (v0.8.4
     * per-request single-load optimization).
     */
    public boolean isEntityDeclared(OWLOntology ontology, OntologyId ontologyId, String entityIRI, String kind) {
        if (entityIRI == null || entityIRI.isBlank()) return false;
        try {
            // v0.8.4: fast path via EntitySignatureCache (O(1) lookup)
            if (entitySignatureCacheManager != null) {
                EntitySignatureCache cache = entitySignatureCacheManager.getOrCreate(ontologyId, ontology);
                if (cache != null) {
                    boolean found = cache.contains(kind, entityIRI);
                    if (!found) {
                        LOG.warning("isEntityDeclared CACHE MISS: ontology=" + ontologyId.id()
                            + " kind=" + kind + " iri=" + entityIRI
                            + " ontologyIRI=" + (ontology.getOntologyID().getOntologyIRI().isPresent()
                                ? ontology.getOntologyID().getOntologyIRI().get().toString() : "(none)"));
                    }
                    return found;
                } else {
                    LOG.warning("isEntityDeclared cache NULL (build failed), falling back to stream scan: ontology="
                        + ontologyId.id() + " iri=" + entityIRI);
                }
            }
            // Fallback: v0.8.3 stream scan (deprecated constructors with manager=null)
            boolean found = isEntityDeclaredStreamScan(ontology, entityIRI, kind);
            if (!found) {
                LOG.warning("isEntityDeclared STREAM SCAN MISS: ontology=" + ontologyId.id()
                    + " kind=" + kind + " iri=" + entityIRI);
            }
            return found;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "isEntityDeclared exception for ontology=" + ontologyId.id()
                + " iri=" + entityIRI, e);
            return false;
        }
    }

    /**
     * v0.8.3 fallback: full stream scan for entity declaration.
     * Used when {@code entitySignatureCacheManager} is null (deprecated constructors).
     *
     * <p>v0.8.5 fix: checks signature only (Imports.EXCLUDED), without requiring
     * an explicit Declaration axiom. Per OWL 2 spec, an entity referenced in any
     * axiom is part of the ontology signature. This is consistent with
     * {@link EntitySignatureCache#build(OWLOntology)} which now collects from
     * both Declaration axioms and the signature.</p>
     */
    private boolean isEntityDeclaredStreamScan(OWLOntology ontology, String entityIRI, String kind) {
        // v0.9.0 fix: RESTORED the OBO namespace prefix check with correct
        // contains() logic (same fix as EntitySignatureCache.contains()).
        // When the ontology has an OBO prefix (e.g., MONDO, HP), entities
        // from other OBO namespaces (BFO_, RO_, IAO_, UBERON_, CL_) are
        // rejected as out_of_scope. When oboPrefix is null (pizza, sosa),
        // the check is skipped.
        String oboPrefix = null;
        if (ontology.getOntologyID().getOntologyIRI().isPresent()) {
            String ontIri = ontology.getOntologyID().getOntologyIRI().get().toString();
            if (ontIri.startsWith("http://purl.obolibrary.org/obo/") && ontIri.endsWith(".owl")) {
                String fileName = ontIri.substring(ontIri.lastIndexOf('/') + 1);
                oboPrefix = fileName.substring(0, fileName.length() - ".owl".length()).toUpperCase();
            }
        }
        if (oboPrefix != null && !entityIRI.contains("/" + oboPrefix + "_")) {
            return false;
        }

        String k = kind == null ? "" : kind.toLowerCase().replace("_", "");
        IRI iri = IRI.create(entityIRI);

        if (k.equals("class") || k.isEmpty()) {
            if (ontology.getClassesInSignature(Imports.EXCLUDED)
                .stream().anyMatch(c -> c.getIRI().equals(iri))) {
                return true;
            }
        }
        if (k.equals("objectproperty") || k.equals("property") || k.isEmpty()) {
            if (ontology.getObjectPropertiesInSignature(Imports.EXCLUDED)
                .stream().anyMatch(p -> p.getIRI().equals(iri))) {
                return true;
            }
        }
        if (k.equals("dataproperty") || k.equals("property") || k.isEmpty()) {
            if (ontology.getDataPropertiesInSignature(Imports.EXCLUDED)
                .stream().anyMatch(p -> p.getIRI().equals(iri))) {
                return true;
            }
        }
        if (k.equals("individual") || k.isEmpty()) {
            if (ontology.getIndividualsInSignature(Imports.EXCLUDED)
                .stream().anyMatch(i -> i.getIRI().equals(iri))) {
                return true;
            }
        }
        return false;
    }

    // ── Private Helpers ──

    private OWLOntology loadOntology(OntologyId ontologyId) throws OWLOntologyCreationException {
        return ontologyCache.getOrCreate(ontologyId);
    }

    private String detectProfile(OWLOntology ontology) {
        try {
            org.semanticweb.owlapi.profiles.OWL2DLProfile dlProfile = new org.semanticweb.owlapi.profiles.OWL2DLProfile();
            org.semanticweb.owlapi.profiles.OWL2ELProfile elProfile = new org.semanticweb.owlapi.profiles.OWL2ELProfile();

            if (elProfile.checkOntology(ontology).getViolations().isEmpty()) return "OWL 2 EL";
            if (dlProfile.checkOntology(ontology).getViolations().isEmpty()) return "OWL 2 DL";
            return "OWL 2 Full";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION, Imports.INCLUDED)) {
            if (ax.getSubject().equals(entity.getIRI()) &&
                ax.getProperty().isLabel()) {
                if (ax.getValue() instanceof OWLLiteral literal) {
                    return literal.getLiteral();
                }
            }
        }
        return null;
    }
}