package org.owl4agents.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v0.9.0 D4 / task 7.5: Verifies {@link AggregateAnswerStatus#VERIFIED}
 * serializes to JSON name {@code "supported"} (not {@code "verified"}).
 * This is the BREAKING vocabulary change from v0.8.x to v0.9.0.
 */
@DisplayName("v0.9.0 D4 / task 7.5: AggregateAnswerStatus vocabulary")
class AggregateAnswerStatusTest {

    @Test
    @DisplayName("VERIFIED.jsonName() returns 'supported' (not 'verified')")
    void verifiedJsonNameIsSupported() {
        assertEquals("supported", AggregateAnswerStatus.VERIFIED.jsonName(),
            "VERIFIED jsonName must be 'supported' (v0.9.0 D4 BREAKING), but was '"
                + AggregateAnswerStatus.VERIFIED.jsonName() + "'");
    }

    @Test
    @DisplayName("All enum constants have correct jsonName values")
    void allJsonNamesCorrect() {
        assertEquals("invalid_input", AggregateAnswerStatus.INVALID_INPUT.jsonName());
        assertEquals("contradicted", AggregateAnswerStatus.CONTRADICTED.jsonName());
        assertEquals("insufficient_evidence", AggregateAnswerStatus.INSUFFICIENT_EVIDENCE.jsonName());
        assertEquals("out_of_scope", AggregateAnswerStatus.OUT_OF_SCOPE.jsonName());
        assertEquals("partially_verified", AggregateAnswerStatus.PARTIALLY_VERIFIED.jsonName());
        assertEquals("supported", AggregateAnswerStatus.VERIFIED.jsonName());
    }

    @Test
    @DisplayName("VERIFIED is distinct from PARTIALLY_VERIFIED")
    void verifiedDistinctFromPartiallyVerified() {
        assertEquals("supported", AggregateAnswerStatus.VERIFIED.jsonName());
        assertEquals("partially_verified", AggregateAnswerStatus.PARTIALLY_VERIFIED.jsonName());
        assert !AggregateAnswerStatus.VERIFIED.jsonName()
            .equals(AggregateAnswerStatus.PARTIALLY_VERIFIED.jsonName())
            : "VERIFIED and PARTIALLY_VERIFIED must have different jsonNames";
    }
}
