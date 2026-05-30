package org.owl4agents.retrieval;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.core.evidence.EvidenceMetadata;

import org.semanticweb.owlapi.model.OWLOntology;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates ontology-grounded QA context from matched entities,
 * class context, property context, individual context,
 * and optional SPARQL evidence.
 */
public class QaContextService {

    private final EntityIndex entityIndex;
    private final OntologyId ontologyId;
    private final OWLOntology ontology;

    private final EntitySearchService searchService;
    private final ClassContextService classContextService;
    private final ObjectPropertyContextService objPropContextService;
    private final DataPropertyContextService dataPropContextService;
    private final IndividualContextService individualContextService;

    public QaContextService(EntityIndex entityIndex, OntologyId ontologyId, OWLOntology ontology) {
        this.entityIndex = entityIndex;
        this.ontologyId = ontologyId;
        this.ontology = ontology;

        this.searchService = new EntitySearchService(entityIndex, ontologyId);
        this.classContextService = new ClassContextService(entityIndex, ontologyId, ontology);
        this.objPropContextService = new ObjectPropertyContextService(entityIndex, ontologyId, ontology);
        this.dataPropContextService = new DataPropertyContextService(entityIndex, ontologyId, ontology);
        this.individualContextService = new IndividualContextService(entityIndex, ontologyId, ontology);
    }

    /**
     * Generate QA context from a question and ontology.
     * Uses entity and label indexes for question-to-entity matching.
     */
    public ServiceResult<QaContext> generateContext(String question,
                                                     Optional<Integer> maxEntities,
                                                     Optional<Integer> maxDepth) {
        int maxEntitiesValue = maxEntities.orElse(10);
        int maxDepthValue = maxDepth.orElse(3);

        // 1. Match entities from the question
        List<SearchMatch> matchedEntities = matchEntities(question, maxEntitiesValue);

        // 2. Build context sections
        List<ClassContext> classContextList = new ArrayList<>();
        List<ObjectPropertyContext> propertyContextList = new ArrayList<>();
        List<IndividualContext> individualContextList = new ArrayList<>();
        List<SparqlEvidence> sparqlEvidence = new ArrayList<>();
        List<QaWarning> warnings = new ArrayList<>();

        for (SearchMatch match : matchedEntities) {
            EntityId entityId = new EntityId(match.iri());

            switch (match.type()) {
                case CLASS -> {
                    ServiceResult<ClassContext> ctx = classContextService.getClassContext(entityId);
                    if (ctx.isSuccess()) {
                        classContextList.add(((ServiceResult.Success<ClassContext>) ctx).data());
                    }
                }
                case OBJECT_PROPERTY -> {
                    ServiceResult<ObjectPropertyContext> ctx = objPropContextService.getObjectPropertyContext(entityId);
                    if (ctx.isSuccess()) {
                        propertyContextList.add(((ServiceResult.Success<ObjectPropertyContext>) ctx).data());
                    }
                }
                case DATA_PROPERTY -> {
                    ServiceResult<DataPropertyContext> ctx = dataPropContextService.getDataPropertyContext(entityId);
                    if (ctx.isSuccess()) {
                        // Convert data property to object property context shape for QA output
                        // Data property context is included via propertyContext
                        DataPropertyContext dpCtx = ((ServiceResult.Success<DataPropertyContext>) ctx).data();
                        propertyContextList.add(new ObjectPropertyContext(
                            dpCtx.iri(), dpCtx.prefixedName(), dpCtx.label(), dpCtx.comment(),
                            dpCtx.domain(), dpCtx.range(), List.of(), List.of(), dpCtx.subProperties(),
                            List.of("data_property")));
                    }
                }
                case INDIVIDUAL -> {
                    ServiceResult<IndividualContext> ctx = individualContextService.getIndividualContext(entityId);
                    if (ctx.isSuccess()) {
                        individualContextList.add(((ServiceResult.Success<IndividualContext>) ctx).data());
                    }
                }
            }
        }

        // 3. Check for no-match situation
        if (matchedEntities.isEmpty()) {
            warnings.add(new QaWarning(
                QaWarning.TYPE_NO_MATCH,
                "No ontology entities matched the question terms: '" + question + "'",
                QaWarning.SEVERITY_LOW_CONFIDENCE
            ));
        }

        // 4. Build natural language context
        String naturalLanguageContext = buildNaturalLanguageContext(
            question, matchedEntities, classContextList, propertyContextList, individualContextList, warnings);

        // 5. Build bounds
        ContextBounds bounds = new ContextBounds(maxEntitiesValue, maxDepthValue,
            determineIncludedSections(classContextList, propertyContextList, individualContextList));

        QaContext qaContext = new QaContext(
            question, matchedEntities, classContextList, propertyContextList,
            individualContextList, sparqlEvidence, naturalLanguageContext, bounds, warnings
        );

        return ServiceResult.success(qaContext, ResultMetadata.explicit(ontologyId));
    }

    /**
     * Match entities from a natural language question using the entity and label indexes.
     */
    private List<SearchMatch> matchEntities(String question, int maxEntities) {
        // Split the question into tokens and search for each
        String[] tokens = question.split("\\s+");
        List<SearchMatch> allMatches = new ArrayList<>();

        // Search the full question first
        ServiceResult<SearchResult> fullResult = searchService.search(question);
        if (fullResult.isSuccess()) {
            allMatches.addAll(((ServiceResult.Success<SearchResult>) fullResult).data().results());
        }

        // Also search individual significant tokens
        for (String token : tokens) {
            if (token.length() < 3) continue; // Skip short words
            ServiceResult<SearchResult> tokenResult = searchService.search(token);
            if (tokenResult.isSuccess()) {
                for (SearchMatch match : ((ServiceResult.Success<SearchResult>) tokenResult).data().results()) {
                    if (!allMatches.stream().anyMatch(m -> m.iri().equals(match.iri()))) {
                        allMatches.add(match);
                    }
                }
            }
        }

        // Sort by score and limit
        allMatches.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (allMatches.size() > maxEntities) {
            return allMatches.subList(0, maxEntities);
        }
        return allMatches;
    }

    private String buildNaturalLanguageContext(String question,
                                                List<SearchMatch> matchedEntities,
                                                List<ClassContext> classContexts,
                                                List<ObjectPropertyContext> propertyContexts,
                                                List<IndividualContext> individualContexts,
                                                List<QaWarning> warnings) {
        StringBuilder sb = new StringBuilder();

        sb.append("Question: ").append(question).append("\n\n");

        if (warnings.stream().anyMatch(w -> w.type().equals(QaWarning.TYPE_NO_MATCH))) {
            sb.append("No ontology entities were found that match the question terms. ")
              .append("The ontology may not cover this domain or the question may need reformulation.\n");
            return sb.toString();
        }

        sb.append("Matched entities:\n");
        for (SearchMatch match : matchedEntities) {
            sb.append("- ").append(match.label() != null ? match.label() : match.prefixedName())
              .append(" (").append(match.type().jsonName()).append("): ").append(match.iri()).append("\n");
        }

        if (!classContexts.isEmpty()) {
            sb.append("\nClass context:\n");
            for (ClassContext ctx : classContexts) {
                sb.append("- ").append(ctx.label()).append(" (").append(ctx.iri()).append(")\n");
                if (!ctx.directSuperclasses().isEmpty()) {
                    sb.append("  Superclasses: ").append(ctx.directSuperclasses()).append("\n");
                }
                if (!ctx.directSubclasses().isEmpty()) {
                    sb.append("  Subclasses: ").append(ctx.directSubclasses()).append("\n");
                }
                if (!ctx.equivalentClasses().isEmpty()) {
                    sb.append("  Equivalent: ").append(ctx.equivalentClasses()).append("\n");
                }
                if (!ctx.disjointClasses().isEmpty()) {
                    sb.append("  Disjoint: ").append(ctx.disjointClasses()).append("\n");
                }
            }
        }

        if (!propertyContexts.isEmpty()) {
            sb.append("\nProperty context:\n");
            for (ObjectPropertyContext ctx : propertyContexts) {
                sb.append("- ").append(ctx.label()).append(" (").append(ctx.iri()).append(")\n");
                if (!ctx.domain().isEmpty()) {
                    sb.append("  Domain: ").append(ctx.domain()).append("\n");
                }
                if (!ctx.range().isEmpty()) {
                    sb.append("  Range: ").append(ctx.range()).append("\n");
                }
            }
        }

        if (!individualContexts.isEmpty()) {
            sb.append("\nIndividual context:\n");
            for (IndividualContext ctx : individualContexts) {
                sb.append("- ").append(ctx.label()).append(" (").append(ctx.iri()).append(")\n");
                if (!ctx.explicitTypes().isEmpty()) {
                    sb.append("  Types: ").append(ctx.explicitTypes()).append("\n");
                }
                if (!ctx.objectPropertyAssertions().isEmpty()) {
                    sb.append("  Relations: ").append(ctx.objectPropertyAssertions()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private List<String> determineIncludedSections(List<ClassContext> classContexts,
                                                    List<ObjectPropertyContext> propertyContexts,
                                                    List<IndividualContext> individualContexts) {
        List<String> sections = new ArrayList<>();
        if (!classContexts.isEmpty()) sections.add("class_context");
        if (!propertyContexts.isEmpty()) sections.add("property_context");
        if (!individualContexts.isEmpty()) sections.add("individual_context");
        return sections;
    }
}