package org.owl4agents.retrieval;

import org.owl4agents.core.EntityId;
import org.owl4agents.core.EntityType;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.DataPropertyAssertion;
import org.owl4agents.core.model.IndividualContext;
import org.owl4agents.core.model.ObjectPropertyAssertion;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;

/**
 * Implements individual context including explicit types,
 * object property assertions, and data property assertions.
 * v0.2: When a reasoner is available, includes inferred types.
 */
public class IndividualContextService {

    private final EntityIndex entityIndex;
    private final OntologyId ontologyId;
    private final OWLOntology ontology;
    private final Optional<OWLReasoner> reasoner;

    public IndividualContextService(EntityIndex entityIndex, OntologyId ontologyId, OWLOntology ontology) {
        this(entityIndex, ontologyId, ontology, null);
    }

    /**
     * v0.2 constructor: also accepts a reasoner for inferred content.
     */
    public IndividualContextService(EntityIndex entityIndex, OntologyId ontologyId, OWLOntology ontology, OWLReasoner reasoner) {
        this.entityIndex = entityIndex;
        this.ontologyId = ontologyId;
        this.ontology = ontology;
        this.reasoner = Optional.ofNullable(reasoner);
    }

    public ServiceResult<IndividualContext> getIndividualContext(EntityId individualIri) {
        Optional<EntityIndex.IndexEntry> entry = entityIndex.findByIri(individualIri.iri());
        if (entry.isEmpty() || entry.get().type() != EntityType.INDIVIDUAL) {
            return ServiceResult.error(ServiceError.entityNotFound(individualIri, ontologyId));
        }

        EntityIndex.IndexEntry indexEntry = entry.get();
        OWLNamedIndividual ind = ontology.getOWLOntologyManager().getOWLDataFactory()
            .getOWLNamedIndividual(IRI.create(individualIri.iri()));

        // Extract explicit types (class assertions)
        List<String> explicitTypes = getExplicitTypes(ind);

        // Extract object property assertions
        List<ObjectPropertyAssertion> objectAssertions = getObjectPropertyAssertions(ind);

        // Extract data property assertions
        List<DataPropertyAssertion> dataAssertions = getDataPropertyAssertions(ind);

        // v0.2: Add inferred types if reasoner is available
        if (reasoner.isPresent() && reasoner.get().isConsistent()) {
            OWLReasoner r = reasoner.get();
            List<String> inferredTypes = getInferredTypes(r, ind, explicitTypes);

            IndividualContext context = IndividualContext.withInferred(
                indexEntry.iri(), indexEntry.prefixedName(), indexEntry.label(), indexEntry.comment(),
                explicitTypes, objectAssertions, dataAssertions,
                r.getReasonerName(), inferredTypes
            );
            return ServiceResult.success(context, ResultMetadata.empty());
        }

        IndividualContext context = IndividualContext.explicit(
            indexEntry.iri(),
            indexEntry.prefixedName(),
            indexEntry.label(),
            indexEntry.comment(),
            explicitTypes,
            objectAssertions,
            dataAssertions
        );

        return ServiceResult.success(context, ResultMetadata.explicit(ontologyId));
    }

    /**
     * v0.2: Get inferred types not already in explicit types.
     */
    private List<String> getInferredTypes(OWLReasoner r, OWLNamedIndividual ind, List<String> explicitTypes) {
        List<String> inferred = new ArrayList<>();
        NodeSet<OWLClass> types = r.getTypes(ind, false);
        for (OWLClass cls : types.getFlattened()) {
            if (cls.isOWLThing()) continue;
            String iri = cls.getIRI().toString();
            if (!explicitTypes.contains(iri)) {
                inferred.add(iri);
            }
        }
        return inferred;
    }

    private List<String> getExplicitTypes(OWLNamedIndividual ind) {
        // OWL API 5.x: EntitySearcher.getTypes returns Stream<OWLClassExpression>
        return EntitySearcher.getTypes(ind, ontology)
            .filter(expr -> expr instanceof OWLClass)
            .map(expr -> ((OWLClass) expr).getIRI().toString())
            .collect(java.util.stream.Collectors.toList());
    }

    private List<ObjectPropertyAssertion> getObjectPropertyAssertions(OWLNamedIndividual ind) {
        List<ObjectPropertyAssertion> assertions = new ArrayList<>();

        ontology.getObjectPropertyAssertionAxioms(ind)
            .forEach(axiom -> {
                OWLObjectProperty prop = axiom.getProperty().asOWLObjectProperty();
                OWLIndividual object = axiom.getObject();
                String propIri = prop.getIRI().toString();
                String propLabel = entityIndex.findByIri(propIri)
                    .map(e -> e.label()).orElse(prop.getIRI().getShortForm());

                // Only named individuals have IRIs
                String objectIri;
                String objectLabel;
                if (object instanceof OWLNamedIndividual) {
                    objectIri = ((OWLNamedIndividual) object).getIRI().toString();
                    objectLabel = entityIndex.findByIri(objectIri)
                        .map(e -> e.label()).orElse(((OWLNamedIndividual) object).getIRI().getShortForm());
                } else {
                    objectIri = object.toString();
                    objectLabel = object.toString();
                }

                assertions.add(new ObjectPropertyAssertion(
                    propIri,
                    propLabel,
                    objectIri,
                    objectLabel
                ));
            });

        return assertions;
    }

    private List<DataPropertyAssertion> getDataPropertyAssertions(OWLNamedIndividual ind) {
        List<DataPropertyAssertion> assertions = new ArrayList<>();

        ontology.getDataPropertyAssertionAxioms(ind)
            .forEach(axiom -> {
                OWLDataProperty prop = axiom.getProperty().asOWLDataProperty();
                OWLLiteral value = axiom.getObject();

                String propIri = prop.getIRI().toString();
                String propLabel = entityIndex.findByIri(propIri)
                    .map(e -> e.label()).orElse(prop.getIRI().getShortForm());
                String datatypeIri = value.getDatatype().isString() ?
                    "http://www.w3.org/2001/XMLSchema#string" :
                    value.getDatatype().isInteger() ?
                        "http://www.w3.org/2001/XMLSchema#integer" :
                        value.getDatatype().isFloat() ?
                            "http://www.w3.org/2001/XMLSchema#float" :
                            value.getDatatype().isDouble() ?
                                "http://www.w3.org/2001/XMLSchema#double" :
                                value.getDatatype().isBoolean() ?
                                    "http://www.w3.org/2001/XMLSchema#boolean" :
                                    value.getDatatype().getIRI().toString();

                assertions.add(new DataPropertyAssertion(
                    propIri,
                    propLabel,
                    value.getLiteral(),
                    datatypeIri
                ));
            });

        return assertions;
    }
}