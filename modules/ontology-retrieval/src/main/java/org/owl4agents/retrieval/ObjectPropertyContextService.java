package org.owl4agents.retrieval;

import org.owl4agents.core.EntityId;
import org.owl4agents.core.EntityType;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ObjectPropertyContext;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements object property context including domain, range,
 * inverse properties, and hierarchy.
 */
public class ObjectPropertyContextService {

    private final EntityIndex entityIndex;
    private final OntologyId ontologyId;
    private final OWLOntology ontology;

    public ObjectPropertyContextService(EntityIndex entityIndex, OntologyId ontologyId, OWLOntology ontology) {
        this.entityIndex = entityIndex;
        this.ontologyId = ontologyId;
        this.ontology = ontology;
    }

    public ServiceResult<ObjectPropertyContext> getObjectPropertyContext(EntityId propertyIri) {
        Optional<EntityIndex.IndexEntry> entry = entityIndex.findByIri(propertyIri.iri());
        if (entry.isEmpty() || entry.get().type() != EntityType.OBJECT_PROPERTY) {
            return ServiceResult.error(ServiceError.entityNotFound(propertyIri, ontologyId));
        }

        EntityIndex.IndexEntry indexEntry = entry.get();
        OWLObjectProperty prop = ontology.getOWLOntologyManager().getOWLDataFactory()
            .getOWLObjectProperty(IRI.create(propertyIri.iri()));

        List<String> domain = getDomain(prop);
        List<String> range = getRange(prop);
        List<String> inverseProperties = getInverseProperties(prop);
        List<String> superProperties = getSuperProperties(prop);
        List<String> subProperties = getSubProperties(prop);
        List<String> characteristics = getCharacteristics(prop);

        ObjectPropertyContext context = ObjectPropertyContext.explicit(
            indexEntry.iri(),
            indexEntry.prefixedName(),
            indexEntry.label(),
            indexEntry.comment(),
            domain,
            range,
            inverseProperties,
            superProperties,
            subProperties,
            characteristics
        );

        return ServiceResult.success(context, ResultMetadata.explicit(ontologyId));
    }

    private List<String> getDomain(OWLObjectProperty prop) {
        // OWL API 5.x: EntitySearcher.getDomains returns Stream<OWLClassExpression>
        return EntitySearcher.getDomains(prop, ontology)
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .collect(Collectors.toList());
    }

    private List<String> getRange(OWLObjectProperty prop) {
        return EntitySearcher.getRanges(prop, ontology)
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .collect(Collectors.toList());
    }

    private List<String> getInverseProperties(OWLObjectProperty prop) {
        // OWL API 5.x: EntitySearcher.getInverses returns Stream<OWLObjectPropertyExpression>
        return EntitySearcher.getInverses(prop, ontology)
            .filter(expr -> expr instanceof OWLObjectProperty)
            .map(expr -> ((OWLObjectProperty) expr).getIRI().toString())
            .collect(Collectors.toList());
    }

    private List<String> getSuperProperties(OWLObjectProperty prop) {
        return EntitySearcher.getSuperProperties(prop, ontology)
            .filter(expr -> expr instanceof OWLObjectProperty)
            .map(expr -> ((OWLObjectProperty) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#topObjectProperty"))
            .collect(Collectors.toList());
    }

    private List<String> getSubProperties(OWLObjectProperty prop) {
        return EntitySearcher.getSubProperties(prop, ontology)
            .filter(expr -> expr instanceof OWLObjectProperty)
            .map(expr -> ((OWLObjectProperty) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#bottomObjectProperty"))
            .collect(Collectors.toList());
    }

    private List<String> getCharacteristics(OWLObjectProperty prop) {
        List<String> characteristics = new ArrayList<>();

        if (EntitySearcher.isFunctional(prop, ontology)) characteristics.add("functional");
        if (EntitySearcher.isInverseFunctional(prop, ontology)) characteristics.add("inverseFunctional");
        if (EntitySearcher.isTransitive(prop, ontology)) characteristics.add("transitive");
        if (EntitySearcher.isSymmetric(prop, ontology)) characteristics.add("symmetric");
        if (EntitySearcher.isAsymmetric(prop, ontology)) characteristics.add("asymmetric");
        if (EntitySearcher.isReflexive(prop, ontology)) characteristics.add("reflexive");
        if (EntitySearcher.isIrreflexive(prop, ontology)) characteristics.add("irreflexive");

        return characteristics;
    }
}