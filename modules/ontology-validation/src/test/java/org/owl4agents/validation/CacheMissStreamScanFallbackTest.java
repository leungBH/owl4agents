package org.owl4agents.validation;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7.8 unit test: verify that when the per-ontology
 * {@link org.owl4agents.owlapi.EntitySignatureCache} does NOT contain an
 * entity IRI (cache miss), the {@link ConsistencyAnalysisService#isEntityDeclaredStreamScan}
 * fallback finds the entity in the ontology signature (via
 * {@code Imports.EXCLUDED}).
 *
 * <p><b>v0.9.0 D1:</b> The cache-miss fallback path is the deprecated
 * {@code ConsistencyAnalysisService(ReasonerLifecycleManager, String, OntologyCache)}
 * 3-arg constructor with {@code manager=null}. This forces
 * {@code isEntityDeclared} to skip the cache and use the stream-scan path.
 * The test builds a synthetic OBO ontology in a temp directory and verifies
 * that an entity referenced in a SubClassOf axiom (without a Declaration
 * axiom) is found via stream scan.</p>
 */
@DisplayName("v0.9.0 D1 / task 7.8: cache-miss stream scan fallback")
class CacheMissStreamScanFallbackTest {

    @Test
    @DisplayName("Stream scan finds entity used in SubClassOf (no Declaration axiom)")
    void streamScanFindsEntityWithoutDeclaration(@TempDir Path tempDir) throws Exception {
        // Build a synthetic OBO ontology (hp.owl) with two classes referenced
        // in a SubClassOf axiom but WITHOUT explicit Declaration axioms.
        // Per OWL 2 spec, entities referenced in axioms are part of the
        // signature — stream scan (Imports.EXCLUDED) MUST find them.
        Path ontDir = tempDir.resolve("default/ontologies/hpo/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns="http://purl.obolibrary.org/obo/hp.owl"
                 xml:base="http://purl.obolibrary.org/obo/hp.owl"
                 xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns:owl="http://www.w3.org/2002/07/owl#"
                 xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/hp.owl"/>
                <owl:Class rdf:about="http://purl.obolibrary.org/obo/HP_0001250">
                    <rdfs:subClassOf rdf:resource="http://purl.obolibrary.org/obo/HP_0000118"/>
                </owl:Class>
            </rdf:RDF>
            """;
        Files.writeString(owlFile, owlXml);

        OntologyCache cache = new OntologyCache(tempDir.toString(), "default");
        ReasonerLifecycleManager lm = new ReasonerLifecycleManager();

        // Use deprecated 3-arg constructor with manager=null to force the
        // stream-scan fallback path (no per-ontology cache).
        @SuppressWarnings("deprecation")
        ConsistencyAnalysisService service = new ConsistencyAnalysisService(
            lm, cache.getWorkspaceBasePath(), cache);

        OntologyId ontId = new OntologyId("hpo");

        // Stream scan MUST find HP_0001250 (referenced in SubClassOf).
        assertTrue(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/HP_0001250", "class"),
            "Stream scan must find HP_0001250 referenced in SubClassOf axiom");

        // Stream scan MUST find HP_0000118 (referenced as superclass in
        // SubClassOf axiom — no Declaration axiom for it either).
        assertTrue(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/HP_0000118", "class"),
            "Stream scan must find HP_0000118 referenced as superclass in SubClassOf axiom");

        // Stream scan MUST reject non-existent entity.
        assertFalse(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/HP_9999999", "class"),
            "Stream scan must reject non-existent HP_ entity");

        // Stream scan MUST reject cross-namespace entity (UBERON_ instead of HP_).
        assertFalse(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/UBERON_0001234", "class"),
            "Stream scan must reject cross-namespace UBERON_ entity (OBO namespace check)");
    }

    @Test
    @DisplayName("Stream scan finds entity WITH Declaration axiom (parity with cache path)")
    void streamScanFindsEntityWithDeclaration(@TempDir Path tempDir) throws Exception {
        // Build a synthetic OBO ontology with an explicit Declaration axiom.
        Path ontDir = tempDir.resolve("default/ontologies/mondo/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns="http://purl.obolibrary.org/obo/mondo.owl"
                 xml:base="http://purl.obolibrary.org/obo/mondo.owl"
                 xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns:owl="http://www.w3.org/2002/07/owl#">
                <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/mondo.owl"/>
                <owl:Class rdf:about="http://purl.obolibrary.org/obo/MONDO_0000001"/>
            </rdf:RDF>
            """;
        Files.writeString(owlFile, owlXml);

        OntologyCache cache = new OntologyCache(tempDir.toString(), "default");
        ReasonerLifecycleManager lm = new ReasonerLifecycleManager();

        @SuppressWarnings("deprecation")
        ConsistencyAnalysisService service = new ConsistencyAnalysisService(
            lm, cache.getWorkspaceBasePath(), cache);

        OntologyId ontId = new OntologyId("mondo");

        assertTrue(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/MONDO_0000001", "class"),
            "Stream scan must find MONDO_0000001 with explicit Declaration axiom");
        assertFalse(service.isEntityDeclared(ontId, "http://purl.obolibrary.org/obo/MONDO_9999999", "class"),
            "Stream scan must reject non-existent MONDO_ entity");
    }

    @Test
    @DisplayName("Stream scan handles null/blank entityIRI gracefully")
    void streamScanHandlesNullOrBlankIri(@TempDir Path tempDir) throws Exception {
        Path ontDir = tempDir.resolve("default/ontologies/hpo/canonical");
        Files.createDirectories(ontDir);
        Path owlFile = ontDir.resolve("ontology.owl");
        String owlXml = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns="http://purl.obolibrary.org/obo/hp.owl"
                 xml:base="http://purl.obolibrary.org/obo/hp.owl"
                 xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns:owl="http://www.w3.org/2002/07/owl#">
                <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/hp.owl"/>
                <owl:Class rdf:about="http://purl.obolibrary.org/obo/HP_0001250"/>
            </rdf:RDF>
            """;
        Files.writeString(owlFile, owlXml);

        OntologyCache cache = new OntologyCache(tempDir.toString(), "default");
        ReasonerLifecycleManager lm = new ReasonerLifecycleManager();

        @SuppressWarnings("deprecation")
        ConsistencyAnalysisService service = new ConsistencyAnalysisService(
            lm, cache.getWorkspaceBasePath(), cache);

        OntologyId ontId = new OntologyId("hpo");

        assertFalse(service.isEntityDeclared(ontId, null, "class"),
            "Null entityIRI must return false");
        assertFalse(service.isEntityDeclared(ontId, "", "class"),
            "Empty entityIRI must return false");
        assertFalse(service.isEntityDeclared(ontId, "   ", "class"),
            "Blank entityIRI must return false");
    }
}
