package org.owl4agents.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Scope diagnostic attached to workflow reports when aggregate status is
 * partially_verified or out_of_scope. Contains out-of-scope entity warnings.
 */
public record ScopeDiagnostic(
    List<String> outOfScopeEntities,
    Optional<String> scopeWarning,
    Optional<String> scopeSummary
) {}