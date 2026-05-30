package org.owl4agents.query;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

/**
 * Enforces timeout, graph scope, and result limit controls for SPARQL execution.
 */
public class SparqlLimitEnforcer {

    private int maxTimeoutMs;
    private int maxResultLimit;
    private int defaultTimeoutMs;
    private int defaultResultLimit;

    public SparqlLimitEnforcer() {
        this.defaultTimeoutMs = 30000;
        this.defaultResultLimit = 1000;
        this.maxTimeoutMs = 120000;
        this.maxResultLimit = 10000;
    }

    public SparqlLimitEnforcer(int defaultTimeoutMs, int defaultResultLimit,
                                int maxTimeoutMs, int maxResultLimit) {
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.defaultResultLimit = defaultResultLimit;
        this.maxTimeoutMs = maxTimeoutMs;
        this.maxResultLimit = maxResultLimit;
    }

    /**
     * Validate and clamp a requested timeout value.
     */
    public int resolveTimeout(int requestedTimeoutMs) {
        if (requestedTimeoutMs <= 0) return defaultTimeoutMs;
        if (requestedTimeoutMs > maxTimeoutMs) return maxTimeoutMs;
        return requestedTimeoutMs;
    }

    /**
     * Validate and clamp a requested result limit.
     */
    public int resolveResultLimit(int requestedLimit) {
        if (requestedLimit <= 0) return defaultResultLimit;
        if (requestedLimit > maxResultLimit) return maxResultLimit;
        return requestedLimit;
    }

    /**
     * Check that graph scope is valid for v0.1.
     * v0.1 only supports EXPLICIT and UNION (which is same as EXPLICIT for v0.1).
     */
    public ServiceResult<Void> validateGraphScope(org.owl4agents.core.GraphScope scope) {
        if (scope == null) {
            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.explicit(new OntologyId("limit-check")));
        }

        // v0.1 supports EXPLICIT and UNION
        if (scope == org.owl4agents.core.GraphScope.EXPLICIT ||
            scope == org.owl4agents.core.GraphScope.UNION) {
            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.explicit(new OntologyId("limit-check")));
        }

        // INFERRED is not available in v0.1
        return ServiceResult.error(ServiceError.of(
            org.owl4agents.core.ErrorCode.ONTOLOGY_NOT_FOUND,
            "INFERRED graph scope is not available in v0.1. Use EXPLICIT or UNION scope."
        ));
    }

    public int getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public int getDefaultResultLimit() { return defaultResultLimit; }
    public int getMaxTimeoutMs() { return maxTimeoutMs; }
    public int getMaxResultLimit() { return maxResultLimit; }
}