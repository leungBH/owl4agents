package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.4 Section 8 tests: OntologyCache TTL window (Decision 7).
 *
 * <p>Task 8.4 (INTEG-2): 5s within second call skips file stat syscalls.
 * <p>Task 8.5: 5s after re-validate + invalidate() immediately clears.
 */
@DisplayName("OntologyCache TTL window tests (v0.8.4 Decision 7)")
class OntologyCacheTtlTest {

    @TempDir
    Path tempDir;

    private Path createOntologyFile(String ontologyId) throws Exception {
        Path ontDir = tempDir.resolve("default/ontologies/" + ontologyId + "/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://example.org/test#" + ontologyId + "\"\n" +
            "     xml:base=\"http://example.org/test#" + ontologyId + "\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
            "     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">\n" +
            "    <owl:Ontology rdf:about=\"http://example.org/test#" + ontologyId + "\"/>\n" +
            "    <owl:Class rdf:about=\"http://example.org/test#TestClass\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(owlFile, owlXml);
        return owlFile;
    }

    private OntologyCache createCache() {
        return new OntologyCache(tempDir.toString(), "default");
    }

    @Test
    @DisplayName("8.4 INTEG-2: within 5s, second call returns cached ontology even if file deleted")
    void ttlWindowSkipsFileStatWithin5s() throws Exception {
        Path owlFile = createOntologyFile("ttl-test-1");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("ttl-test-1");

        // First call: loads from disk
        OWLOntology first = cache.getOrCreate(ontId);
        assertNotNull(first, "First call should load ontology");

        // Delete the file — within TTL window, getOrCreate should NOT call Files.exists
        Files.delete(owlFile);

        // Second call within 5s: should succeed via TTL cache (no syscall)
        OWLOntology second = cache.getOrCreate(ontId);
        assertSame(first, second,
            "Within 5s TTL, second call should return same instance without file stat");
    }

    @Test
    @DisplayName("8.5: invalidate() immediately clears TTL (next call does full validation)")
    void invalidateClearsTtlImmediately() throws Exception {
        Path owlFile = createOntologyFile("ttl-test-2");
        OntologyCache cache = createCache();
        OntologyId ontId = new OntologyId("ttl-test-2");

        // First call: loads from disk, sets TTL
        OWLOntology first = cache.getOrCreate(ontId);
        assertNotNull(first);

        // Invalidate: should clear both cache and TTL
        cache.invalidate(ontId);

        // Delete the file
        Files.delete(owlFile);

        // Next call: should fail because invalidate cleared TTL,
        // so Files.exists() is called and finds the file missing
        assertThrows(OWLOntologyCreationException.class,
            () -> cache.getOrCreate(ontId),
            "After invalidate(), TTL is cleared so file stat runs and finds missing file");
    }

    @Test
    @DisplayName("8.5: invalidateAll() immediately clears all TTL entries")
    void invalidateAllClearsAllTtl() throws Exception {
        createOntologyFile("ttl-test-3a");
        createOntologyFile("ttl-test-3b");
        OntologyCache cache = createCache();

        // Load both ontologies (sets TTL for both)
        OWLOntology a = cache.getOrCreate(new OntologyId("ttl-test-3a"));
        OWLOntology b = cache.getOrCreate(new OntologyId("ttl-test-3b"));
        assertNotNull(a);
        assertNotNull(b);

        // Invalidate all
        cache.invalidateAll();

        // Both should be re-loadable (TTL cleared, full validation runs)
        OWLOntology aReloaded = cache.getOrCreate(new OntologyId("ttl-test-3a"));
        OWLOntology bReloaded = cache.getOrCreate(new OntologyId("ttl-test-3b"));
        assertNotNull(aReloaded);
        assertNotNull(bReloaded);
        // After invalidateAll + reload, new instances are created
        assertNotSame(a, aReloaded, "After invalidateAll, ontology should be reloaded");
        assertNotSame(b, bReloaded, "After invalidateAll, ontology should be reloaded");
    }
}
