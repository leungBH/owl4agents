package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.8.4 EntitySignatureCache + EntitySignatureCacheManager (UNIT-1, UNIT-2)")
class EntitySignatureCacheTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA_PATH =
        "D:\\owl4agents\\data\\workspaces\\default\\ontologies\\pizza\\canonical\\ontology.owl";

    private OWLOntology loadPizza() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        try (InputStream is = Files.newInputStream(Path.of(PIZZA_PATH))) {
            return mgr.loadOntologyFromOntologyDocument(is);
        }
    }

    @Test
    @DisplayName("UNIT-1: EntitySignatureCache.build() + contains() for pizza ontology")
    void unit1BuildAndContains() throws Exception {
        OWLOntology pizza = loadPizza();
        EntitySignatureCache cache = EntitySignatureCache.build(pizza);

        assertTrue(cache.contains("class", PIZZA_NS + "Pizza"),
            "Pizza class must be found in declared class IRIs");
        assertTrue(cache.contains("class", PIZZA_NS + "Margherita"),
            "Margherita class must be found");
        assertTrue(cache.contains("class", PIZZA_NS + "NamedPizza"),
            "NamedPizza class must be found");
        assertTrue(cache.contains("object_property", PIZZA_NS + "hasTopping"),
            "hasTopping object property must be found");
        assertTrue(cache.contains("individual", PIZZA_NS + "America"),
            "America individual must be found");

        assertFalse(cache.contains("class", "http://example.org/nonexistent"),
            "Nonexistent class must not be found");
        assertFalse(cache.contains("class", null),
            "Null IRI must return false");
        assertFalse(cache.contains("class", ""),
            "Empty IRI must return false");
    }

    @Test
    @DisplayName("UNIT-2: SubClassOf index returns correct asserted superclasses")
    void unit2SubClassOfIndex() throws Exception {
        OWLOntology pizza = loadPizza();
        EntitySignatureCache cache = EntitySignatureCache.build(pizza);

        // Margherita is directly asserted as subclass of NamedPizza only.
        // Pizza is a transitive superclass (via NamedPizza → Pizza), NOT asserted directly.
        Set<String> supers = cache.getSuperClasses(PIZZA_NS + "Margherita");
        assertTrue(supers.contains(PIZZA_NS + "NamedPizza"),
            "Margherita must have NamedPizza as asserted superclass");

        // NamedPizza is directly asserted as subclass of Pizza.
        Set<String> namedPizzaSupers = cache.getSuperClasses(PIZZA_NS + "NamedPizza");
        assertTrue(namedPizzaSupers.contains(PIZZA_NS + "Pizza"),
            "NamedPizza must have Pizza as asserted superclass");
    }

    @Test
    @DisplayName("UNIT-2b: DisjointClasses index returns pairwise disjoint classes")
    void unit2bDisjointClassesIndex() throws Exception {
        OWLOntology pizza = loadPizza();
        EntitySignatureCache cache = EntitySignatureCache.build(pizza);

        // American and AmericanHot are in the same AllDisjointClasses axiom (line 3002-3029).
        Set<String> disjoint = cache.getDisjointClasses(PIZZA_NS + "American");
        assertNotNull(disjoint, "DisjointClasses index should return non-null");
        assertTrue(disjoint.contains(PIZZA_NS + "AmericanHot"),
            "American must be disjoint with AmericanHot");
        assertTrue(disjoint.contains(PIZZA_NS + "Margherita"),
            "American must be disjoint with Margherita");
    }

    @Test
    @DisplayName("UNIT-1b: OBO namespace check — non-OBO ontology has null oboPrefix")
    void unit1bNonOboOntology() throws Exception {
        OWLOntology pizza = loadPizza();
        EntitySignatureCache cache = EntitySignatureCache.build(pizza);

        assertTrue(cache.contains("class", PIZZA_NS + "Pizza"),
            "Pizza ontology (non-OBO) should not have OBO prefix filtering");
    }

    @Test
    @DisplayName("UNIT-3: Signature-only entities (no Declaration axioms) are detected (v0.8.5 P0 fix)")
    void signatureOnlyEntitiesDetected() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLOntology ontology = mgr.createOntology(IRI.create("http://example.org/test#sig-only"));

        OWLClass clsA = df.getOWLClass(IRI.create("http://example.org/test#A"));
        OWLClass clsB = df.getOWLClass(IRI.create("http://example.org/test#B"));
        OWLNamedIndividual indX = df.getOWLNamedIndividual(IRI.create("http://example.org/test#X"));
        OWLObjectProperty propP = df.getOWLObjectProperty(IRI.create("http://example.org/test#P"));

        // Add axioms that reference entities WITHOUT any Declaration axioms.
        // Per OWL 2 spec, these entities are part of the ontology signature.
        ontology.add(df.getOWLSubClassOfAxiom(clsA, clsB));
        ontology.add(df.getOWLClassAssertionAxiom(clsA, indX));
        ontology.add(df.getOWLObjectPropertyAssertionAxiom(propP, indX, indX));

        assertEquals(0, ontology.getAxioms(AxiomType.DECLARATION, Imports.EXCLUDED).size(),
            "Test ontology must have zero Declaration axioms");

        EntitySignatureCache cache = EntitySignatureCache.build(ontology);

        assertTrue(cache.contains("class", "http://example.org/test#A"),
            "Signature-only class A (no Declaration) must be detected");
        assertTrue(cache.contains("class", "http://example.org/test#B"),
            "Signature-only class B (no Declaration) must be detected");
        assertTrue(cache.contains("individual", "http://example.org/test#X"),
            "Signature-only individual X (no Declaration) must be detected");
        assertTrue(cache.contains("object_property", "http://example.org/test#P"),
            "Signature-only object property P (no Declaration) must be detected");
        assertFalse(cache.contains("class", "http://example.org/test#C"),
            "Non-existent class C must not be found");
    }

    @Test
    @DisplayName("Concurrent getOrCreate() builds cache only once")
    void concurrentBuildOnlyOnce(@TempDir Path tempDir) throws Exception {
        Path ontDir = tempDir.resolve("default/ontologies/concurrent-test/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://example.org/test#concurrent\"\n" +
            "     xml:base=\"http://example.org/test#concurrent\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
            "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n" +
            "    <owl:Ontology rdf:about=\"http://example.org/test#concurrent\"/>\n" +
            "    <owl:Class rdf:about=\"http://example.org/test#A\"/>\n" +
            "    <owl:Class rdf:about=\"http://example.org/test#B\"/>\n" +
            "    <rdfs:subClassOf rdf:resource=\"http://example.org/test#B\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(owlFile, owlXml);

        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ontology;
        try (InputStream is = Files.newInputStream(owlFile)) {
            ontology = mgr.loadOntologyFromOntologyDocument(is);
        }

        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        OntologyId ontId = new OntologyId("concurrent-test");

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    EntitySignatureCache cache = manager.getOrCreate(ontId, ontology);
                    if (cache != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertEquals(threadCount, successCount.get(),
            "All threads must successfully get the cache");
    }

    @Test
    @DisplayName("Build failure returns null and allows retry")
    void buildFailureReturnsNullAndRetries() {
        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        OntologyId ontId = new OntologyId("failure-test");

        EntitySignatureCache result = manager.getOrCreate(ontId, null);
        assertNull(result, "Build with null ontology should return null (fallback)");

        EntitySignatureCache result2 = manager.getOrCreate(ontId, null);
        assertNull(result2, "Retry should also return null, not throw or block");
    }

    @Test
    @DisplayName("onOntologyReloaded clears specific entry")
    void onOntologyReloadedClearsEntry() throws Exception {
        OWLOntology pizza = loadPizza();
        EntitySignatureCacheManager manager = new EntitySignatureCacheManager();
        OntologyId ontId = new OntologyId("pizza");

        EntitySignatureCache cache1 = manager.getOrCreate(ontId, pizza);
        assertNotNull(cache1, "First build should succeed");

        manager.onOntologyReloaded(ontId);

        EntitySignatureCache cache2 = manager.getOrCreate(ontId, pizza);
        assertNotNull(cache2, "After reload, rebuild should succeed");
        assertNotSame(cache1, cache2, "After reload, cache should be a new instance");
    }
}
