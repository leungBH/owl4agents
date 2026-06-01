package org.owl4agents.core.model;

/**
 * Result of individual membership check.
 * Indicates whether an individual belongs to a class (explicit, inferred, or both).
 */
public record MembershipResult(
    String ontologyId,
    String individualIRI,
    String classIRI,
    boolean isMember,
    String membershipType,
    String reasonerName
) {
    public static final String EXPLICIT = "explicit";
    public static final String INFERRED = "inferred";
    public static final String BOTH = "both";
}