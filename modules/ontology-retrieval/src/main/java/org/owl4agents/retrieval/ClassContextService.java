package org.owl4agents.retrieval;

import org.owl4agents.core.EntityId;
import org.owl4agents.core.EntityType;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ClassContext;
import org.owl4agents.core.model.RestrictionInfo;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements class context retrieval including subclasses, superclasses,
 * equivalent classes, disjoint classes, and basic restrictions from explicit axioms.
 */
public class ClassContextService {

    private final EntityIndex entityIndex;
    private final OntologyId ontologyId;
    private final OWLOntology ontology;

    public ClassContextService(EntityIndex entityIndex, OntologyId ontologyId, OWLOntology ontology) {
        this.entityIndex = entityIndex;
        this.ontologyId = ontologyId;
        this.ontology = ontology;
    }

    /**
     * Get class context for a given class IRI.
     */
    public ServiceResult<ClassContext> getClassContext(EntityId classIri) {
        Optional<EntityIndex.IndexEntry> entry = entityIndex.findByIri(classIri.iri());
        if (entry.isEmpty() || entry.get().type() != EntityType.CLASS) {
            return ServiceResult.error(ServiceError.entityNotFound(classIri, ontologyId));
        }

        EntityIndex.IndexEntry indexEntry = entry.get();

        // Find the OWLClass in the ontology
        OWLClass cls = ontology.getOWLOntologyManager().getOWLDataFactory()
            .getOWLClass(IRI.create(classIri.iri()));

        // Extract direct subclasses
        List<String> directSubclasses = getDirectSubclasses(cls);

        // Extract direct superclasses
        List<String> directSuperclasses = getDirectSuperclasses(cls);

        // Extract equivalent classes
        List<String> equivalentClasses = getEquivalentClasses(cls);

        // Extract disjoint classes
        List<String> disjointClasses = getDisjointClasses(cls);

        // Extract basic restrictions from explicit axioms
        List<RestrictionInfo> restrictions = getRestrictions(cls);

        ClassContext context = new ClassContext(
            indexEntry.iri(),
            indexEntry.prefixedName(),
            indexEntry.label(),
            indexEntry.comment(),
            directSubclasses,
            directSuperclasses,
            equivalentClasses,
            disjointClasses,
            restrictions
        );

        return ServiceResult.success(context, ResultMetadata.explicit(ontologyId));
    }

    private List<String> getDirectSubclasses(OWLClass cls) {
        // EntitySearcher.getSubClasses returns Collection<OWLClassExpression>
        // Only named classes (OWLClass) have IRI
        return EntitySearcher.getSubClasses(cls, ontology).stream()
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#Nothing"))
            .collect(Collectors.toList());
    }

    private List<String> getDirectSuperclasses(OWLClass cls) {
        return EntitySearcher.getSuperClasses(cls, ontology).stream()
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#Thing"))
            .collect(Collectors.toList());
    }

    private List<String> getEquivalentClasses(OWLClass cls) {
        return EntitySearcher.getEquivalentClasses(cls, ontology).stream()
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#Thing") && !iri.contains("owl#Nothing"))
            .collect(Collectors.toList());
    }

    private List<String> getDisjointClasses(OWLClass cls) {
        List<String> disjoint = new ArrayList<>();

        // Individual disjointWith axioms
        ontology.getDisjointClassesAxioms(cls).stream()
            .flatMap(axiom -> axiom.getClassExpressions().stream())
            .filter(expr -> expr instanceof OWLClass && !expr.equals(cls))
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .forEach(disjoint::add);

        // AllDisjointClasses axioms containing this class
        ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.EXCLUDED).stream()
            .filter(axiom -> axiom.getClassExpressions().contains(cls))
            .flatMap(axiom -> axiom.getClassExpressions().stream())
            .filter(expr -> expr instanceof OWLClass && !expr.equals(cls))
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .forEach(disjoint::add);

        return disjoint;
    }

    private List<RestrictionInfo> getRestrictions(OWLClass cls) {
        List<RestrictionInfo> restrictions = new ArrayList<>();

        // Get restrictions from superclass axioms — anonymous expressions only
        EntitySearcher.getSuperClasses(cls, ontology).stream()
            .filter(expr -> expr instanceof OWLClassExpression && !(expr instanceof OWLClass))
            .forEach(expr -> {
                if (expr instanceof OWLObjectSomeValuesFrom) {
                    OWLObjectSomeValuesFrom svf = (OWLObjectSomeValuesFrom) expr;
                    restrictions.add(new RestrictionInfo(
                        "someValuesFrom",
                        svf.getProperty().asOWLObjectProperty().getIRI().toString(),
                        svf.getFiller().isNamed() ?
                            ((OWLClass) svf.getFiller()).getIRI().toString() :
                            svf.getFiller().toString(),
                        null
                    ));
                } else if (expr instanceof OWLObjectAllValuesFrom) {
                    OWLObjectAllValuesFrom avf = (OWLObjectAllValuesFrom) expr;
                    restrictions.add(new RestrictionInfo(
                        "allValuesFrom",
                        avf.getProperty().asOWLObjectProperty().getIRI().toString(),
                        avf.getFiller().isNamed() ?
                            ((OWLClass) avf.getFiller()).getIRI().toString() :
                            avf.getFiller().toString(),
                        null
                    ));
                } else if (expr instanceof OWLObjectHasValue) {
                    OWLObjectHasValue hv = (OWLObjectHasValue) expr;
                    restrictions.add(new RestrictionInfo(
                        "hasValue",
                        hv.getProperty().asOWLObjectProperty().getIRI().toString(),
                        hv.getFiller() instanceof OWLNamedIndividual ?
                            ((OWLNamedIndividual) hv.getFiller()).getIRI().toString() :
                            hv.getFiller().toString(),
                        null
                    ));
                } else if (expr instanceof OWLObjectMinCardinality) {
                    OWLObjectMinCardinality mc = (OWLObjectMinCardinality) expr;
                    restrictions.add(new RestrictionInfo(
                        "cardinality",
                        mc.getProperty().asOWLObjectProperty().getIRI().toString(),
                        mc.getFiller().isNamed() ?
                            ((OWLClass) mc.getFiller()).getIRI().toString() :
                            mc.getFiller().toString(),
                        mc.getCardinality()
                    ));
                }
            });

        return restrictions;
    }
}