package org.owl4agents.retrieval;

import org.owl4agents.core.EntityType;
import org.owl4agents.core.OntologyId;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds entity and label indexes for classes, object properties,
 * data properties, annotation properties, and individuals.
 * Indexes are stored as JSONL files in the workspace for fast lookup.
 */
public class EntityIndex {

    private final Map<String, List<IndexEntry>> labelIndex = new HashMap<>();
    private final Map<String, IndexEntry> iriIndex = new HashMap<>();
    private final Map<EntityType, List<IndexEntry>> typeIndex = new HashMap<>();

    /**
     * Build indexes from a loaded OWLOntology.
     */
    public void buildFromOntology(OWLOntology ontology) {
        clear();

        // Index classes
        for (OWLClass cls : ontology.getClassesInSignature(Imports.EXCLUDED)) {
            if (cls.isOWLThing() || cls.isOWLNothing()) continue;
            addEntity(cls, EntityType.CLASS, ontology);
        }

        // Index object properties
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            if (prop.isOWLTopObjectProperty() || prop.isOWLBottomObjectProperty()) continue;
            addEntity(prop, EntityType.OBJECT_PROPERTY, ontology);
        }

        // Index data properties
        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            if (prop.isOWLTopDataProperty() || prop.isOWLBottomDataProperty()) continue;
            addEntity(prop, EntityType.DATA_PROPERTY, ontology);
        }

        // Index annotation properties
        for (OWLAnnotationProperty prop : ontology.getAnnotationPropertiesInSignature(Imports.EXCLUDED)) {
            // Skip built-in annotation properties
            String iriString = prop.getIRI().toString();
            if (iriString.startsWith("http://www.w3.org/") || iriString.startsWith("http://xmlns.com/")) continue;
            addEntity(prop, EntityType.ANNOTATION_PROPERTY, ontology);
        }

        // Index individuals
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature(Imports.EXCLUDED)) {
            addEntity(ind, EntityType.INDIVIDUAL, ontology);
        }
    }

    private void addEntity(OWLEntity entity, EntityType type, OWLOntology ontology) {
        String iriString = entity.getIRI().toString();
        String label = getLabel(entity, ontology);
        String comment = getComment(entity, ontology);
        String prefixedName = entity.getIRI().getShortForm();

        IndexEntry entry = new IndexEntry(iriString, prefixedName, label, comment, type);

        // Add to IRI index
        iriIndex.put(iriString, entry);

        // Add to type index
        typeIndex.computeIfAbsent(type, k -> new ArrayList<>()).add(entry);

        // Add to label index (lowercased for search)
        if (label != null && !label.isBlank()) {
            String normalizedLabel = label.toLowerCase();
            labelIndex.computeIfAbsent(normalizedLabel, k -> new ArrayList<>()).add(entry);
        }

        // Also index by prefixed name fragments
        if (prefixedName != null && !prefixedName.isBlank()) {
            String normalizedPrefix = prefixedName.toLowerCase();
            labelIndex.computeIfAbsent(normalizedPrefix, k -> new ArrayList<>()).add(entry);
        }
    }

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        // OWL API 5.x: EntitySearcher.getAnnotationAssertionAxioms returns Stream
        Optional<OWLAnnotationAssertionAxiom> firstLabel = EntitySearcher.getAnnotationAssertionAxioms(
            entity.getIRI(), ontology)
            .filter(ax -> ax.getProperty().isLabel())
            .findFirst();

        if (firstLabel.isPresent()) {
            OWLAnnotationValue value = firstLabel.get().getValue();
            Optional<OWLLiteral> literalOpt = value.asLiteral();
            if (literalOpt.isPresent()) {
                return literalOpt.get().getLiteral();
            }
        }
        return null;
    }

    private String getComment(OWLEntity entity, OWLOntology ontology) {
        Optional<OWLAnnotationAssertionAxiom> firstComment = EntitySearcher.getAnnotationAssertionAxioms(
            entity.getIRI(), ontology)
            .filter(ax -> ax.getProperty().isComment())
            .findFirst();

        if (firstComment.isPresent()) {
            OWLAnnotationValue value = firstComment.get().getValue();
            Optional<OWLLiteral> literalOpt = value.asLiteral();
            if (literalOpt.isPresent()) {
                return literalOpt.get().getLiteral();
            }
        }
        return null;
    }

    /**
     * Search for entities by text query.
     * Matches by IRI, prefixed name, and label.
     */
    public List<IndexEntry> search(String query) {
        String normalizedQuery = query.toLowerCase().trim();
        List<IndexEntry> results = new ArrayList<>();

        // 1. Exact IRI match
        IndexEntry iriMatch = iriIndex.get(query);
        if (iriMatch != null) {
            results.add(iriMatch);
        }

        // 2. Label/prefix matches
        List<IndexEntry> labelMatches = labelIndex.get(normalizedQuery);
        if (labelMatches != null) {
            for (IndexEntry entry : labelMatches) {
                if (!results.contains(entry)) {
                    results.add(entry);
                }
            }
        }

        // 3. Partial label/prefix matches (bidirectional contains)
        for (Map.Entry<String, List<IndexEntry>> e : labelIndex.entrySet()) {
            if (!e.getKey().equals(normalizedQuery)) {
                // Check both directions: key contains query OR query contains key
                // This handles "cities" → "city" and "city" → "cities" type matches
                if (e.getKey().contains(normalizedQuery) || normalizedQuery.contains(e.getKey())) {
                    for (IndexEntry entry : e.getValue()) {
                        if (!results.contains(entry)) {
                            results.add(entry);
                        }
                    }
                }
            }
        }

        return results;
    }

    /**
     * Find an entity by exact IRI.
     */
    public Optional<IndexEntry> findByIri(String iri) {
        return Optional.ofNullable(iriIndex.get(iri));
    }

    /**
     * Get all entities of a given type.
     */
    public List<IndexEntry> getByType(EntityType type) {
        return typeIndex.getOrDefault(type, Collections.emptyList());
    }

    public void clear() {
        labelIndex.clear();
        iriIndex.clear();
        typeIndex.clear();
    }

    /**
     * An entry in the entity index.
     */
    public record IndexEntry(
        String iri,
        String prefixedName,
        String label,
        String comment,
        EntityType type
    ) {}
}