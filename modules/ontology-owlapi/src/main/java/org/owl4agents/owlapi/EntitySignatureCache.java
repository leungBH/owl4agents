package org.owl4agents.owlapi;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-ontology cache of entity IRIs and asserted axiom indices.
 *
 * <p>Builds an O(1) lookup structure from the ontology's signature
 * (Imports.EXCLUDED), collecting entities from BOTH explicit Declaration
 * axioms AND all other axioms that reference entities (e.g., SubClassOf,
 * ClassAssertion, ObjectPropertyAssertion). This correctly handles ontologies
 * where entities are used in axioms without explicit Declaration axioms —
 * per OWL 2 spec, an entity referenced in any axiom is part of the ontology
 * signature. Also builds SubClassOf and DisjointClasses indices for fast
 * asserted-axiom lookups in the claim verification hot path.</p>
 */
public final class EntitySignatureCache {

    private final Set<String> declaredClassIRIs;
    private final Set<String> declaredObjectPropertyIRIs;
    private final Set<String> declaredDataPropertyIRIs;
    private final Set<String> declaredIndividualIRIs;
    private final String oboPrefix;
    private final Map<String, Set<String>> subClassOfIndex;
    private final Map<String, Set<String>> disjointClassesIndex;

    private EntitySignatureCache(Set<String> classes, Set<String> objProps,
                                  Set<String> dataProps, Set<String> individuals,
                                  String oboPrefix,
                                  Map<String, Set<String>> subClassOfIndex,
                                  Map<String, Set<String>> disjointClassesIndex) {
        this.declaredClassIRIs = classes;
        this.declaredObjectPropertyIRIs = objProps;
        this.declaredDataPropertyIRIs = dataProps;
        this.declaredIndividualIRIs = individuals;
        this.oboPrefix = oboPrefix;
        this.subClassOfIndex = subClassOfIndex;
        this.disjointClassesIndex = disjointClassesIndex;
    }

    public static EntitySignatureCache build(OWLOntology ontology) {
        Set<String> classes = new HashSet<>();
        Set<String> objProps = new HashSet<>();
        Set<String> dataProps = new HashSet<>();
        Set<String> individuals = new HashSet<>();

        for (OWLDeclarationAxiom decl : ontology.getAxioms(AxiomType.DECLARATION, Imports.EXCLUDED)) {
            OWLEntity entity = decl.getEntity();
            String iri = entity.getIRI().toString();
            if (entity.isOWLClass()) {
                classes.add(iri);
            } else if (entity.isOWLObjectProperty()) {
                objProps.add(iri);
            } else if (entity.isOWLDataProperty()) {
                dataProps.add(iri);
            } else if (entity.isOWLNamedIndividual()) {
                individuals.add(iri);
            }
        }

        // Also collect entities from the ontology signature (Imports.EXCLUDED).
        // Per OWL 2 spec, an entity referenced in any axiom is part of the
        // signature, even without an explicit Declaration axiom. This catches
        // entities used in ClassAssertion, ObjectPropertyAssertion, SubClassOf,
        // etc. that lack explicit Declaration axioms.
        for (OWLClass cls : ontology.getClassesInSignature(Imports.EXCLUDED)) {
            classes.add(cls.getIRI().toString());
        }
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            objProps.add(prop.getIRI().toString());
        }
        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            dataProps.add(prop.getIRI().toString());
        }
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature(Imports.EXCLUDED)) {
            individuals.add(ind.getIRI().toString());
        }

        String oboPrefix = null;
        if (ontology.getOntologyID().getOntologyIRI().isPresent()) {
            String ontIri = ontology.getOntologyID().getOntologyIRI().get().toString();
            if (ontIri.startsWith("http://purl.obolibrary.org/obo/") && ontIri.endsWith(".owl")) {
                String fileName = ontIri.substring(ontIri.lastIndexOf('/') + 1);
                oboPrefix = fileName.substring(0, fileName.length() - ".owl".length()).toUpperCase();
            }
        }

        Map<String, Set<String>> subClassOfIndex = buildSubClassOfIndex(ontology);
        Map<String, Set<String>> disjointClassesIndex = buildDisjointClassesIndex(ontology);

        return new EntitySignatureCache(
            Collections.unmodifiableSet(classes),
            Collections.unmodifiableSet(objProps),
            Collections.unmodifiableSet(dataProps),
            Collections.unmodifiableSet(individuals),
            oboPrefix,
            Collections.unmodifiableMap(subClassOfIndex),
            Collections.unmodifiableMap(disjointClassesIndex)
        );
    }

    private static Map<String, Set<String>> buildSubClassOfIndex(OWLOntology ontology) {
        Map<String, Set<String>> index = new HashMap<>();
        for (OWLSubClassOfAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)) {
            if (ax.getSubClass() instanceof OWLClass subClass && ax.getSuperClass() instanceof OWLClass superClass) {
                index.computeIfAbsent(subClass.getIRI().toString(), k -> new HashSet<>())
                    .add(superClass.getIRI().toString());
            }
        }
        return index;
    }

    private static Map<String, Set<String>> buildDisjointClassesIndex(OWLOntology ontology) {
        Map<String, Set<String>> index = new HashMap<>();
        for (OWLDisjointClassesAxiom ax : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
            Set<OWLClass> disjointClasses = ax.getClassExpressionsAsList().stream()
                .filter(ce -> ce instanceof OWLClass)
                .map(ce -> (OWLClass) ce)
                .collect(java.util.stream.Collectors.toSet());
            for (OWLClass c1 : disjointClasses) {
                for (OWLClass c2 : disjointClasses) {
                    if (!c1.equals(c2)) {
                        index.computeIfAbsent(c1.getIRI().toString(), k -> new HashSet<>())
                            .add(c2.getIRI().toString());
                    }
                }
            }
        }
        return index;
    }

    public boolean contains(String kind, String iri) {
        if (iri == null || iri.isBlank()) return false;

        if (oboPrefix != null && !iri.contains("/" + oboPrefix + "_")) {
            return false;
        }

        String k = kind == null ? "" : kind.toLowerCase().replace("_", "");
        if (k.equals("class") || k.isEmpty()) {
            if (declaredClassIRIs.contains(iri)) return true;
        }
        if (k.equals("objectproperty") || k.equals("property") || k.isEmpty()) {
            if (declaredObjectPropertyIRIs.contains(iri)) return true;
        }
        if (k.equals("dataproperty") || k.equals("property") || k.isEmpty()) {
            if (declaredDataPropertyIRIs.contains(iri)) return true;
        }
        if (k.equals("individual") || k.isEmpty()) {
            if (declaredIndividualIRIs.contains(iri)) return true;
        }
        return false;
    }

    public Set<String> getSuperClasses(String classIRI) {
        return subClassOfIndex.getOrDefault(classIRI, Collections.emptySet());
    }

    public Set<String> getDisjointClasses(String classIRI) {
        return disjointClassesIndex.getOrDefault(classIRI, Collections.emptySet());
    }
}
