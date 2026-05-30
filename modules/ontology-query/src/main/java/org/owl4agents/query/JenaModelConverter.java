package org.owl4agents.query;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.GraphScope;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts imported ontology explicit graph into a Jena query model.
 * OWL API remains the source of truth; Jena is used for SPARQL query views only.
 */
public class JenaModelConverter {

    /**
     * Convert an ontology file to a Jena Model for query execution.
     * The OWL API ontology is the authoritative source; this conversion
     * produces a Jena-side RDF view for SPARQL access.
     */
    public ServiceResult<Model> convertToJenaModel(OntologyId ontologyId, Path ontologyFilePath, GraphScope scope) {
        if (!Files.exists(ontologyFilePath)) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontologyId));
        }

        try {
            // 1. Load with OWL API first (to verify it's valid)
            OWLOntologyManager owlManager = OWLManager.createOWLOntologyManager();
            OWLOntology owlOntology = owlManager.loadOntologyFromOntologyDocument(ontologyFilePath.toFile());

            // 2. Serialize the OWL ontology to RDF/XML
            ByteArrayOutputStream rdfXmlStream = new ByteArrayOutputStream();
            owlManager.saveOntology(owlOntology, rdfXmlStream);

            // 3. Load the RDF/XML into a Jena Model
            Model jenaModel = ModelFactory.createDefaultModel();
            jenaModel.read(new ByteArrayInputStream(rdfXmlStream.toByteArray()),
                ontologyFilePath.toUri().toString(), "RDF/XML");

            return ServiceResult.success(jenaModel,
                org.owl4agents.core.ResultMetadata.explicit(ontologyId));

        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ServiceError.importFailed(
                ontologyFilePath.toString(), "Cannot load ontology for Jena conversion: " + e.getMessage()));
        } catch (Exception e) {
            return ServiceResult.error(ServiceError.importFailed(
                ontologyFilePath.toString(), "Jena model conversion failed: " + e.getMessage()));
        }
    }

    /**
     * Convert from a file path directly, without pre-loading via OWL API.
     * Used when the ontology has already been validated during import.
     */
    public ServiceResult<Model> convertFromPath(OntologyId ontologyId, Path ontologyFilePath) {
        if (!Files.exists(ontologyFilePath)) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontologyId));
        }

        try {
            Model jenaModel = ModelFactory.createDefaultModel();
            String baseUri = ontologyFilePath.toUri().toString();

            // Try RDF/XML first, then Turtle
            try {
                jenaModel.read(ontologyFilePath.toUri().toString(), "RDF/XML");
            } catch (Exception rdfXmlError) {
                try {
                    jenaModel.read(ontologyFilePath.toUri().toString(), "TTL");
                } catch (Exception turtleError) {
                    jenaModel.read(ontologyFilePath.toUri().toString());
                }
            }

            return ServiceResult.success(jenaModel,
                org.owl4agents.core.ResultMetadata.explicit(ontologyId));

        } catch (Exception e) {
            return ServiceResult.error(ServiceError.importFailed(
                ontologyFilePath.toString(), "Jena model read failed: " + e.getMessage()));
        }
    }
}