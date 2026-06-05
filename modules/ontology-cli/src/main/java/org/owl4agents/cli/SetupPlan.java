package org.owl4agents.cli;

import java.util.List;

/**
 * Planned actions for setup --dry-run output.
 */
record SetupPlan(List<SetupAction> actions) {
}