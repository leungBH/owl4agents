package org.owl4agents.reasoner;

import org.owl4agents.core.ErrorCode;

/**
 * v0.8.7 post-release fix: thrown when a user explicitly selects a reasoner
 * that is incompatible with the ontology size (e.g. HermiT on >20K classes,
 * or any reasoner on >50K classes with v0.8.7's increased heap footprint).
 *
 * <p>Callers should catch this and return
 * {@code ServiceResult.error(ErrorCode.REASONER_INCOMPATIBLE_WITH_ONTOLOGY_SIZE, e.getMessage())}
 * so the user gets a clear, fast error instead of a 25s timeout or OOM.</p>
 */
public class ReasonerIncompatibleException extends RuntimeException {

    public ReasonerIncompatibleException(String message) {
        super(message);
    }

    /**
     * The error code to propagate to the caller.
     */
    public ErrorCode errorCode() {
        return ErrorCode.REASONER_INCOMPATIBLE_WITH_ONTOLOGY_SIZE;
    }
}
