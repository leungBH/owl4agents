package org.owl4agents.toolcall.decomposition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.GraphScope;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimBatchInput;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.WorkflowOptions;

/**
 * v0.8.7 CL-002: Adapter that converts a {@link ToolCallClaimBatch} (produced
 * by {@link ClaimDecomposer}) to a {@link ClaimBatchInput} (consumed by
 * {@code ClaimWorkflowService.verifyBatch}).
 *
 * <p>Per the {@code claim-decomposition} spec "Reuse Batch Verification"
 * requirement, the pipeline MUST NOT invoke {@code ClaimVerificationService.verify}
 * once per claim. All claims in a batch share the same ontology load, the
 * same reasoner instance, the same entity cache, and the same evidence
 * context. This adapter is the single bridge between the tool-call-specific
 * batch representation and the existing batch verification input schema.</p>
 *
 * <p>The adapter:</p>
 * <ol>
 *   <li>projects each {@link DecomposedClaim} onto a
 *       {@link ClaimBatchInput.BatchClaim} (the structured claim schema
 *       shared between {@code ClaimDecomposer} and
 *       {@code ClaimWorkflowService});</li>
 *   <li>preserves {@code callId} and {@code claimRole} as out-of-band
 *       metadata (the {@link #callId()} and {@link #claimRole()} accessors)
 *       so the pipeline can re-attach role information to each
 *       {@code ClaimResult} after {@code verifyBatch} returns;</li>
 *   <li>does NOT mutate the original {@link ToolCallClaimBatch}.</li>
 * </ol>
 *
 * <p>The {@code ontologyId} required by the {@link Claim} schema is supplied
 * at adapter construction time (not stored on the batch) so a single batch
 * can be replayed against different ontologies in tests.</p>
 */
public final class ToolCallClaimBatchAdapter {

    private final ToolCallClaimBatch batch;
    private final String ontologyId;
    private final Optional<WorkflowOptions> options;

    /**
     * Build an adapter for the given batch and ontology, with default
     * (empty) workflow options.
     *
     * @param batch      the {@link ToolCallClaimBatch} to convert
     * @param ontologyId the ontology ID to embed in each {@link Claim}
     */
    public ToolCallClaimBatchAdapter(ToolCallClaimBatch batch, String ontologyId) {
        this(batch, ontologyId, Optional.empty());
    }

    /**
     * Build an adapter for the given batch, ontology, and workflow options.
     *
     * @param batch      the {@link ToolCallClaimBatch} to convert
     * @param ontologyId the ontology ID to embed in each {@link Claim}
     * @param options    workflow options to forward to
     *                   {@link ClaimBatchInput#options()}
     */
    public ToolCallClaimBatchAdapter(ToolCallClaimBatch batch,
                                     String ontologyId,
                                     Optional<WorkflowOptions> options) {
        if (batch == null) {
            throw new IllegalArgumentException("ToolCallClaimBatchAdapter: batch must not be null");
        }
        if (ontologyId == null || ontologyId.isBlank()) {
            throw new IllegalArgumentException("ToolCallClaimBatchAdapter: ontologyId must not be blank");
        }
        this.batch = batch;
        this.ontologyId = ontologyId;
        this.options = options != null ? options : Optional.empty();
    }

    /**
     * Convert the batch to a {@link ClaimBatchInput} for delegation to
     * {@code ClaimWorkflowService.verifyBatch}.
     *
     * <p>The {@code answerId} field of the resulting {@link ClaimBatchInput}
     * is set to the batch's {@code callId} (the tool-call analogue of an
     * "answer" in the v0.5 workflow schema). The {@code question} and
     * {@code answerText} fields are left empty (the tool call candidate's
     * user-request text is not part of the batch verification contract).</p>
     *
     * @return a new {@link ClaimBatchInput}; never {@code null}
     */
    public ClaimBatchInput toClaimBatchInput() {
        List<ClaimBatchInput.BatchClaim> batchClaims = new ArrayList<>();
        for (DecomposedClaim dc : batch.claims()) {
            batchClaims.add(toBatchClaim(dc));
        }
        return new ClaimBatchInput(
            batch.callId(),
            Optional.empty(),
            Optional.empty(),
            batchClaims,
            options
        );
    }

    /**
     * Project a single {@link DecomposedClaim} onto a
     * {@link ClaimBatchInput.BatchClaim}. The {@code ontologyId} is supplied
     * by the adapter (not stored on the claim) so a batch can be replayed
     * against different ontologies.
     */
    private ClaimBatchInput.BatchClaim toBatchClaim(DecomposedClaim dc) {
        return new ClaimBatchInput.BatchClaim(
            dc.claimId(),
            dc.claimType(),
            dc.required(),
            Optional.ofNullable(dc.subject()),
            Optional.ofNullable(dc.predicate()).filter(s -> !s.isBlank()),
            Optional.ofNullable(dc.object()),
            dc.reasoner(),
            dc.graphScope(),
            Optional.empty()
        );
    }

    /**
     * Convert a {@link DecomposedClaim} to a full {@link Claim} (used by
     * tests that need to exercise {@code ClaimVerificationService.verify}
     * directly without going through the batch API).
     */
    public Claim toClaim(DecomposedClaim dc) {
        return new Claim(
            dc.claimId(),
            dc.claimType(),
            ontologyId,
            dc.subject(),
            dc.predicate(),
            dc.object(),
            dc.reasoner(),
            dc.graphScope(),
            Optional.empty()
        );
    }

    /**
     * The {@code callId} carried out-of-band by this adapter. The pipeline
     * uses this to correlate the {@code ClaimWorkflowService.verifyBatch}
     * result with the originating {@link ToolCallClaimBatch}.
     */
    public String callId() {
        return batch.callId();
    }

    /**
     * The {@code claimRole} map carried out-of-band by this adapter. The
     * pipeline uses this to re-attach role information to each
     * {@code ClaimResult} after {@code verifyBatch} returns.
     */
    public Map<String, String> claimRole() {
        return batch.claimRole();
    }

    /**
     * The underlying batch (does not mutate; exposed for inspection).
     */
    public ToolCallClaimBatch batch() {
        return batch;
    }

    /**
     * The ontology ID embedded in each converted {@link Claim}.
     */
    public String ontologyId() {
        return ontologyId;
    }

    /**
     * Helper: build a {@link ClaimEntity} for a named individual IRI.
     */
    public static ClaimEntity individual(String iri) {
        return new ClaimEntity("individual", iri);
    }

    /**
     * Helper: build a {@link ClaimEntity} for a named class IRI.
     */
    public static ClaimEntity klass(String iri) {
        return new ClaimEntity("class", iri);
    }

    /**
     * Helper: build a {@link ClaimEntity} for a datatype IRI.
     */
    public static ClaimEntity datatype(String iri) {
        return new ClaimEntity("datatype", iri);
    }

    /**
     * Helper: build a {@link ClaimEntity} for a literal value (typed
     * literal whose datatype is given by the {@code iri} argument).
     */
    public static ClaimEntity literal(String iri) {
        return new ClaimEntity("literal", iri);
    }

    /**
     * Helper: build a {@link GraphScope} union scope (the default for
     * claim verification).
     */
    public static GraphScope unionScope() {
        return GraphScope.UNION;
    }
}
