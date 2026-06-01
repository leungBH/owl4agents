package org.owl4agents.reasoner;

import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import openllet.owlapi.PelletReasonerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Openllet reasoner adapter for OWL 2 DL ontologies.
 * Openllet provides full classification, realization, consistency checking,
 * and inconsistency/unsat explanation capabilities via built-in fallback analysis.
 */
public class OpenlletAdapter implements OWLReasonerAdapter {

    private OWLReasoner reasoner;
    private OWLOntology ontology;
    private boolean initialized = false;
    private boolean shutdown = false;
    private long initializationTimeMs = 0;
    private OWLOntologyManager manager;

    @Override
    public String getName() {
        return "Openllet";
    }

    @Override
    public void initialize(OWLOntology ontology) {
        if (shutdown) {
            throw new IllegalStateException("Reasoner has been shut down");
        }
        long start = System.currentTimeMillis();
        this.ontology = ontology;
        this.manager = ontology.getOWLOntologyManager();

        // Openllet reasoner factory
        PelletReasonerFactory factory = new PelletReasonerFactory();
        this.reasoner = factory.createReasoner(ontology);
        this.initialized = true;
        this.initializationTimeMs = System.currentTimeMillis() - start;
    }

    @Override
    public ClassificationResult classify(String ontologyId) {
        checkActive();
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);

        List<InferredHierarchyEntry> complete = new ArrayList<>();
        List<InferredHierarchyEntry> delta = new ArrayList<>();

        Set<OWLClass> allClasses = ontology.getClassesInSignature().stream()
            .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
            .collect(Collectors.toSet());

        Set<String> explicitSubClassOf = new HashSet<>();
        for (OWLSubClassOfAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)) {
            if (ax.getSubClass().isNamed() && ax.getSuperClass().isNamed()) {
                String sub = ax.getSubClass().asOWLClass().getIRI().toString();
                String sup = ax.getSuperClass().asOWLClass().getIRI().toString();
                explicitSubClassOf.add(sub + "|" + sup);
            }
        }

        for (OWLClass cls : allClasses) {
            NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(cls, false);
            for (OWLClass superClass : superClasses.getFlattened()) {
                if (superClass.isOWLThing()) continue;
                String subIRI = cls.getIRI().toString();
                String supIRI = superClass.getIRI().toString();
                String key = subIRI + "|" + supIRI;

                complete.add(new InferredHierarchyEntry(ontologyId, subIRI, "rdfs:subClassOf", supIRI, "inferred", getName()));

                if (!explicitSubClassOf.contains(key)) {
                    delta.add(new InferredHierarchyEntry(ontologyId, subIRI, "rdfs:subClassOf", supIRI, "inferred", getName()));
                } else {
                    complete.add(new InferredHierarchyEntry(ontologyId, subIRI, "rdfs:subClassOf", supIRI, "explicit", getName()));
                }
            }
        }

        return new ClassificationResult(ontologyId, getName(), complete, delta);
    }

    @Override
    public RealizationResult realize(String ontologyId) {
        checkActive();
        reasoner.precomputeInferences(InferenceType.CLASS_ASSERTIONS);

        List<InferredTypeEntry> complete = new ArrayList<>();
        List<InferredTypeEntry> delta = new ArrayList<>();

        Set<String> explicitTypes = new HashSet<>();
        for (OWLClassAssertionAxiom ax : ontology.getAxioms(AxiomType.CLASS_ASSERTION, Imports.INCLUDED)) {
            if (ax.getIndividual().isNamed() && ax.getClassExpression().isNamed()) {
                String ind = ax.getIndividual().asOWLNamedIndividual().getIRI().toString();
                String cls = ax.getClassExpression().asOWLClass().getIRI().toString();
                explicitTypes.add(ind + "|" + cls);
            }
        }

        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();

        for (OWLNamedIndividual ind : individuals) {
            NodeSet<OWLClass> types = reasoner.getTypes(ind, false);
            for (OWLClass cls : types.getFlattened()) {
                if (cls.isOWLThing()) continue;
                String indIRI = ind.getIRI().toString();
                String clsIRI = cls.getIRI().toString();
                String key = indIRI + "|" + clsIRI;

                complete.add(new InferredTypeEntry(ontologyId, indIRI, "rdf:type", clsIRI, "inferred", getName()));

                if (!explicitTypes.contains(key)) {
                    delta.add(new InferredTypeEntry(ontologyId, indIRI, "rdf:type", clsIRI, "inferred", getName()));
                } else {
                    complete.add(new InferredTypeEntry(ontologyId, indIRI, "rdf:type", clsIRI, "explicit", getName()));
                }
            }
        }

        return new RealizationResult(ontologyId, getName(), complete, delta);
    }

    @Override
    public ConsistencyResult checkConsistency(String ontologyId) {
        checkActive();
        boolean consistent = reasoner.isConsistent();
        List<String> unsatIRIs = new ArrayList<>();
        if (!consistent) {
            unsatIRIs = ontology.getClassesInSignature().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> c.getIRI().toString())
                .collect(Collectors.toList());
        } else {
            Node<OWLClass> unsatNode = reasoner.getUnsatisfiableClasses();
            unsatIRIs = unsatNode.getEntitiesMinusBottom().stream()
                .map(c -> c.getIRI().toString())
                .collect(Collectors.toList());
        }
        return new ConsistencyResult(ontologyId, getName(), consistent, unsatIRIs);
    }

    @Override
    public Set<String> getUnsatClasses() {
        checkActive();
        if (!reasoner.isConsistent()) {
            return ontology.getClassesInSignature().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> c.getIRI().toString())
                .collect(Collectors.toSet());
        }
        Node<OWLClass> unsatNode = reasoner.getUnsatisfiableClasses();
        return unsatNode.getEntitiesMinusBottom().stream()
            .map(c -> c.getIRI().toString())
            .collect(Collectors.toSet());
    }

    @Override
    public InconsistencyExplanation explainInconsistency(String ontologyId) {
        checkActive();
        if (reasoner.isConsistent()) {
            return null; // Service layer handles ONTOLOGY_CONSISTENT error
        }

        List<ConflictingAxiomSet> conflictingSets = new ArrayList<>();
        long explainStart = System.currentTimeMillis();

        // Use built-in fallback explanation
        List<String> descriptions = collectBasicInconsistencyInfo();
        if (!descriptions.isEmpty()) {
            conflictingSets.add(new ConflictingAxiomSet(descriptions, "Manchester"));
        }

        long explainTime = System.currentTimeMillis() - explainStart;

        return new InconsistencyExplanation(
            ontologyId,
            conflictingSets,
            conflictingSets.size(),
            new ReasonerMetadata(getName(), "Openllet", explainTime)
        );
    }

    @Override
    public UnsatClassExplanation explainUnsatClass(String ontologyId, String classIRI) {
        checkActive();
        IRI classIriObj = IRI.create(classIRI);
        OWLClass owlClass = manager.getOWLDataFactory().getOWLClass(classIriObj);

        if (reasoner.isSatisfiable(owlClass)) {
            return null; // Service layer handles ONTOLOGY_CONSISTENT error
        }

        List<ConflictingAxiomSet> conflictingSets = new ArrayList<>();
        long explainStart = System.currentTimeMillis();

        // Use built-in fallback explanation
        List<String> descriptions = collectBasicUnsatInfo(owlClass);
        if (!descriptions.isEmpty()) {
            conflictingSets.add(new ConflictingAxiomSet(descriptions, "Manchester"));
        }

        long explainTime = System.currentTimeMillis() - explainStart;

        return new UnsatClassExplanation(
            ontologyId,
            classIRI,
            conflictingSets,
            conflictingSets.size(),
            new ReasonerMetadata(getName(), "Openllet", explainTime)
        );
    }

    @Override
    public boolean supportsExplanation() {
        return true;
    }

    @Override
    public List<String> getSupportedProfiles() {
        return List.of("OWL 2 DL");
    }

    @Override
    public List<String> getSupportedOperations() {
        return List.of("classify", "realize", "checkConsistency", "explain");
    }

    @Override
    public void shutdown() {
        if (reasoner != null) {
            reasoner.dispose();
        }
        reasoner = null;
        ontology = null;
        manager = null;
        initialized = false;
        shutdown = true;
    }

    @Override
    public boolean isActive() {
        return initialized && !shutdown;
    }

    private void checkActive() {
        if (shutdown) {
            throw new IllegalStateException("Reasoner has been shut down");
        }
        if (!initialized) {
            throw new IllegalStateException("Reasoner has not been initialized");
        }
    }

    /**
     * Collect basic inconsistency information using built-in fallback analysis.
     */
    private List<String> collectBasicInconsistencyInfo() {
        List<String> descriptions = new ArrayList<>();

        // Check disjoint class conflicts with common subclass
        for (OWLDisjointClassesAxiom disjointAx : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
            List<OWLClass> disjointPair = disjointAx.getClassExpressionsAsList()
                .stream().filter(OWLClassExpression::isNamed)
                .map(ce -> ce.asOWLClass())
                .collect(Collectors.toList());

            for (OWLClass cls : ontology.getClassesInSignature()) {
                if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                Set<OWLClass> superClasses = reasoner.getSuperClasses(cls, false).getFlattened();
                Set<OWLClass> matchedDisjoint = superClasses.stream()
                    .filter(c -> disjointPair.contains(c))
                    .collect(Collectors.toSet());
                if (matchedDisjoint.size() >= 2) {
                    descriptions.add("Class " + cls.getIRI() + " is subclass of disjoint classes: " +
                        matchedDisjoint.stream().map(c -> c.getIRI().toString()).collect(Collectors.joining(", ")));
                }
            }
        }

        // Check individuals with conflicting type assertions
        for (OWLClassAssertionAxiom ax : ontology.getAxioms(AxiomType.CLASS_ASSERTION, Imports.INCLUDED)) {
            if (!ax.getIndividual().isNamed() || !ax.getClassExpression().isNamed()) continue;
            OWLNamedIndividual ind = ax.getIndividual().asOWLNamedIndividual();
            OWLClass cls = ax.getClassExpression().asOWLClass();

            // Check if this individual has types from disjoint classes
            NodeSet<OWLClass> allTypes = reasoner.getTypes(ind, false);
            Set<OWLClass> flattened = allTypes.getFlattened();
            for (OWLDisjointClassesAxiom disjointAx : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
                List<OWLClass> disjointPair = disjointAx.getClassExpressionsAsList()
                    .stream().filter(OWLClassExpression::isNamed)
                    .map(ce -> ce.asOWLClass())
                    .collect(Collectors.toList());
                Set<OWLClass> matched = flattened.stream()
                    .filter(c -> disjointPair.contains(c))
                    .collect(Collectors.toSet());
                if (matched.size() >= 2) {
                    descriptions.add("Individual " + ind.getIRI() + " has types from disjoint classes: " +
                        matched.stream().map(c -> c.getIRI().toString()).collect(Collectors.joining(", ")));
                }
            }
        }

        return descriptions;
    }

    /**
     * Collect basic unsatisfiability info for a specific class using built-in fallback analysis.
     */
    private List<String> collectBasicUnsatInfo(OWLClass owlClass) {
        List<String> descriptions = new ArrayList<>();
        Set<OWLClass> superClasses = reasoner.getSuperClasses(owlClass, false).getFlattened();

        for (OWLDisjointClassesAxiom disjointAx : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
            List<OWLClass> disjointPair = disjointAx.getClassExpressionsAsList()
                .stream().filter(OWLClassExpression::isNamed)
                .map(ce -> ce.asOWLClass())
                .collect(Collectors.toList());
            for (OWLClass c1 : disjointPair) {
                for (OWLClass c2 : disjointPair) {
                    if (c1 != c2 && superClasses.contains(c1) && superClasses.contains(c2)) {
                        descriptions.add("SubClassOf(" + owlClass.getIRI() + ", " + c1.getIRI() + ") AND " +
                            "SubClassOf(" + owlClass.getIRI() + ", " + c2.getIRI() + ") AND " +
                            "DisjointClasses(" + c1.getIRI() + ", " + c2.getIRI() + ")");
                    }
                }
            }
        }

        return descriptions;
    }

    public long getInitializationTimeMs() {
        return initializationTimeMs;
    }
}