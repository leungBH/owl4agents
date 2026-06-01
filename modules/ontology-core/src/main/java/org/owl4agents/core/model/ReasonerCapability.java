package org.owl4agents.core.model;

import java.util.List;

/**
 * Description of a single reasoner adapter's capabilities.
 */
public record ReasonerCapability(
    String name,
    List<String> supportedProfiles,
    List<String> supportedOperations,
    boolean explanationSupported
) {}