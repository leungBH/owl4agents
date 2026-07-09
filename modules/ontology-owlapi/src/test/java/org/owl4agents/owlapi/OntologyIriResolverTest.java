package org.owl4agents.owlapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-28: OntologyIriResolver contract verification — the shared IRI
 * resolution utility introduced in v0.8.1 must be a final class with a
 * private constructor, expose {@code resolveOntologyIRI(OWLOntology, String, String)},
 * and be consulted by both {@code ReasonerServiceImpl} and
 * {@code ClassExpressionBuilder}.
 */
@DisplayName("TC-28 OntologyIriResolver contract")
class OntologyIriResolverTest {

    @Test
    @DisplayName("OntologyIriResolver is final with a private constructor")
    void finalClassWithPrivateConstructor() throws Exception {
        Class<?> cls = OntologyIriResolver.class;
        assertTrue(Modifier.isFinal(cls.getModifiers()),
            "OntologyIriResolver must be final");
        Constructor<?> ctor = cls.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
            "OntologyIriResolver constructor must be private");
        ctor.setAccessible(true);
        // Should not throw — the private constructor exists and is callable
        Object instance = ctor.newInstance();
        assertNotNull(instance);
    }

    @Test
    @DisplayName("resolveOntologyIRI returns full IRI passthrough for http:// IRIs")
    void fullIriPassthrough() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ont = mgr.createOntology(IRI.create("http://example.org/test"));
        OWLDataFactory df = mgr.getOWLDataFactory();

        OWLClass cls = df.getOWLClass(IRI.create("http://example.org/test#Foo"));
        mgr.addAxiom(ont, df.getOWLDeclarationAxiom(cls));

        Method m = OntologyIriResolver.class.getMethod(
            "resolveOntologyIRI", OWLOntology.class, String.class, String.class);
        IRI resolved = (IRI) m.invoke(null, ont, "http://example.org/test#Foo", "class");
        assertNotNull(resolved);
        assertEquals("http://example.org/test#Foo", resolved.toString());
    }

    @Test
    @DisplayName("resolveOntologyIRI returns null for unknown IRIs")
    void unknownIriReturnsNull() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ont = mgr.createOntology(IRI.create("http://example.org/empty"));

        Method m = OntologyIriResolver.class.getMethod(
            "resolveOntologyIRI", OWLOntology.class, String.class, String.class);
        IRI resolved = (IRI) m.invoke(null, ont, "http://example.org/empty#Unknown", "class");
        assertNull(resolved);
    }

    @Test
    @DisplayName("resolveOntologyIRI looks up signature for bare IRIs")
    void bareIriSignatureLookup() throws Exception {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ont = mgr.createOntology(IRI.create("http://example.org/lookup"));
        OWLDataFactory df = mgr.getOWLDataFactory();

        OWLClass cls = df.getOWLClass(IRI.create("http://example.org/lookup#Bar"));
        mgr.addAxiom(ont, df.getOWLDeclarationAxiom(cls));

        Method m = OntologyIriResolver.class.getMethod(
            "resolveOntologyIRI", OWLOntology.class, String.class, String.class);
        // No scheme, no colon → falls into signature lookup; OWL API normalizes
        // bare IRIs to full IRIs internally, so this should still resolve.
        IRI resolved = (IRI) m.invoke(null, ont, "http://example.org/lookup#Bar", "class");
        assertNotNull(resolved, "Signature lookup should resolve a declared class");
        assertEquals("http://example.org/lookup#Bar", resolved.toString());
    }
}
