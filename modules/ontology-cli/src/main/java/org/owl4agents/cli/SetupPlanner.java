package org.owl4agents.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans setup actions based on the current environment status.
 * Used for --dry-run mode to report what would be done without executing.
 */
class SetupPlanner {

    private final SetupStatus status;

    SetupPlanner(SetupStatus status) {
        this.status = status;
    }

    SetupPlan plan() {
        List<SetupAction> actions = new ArrayList<>();

        for (SetupStep step : status.steps()) {
            if (!step.passed()) {
                switch (step.name()) {
                    case "java":
                        actions.add(new SetupAction("INSTALL", "Install Java 22 and configure JAVA_HOME or PATH"));
                        break;
                    case "gradle_wrapper":
                        actions.add(new SetupAction("CLONE", "Ensure owl4agents is cloned with Gradle wrapper files"));
                        break;
                    case "source_layout":
                        actions.add(new SetupAction("CLONE", "Ensure owl4agents source checkout includes modules/ and npm/"));
                        break;
                    case "workspace":
                        actions.add(new SetupAction("INIT", "Initialize workspace '" + "default" + "' using owl4agents init"));
                        break;
                    case "npm_launcher":
                        actions.add(new SetupAction("CLONE", "Ensure npm/bin/owl4agents.js and npm/package.json exist"));
                        break;
                    case "runtime_jar":
                        actions.add(new SetupAction("BUILD", "Run 'gradlew shadowJar' to build owl4agents.jar"));
                        break;
                    default:
                        actions.add(new SetupAction("FIX", "Resolve: " + step.detail()));
                }
            }
        }

        return new SetupPlan(actions);
    }
}