package org.owl4agents.cli;

/**
 * One step result in the smoke test workflow.
 * Each step has a name, pass/fail status, detail message, and optional verdict.
 */
record SmokeStepResult(String stepName, boolean passed, String detail, String verdict) {

    static SmokeStepResult pass(String stepName, String detail) {
        return new SmokeStepResult(stepName, true, detail, null);
    }

    static SmokeStepResult pass(String stepName, String detail, String verdict) {
        return new SmokeStepResult(stepName, true, detail, verdict);
    }

    static SmokeStepResult fail(String stepName, String detail) {
        return new SmokeStepResult(stepName, false, detail, null);
    }
}