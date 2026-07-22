package org.owl4agents.overlay;

import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 OV-003 / D10 / TC-001: Structured representation of a pending
 * tool call that will be validated against the dynamic environment.
 *
 * <p>Defined here in the {@code ontology-overlay} module because the
 * overlay needs to inject pending tool calls as dynamic ABox assertions
 * (so SHACL can validate closed-world constraints over them). The
 * {@code ontology-toolcall} module (Section 5) reuses this record as
 * the input to the 10-stage validation pipeline.</p>
 *
 * <p>Each {@code ToolCallCandidate} is converted by
 * {@link StructuredStateConverter} into OWL ABox axioms:</p>
 * <ul>
 *   <li>{@code ClassAssertion(callIri, ToolCall)}</li>
 *   <li>{@code DataPropertyAssertion(hasToolName, callIri, toolName)}</li>
 *   <li>{@code DataPropertyAssertion(hasTargetEntity, callIri, targetEntity)}
 *       when {@code targetEntity} is present.</li>
 *   <li>{@code ObjectPropertyAssertion(requestedBy, callIri, userIri)}
 *       when {@code requestedBy} is present.</li>
 *   <li>One {@code DataPropertyAssertion(hasArgument, callIri, literal)}
 *       per entry in {@code arguments} (value is the JSON-encoded argument).</li>
 * </ul>
 *
 * @param callId      unique identifier of the call (UUID); used to derive
 *                    the call individual IRI
 * @param requestId   optional correlation ID for the originating request
 * @param userRequest optional free-text user request that triggered the call
 * @param toolName    name of the tool to invoke (e.g. {@code "set_temperature"})
 * @param targetEntity IRI of the device/entity the tool targets, or empty
 * @param arguments   tool arguments keyed by argument name
 * @param requestedBy IRI of the user requesting the call, or empty
 * @param timestamp   ISO-8601 timestamp string of when the call was made,
 *                    or empty
 * @param environmentSnapshotId optional reference to the snapshot ID this
 *                    call was validated against
 * @param sourceModel optional name of the LLM/model that produced the call
 * @param sourceModelResponseId optional correlation ID for the model response
 */
public record ToolCallCandidate(
    String callId,
    Optional<String> requestId,
    Optional<String> userRequest,
    String toolName,
    Optional<String> targetEntity,
    Map<String, String> arguments,
    Optional<String> requestedBy,
    Optional<String> timestamp,
    Optional<String> environmentSnapshotId,
    Optional<String> sourceModel,
    Optional<String> sourceModelResponseId
) {
    public ToolCallCandidate {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (requestId == null) requestId = Optional.empty();
        if (userRequest == null) userRequest = Optional.empty();
        if (targetEntity == null) targetEntity = Optional.empty();
        if (requestedBy == null) requestedBy = Optional.empty();
        if (timestamp == null) timestamp = Optional.empty();
        if (environmentSnapshotId == null) environmentSnapshotId = Optional.empty();
        if (sourceModel == null) sourceModel = Optional.empty();
        if (sourceModelResponseId == null) sourceModelResponseId = Optional.empty();
        if (arguments == null) {
            arguments = Map.of();
        } else {
            arguments = Map.copyOf(arguments);
        }
    }

    /**
     * Convenience constructor for the common case of a call with a tool
     * name, target, and arguments.
     */
    public ToolCallCandidate(String callId, String toolName, String targetEntity,
                             Map<String, String> arguments, String requestedBy) {
        this(callId,
            Optional.empty(),
            Optional.empty(),
            toolName,
            Optional.ofNullable(targetEntity).filter(s -> !s.isBlank()),
            arguments == null ? Map.of() : Map.copyOf(arguments),
            Optional.ofNullable(requestedBy).filter(s -> !s.isBlank()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    }
}
