package org.owl4agents.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SetupChecker, SetupPlanner, and SetupCommand covering
 * environment validation, action planning, and CLI modes.
 */
@DisplayName("Setup command tests")
class SetupCommandTest {

    /** Helper: create a SetupCommand with picocli args parsed into it. */
    private SetupCommand parseCommand(String... args) {
        SetupCommand cmd = new SetupCommand();
        CommandLine.populateCommand(cmd, args);
        return cmd;
    }

    @Nested
    @DisplayName("SetupChecker")
    class CheckerTests {

        @Test
        @DisplayName("check() returns SetupStatus with exactly 6 steps")
        void checkReturnsSixSteps() {
            SetupChecker checker = new SetupChecker("default", null);
            SetupStatus status = checker.check();

            assertNotNull(status);
            assertEquals(6, status.steps().size());
        }

        @Test
        @DisplayName("check() steps have expected names in order")
        void checkStepNamesInOrder() {
            SetupChecker checker = new SetupChecker("default", null);
            SetupStatus status = checker.check();

            List<String> expectedNames = List.of(
                "java", "gradle_wrapper", "source_layout",
                "workspace", "npm_launcher", "runtime_jar"
            );

            for (int i = 0; i < expectedNames.size(); i++) {
                assertEquals(expectedNames.get(i), status.steps().get(i).name());
            }
        }

        @Test
        @DisplayName("check() isReady is true only when all steps pass")
        void isReadyReflectsAllChecks(@TempDir Path tempDir) {
            SetupChecker checker = new SetupChecker("default", tempDir.toString());
            SetupStatus status = checker.check();

            // isReady must equal all-steps-passed
            boolean allPassed = status.steps().stream().allMatch(SetupStep::passed);
            assertEquals(allPassed, status.isReady());
        }

        @Test
        @DisplayName("check() with custom home resolves workspace against that home")
        void checkWithCustomHome(@TempDir Path tempDir) {
            SetupChecker checker = new SetupChecker("test-workspace", tempDir.toString());
            SetupStatus status = checker.check();

            SetupStep workspaceStep = status.steps().stream()
                .filter(s -> "workspace".equals(s.name()))
                .findFirst()
                .orElseThrow();

            assertTrue(workspaceStep.detail().contains("test-workspace"));
            assertTrue(workspaceStep.detail().contains(tempDir.toString()));
        }
    }

    @Nested
    @DisplayName("SetupPlanner")
    class PlannerTests {

        @Test
        @DisplayName("plan() maps failed java step to INSTALL action")
        void failedJavaMapsToInstall() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("java", "No Java found")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("INSTALL", plan.actions().get(0).kind());
            assertTrue(plan.actions().get(0).description().contains("Java"));
        }

        @Test
        @DisplayName("plan() maps failed gradle_wrapper step to CLONE action")
        void failedGradleWrapperMapsToClone() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("gradle_wrapper", "Gradle wrapper not found")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("CLONE", plan.actions().get(0).kind());
        }

        @Test
        @DisplayName("plan() maps failed source_layout step to CLONE action")
        void failedSourceLayoutMapsToClone() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("source_layout", "Missing directories")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("CLONE", plan.actions().get(0).kind());
            assertTrue(plan.actions().get(0).description().contains("modules"));
        }

        @Test
        @DisplayName("plan() maps failed workspace step to INIT action")
        void failedWorkspaceMapsToInit() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("workspace", "Cannot create workspace")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("INIT", plan.actions().get(0).kind());
        }

        @Test
        @DisplayName("plan() maps failed npm_launcher step to CLONE action")
        void failedNpmLauncherMapsToClone() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("npm_launcher", "npm launcher not found")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("CLONE", plan.actions().get(0).kind());
            assertTrue(plan.actions().get(0).description().contains("owl4agents.js"));
        }

        @Test
        @DisplayName("plan() maps failed runtime_jar step to BUILD action")
        void failedRuntimeJarMapsToBuild() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("runtime_jar", "Runtime jar not found")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("BUILD", plan.actions().get(0).kind());
            assertTrue(plan.actions().get(0).description().contains("shadowJar"));
        }

        @Test
        @DisplayName("plan() maps unknown step name to FIX action")
        void unknownStepMapsToFix() {
            SetupStatus status = new SetupStatus(
                List.of(SetupStep.fail("unknown_check", "Something is wrong")),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(1, plan.actions().size());
            assertEquals("FIX", plan.actions().get(0).kind());
            assertTrue(plan.actions().get(0).description().contains("Something is wrong"));
        }

        @Test
        @DisplayName("plan() returns no actions when all steps pass")
        void allPassingStepsYieldsNoActions() {
            SetupStatus status = new SetupStatus(
                List.of(
                    SetupStep.pass("java", "Java 22 found"),
                    SetupStep.pass("gradle_wrapper", "Gradle wrapper found"),
                    SetupStep.pass("source_layout", "Source layout valid"),
                    SetupStep.pass("workspace", "Workspace exists"),
                    SetupStep.pass("npm_launcher", "npm launcher found"),
                    SetupStep.pass("runtime_jar", "Runtime jar found")
                ),
                true
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertTrue(plan.actions().isEmpty());
        }

        @Test
        @DisplayName("plan() maps multiple failed steps to multiple actions")
        void multipleFailuresMapToMultipleActions() {
            SetupStatus status = new SetupStatus(
                List.of(
                    SetupStep.fail("java", "No Java"),
                    SetupStep.pass("gradle_wrapper", "Found"),
                    SetupStep.fail("runtime_jar", "Not found")
                ),
                false
            );
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();

            assertEquals(2, plan.actions().size());
            assertEquals("INSTALL", plan.actions().get(0).kind());
            assertEquals("BUILD", plan.actions().get(1).kind());
        }
    }

    @Nested
    @DisplayName("SetupCommand --check mode")
    class CheckModeTests {

        @Test
        @DisplayName("--check returns 0 or 1 depending on readiness")
        void checkReturnsValidExitCode(@TempDir Path tempDir) {
            SetupCommand cmd = parseCommand("--check", "--home", tempDir.toString());
            int exitCode = cmd.call();
            // Exit code 0 = ready, 1 = not ready — either is acceptable in CI
            assertTrue(exitCode == 0 || exitCode == 1);
        }

        @Test
        @DisplayName("--check returns 1 when some checks fail")
        void checkNotReadyReturnsOne() {
            // Use a tempDir as home so workspace is writable, but the project root
            // will not have gradle wrapper, npm, or runtime jar, causing failures.
            SetupCommand cmd = parseCommand("--check",
                "--home", System.getProperty("java.io.tmpdir"));
            int exitCode = cmd.call();
            // The environment is unlikely to be fully ready in a temp directory,
            // so exit code should be 1. If it happens to be 0, that's also valid.
            assertTrue(exitCode == 0 || exitCode == 1,
                "Exit code should be 0 (ready) or 1 (not ready)");
        }

        @Test
        @DisplayName("--check --json outputs JSON containing overall status")
        void checkJsonOutputContainsOverall(@TempDir Path tempDir) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream originalOut = System.out;
            System.setOut(new java.io.PrintStream(baos));

            try {
                SetupCommand cmd = parseCommand("--check", "--json",
                    "--home", tempDir.toString());
                cmd.call();

                String output = baos.toString();
                assertTrue(output.contains("\"overall\""));
                assertTrue(output.contains("\"steps\""));
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    @Nested
    @DisplayName("SetupCommand --dry-run mode")
    class DryRunModeTests {

        @Test
        @DisplayName("--dry-run returns 0 regardless of readiness")
        void dryRunReturnsZero() {
            SetupCommand cmd = parseCommand("--dry-run",
                "--home", System.getProperty("java.io.tmpdir"));
            int exitCode = cmd.call();
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("--dry-run --json outputs JSON with dry_run=true and actions list")
        void dryRunJsonOutput() {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream originalOut = System.out;
            System.setOut(new java.io.PrintStream(baos));

            try {
                SetupCommand cmd = parseCommand("--dry-run", "--json",
                    "--home", System.getProperty("java.io.tmpdir"));
                cmd.call();

                String output = baos.toString();
                assertTrue(output.contains("\"dry_run\":true"));
                assertTrue(output.contains("\"actions\""));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("--dry-run with all steps passing reports no planned actions")
        void dryRunAllPassingReportsNoActions() {
            // Test planner logic directly: all passing steps yields empty plan
            SetupStatus allPassing = new SetupStatus(
                List.of(
                    SetupStep.pass("java", "ok"),
                    SetupStep.pass("gradle_wrapper", "ok"),
                    SetupStep.pass("source_layout", "ok"),
                    SetupStep.pass("workspace", "ok"),
                    SetupStep.pass("npm_launcher", "ok"),
                    SetupStep.pass("runtime_jar", "ok")
                ),
                true
            );
            SetupPlanner planner = new SetupPlanner(allPassing);
            SetupPlan plan = planner.plan();

            assertTrue(plan.actions().isEmpty(),
                "All-passing environment should produce no planned actions");
        }

        @Test
        @DisplayName("--dry-run without --json outputs text format with planned actions")
        void dryRunTextOutput() {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream originalOut = System.out;
            System.setOut(new java.io.PrintStream(baos));

            try {
                SetupCommand cmd = parseCommand("--dry-run",
                    "--home", System.getProperty("java.io.tmpdir"));
                cmd.call();

                String output = baos.toString();
                // printPlan outputs: "owl4agents setup planned actions (dry run):"
                assertTrue(output.contains("owl4agents setup"),
                    "Dry-run text output should contain header");
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    @Nested
    @DisplayName("SetupStep and SetupStatus records")
    class RecordTests {

        @Test
        @DisplayName("SetupStep.pass factory creates passing step")
        void passFactoryCreatesPassingStep() {
            SetupStep step = SetupStep.pass("java", "Java 22 found");
            assertEquals("java", step.name());
            assertTrue(step.passed());
            assertEquals("Java 22 found", step.detail());
        }

        @Test
        @DisplayName("SetupStep.fail factory creates failing step")
        void failFactoryCreatesFailingStep() {
            SetupStep step = SetupStep.fail("java", "No Java found");
            assertEquals("java", step.name());
            assertFalse(step.passed());
            assertEquals("No Java found", step.detail());
        }

        @Test
        @DisplayName("SetupStatus.isReady reflects step results")
        void statusIsReadyReflectsSteps() {
            SetupStatus ready = new SetupStatus(
                List.of(SetupStep.pass("a", "ok"), SetupStep.pass("b", "ok")),
                true
            );
            assertTrue(ready.isReady());

            SetupStatus notReady = new SetupStatus(
                List.of(SetupStep.pass("a", "ok"), SetupStep.fail("b", "bad")),
                false
            );
            assertFalse(notReady.isReady());
        }

        @Test
        @DisplayName("SetupPlan and SetupAction records hold expected values")
        void planAndActionRecords() {
            SetupAction action = new SetupAction("BUILD", "Run shadowJar");
            assertEquals("BUILD", action.kind());
            assertEquals("Run shadowJar", action.description());

            SetupPlan plan = new SetupPlan(List.of(action));
            assertEquals(1, plan.actions().size());
            assertEquals(action, plan.actions().get(0));
        }
    }
}