package org.owl4agents.cli;

/**
 * One step result in the setup check workflow.
 * Each step has a name, pass/fail status, and detail message.
 */
record SetupStep(String name, boolean passed, String detail) {

    static SetupStep pass(String name, String detail) {
        return new SetupStep(name, true, detail);
    }

    static SetupStep fail(String name, String detail) {
        return new SetupStep(name, false, detail);
    }
}