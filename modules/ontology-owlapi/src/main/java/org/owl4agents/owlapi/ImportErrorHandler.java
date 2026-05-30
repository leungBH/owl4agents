package org.owl4agents.owlapi;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.nio.file.Path;

/**
 * Handles structured import errors for invalid or unsupported ontology files.
 * Provides clear, actionable error messages for different failure modes.
 */
public class ImportErrorHandler {

    /**
     * Classify and create a structured error for an OWL API import failure.
     */
    public ServiceResult<Void> handleImportError(Path filePath, Exception exception) {
        if (exception instanceof OWLOntologyCreationException) {
            OWLOntologyCreationException owlException = (OWLOntologyCreationException) exception;
            String message = owlException.getMessage();

            // Determine specific failure type from the exception
            if (message.contains("parse") || message.contains("syntax") || message.contains("unparseable")) {
                return ServiceResult.error(ServiceError.importFailed(
                    filePath.toString(),
                    "The file contains invalid RDF/XML or Turtle syntax that OWL API cannot parse: " + message));
            }

            if (message.contains("IO") || message.contains("file not found") || message.contains("FileNotFoundException")) {
                return ServiceResult.error(ServiceError.importFailed(
                    filePath.toString(),
                    "The file could not be read: " + message));
            }

            if (message.contains("unsupported") || message.contains("not recognized")) {
                return ServiceResult.error(ServiceError.importFailed(
                    filePath.toString(),
                    "The file format is not supported by OWL API: " + message));
            }

            // Generic import failure
            return ServiceResult.error(ServiceError.importFailed(
                filePath.toString(), message));
        }

        // Non-OWL API exception
        return ServiceResult.error(ServiceError.importFailed(
            filePath.toString(),
            "Unexpected error during import: " + exception.getClass().getSimpleName() + " - " + exception.getMessage()));
    }

    /**
     * Create a structured error for a file that is not a valid OWL/RDF ontology at all
     * (e.g., a plain text file).
     */
    public ServiceResult<Void> handleNonOntologyFile(Path filePath) {
        return ServiceResult.error(ServiceError.importFailed(
            filePath.toString(),
            "The file is not a valid OWL/RDF ontology file. OWL API could not parse it."));
    }
}