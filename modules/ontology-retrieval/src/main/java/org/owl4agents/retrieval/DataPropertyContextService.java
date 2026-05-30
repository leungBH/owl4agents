package org.owl4agents.retrieval;

import org.owl4agents.core.EntityId;
import org.owl4agents.core.EntityType;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.DataPropertyContext;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements data property context including domain, range,
 * datatype, and hierarchy.
 */
public class DataPropertyContextService {

    private final EntityIndex entityIndex;
    private final OntologyId ontologyId;
    private final OWLOntology ontology;

    public DataPropertyContextService(EntityIndex entityIndex, OntologyId ontologyId, OWLOntology ontology) {
        this.entityIndex = entityIndex;
        this.ontologyId = ontologyId;
        this.ontology = ontology;
    }

    public ServiceResult<DataPropertyContext> getDataPropertyContext(EntityId propertyIri) {
        Optional<EntityIndex.IndexEntry> entry = entityIndex.findByIri(propertyIri.iri());
        if (entry.isEmpty() || entry.get().type() != EntityType.DATA_PROPERTY) {
            return ServiceResult.error(ServiceError.entityNotFound(propertyIri, ontologyId));
        }

        EntityIndex.IndexEntry indexEntry = entry.get();
        OWLDataProperty prop = ontology.getOWLOntologyManager().getOWLDataFactory()
            .getOWLDataProperty(IRI.create(propertyIri.iri()));

        List<String> domain = getDomain(prop);
        List<String> range = getRange(prop);
        String datatype = getDatatype(prop);
        List<String> superProperties = getSuperProperties(prop);
        List<String> subProperties = getSubProperties(prop);

        DataPropertyContext context = new DataPropertyContext(
            indexEntry.iri(),
            indexEntry.prefixedName(),
            indexEntry.label(),
            indexEntry.comment(),
            domain,
            range,
            datatype,
            superProperties,
            subProperties
        );

        return ServiceResult.success(context, ResultMetadata.explicit(ontologyId));
    }

    private List<String> getDomain(OWLDataProperty prop) {
        return EntitySearcher.getDomains(prop, ontology).stream()
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .collect(Collectors.toList());
    }

    private List<String> getRange(OWLDataProperty prop) {
        return EntitySearcher.getRanges(prop, ontology).stream()
            .map(range -> {
                if (range.isDatatype()) {
                    return ((OWLDatatype) range).getIRI().toString();
                }
                return range.toString();
            })
            .collect(Collectors.toList());
    }

    private String getDatatype(OWLDataProperty prop) {
        // Return the primary datatype from the range
        Optional<OWLDataRange> firstRange = EntitySearcher.getRanges(prop, ontology).stream().findFirst();
        if (firstRange.isPresent() && firstRange.get().isDatatype()) {
            return ((OWLDatatype) firstRange.get()).getIRI().toString();
        }
        return null;
    }

    private List<String> getSuperProperties(OWLDataProperty prop) {
        // EntitySearcher.getSuperProperties returns Collection<OWLDataPropertyExpression>
        return EntitySearcher.getSuperProperties(prop, ontology).stream()
            .filter(expr -> expr instanceof OWLDataProperty)
            .map(expr -> ((OWLDataProperty) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#topDataProperty"))
            .collect(Collectors.toList());
    }

    private List<String> getSubProperties(OWLDataProperty prop) {
        return EntitySearcher.getSubProperties(prop, ontology).stream()
            .filter(expr -> expr instanceof OWLDataProperty)
            .map(expr -> ((OWLDataProperty) expr).getIRI().toString())
            .filter(iri -> !iri.contains("owl#bottomDataProperty"))
            .collect(Collectors.toList());
    }
}