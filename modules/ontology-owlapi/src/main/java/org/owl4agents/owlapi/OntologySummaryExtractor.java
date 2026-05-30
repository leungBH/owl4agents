package org.owl4agents.owlapi;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.profiles.OWLProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.profiles.Profiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts ontology metadata, IRI, version IRI, imports, entity counts,
 * and profile information for imported ontologies.
 */
public class OntologySummaryExtractor {

    /**
     * Extract a full ontology summary from an ontology file path.
     */
    public ServiceResult<OntologySummary> extractSummary(OntologyId ontologyId, Path ontologyFilePath) {
        if (!Files.exists(ontologyFilePath)) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontologyId));
        }

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology;

        try {
            ontology = manager.loadOntologyFromOntologyDocument(ontologyFilePath.toFile());
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ServiceError.importFailed(
                ontologyFilePath.toString(), "Could not load ontology: " + e.getMessage()));
        }

        OntologySummary summary = buildSummary(ontologyId, ontology);

        return ServiceResult.success(summary,
            ResultMetadata.explicit(ontologyId));
    }

    /**
     * Build a summary from a loaded OWLOntology object.
     */
    public OntologySummary buildSummary(OntologyId ontologyId, OWLOntology ontology) {
        // Extract ontology IRI — OWL API 4.5.x uses Guava Optional
        String ontologyIri = null;
        com.google.common.base.Optional<IRI> optOntologyIRI = ontology.getOntologyID().getOntologyIRI();
        if (optOntologyIRI.isPresent()) {
            ontologyIri = optOntologyIRI.get().toString();
        }

        // Extract version IRI — OWL API 4.5.x uses Guava Optional
        String versionIri = null;
        com.google.common.base.Optional<IRI> optVersionIRI = ontology.getOntologyID().getVersionIRI();
        if (optVersionIRI.isPresent()) {
            versionIri = optVersionIRI.get().toString();
        }

        // Extract imports (only direct imports documents)
        // getDirectImportsDocuments() returns Set<IRI>, and IRI.toString() gives the full IRI string
        List<String> imports = new ArrayList<>();
        for (IRI importIri : ontology.getDirectImportsDocuments()) {
            imports.add(importIri.toString());
        }

        // Extract entity counts (from the ontology without imports)
        EntityCounts entityCounts = countEntities(ontology);

        // Extract profile information
        ProfileInfo profileInfo = extractProfileInfo(ontology);

        return new OntologySummary(
            ontologyId, ontologyIri, versionIri, imports, profileInfo, entityCounts
        );
    }

    private EntityCounts countEntities(OWLOntology ontology) {
        // Count classes in the signature (excluding imports)
        int classes = ontology.getClassesInSignature(Imports.EXCLUDED).size()
            - 1; // Subtract owl:Thing which is always present

        int objectProperties = ontology.getObjectPropertiesInSignature(Imports.EXCLUDED).size()
            - 2; // Subtract owl:topObjectProperty and owl:bottomObjectProperty

        int dataProperties = ontology.getDataPropertiesInSignature(Imports.EXCLUDED).size()
            - 2; // Subtract owl:topDataProperty and owl:bottomDataProperty

        int annotationProperties = ontology.getAnnotationPropertiesInSignature(Imports.EXCLUDED).size()
            - 2; // Subtract rdfs:label and rdfs:comment (built-in)

        int individuals = ontology.getIndividualsInSignature(Imports.EXCLUDED).size();

        int datatypes = ontology.getDatatypesInSignature(Imports.EXCLUDED).size()
            - 2; // Subtract rdfs:Literal and xsd:string (built-in)

        // Ensure counts are not negative
        return new EntityCounts(
            Math.max(0, classes),
            Math.max(0, objectProperties),
            Math.max(0, dataProperties),
            Math.max(0, annotationProperties),
            Math.max(0, individuals),
            Math.max(0, datatypes)
        );
    }

    private ProfileInfo extractProfileInfo(OWLOntology ontology) {
        List<String> profiles = new ArrayList<>();
        List<ProfileViolation> violations = new ArrayList<>();

        // OWL API 4.5.x: Profiles has static constants, not getProfiles() method
        // Iterate over known OWL 2 profiles
        OWLProfile[] knownProfiles = {
            Profiles.OWL2_DL,
            Profiles.OWL2_EL,
            Profiles.OWL2_QL,
            Profiles.OWL2_RL,
            Profiles.OWL2_FULL
        };

        for (OWLProfile profile : knownProfiles) {
            OWLProfileReport report = profile.checkOntology(ontology);
            if (report.isInProfile()) {
                profiles.add(profile.getName());
            } else {
                // Record violations — OWLProfileViolation has toString() for description
                for (OWLProfileViolation violation : report.getViolations()) {
                    violations.add(new ProfileViolation(
                        profile.getName(),
                        violation.toString(),
                        violation.getAxiom() != null ? violation.getAxiom().getAxiomType().getName() : null,
                        1
                    ));
                }
            }
        }

        return new ProfileInfo(profiles, violations);
    }
}