package org.owl4agents.core.model;

import java.util.List;

/**
 * Description of a single reasoner adapter's capabilities.
 *
 * <p>v0.8.5 adds {@link #supportsConsistency} and {@link #supportsTemporaryOntology}
 * to drive the exact consistency verification pipeline's capability matrix
 * (see task 7.1). All current adapters (HermiT, ELK, Openllet) report
 * {@code true} for both fields.
 */
public record ReasonerCapability(
    String name,
    List<String> supportedProfiles,
    List<String> supportedOperations,
    boolean explanationSupported,
    boolean supportsConsistency,
    boolean supportsTemporaryOntology
) {}