package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import org.owl4agents.core.EntityId;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;
import org.owl4agents.retrieval.*;

import org.semanticweb.owlapi.model.OWLOntology;

import java.util.concurrent.Callable;

/**
 * CLI command for entity context retrieval.
 */
@Command(name = "entity", description = "Get entity context by IRI.")
public class EntityCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Entity IRI or label")
    private String entityIri;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        try {
            CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
            OntologyId ontId = new OntologyId(ontologyId);

            // Load ontology and build index
            OWLOntology ontology = factory.loadOntology(ontId);
            EntityIndex index = new EntityIndex();
            index.buildFromOntology(ontology);

            // Search for the entity
            EntitySearchService searchService = new EntitySearchService(index, ontId);
            ServiceResult<SearchResult> searchResult = searchService.search(entityIri);

            if (!searchResult.isSuccess()) {
                System.err.println("Error searching for entity: " + entityIri);
                return 1;
            }

            SearchResult results = ((ServiceResult.Success<SearchResult>) searchResult).data();
            if (results.results().isEmpty()) {
                System.err.println("Entity not found: " + entityIri);
                return 1;
            }

            // Get the first match
            SearchMatch match = results.results().get(0);
            EntityId entityId = new EntityId(match.iri());

            System.out.println("Entity: " + (match.label() != null ? match.label() : match.iri()));
            System.out.println("IRI: " + match.iri());
            System.out.println("Type: " + match.type().jsonName());
            System.out.println();

            // Get context based on entity type
            switch (match.type()) {
                case CLASS -> {
                    ClassContextService ctxService = new ClassContextService(index, ontId, ontology);
                    ServiceResult<ClassContext> ctx = ctxService.getClassContext(entityId);
                    if (ctx.isSuccess()) {
                        ClassContext cc = ((ServiceResult.Success<ClassContext>) ctx).data();
                        printClassContext(cc);
                    }
                }
                case OBJECT_PROPERTY -> {
                    ObjectPropertyContextService ctxService = new ObjectPropertyContextService(index, ontId, ontology);
                    ServiceResult<ObjectPropertyContext> ctx = ctxService.getObjectPropertyContext(entityId);
                    if (ctx.isSuccess()) {
                        ObjectPropertyContext pc = ((ServiceResult.Success<ObjectPropertyContext>) ctx).data();
                        printObjectPropertyContext(pc);
                    }
                }
                case DATA_PROPERTY -> {
                    DataPropertyContextService ctxService = new DataPropertyContextService(index, ontId, ontology);
                    ServiceResult<DataPropertyContext> ctx = ctxService.getDataPropertyContext(entityId);
                    if (ctx.isSuccess()) {
                        DataPropertyContext dc = ((ServiceResult.Success<DataPropertyContext>) ctx).data();
                        printDataPropertyContext(dc);
                    }
                }
                case INDIVIDUAL -> {
                    IndividualContextService ctxService = new IndividualContextService(index, ontId, ontology);
                    ServiceResult<IndividualContext> ctx = ctxService.getIndividualContext(entityId);
                    if (ctx.isSuccess()) {
                        IndividualContext ic = ((ServiceResult.Success<IndividualContext>) ctx).data();
                        printIndividualContext(ic);
                    }
                }
                default -> {
                    System.out.println("No detailed context available for this entity type.");
                }
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void printClassContext(ClassContext cc) {
        if (!cc.directSuperclasses().isEmpty()) {
            System.out.println("Superclasses: " + cc.directSuperclasses());
        }
        if (!cc.directSubclasses().isEmpty()) {
            System.out.println("Subclasses: " + cc.directSubclasses());
        }
        if (!cc.equivalentClasses().isEmpty()) {
            System.out.println("Equivalent: " + cc.equivalentClasses());
        }
        if (!cc.disjointClasses().isEmpty()) {
            System.out.println("Disjoint: " + cc.disjointClasses());
        }
    }

    private void printObjectPropertyContext(ObjectPropertyContext pc) {
        if (!pc.domain().isEmpty()) {
            System.out.println("Domain: " + pc.domain());
        }
        if (!pc.range().isEmpty()) {
            System.out.println("Range: " + pc.range());
        }
        if (!pc.inverseProperties().isEmpty()) {
            System.out.println("Inverse: " + pc.inverseProperties());
        }
        if (!pc.superProperties().isEmpty()) {
            System.out.println("Super properties: " + pc.superProperties());
        }
    }

    private void printDataPropertyContext(DataPropertyContext dc) {
        if (!dc.domain().isEmpty()) {
            System.out.println("Domain: " + dc.domain());
        }
        if (!dc.range().isEmpty()) {
            System.out.println("Range: " + dc.range());
        }
        if (dc.datatype() != null) {
            System.out.println("Datatype: " + dc.datatype());
        }
    }

    private void printIndividualContext(IndividualContext ic) {
        if (!ic.explicitTypes().isEmpty()) {
            System.out.println("Types: " + ic.explicitTypes());
        }
        if (!ic.objectPropertyAssertions().isEmpty()) {
            System.out.println("Object assertions:");
            for (ObjectPropertyAssertion a : ic.objectPropertyAssertions()) {
                System.out.println("  " + a.propertyIri() + " -> " + a.targetIri());
            }
        }
        if (!ic.dataPropertyAssertions().isEmpty()) {
            System.out.println("Data assertions:");
            for (DataPropertyAssertion a : ic.dataPropertyAssertions()) {
                System.out.println("  " + a.propertyIri() + " = " + a.literalValue());
            }
        }
    }
}