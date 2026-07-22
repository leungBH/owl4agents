package org.owl4agents.overlay;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.vocabulary.RDF;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * v0.8.7 OV-003 / D10: Parses RDF dynamic state input (Turtle,
 * JSON-LD, or N-Triples) into a {@link Collection} of
 * {@link OWLAxiom}s.
 *
 * <p>Conversion path (per design D10):</p>
 * <ol>
 *   <li>Parse the RDF string into a Jena {@link Model} using
 *       {@link RDFDataMgr#read(Model, InputStream, String, Lang)}.</li>
 *   <li>Iterate over the Jena Model's statements and convert each
 *       triple to the appropriate OWL axiom based on the object
 *       position (literal &rarr; data property assertion; IRI object
 *       with {@code rdf:type} &rarr; class assertion; other IRI object
 *       &rarr; object property assertion). This manual conversion
 *       ensures that undeclared properties are typed correctly, which
 *       is required for equivalence with the Java structured-object
 *       path (see {@link StructuredStateConverter}).</li>
 * </ol>
 *
 * <p>Format auto-detection: when the caller does not specify a format,
 * the parser tries Turtle first, then JSON-LD, then N-Triples. This
 * covers the three formats required by OV-003.</p>
 */
public final class RdfStateParser {

    private RdfStateParser() {
        // utility class
    }

    /**
     * Parse an RDF string with explicit format.
     *
     * @param rdfContent the RDF string (must not be null or blank)
     * @param lang       the Jena {@link Lang} ({@link Lang#TTL},
     *                   {@link Lang#JSONLD}, or {@link Lang#NT})
     * @return the parsed axiom set
     * @throws IllegalArgumentException if parsing fails
     */
    public static Collection<OWLAxiom> parse(String rdfContent, Lang lang) {
        if (rdfContent == null || rdfContent.isBlank()) {
            throw new IllegalArgumentException("rdfContent must not be null or blank");
        }
        if (lang == null) {
            throw new IllegalArgumentException("lang must not be null");
        }
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = new ByteArrayInputStream(rdfContent.getBytes(StandardCharsets.UTF_8))) {
            RDFDataMgr.read(model, in, null, lang);
        } catch (RiotException e) {
            throw new IllegalArgumentException("Failed to parse RDF as " + lang.getName() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            if (e.getCause() instanceof RiotException re) {
                throw new IllegalArgumentException("Failed to parse RDF as " + lang.getName() + ": " + re.getMessage(), re);
            }
            throw new IllegalArgumentException("Failed to parse RDF as " + lang.getName() + ": " + e.getMessage(), e);
        }
        return convertModelToAxioms(model);
    }

    /**
     * Parse an RDF string with auto-detected format. Tries Turtle,
     * then JSON-LD, then N-Triples.
     *
     * @param rdfContent the RDF string
     * @return the parsed axiom set
     * @throws IllegalArgumentException if all format attempts fail
     */
    public static Collection<OWLAxiom> parse(String rdfContent) {
        if (rdfContent == null || rdfContent.isBlank()) {
            throw new IllegalArgumentException("rdfContent must not be null or blank");
        }
        // Fast-path detection: JSON-LD content typically starts with '{' or '['.
        String trimmed = rdfContent.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return parse(rdfContent, Lang.JSONLD);
            } catch (IllegalArgumentException jsonLdError) {
                // Fall through to try other formats.
            }
        }
        // Try Turtle (a superset of N-Triples in Jena's parser).
        try {
            return parse(rdfContent, Lang.TTL);
        } catch (IllegalArgumentException turtleError) {
            // Fall through.
        }
        // Try N-Triples directly.
        try {
            return parse(rdfContent, Lang.NT);
        } catch (IllegalArgumentException ntError) {
            // Fall through.
        }
        // Last resort: try JSON-LD even if it doesn't start with '{'.
        try {
            return parse(rdfContent, Lang.JSONLD);
        } catch (IllegalArgumentException jsonLdError) {
            throw new IllegalArgumentException(
                "Failed to parse RDF content as Turtle, JSON-LD, or N-Triples. " +
                "Last error: " + jsonLdError.getMessage(), jsonLdError);
        }
    }

    /**
     * Convert a Jena {@link Model} to a {@link Collection} of
     * {@link OWLAxiom}s by iterating over the model's statements and
     * creating the appropriate OWL axiom for each triple based on the
     * object position.
     *
     * <p>This manual conversion is required because the OWL API's
     * default RDF loader treats undeclared properties as annotation
     * properties, which would produce {@code OWLAnnotationAssertionAxiom}
     * instead of {@code OWLDataPropertyAssertionAxiom} or
     * {@code OWLObjectPropertyAssertionAxiom}. By converting manually,
     * we ensure that:</p>
     * <ul>
     *   <li>Triples with literal objects become data property assertions.</li>
     *   <li>Triples with {@code rdf:type} and IRI objects become class
     *       assertions.</li>
     *   <li>Other triples with IRI objects become object property
     *       assertions.</li>
     * </ul>
     *
     * <p>This matches the axiom types produced by
     * {@link StructuredStateConverter}, so the same logical state
     * expressed as RDF or as a Java structured object produces
     * equivalent axiom sets (verified via {@link SnapshotChecksum}).</p>
     */
    static Collection<OWLAxiom> convertModelToAxioms(Model model) {
        if (model == null || model.isEmpty()) {
            return Set.of();
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        Set<OWLAxiom> axioms = new HashSet<>();

        for (Statement stmt : model.listStatements().toList()) {
            Resource subject = stmt.getSubject();
            Property predicate = stmt.getPredicate();
            RDFNode object = stmt.getObject();

            // Skip blank node subjects — v0.8.7 dynamic state uses named individuals.
            if (!subject.isURIResource()) {
                continue;
            }

            OWLNamedIndividual subjectInd =
                df.getOWLNamedIndividual(IRI.create(subject.getURI()));
            String predIri = predicate.getURI();

            if (object.isLiteral()) {
                // Data property assertion.
                OWLDataProperty prop = df.getOWLDataProperty(IRI.create(predIri));
                Literal lit = object.asLiteral();
                OWLLiteral owlLit = toOwlLiteral(lit, df);
                axioms.add(df.getOWLDataPropertyAssertionAxiom(prop, subjectInd, owlLit));
            } else if (object.isResource()) {
                if (predIri.equals(RDF.type.getURI())) {
                    // Class assertion (rdf:type).
                    if (object.isURIResource()) {
                        OWLClass cls =
                            df.getOWLClass(IRI.create(object.asResource().getURI()));
                        axioms.add(df.getOWLClassAssertionAxiom(cls, subjectInd));
                    }
                } else {
                    // Object property assertion.
                    if (object.isURIResource()) {
                        OWLObjectProperty prop =
                            df.getOWLObjectProperty(IRI.create(predIri));
                        OWLNamedIndividual objInd = df.getOWLNamedIndividual(
                            IRI.create(object.asResource().getURI()));
                        axioms.add(df.getOWLObjectPropertyAssertionAxiom(
                            prop, subjectInd, objInd));
                    }
                }
            }
        }

        return axioms;
    }

    /**
     * Convert a Jena {@link Literal} to an OWL API {@link OWLLiteral},
     * preserving the datatype and language tag where present.
     */
    private static OWLLiteral toOwlLiteral(Literal lit, OWLDataFactory df) {
        String lex = lit.getString();
        String dtypeUri = lit.getDatatypeURI();
        String lang = lit.getLanguage();

        // Language-tagged string (e.g. "on"@en).
        if (lang != null && !lang.isEmpty()) {
            return df.getOWLLiteral(lex, lang);
        }

        // Plain string (xsd:string or no datatype) — use the plain string
        // overload so the OWL API tags it as xsd:string, matching
        // StructuredStateConverter which uses df.getOWLLiteral(String).
        if (dtypeUri == null || dtypeUri.isEmpty()
            || dtypeUri.equals("http://www.w3.org/2001/XMLSchema#string")) {
            return df.getOWLLiteral(lex);
        }

        // Typed literal — preserve the datatype.
        return df.getOWLLiteral(lex, df.getOWLDatatype(IRI.create(dtypeUri)));
    }
}
