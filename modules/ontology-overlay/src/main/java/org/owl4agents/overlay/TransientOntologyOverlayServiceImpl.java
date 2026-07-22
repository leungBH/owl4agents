package org.owl4agents.overlay;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.TemporaryOntologyFactory;
import org.owl4agents.reasoner.TemporaryOntologyHandle;
import org.owl4agents.reasoner.TemporaryOntologyOptions;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * v0.8.7 OV-002 / D9 / D16: Default implementation of
 * {@link TransientOntologyOverlayService}.
 *
 * <p>Wraps the v0.8.5 {@link TemporaryOntologyFactory} (reusing its
 * imports-closure copy, isolated {@code OWLOntologyManager}, and
 * release semantics) and adds general-purpose dynamic ABox overlay
 * support.</p>
 *
 * <h2>Original Ontology Immutability</h2>
 *
 * <p>The implementation SHALL NOT modify the original workspace
 * ontology. The overlay uses an isolated {@code OWLOntologyManager}
 * (provided by {@link TemporaryOntologyFactory#createBase}) so that
 * adding dynamic axioms cannot leak into cached ontologies. The
 * original {@link OWLOntology} returned by
 * {@link OntologyCache#getOrCreate} is never modified, and the
 * workspace file on disk is never touched.</p>
 *
 * <h2>Cache Pollution Protection</h2>
 *
 * <p>The service does NOT call {@code OntologyCache.put} or any other
 * cache mutation method. The overlay ontology lives only inside the
 * {@link TransientOverlay} handle; when the handle is released, the
 * isolated manager is cleared and the overlay ontology becomes
 * eligible for garbage collection. The {@link OntologyCache} internal
 * map SHALL NOT contain any entry keyed by the overlay's ontology ID
 * or by any snapshot ID, and the cache size is unchanged from before
 * overlay creation (per D16 / REL-003).</p>
 *
 * <h2>Single-Call Implementation (v0.8.7)</h2>
 *
 * <p>v0.8.7 implements the single-call {@code createOverlay} interface.
 * A batch variant ({@code createOverlayBatch}) is deferred to a later
 * version per D9 / Open Question #2.</p>
 */
public final class TransientOntologyOverlayServiceImpl implements TransientOntologyOverlayService {

    private final OntologyCache ontologyCache;
    private final TemporaryOntologyFactory temporaryOntologyFactory;

    /**
     * Construct the service with an {@link OntologyCache} for resolving
     * base ontologies and a {@link TemporaryOntologyFactory} for
     * creating isolated copies.
     *
     * @param ontologyCache            the workspace ontology cache (must not be null)
     * @param temporaryOntologyFactory the temporary ontology factory (must not be null)
     */
    public TransientOntologyOverlayServiceImpl(OntologyCache ontologyCache,
                                               TemporaryOntologyFactory temporaryOntologyFactory) {
        if (ontologyCache == null) {
            throw new IllegalArgumentException("ontologyCache must not be null");
        }
        if (temporaryOntologyFactory == null) {
            throw new IllegalArgumentException("temporaryOntologyFactory must not be null");
        }
        this.ontologyCache = ontologyCache;
        this.temporaryOntologyFactory = temporaryOntologyFactory;
    }

    /**
     * Convenience constructor that creates a fresh
     * {@link TemporaryOntologyFactory} internally.
     */
    public TransientOntologyOverlayServiceImpl(OntologyCache ontologyCache) {
        this(ontologyCache, new TemporaryOntologyFactory());
    }

    @Override
    public ServiceResult<TransientOverlay> createOverlay(
        OntologyId baseOntology,
        Collection<OWLAxiom> dynamicAxioms,
        OverlayOptions options
    ) {
        if (baseOntology == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "baseOntology must not be null");
        }
        if (dynamicAxioms == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "dynamicAxioms must not be null (may be empty)");
        }
        OverlayOptions opts = (options == null) ? OverlayOptions.defaults() : options;

        // 1. Resolve the base ontology via OntologyCache.getOrCreate.
        OWLOntology base;
        try {
            base = ontologyCache.getOrCreate(baseOntology);
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND,
                "Failed to load base ontology '" + baseOntology.id() + "': " + e.getMessage());
        }

        // 2. Create an isolated copy via TemporaryOntologyFactory.createBase.
        // This reuses the v0.8.5 imports-closure copy and isolated manager.
        TemporaryOntologyOptions tempOpts = new TemporaryOntologyOptions(
            opts.copyImportsClosure(),
            opts.resolvedIriSuffix()
        );
        ServiceResult<TemporaryOntologyHandle> baseResult =
            temporaryOntologyFactory.createBase(base, tempOpts);
        if (!baseResult.isSuccess()) {
            ServiceResult.Error<TemporaryOntologyHandle> err =
                (ServiceResult.Error<TemporaryOntologyHandle>) baseResult;
            return ServiceResult.error(err.error());
        }
        TemporaryOntologyHandle handle =
            ((ServiceResult.Success<TemporaryOntologyHandle>) baseResult).data();

        // 3. Add the dynamic axioms to the isolated copy (NOT to the source).
        Collection<OWLAxiom> axiomCopy = new ArrayList<>(dynamicAxioms);
        if (!axiomCopy.isEmpty()) {
            try {
                OWLOntology temp = handle.ontology();
                temp.getOWLOntologyManager().addAxioms(temp, axiomCopy);
            } catch (Exception e) {
                handle.release();
                return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                    "Failed to inject dynamic axioms into overlay: " + e.getMessage());
            }
        }

        // 4. Build a metadata-only EnvironmentSnapshot from the axioms.
        // The checksum is the canonical SHA256 of the dynamic axiom set.
        String snapshotId = UUID.randomUUID().toString();
        Instant capturedAt = Instant.now();
        String checksum = SnapshotChecksum.compute(axiomCopy);
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            snapshotId,
            capturedAt,
            "api",
            0L,
            checksum,
            List.of(),
            List.of(),
            List.of()
        );

        // 5. Wrap and return.
        TransientOverlayImpl overlay = new TransientOverlayImpl(
            UUID.randomUUID().toString(),
            baseOntology,
            axiomCopy,
            snapshotId,
            capturedAt,
            handle.ontology(),
            snapshot,
            handle
        );
        return ServiceResult.success(overlay, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<TransientOverlay> createOverlay(
        OntologyId baseOntology,
        EnvironmentSnapshot snapshot,
        OverlayOptions options
    ) {
        if (baseOntology == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "baseOntology must not be null");
        }
        if (snapshot == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "snapshot must not be null");
        }
        OverlayOptions opts = (options == null) ? OverlayOptions.defaults() : options;

        // Convert the structured snapshot to axioms via StructuredStateConverter.
        Collection<OWLAxiom> axioms = StructuredStateConverter.convert(snapshot);

        // Build the overlay using the axiom-based path.
        ServiceResult<TransientOverlay> result = createOverlay(baseOntology, axioms, opts);
        if (!result.isSuccess()) {
            return result;
        }
        // Replace the metadata-only snapshot with the caller's structured snapshot
        // (which carries the devices/userContexts/pendingToolCalls).
        TransientOverlayImpl overlay = (TransientOverlayImpl) ((ServiceResult.Success<TransientOverlay>) result).data();
        overlay.replaceSnapshot(snapshot);
        return ServiceResult.success(overlay, ResultMetadata.empty());
    }

    /**
     * Inner {@link TransientOverlay} implementation that wraps a
     * {@link TemporaryOntologyHandle} and the snapshot metadata.
     */
    static final class TransientOverlayImpl implements TransientOverlay {

        private final String overlayId;
        private final OntologyId baseOntologyId;
        private final Collection<OWLAxiom> dynamicAxioms;
        private final String snapshotId;
        private final Instant createdAt;
        private final OWLOntology ontology;
        private EnvironmentSnapshot snapshot;
        private final TemporaryOntologyHandle handle;
        private volatile boolean released = false;

        TransientOverlayImpl(String overlayId,
                             OntologyId baseOntologyId,
                             Collection<OWLAxiom> dynamicAxioms,
                             String snapshotId,
                             Instant createdAt,
                             OWLOntology ontology,
                             EnvironmentSnapshot snapshot,
                             TemporaryOntologyHandle handle) {
            this.overlayId = overlayId;
            this.baseOntologyId = baseOntologyId;
            this.dynamicAxioms = List.copyOf(dynamicAxioms);
            this.snapshotId = snapshotId;
            this.createdAt = createdAt;
            this.ontology = ontology;
            this.snapshot = snapshot;
            this.handle = handle;
        }

        @Override
        public String overlayId() {
            return overlayId;
        }

        @Override
        public OntologyId baseOntologyId() {
            return baseOntologyId;
        }

        @Override
        public Collection<OWLAxiom> dynamicAxioms() {
            return dynamicAxioms;
        }

        @Override
        public String snapshotId() {
            return snapshotId;
        }

        @Override
        public Instant createdAt() {
            return createdAt;
        }

        @Override
        public OWLOntology ontology() {
            return ontology;
        }

        @Override
        public EnvironmentSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public synchronized void release() {
            if (!released) {
                released = true;
                if (handle != null) {
                    handle.release();
                }
            }
        }

        /**
         * Replace the metadata-only snapshot with the caller's structured
         * snapshot (used by the EnvironmentSnapshot convenience overload).
         */
        synchronized void replaceSnapshot(EnvironmentSnapshot newSnapshot) {
            if (newSnapshot != null) {
                this.snapshot = newSnapshot;
            }
        }
    }
}
