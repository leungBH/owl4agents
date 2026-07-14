package org.owl4agents.core.model;

/**
 * Status of an exact consistency check after adding a claim axiom to a temporary ontology.
 */
public enum ConsistencyAfterAdditionStatus {
    CONSISTENT,
    INCONSISTENT,
    TIMEOUT,
    ERROR
}
