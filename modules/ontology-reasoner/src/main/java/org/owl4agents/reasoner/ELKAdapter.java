package org.owl4agents.reasoner;

import org.owl4agents.core.model.*;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ELK reasoner adapter for OWL 2 EL ontologies.
 * ELK provides fast classification and consistency checking for OWL 2 EL profile.
 * ELK does NOT support explanation — explain operations will throw UnsupportedOperationException.
 */
public class ELKAdapter implements OWLReasonerAdapter {

    private OWLReasoner reasoner;
    private OWLOntology ontology;
    private boolean initialized = false;
    private boolean shutdown = false;
    private long initializationTimeMs = 0;

    @Override
    public String getName() {
        return "ELK";
    }

    @Override
    public void initialize(OWLOntology ontology) {
        if (shutdown) {
            throw new IllegalStateException("Reasoner has been shut down");
        }
        long start = System.currentTimeMillis();
        this.ontology = ontology;
        ElkReasonerFactory factory = new ElkReasonerFactory();
        this.reasoner = factory.createReasoner(ontology);
        this.initialized = true;
        this.initializationTimeMs = System.currentTimeMillis() - start;
    }

    @Override
    public ClassificationResult classify(String ontologyId) {
        checkActive();
        long start = System.currentTimeMillis();
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
        throw new UnsupportedOperationException(
            "ELK reasoner does not support explanation functionality. " +
            "Use HermiT or Openllet for explanation capabilities.");
    }

    @Override
    public UnsatClassExplanation explainUnsatClass(String ontologyId, String classIRI) {
        throw new UnsupportedOperationException(
            "ELK reasoner does not support explanation functionality. " +
            "Use HermiT or Openllet for explanation capabilities.");
    }

    @Override
    public boolean supportsExplanation() {
        return false;
    }

    @Override
    public List<String> getSupportedProfiles() {
        return List.of("OWL 2 EL");
    }

    @Override
    public List<String> getSupportedOperations() {
        return List.of("classify", "checkConsistency");
    }

    @Override
    public void shutdown() {
        if (reasoner != null) {
            reasoner.dispose();
        }
        reasoner = null;
        ontology = null;
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

    public long getInitializationTimeMs() {
        return initializationTimeMs;
    }
}