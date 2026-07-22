package org.owl4agents.toolcall.decomposition;

/**
 * v0.8.7 CL-001: The six semantic claim categories produced by
 * {@link ClaimDecomposer} when decomposing a single tool call.
 *
 * <p>Each category maps to a distinct semantic responsibility defined by
 * the {@code claim-decomposition} spec. The category drives both the
 * {@link org.owl4agents.core.model.ClaimType} used when building the
 * structural claim and the {@code claimRole} metadata preserved in
 * {@link ToolCallClaimBatch} so the pipeline can re-attach role
 * information to each {@code ClaimResult} after batch verification.</p>
 *
 * <p>The {@link #roleName()} value matches the {@code claimRole} string
 * set mandated by the spec:
 * {@code target_class} / {@code location} / {@code capability} /
 * {@code operation_capability} / {@code permission} / {@code datatype}.</p>
 */
public enum ClaimCategory {
    /** Target entity belongs to {@code contract.targetClass}. */
    CLASS_MEMBERSHIP("target_class"),
    /** Target entity is located in a valid room (hasLocation + Room). */
    LOCATION("location"),
    /** Target entity possesses each required capability (inferred or asserted). */
    CAPABILITY("capability"),
    /** The tool operation requires the declared capabilities. */
    OPERATION_REQUIREMENTS("operation_capability"),
    /** The requesting user has the required permission. */
    PERMISSION("permission"),
    /** Each numeric/string argument satisfies the contract datatype constraints. */
    DATATYPE("datatype");

    private final String roleName;

    ClaimCategory(String roleName) {
        this.roleName = roleName;
    }

    /**
     * The role string used as a value in the {@code claimRole} map of
     * {@link ToolCallClaimBatch}.
     */
    public String roleName() {
        return roleName;
    }
}
