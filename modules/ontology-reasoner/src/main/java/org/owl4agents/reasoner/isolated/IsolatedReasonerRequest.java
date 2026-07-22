package org.owl4agents.reasoner.isolated;

/**
 * v0.8.7 REL-001 / D15: Request payload sent from the parent process
 * to the isolated reasoner worker JVM over stdin (JSON-RPC over stdio).
 *
 * <p>Per the reasoner-runtime spec "Reasoner worker communication":
 * each request carries the {@code ontologyPath} (filesystem path to the
 * ontology file, NOT a serialized ontology object, to avoid large
 * serialization overhead), the {@code reasonerName} to use, the
 * {@code operation} to perform, an optional {@code axiom} for
 * {@code checkConsistencyAfterAdding} operations, and the per-call
 * {@code timeoutMs}.</p>
 *
 * <p>Serialization contract (JSON over stdio):</p>
 * <pre>{@code
 * {
 *   "ontologyPath": "/path/to/mondo.owl",
 *   "reasonerName": "HermiT",
 *   "operation": "classify",
 *   "axiom": null,
 *   "timeoutMs": 30000
 * }
 * }</pre>
 *
 * <p>The child JVM loads the ontology from {@code ontologyPath}
 * directly from disk (sharing the filesystem with the parent process)
 * rather than receiving a serialized ontology over the wire.</p>
 *
 * @param ontologyPath filesystem path to the ontology file (non-null, non-blank)
 * @param reasonerName reasoner to use: "HermiT", "ELK", "Openllet", or "auto"
 * @param operation    one of: "ping", "classify", "realize",
 *                     "checkConsistency", "getUnsatClasses", "explain",
 *                     "checkConsistencyAfterAdding"
 * @param axiom        optional axiom string (functional syntax) for
 *                     {@code checkConsistencyAfterAdding}; null for other ops
 * @param timeoutMs    per-call timeout in milliseconds; the parent
 *                     enforces this via {@code Process.destroyForcibly()}
 *                     on timeout
 */
public record IsolatedReasonerRequest(
    String ontologyPath,
    String reasonerName,
    String operation,
    String axiom,
    long timeoutMs
) {
    public IsolatedReasonerRequest {
        if (ontologyPath == null || ontologyPath.isBlank()) {
            throw new IllegalArgumentException("ontologyPath must not be blank");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (reasonerName == null || reasonerName.isBlank()) {
            reasonerName = "auto";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 30_000L;
        }
    }

    /**
     * Build a ping request used for the health check after spawning
     * the child JVM.
     */
    public static IsolatedReasonerRequest ping() {
        return new IsolatedReasonerRequest(
            "<ping>", "auto", "ping", null, 5_000L);
    }
}
