package org.owl4agents.reasoner;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.util.Set;

/**
 * Creates isolated temporary ontologies for exact consistency checks (D3).
 *
 * <p>The temporary ontology uses an independent {@link OWLOntologyManager}
 * (not the shared manager) to guarantee complete isolation: when the
 * {@link TemporaryOntologyHandle} is released, the manager is cleared and
 * the temporary ontology becomes eligible for garbage collection.
 *
 * <p>The temporary ontology contains:
 * <ul>
 *   <li>The source ontology's imports closure axioms (copied directly from
 *       the already-loaded source via {@code source.getAxioms(Imports.INCLUDED)});
 *       the source's imports closure is already resolved by the main manager
 *       and {@code CatalogStore}, so the temporary factory copies resolved
 *       axioms rather than re-resolving imports — avoiding the need for
 *       IRI mappers in the independent manager.</li>
 *   <li>The claim axiom to verify.</li>
 * </ul>
 *
 * <p>The factory SHALL NOT: write to disk, register in the main ontology
 * cache, modify the source ontology, or survive after the check.
 */
public final class TemporaryOntologyFactory {

    /**
     * Create an isolated temporary ontology containing the source's imports
     * closure (when {@code options.copyImportsClosure()} is true) plus the
     * claim axiom.
     *
     * @param source     the source ontology (must not be null); not modified
     * @param claimAxiom the axiom to add to the temporary ontology (must not be null)
     * @param options    creation options (must not be null); use
     *                   {@link TemporaryOntologyOptions#defaults()} for defaults
     * @return a {@link ServiceResult} containing the {@link TemporaryOntologyHandle}
     *         on success, or an error with code
     *         {@link ErrorCode#TEMPORARY_ONTOLOGY_CREATION_FAILED} on failure
     */
    public ServiceResult<TemporaryOntologyHandle> create(
            OWLOntology source,
            OWLAxiom claimAxiom,
            TemporaryOntologyOptions options) {
        if (source == null) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Source ontology must not be null.");
        }
        if (claimAxiom == null) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Claim axiom must not be null.");
        }
        if (options == null) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "TemporaryOntologyOptions must not be null.");
        }

        // 1. Create an independent OWLOntologyManager — NOT the shared manager.
        // This guarantees complete isolation: when the handle is released,
        // manager.clear() frees all temporary ontology state.
        OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();

        // 2. Create an empty in-memory ontology with a derived IRI.
        IRI tempIri = deriveTemporaryIri(source, options.iriSuffix());
        OWLOntology tempOntology;
        try {
            tempOntology = tempManager.createOntology(tempIri);
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Failed to create empty temporary ontology: " + e.getMessage());
        }

        // 3. Copy imports closure axioms directly from the already-loaded source.
        // The source's imports closure is already resolved by the main manager
        // and CatalogStore (catalog.json); copying resolved axioms avoids
        // re-resolving imports and avoids the need for IRI mappers in the
        // independent manager.
        try {
            Set<OWLAxiom> sourceAxioms;
            if (options.copyImportsClosure()) {
                sourceAxioms = source.getAxioms(Imports.INCLUDED);
            } else {
                sourceAxioms = source.getAxioms(Imports.EXCLUDED);
            }
            // applyChange-based add is atomic and avoids disk persistence
            tempOntology.getOWLOntologyManager().addAxioms(tempOntology, sourceAxioms);
        } catch (Exception e) {
            // Defensive: source is already loaded so this should not happen,
            // but if copying fails (e.g. axiom set unstable), surface the error.
            releaseQuietly(tempManager);
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Failed to copy source imports closure into temporary ontology: " + e.getMessage());
        }

        // 4. Add the claim axiom to the temporary ontology (NOT to the source).
        try {
            tempOntology.getOWLOntologyManager().addAxiom(tempOntology, claimAxiom);
        } catch (Exception e) {
            releaseQuietly(tempManager);
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Failed to add claim axiom to temporary ontology: " + e.getMessage());
        }

        // 5. Return the handle. The caller is responsible for releasing it
        // (typically via try-with-resources).
        return ServiceResult.success(
            new TemporaryOntologyHandle(tempOntology, tempManager),
            ResultMetadata.empty());
    }

    /**
     * Create an isolated temporary ontology containing only the source's
     * imports closure axioms — WITHOUT any claim axiom. Used by the cached
     * exact-check session (v0.8.5 P1 fix): the base copy is created once per
     * ontology, the reasoner is initialized on it, and individual claim
     * axioms are added/removed via {@code OWLOntologyManager.applyChange}
     * for incremental consistency checks.
     *
     * @param source  the source ontology (must not be null); not modified
     * @param options creation options (must not be null)
     * @return a {@link ServiceResult} containing the base
     *         {@link TemporaryOntologyHandle} on success, or an error on failure
     */
    public ServiceResult<TemporaryOntologyHandle> createBase(
            OWLOntology source, TemporaryOntologyOptions options) {
        if (source == null) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Source ontology must not be null.");
        }
        if (options == null) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "TemporaryOntologyOptions must not be null.");
        }

        OWLOntologyManager tempManager = OWLManager.createOWLOntologyManager();
        IRI tempIri = deriveTemporaryIri(source, options.iriSuffix());
        OWLOntology tempOntology;
        try {
            tempOntology = tempManager.createOntology(tempIri);
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Failed to create empty temporary ontology: " + e.getMessage());
        }

        try {
            Set<OWLAxiom> sourceAxioms;
            if (options.copyImportsClosure()) {
                sourceAxioms = source.getAxioms(Imports.INCLUDED);
            } else {
                sourceAxioms = source.getAxioms(Imports.EXCLUDED);
            }
            tempManager.addAxioms(tempOntology, sourceAxioms);
        } catch (Exception e) {
            releaseQuietly(tempManager);
            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                "Failed to copy source imports closure into base ontology: " + e.getMessage());
        }

        return ServiceResult.success(
            new TemporaryOntologyHandle(tempOntology, tempManager),
            ResultMetadata.empty());
    }

    /**
     * Derive a temporary ontology IRI from the source ontology's IRI plus
     * the configured suffix. Falls back to {@code urn:owl4agents:temp}
     * when the source has no ontology IRI.
     */
    private static IRI deriveTemporaryIri(OWLOntology source, String iriSuffix) {
        String suffix = (iriSuffix == null || iriSuffix.isBlank()) ? "-temp" : iriSuffix;
        return source.getOntologyID().getOntologyIRI()
            .map(base -> IRI.create(base.toString() + suffix))
            .orElse(IRI.create("urn:owl4agents:temp" + suffix));
    }

    /**
     * Best-effort cleanup when creation fails partway through. Never throws.
     */
    private static void releaseQuietly(OWLOntologyManager manager) {
        if (manager == null) {
            return;
        }
        try {
            for (OWLOntology ont : java.util.Set.copyOf(manager.getOntologies())) {
                try {
                    manager.removeOntology(ont);
                } catch (Exception ignored) {
                    // best-effort per-ontology removal
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
