package org.owl4agents.overlay;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.semanticweb.owlapi.model.OWLAxiom;

import java.util.Collection;

/**
 * v0.8.7 OV-001 / OV-002 / D9: Service that builds transient overlay
 * ontologies for tool-call validation.
 *
 * <h2>Static and Dynamic Knowledge Boundary</h2>
 *
 * <p>The overlay subsystem distinguishes three knowledge layers:</p>
 * <ul>
 *   <li><strong>Static TBox</strong> — device categories, capabilities,
 *       operation categories, risk categories, state types, class
 *       hierarchies, class disjointness, and property definitions.
 *       Loaded once from the workspace via {@code OntologyCache.getOrCreate}
 *       and reused across overlay calls.</li>
 *   <li><strong>Semi-static ABox</strong> — households, rooms, registered
 *       devices, device models, users, user roles, and capability
 *       instances. Also loaded from the workspace as part of the base
 *       ontology.</li>
 *   <li><strong>Dynamic ABox</strong> — current switch states, current
 *       temperatures, door/window states, online status, alarm states,
 *       current time, current user, pending tool calls, and scheduled
 *       tasks. Provided per-call via {@code dynamicAxioms}.</li>
 * </ul>
 *
 * <p><strong>Dynamic ABox MUST NOT be written to the original workspace.</strong>
 * The overlay service creates an isolated in-memory copy of the base
 * ontology (via the v0.8.5 {@code TemporaryOntologyFactory}) and adds
 * the dynamic axioms only to that copy. The original
 * {@link org.semanticweb.owlapi.model.OWLOntology} returned by
 * {@code OntologyCache.getOrCreate} is never modified, and the
 * workspace file on disk is never touched.</p>
 *
 * <h2>Original Ontology Immutability</h2>
 *
 * <p>The overlay uses a separate {@code OWLOntologyManager} (provided by
 * {@code TemporaryOntologyFactory.createBase}) so that adding dynamic
 * axioms cannot leak into cached ontologies. After the overlay is
 * released, the {@code OntologyCache} internal map SHALL NOT contain
 * any entry keyed by the overlay's ontology ID or by any snapshot ID,
 * and the cache size is unchanged from before overlay creation.</p>
 *
 * <h2>Single-Call Interface (v0.8.7)</h2>
 *
 * <p>v0.8.7 implements the single-call {@code createOverlay} method.
 * A batch variant ({@code createOverlayBatch}) that lets multiple axiom
 * sets share one base overlay is deferred to a later version (design
 * D9, Open Question #2). The pipeline currently creates one overlay
 * per {@code validate} call, which is sufficient.</p>
 */
public interface TransientOntologyOverlayService {

    /**
     * Create a transient overlay ontology containing the base ontology's
     * imports closure (when {@code options.copyImportsClosure=true})
     * plus the supplied dynamic axioms.
     *
     * <p>The returned {@link TransientOverlay} implements
     * {@link AutoCloseable}; callers MUST release it (typically via
     * try-with-resources) so the isolated {@code OWLOntologyManager}
     * is cleared and heap is reclaimed.</p>
     *
     * @param baseOntology  the ontology ID to load as the base (resolved
     *                      via {@code OntologyCache.getOrCreate})
     * @param dynamicAxioms the dynamic ABox axioms to add on top of the
     *                      base; must not be null (may be empty)
     * @param options       overlay creation options; {@code null} falls
     *                      back to {@link OverlayOptions#defaults()}
     * @return a {@link ServiceResult} containing the
     *         {@link TransientOverlay} on success, or an error with one
     *         of: {@code ONTOLOGY_NOT_FOUND} (base ontology not in
     *         catalog), {@code TEMPORARY_ONTOLOGY_CREATION_FAILED}
     *         (isolated copy creation or axiom injection failed)
     */
    ServiceResult<TransientOverlay> createOverlay(
        OntologyId baseOntology,
        Collection<OWLAxiom> dynamicAxioms,
        OverlayOptions options
    );

    /**
     * Convenience overload: build the overlay from an
     * {@link EnvironmentSnapshot}. The snapshot's structured fields
     * (devices, user contexts, pending tool calls) are converted to
     * OWL axioms via {@link StructuredStateConverter}, and the snapshot
     * metadata is attached to the returned overlay.
     *
     * <p>When the snapshot already carries a checksum, it is preserved
     * on the returned overlay; otherwise a fresh checksum is computed
     * from the converted axioms.</p>
     *
     * @param baseOntology the ontology ID to load as the base
     * @param snapshot     the structured environment snapshot
     * @param options      overlay creation options
     * @return a {@link ServiceResult} containing the
     *         {@link TransientOverlay} on success
     */
    ServiceResult<TransientOverlay> createOverlay(
        OntologyId baseOntology,
        EnvironmentSnapshot snapshot,
        OverlayOptions options
    );
}
