package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.2 OntologyImporter ↔ OntologyCache interaction tests (TC-8.1 through TC-8.3).
 *
 * <p>Verifies that after {@link OntologyImporter#importOntology} writes the
 * canonical ontology file, the next {@link OntologyCache#getOrCreate} call
 * loads it, and subsequent calls hit the cache. Also verifies that
 * re-importing (overwriting the file) triggers a cache reload on the next
 * {@code getOrCreate} call via mtime+size detection.</p>
 */
@DisplayName("v0.8.2 OntologyImporter ↔ OntologyCache interaction (TC-8.1 through TC-8.3)")
class OntologyImporterCacheInteractionTest {

    @TempDir
    Path tempDir;

    private String workspaceBasePath() {
        return tempDir.resolve("workspaces").toString();
    }

    private Path createSourceOntology(String fileName) throws Exception {
        Path file = tempDir.resolve(fileName);
        String owlXml = "<?xml version=\"1.0\"?>\n" +
            "<rdf:RDF xmlns=\"http://example.org/test#ont\"\n" +
            "     xml:base=\"http://example.org/test#ont\"\n" +
            "     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n" +
            "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n" +
            "     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">\n" +
            "    <owl:Ontology rdf:about=\"http://example.org/test#ont\"/>\n" +
            "    <owl:Class rdf:about=\"http://example.org/test#ClassA\"/>\n" +
            "</rdf:RDF>\n";
        Files.writeString(file, owlXml);
        return file;
    }

    @Test
    @DisplayName("TC-8.1: OntologyImporter writes to canonical/ontology.owl; OntologyCache loads it")
    void tc81ImporterWritesCanonicalPathCacheLoadsIt() throws Exception {
        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tempDir);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        OntologyImporter importer = new OntologyImporter(homeResolver, catalogStore);

        Path sourceFile = createSourceOntology("source-v1.owl");
        OntologyId ontId = new OntologyId("import-test");
        importer.importOntology(ontId, sourceFile, WorkspaceId.DEFAULT);

        Path canonicalPath = tempDir.resolve("workspaces/default/ontologies/import-test/canonical/ontology.owl");
        assertTrue(Files.exists(canonicalPath),
            "OntologyImporter must write canonical ontology file at: " + canonicalPath);

        OntologyCache cache = new OntologyCache(workspaceBasePath(), "default");
        OWLOntology ontology = cache.getOrCreate(ontId);
        assertNotNull(ontology, "OntologyCache must load the imported ontology");
    }

    @Test
    @DisplayName("TC-8.2: OntologyImporter does not hold in-memory reference bypassing file write")
    void tc82ImporterDoesNotHoldInMemoryReference() throws Exception {
        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tempDir);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        OntologyImporter importer = new OntologyImporter(homeResolver, catalogStore);

        Path sourceFile = createSourceOntology("source-noref.owl");
        OntologyId ontId = new OntologyId("noref-test");
        importer.importOntology(ontId, sourceFile, WorkspaceId.DEFAULT);

        OntologyCache cache = new OntologyCache(workspaceBasePath(), "default");
        OWLOntology first = cache.getOrCreate(ontId);
        OWLOntology second = cache.getOrCreate(ontId);

        assertSame(first, second,
            "Cache must return same instance — importer does not bypass cache");
    }

    @Test
    @DisplayName("TC-8.3: import → cache miss (load) → subsequent calls hit cache → re-import triggers reload")
    void tc83ImportThenCacheMissThenHitThenReloadOnReimport() throws Exception {
        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tempDir);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        OntologyImporter importer = new OntologyImporter(homeResolver, catalogStore);
        OntologyCache cache = new OntologyCache(workspaceBasePath(), "default");
        OntologyId ontId = new OntologyId("reload-test");

        Path sourceV1 = createSourceOntology("source-v1.owl");
        importer.importOntology(ontId, sourceV1, WorkspaceId.DEFAULT);

        OWLOntology first = cache.getOrCreate(ontId);
        OWLOntology cachedHit = cache.getOrCreate(ontId);
        assertSame(first, cachedHit, "Subsequent call must hit cache");

        Thread.sleep(50);
        Path sourceV2 = tempDir.resolve("source-v2.owl");
        Files.writeString(sourceV2, Files.readString(sourceV1) +
            "\n<!-- re-imported version -->\n");
        importer.importOntology(ontId, sourceV2, WorkspaceId.DEFAULT);

        OWLOntology reloaded = cache.getOrCreate(ontId);
        assertNotSame(first, reloaded,
            "Re-import must trigger cache reload via mtime+size detection");

        OWLOntology cachedAgain = cache.getOrCreate(ontId);
        assertSame(reloaded, cachedAgain,
            "After reload, subsequent call must hit cache again");
    }
}
