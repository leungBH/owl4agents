package org.owl4agents.reasoner.isolated;

/**
 * v0.8.7 REL-001 / D15: Response payload returned from the isolated
 * reasoner worker JVM to the parent process over stdout (JSON-RPC
 * over stdio).
 *
 * <p>Per the reasoner-runtime spec "Reasoner worker communication":
 * each response carries the operation {@code result} (operation-specific
 * payload, serialized as a JSON object), the {@code elapsedMs} the
 * child JVM spent processing the request, and an optional {@code error}
 * with structured error code and message.</p>
 *
 * <p>Serialization contract (JSON over stdio):</p>
 * <pre>{@code
 * {
 *   "result": { ... },
 *   "elapsedMs": 1234,
 *   "error": null
 * }
 * }</pre>
 *
 * <p>On error, the {@code result} field is null and {@code error} is
 * populated:</p>
 * <pre>{@code
 * {
 *   "result": null,
 *   "elapsedMs": 0,
 *   "error": {
 *     "code": "REASONER_INTERNAL_ERROR",
 *     "message": "..."
 *   }
 * }
 * }</pre>
 *
 * @param result    operation-specific payload (e.g. classification
 *                  hierarchy for {@code classify}, boolean for
 *                  {@code checkConsistency}); null on error
 * @param elapsedMs wall-clock milliseconds the child JVM spent on the request
 * @param error     structured error (code + message); null on success
 */
public record IsolatedReasonerResponse(
    Object result,
    long elapsedMs,
    Error error
) {
    public IsolatedReasonerResponse {
        if (error == null && result == null) {
            // Allow null result for "ping" responses (pong: true is set as result)
            // — but if both are null, treat as protocol error.
        }
    }

    /**
     * Build a successful response.
     */
    public static IsolatedReasonerResponse success(Object result, long elapsedMs) {
        return new IsolatedReasonerResponse(result, elapsedMs, null);
    }

    /**
     * Build an error response.
     */
    public static IsolatedReasonerResponse error(String code, String message, long elapsedMs) {
        return new IsolatedReasonerResponse(null, elapsedMs, new Error(code, message));
    }

    /**
     * Convenience: is this an error response?
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Structured error payload.
     *
     * @param code    error code string (e.g. "REASONER_INTERNAL_ERROR",
     *                "REASONER_TIMEOUT", "REASONER_REJECTED_ONTOLOGY")
     * @param message human-readable error message
     */
    public record Error(String code, String message) {
        public Error {
            if (code == null || code.isBlank()) {
                code = "REASONER_INTERNAL_ERROR";
            }
            if (message == null || message.isBlank()) {
                message = "Unknown error";
            }
        }
    }
}
