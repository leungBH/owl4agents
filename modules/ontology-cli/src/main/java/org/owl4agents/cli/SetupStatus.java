package org.owl4agents.cli;

import java.util.List;

/**
 * Aggregate setup check status containing individual step results
 * and an overall readiness verdict.
 */
record SetupStatus(List<SetupStep> steps, boolean isReady) {
}