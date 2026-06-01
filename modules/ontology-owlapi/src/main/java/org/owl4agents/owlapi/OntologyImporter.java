package org.owl4agents.owlapi;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * OWL/RDF file import through OWL API.
 * Preserves the original source file without overwriting it.
 * Stores the ontology in the workspace catalog and creates a canonical copy.
 */
public class OntologyImporter {

    private final HomeDirectoryResolver homeResolver;
    private final CatalogStore catalogStore;

    public OntologyImporter(HomeDirectoryResolver homeResolver, CatalogStore catalogStore) {
        this.homeResolver = homeResolver;
        this.catalogStore = catalogStore;
    }

    /**
     * Import a local OWL/RDF file into the workspace.
     * The file is loaded through OWL API to verify it is a valid ontology.
     * The original file is copied to the workspace source directory.
     * A canonical copy is created.
     */
    public ServiceResult<Void> importOntology(OntologyId ontologyId, Path sourceFilePath, WorkspaceId workspaceId) {
        // 1. Verify the source file exists
        if (!Files.exists(sourceFilePath)) {
            return ServiceResult.error(ServiceError.importFailed(
                sourceFilePath.toString(), "Source file does not exist."));
        }

        // 2. Try to load the ontology through OWL API
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology;
        try {
            ontology = manager.loadOntologyFromOntologyDocument(sourceFilePath.toFile());
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ServiceError.importFailed(
                sourceFilePath.toString(), "OWL API could not parse the file: " + e.getMessage()));
        }

        // 3. Create workspace directory structure for this ontology
        Path workspaceDir = homeResolver.resolveWorkspaceDirectory(workspaceId);
        Path ontologyDir = workspaceDir.resolve("ontologies").resolve(ontologyId.id());
        try {
            Files.createDirectories(ontologyDir.resolve("source"));
            Files.createDirectories(ontologyDir.resolve("canonical"));
            Files.createDirectories(ontologyDir.resolve("index"));
        } catch (IOException e) {
            return ServiceResult.error(ServiceError.importFailed(
                sourceFilePath.toString(), "Failed to create ontology directory: " + e.getMessage()));
        }

        // 4. Copy the original source file (preserve without overwriting)
        Path sourceCopyPath = ontologyDir.resolve("source").resolve(sourceFilePath.getFileName());
        try {
            Files.copy(sourceFilePath, sourceCopyPath);
        } catch (IOException e) {
            return ServiceResult.error(ServiceError.importFailed(
                sourceFilePath.toString(), "Failed to copy source file: " + e.getMessage()));
        }

        // 5. Save canonical ontology copy using OWL API serialization
        Path canonicalPath = ontologyDir.resolve("canonical").resolve("ontology.owl");
        try {
            // Save the loaded ontology to the canonical path using OWL API IRI target
            IRI canonicalIri = IRI.create(canonicalPath.toUri());
            manager.saveOntology(ontology, canonicalIri);
        } catch (Exception e) {
            // Fallback: copy the source file as canonical if OWL API save fails
            try {
                Files.copy(sourceFilePath, canonicalPath);
            } catch (IOException ex) {
                // Source copy already exists, proceed
            }
        }

        // 6. Create catalog entry
        CatalogEntry entry = new CatalogEntry(
            ontologyId,
            extractDisplayName(ontology, ontologyId),
            sourceCopyPath,
            canonicalPath,
            Instant.now(),
            ontologyDir.resolve("metadata.json")
        );

        ServiceResult<Void> catalogResult = catalogStore.addEntry(workspaceId, entry);
        if (!catalogResult.isSuccess()) {
            return catalogResult;
        }

        return ServiceResult.success(null,
            org.owl4agents.core.ResultMetadata.explicit(ontologyId));
    }

    private String extractDisplayName(OWLOntology ontology, OntologyId ontologyId) {
        // OWL API 5.1.20 uses java.util.Optional for getOntologyIRI()
        Optional<IRI> ontologyIRI = ontology.getOntologyID().getOntologyIRI();
        if (ontologyIRI.isPresent()) {
            return ontologyIRI.get().getShortForm();
        }
        return ontologyId.id();
    }
}