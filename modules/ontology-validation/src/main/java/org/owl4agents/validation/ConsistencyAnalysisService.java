package org.owl4agents.validation;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
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

/**
 * Consistency-analysis service implementation.
 * Provides class compatibility checking, individual membership checks,
 * relation assertion checks, and ontology scope description.
 */
public class ConsistencyAnalysisService {

    private final ReasonerLifecycleManager reasonerLifecycle;
    private final String workspaceBasePath;

    public ConsistencyAnalysisService(ReasonerLifecycleManager reasonerLifecycle, String workspaceBasePath) {
        this.reasonerLifecycle = reasonerLifecycle;
        this.workspaceBasePath = workspaceBasePath;
    }

    // ── Class Compatibility ──

    public ServiceResult<ClassCompatibilityResult> checkClassCompatibility(OntologyId ontologyId, String class1IRI, String class2IRI) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
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
                // Check if class1 is subclass of class2 or vice versa
                try {
                    OWLReasoner owlReasoner = getOWLReasonerFromAdapter(adapter.get());
                    if (owlReasoner != null) {
                        if (owlReasoner.isEntailed(df.getOWLSubClassOfAxiom(class1, class2)) ||
                            owlReasoner.isEntailed(df.getOWLSubClassOfAxiom(class2, class1))) {
                            return ServiceResult.success(
                                new ClassCompatibilityResult(ontologyId.id(), class1IRI, class2IRI,
                                    ClassCompatibilityResult.COMPATIBLE, adapter.get().getName()),
                                ResultMetadata.empty());
                        }

                        // Check if intersection is unsatisfiable
                        OWLClass intersection = df.getOWLClass(IRI.create("intersection:" + class1IRI + ":" + class2IRI));
                        OWLObjectIntersectionOf intersectExpr = df.getOWLObjectIntersectionOf(class1, class2);

                        // Check unsat together via reasoner
                        boolean unsatTogether = !owlReasoner.isSatisfiable(df.getOWLClass(
                            IRI.create("urn:temp:intersection_" + System.nanoTime())));
                        // Simplified check: if both classes are unsat, they're unsat together
                        // For a proper check, we'd create an intersection class

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

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
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
                    OWLReasoner owlReasoner = getOWLReasonerFromAdapter(adapter.get());
                    if (owlReasoner != null) {
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
                    OWLReasoner owlReasoner = getOWLReasonerFromAdapter(adapter.get());
                    if (owlReasoner != null) {
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

    // ── Private Helpers ──

    private OWLOntology loadOntology(OntologyId ontologyId) throws OWLOntologyCreationException {
        Path ontologyPath = Path.of(workspaceBasePath, "default", "ontologies", ontologyId.id(), "canonical", "ontology.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(ontologyPath.toFile());
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

    private OWLReasoner getOWLReasonerFromAdapter(OWLReasonerAdapter adapter) {
        // This is a temporary bridge. The full implementation will store
        // the underlying OWLReasoner in the adapter for direct access.
        return null; // Will be resolved when adapter exposes OWLReasoner
    }
}