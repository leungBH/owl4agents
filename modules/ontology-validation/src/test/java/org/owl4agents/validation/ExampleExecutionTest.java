package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI example execution tests through real child processes (task 4.3).
 *
 * Uses `node tools/npm/bin/owl4agents.js` as the entry point per design Decision 6.
 * Tests are conditionally enabled when the shadow jar and Node.js are available.
 * These tests MUST NOT use direct `java -jar` invocation.
 *
 * These tests create a temporary OWL4AGENTS_HOME directory, import fixtures,
 * and verify structured output through schema/field assertions (task 4.4).
 * All data goes into the "default" workspace under the redirected OWL4AGENTS_HOME,
 * which avoids Picocli parent/subcommand --workspace option conflicts.
 */
@DisplayName("v0.4 CLI example execution")
class ExampleExecutionTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final String NODE_LAUNCHER = "node";
    private static final String LAUNCHER_SCRIPT = "tools/npm/bin/owl4agents.js";
    private static final long PROCESS_TIMEOUT_SECONDS = 60;
    private static Path tempHome;
    private static boolean jarAvailable;
    private static boolean nodeAvailable;

    private static Path findProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path cwd = userDir != null ? Path.of(userDir).toAbsolutePath() : Path.of("").toAbsolutePath();
        Path dir = cwd;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts")) && Files.exists(dir.resolve("test"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return cwd;
    }

    @BeforeAll
    static void checkPrerequisites() throws Exception {
        // Check if shadow jar exists
        Path jarPath = PROJECT_ROOT.resolve("modules/ontology-cli/build/libs/owl4agents.jar");
        jarAvailable = Files.exists(jarPath);

        // Check if Node.js is available
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--version");
            pb.directory(PROJECT_ROOT.toFile());
            Process p = pb.start();
            boolean exited = p.waitFor(10, TimeUnit.SECONDS);
            nodeAvailable = exited && p.exitValue() == 0;
        } catch (Exception e) {
            nodeAvailable = false;
        }

        // Create temp OWL4AGENTS_HOME if prerequisites are met
        if (jarAvailable && nodeAvailable) {
            tempHome = Files.createTempDirectory("owl4agents-example-test");
        }
    }

    static boolean prerequisitesAvailable() {
        return jarAvailable && nodeAvailable;
    }

    // Helper: run a command through the npm launcher and capture output
    // Uses OWL4AGENTS_HOME env var to redirect all data to temp directory
    // All commands use the "default" workspace under OWL4AGENTS_HOME to avoid
    // Picocli parent/subcommand --workspace option parsing conflicts
    private ProcessResult runCommand(String... args) throws Exception {
        String[] fullArgs = new String[args.length + 1];
        fullArgs[0] = LAUNCHER_SCRIPT;
        System.arraycopy(args, 0, fullArgs, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(NODE_LAUNCHER);
        pb.command().addAll(java.util.Arrays.asList(fullArgs));
        pb.directory(PROJECT_ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("OWL4AGENTS_HOME", tempHome.toString());

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int exitCode = exited ? process.exitValue() : -1;
        if (!exited) {
            process.destroyForcibly();
        }

        return new ProcessResult(exitCode, output.toString());
    }

    private static class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean isJson() {
            // Look for JSON output anywhere in the captured output.
            // SLF4J warnings on stderr get merged with stdout, so the JSON
            // payload may not start at the beginning of the output.
            String trimmed = output.trim();
            return trimmed.contains("{\"") || trimmed.contains("[{");
        }

        String extractJsonField(String field) {
            // Simple field extraction for assertion-based validation
            // Does NOT require byte-for-byte matching
            Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return matcher.group(1);
            }
            // Also try numeric values
            Pattern numPattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
            java.util.regex.Matcher numMatcher = numPattern.matcher(output);
            if (numMatcher.find()) {
                return numMatcher.group(1);
            }
            // Also try boolean values
            Pattern boolPattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)");
            java.util.regex.Matcher boolMatcher = boolPattern.matcher(output);
            if (boolMatcher.find()) {
                return boolMatcher.group(1);
            }
            return null;
        }

        boolean containsCrashOutput() {
            return output.contains("ACCESS_VIOLATION") ||
                   output.contains("Unhandled exception") ||
                   output.contains("Stacktrace") ||
                   output.contains("Exception in thread");
        }

        boolean containsPlaceholder() {
            String lower = output.toLowerCase();
            return lower.contains("todo") || lower.contains("stub") ||
                   lower.contains("not implemented") || lower.contains("fake evidence");
        }
    }

    // --- Claim verification example (V04-EX-002) ---
    // All imports into the "default" workspace under OWL4AGENTS_HOME override

    @Nested
    @DisplayName("Claim verification example (V04-EX-002)")
    class ClaimVerificationTests {

        @BeforeAll
        static void checkPrereqs() {
            Assumptions.assumeTrue(prerequisitesAvailable(),
                "Skipping CLI execution tests — shadow jar or Node.js not available");
        }

        @Test
        @DisplayName("Import golden ontology into workspace")
        void importGoldenOntology() throws Exception {
            ProcessResult result = runCommand(
                "import",
                "test/corpus/golden/v0.3-claim-verification.owl",
                "v0.3-claim-verification"
            );
            assertEquals(0, result.exitCode, "Import should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
        }

        @Test
        @DisplayName("Verify supported claim — Dog subClassOf Animal")
        void verifySupportedClaim() throws Exception {
            // Need to run reasoner first for subclass entailment
            ProcessResult reasonResult = runCommand(
                "reason", "v0.3-claim-verification"
            );
            assertEquals(0, reasonResult.exitCode, "Reasoner should exit 0");

            ProcessResult result = runCommand(
                "verify-claim", "v0.3-claim-verification",
                "--claim", "test/fixtures/v0.3/claim-supported.json",
                "--json"
            );
            assertEquals(0, result.exitCode, "Supported claim should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            assertFalse(result.containsPlaceholder(), "Output must not contain placeholder text");
            assertTrue(result.isJson(), "Output should be JSON for --json flag");
            String verdict = result.extractJsonField("verdict");
            assertEquals("supported", verdict, "Verdict should be 'supported'");
            assertNotNull(result.extractJsonField("claimId"), "claimId must be present");
            assertNotNull(result.extractJsonField("ontologyId"), "ontologyId must be present");
        }

        @Test
        @DisplayName("Verify unknown claim — Goldfish subClassOf Fish (sparse)")
        void verifyUnknownClaim() throws Exception {
            ProcessResult result = runCommand(
                "verify-claim", "v0.3-claim-verification",
                "--claim", "test/fixtures/v0.3/claim-unknown.json",
                "--json"
            );
            assertEquals(0, result.exitCode, "Unknown claim should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            assertTrue(result.isJson(), "Output should be JSON");
            String verdict = result.extractJsonField("verdict");
            assertEquals("unknown", verdict, "Verdict should be 'unknown'");
            assertNotNull(result.extractJsonField("unknownReason"), "unknownReason must be present for unknown verdict");
        }

        @Test
        @DisplayName("Verify out_of_scope claim — DeliveryPrice not in ontology")
        void verifyOutOfScopeClaim() throws Exception {
            ProcessResult result = runCommand(
                "verify-claim", "v0.3-claim-verification",
                "--claim", "test/fixtures/v0.3/claim-real-out-of-scope.json",
                "--json"
            );
            assertEquals(0, result.exitCode, "Out-of-scope claim should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            assertTrue(result.isJson(), "Output should be JSON");
            String verdict = result.extractJsonField("verdict");
            assertEquals("out_of_scope", verdict, "Verdict should be 'out_of_scope'");
        }
    }

    // --- Pizza reasoning example (V04-EX-003) ---

    @Nested
    @DisplayName("Pizza reasoning example (V04-EX-003)")
    class PizzaReasoningTests {

        @BeforeAll
        static void checkPrereqs() {
            Assumptions.assumeTrue(prerequisitesAvailable(),
                "Skipping CLI execution tests — shadow jar or Node.js not available");
        }

        @BeforeAll
        static void setupPizzaWorkspace() throws Exception {
            ProcessResult result = new ExampleExecutionTest().runCommand(
                "import",
                "test/corpus/smoke/pizza.owl",
                "pizza"
            );
            assertEquals(0, result.exitCode, "Pizza import should exit 0");
        }

        @Test
        @DisplayName("Summary shows ontology ID and entity counts")
        void pizzaSummary() throws Exception {
            ProcessResult result = new ExampleExecutionTest().runCommand(
                "summary", "pizza"
            );
            assertEquals(0, result.exitCode, "Summary should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            // Summary outputs human-readable text with ontology name and entity counts
            assertTrue(result.output.contains("pizza"), "Summary must reference ontology ID 'pizza'");
            assertTrue(result.output.contains("Classes:"), "Summary must contain class count");
        }

        @Test
        @DisplayName("Classification returns reasoner metadata")
        void pizzaClassify() throws Exception {
            ProcessResult result = new ExampleExecutionTest().runCommand(
                "classify", "pizza"
            );
            assertEquals(0, result.exitCode, "Classify should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            // Classification outputs human-readable text with reasoner name
            assertTrue(result.output.contains("Reasoner:"), "Classification must contain reasoner name");
            assertTrue(result.output.contains("HermiT") || result.output.contains("ELK"),
                "Classification must mention a reasoner name (HermiT or ELK)");
        }
    }

    // --- Biomedical grounding example (V04-EX-005) ---

    @Nested
    @DisplayName("Biomedical grounding example (V04-EX-005)")
    class BiomedicalGroundingTests {

        @BeforeAll
        static void checkPrereqs() {
            Assumptions.assumeTrue(prerequisitesAvailable(),
                "Skipping CLI execution tests — shadow jar or Node.js not available");
        }

        @BeforeAll
        static void setupBioWorkspace() throws Exception {
            ProcessResult result = new ExampleExecutionTest().runCommand(
                "import",
                "test/corpus/golden/v0.4-biomedical-grounding.owl",
                "v0.4-biomedical-grounding"
            );
            assertEquals(0, result.exitCode, "Biomedical import should exit 0");
        }

        @Test
        @DisplayName("Search finds Hypertension")
        void bioSearch() throws Exception {
            ProcessResult result = new ExampleExecutionTest().runCommand(
                "search", "v0.4-biomedical-grounding", "Hypertension"
            );
            assertEquals(0, result.exitCode, "Search should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            assertTrue(result.output.contains("Hypertension"), "Search must find Hypertension entity");
        }

        @Test
        @DisplayName("Verify supported claim — Hypertension is a Disease")
        void bioSupportedClaim() throws Exception {
            // Need to run reasoner first for subclass entailment
            ProcessResult reasonResult = new ExampleExecutionTest().runCommand(
                "reason", "v0.4-biomedical-grounding"
            );
            assertEquals(0, reasonResult.exitCode, "Reasoner should exit 0");

            ProcessResult result = new ExampleExecutionTest().runCommand(
                "verify-claim", "v0.4-biomedical-grounding",
                "--claim", "test/fixtures/v0.4/claim-bio-supported.json",
                "--json"
            );
            assertEquals(0, result.exitCode, "Bio supported claim should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            String verdict = result.extractJsonField("verdict");
            assertEquals("supported", verdict, "Verdict should be 'supported' for Hypertension subClassOf Disease");
        }

        @Test
        @DisplayName("Verify out_of_scope claim — CancerStage not in ontology")
        void bioOutOfScopeClaim() throws Exception {
            ProcessResult result = new ExampleExecutionTest().runCommand(
                "verify-claim", "v0.4-biomedical-grounding",
                "--claim", "test/fixtures/v0.4/claim-bio-out-of-scope.json",
                "--json"
            );
            assertEquals(0, result.exitCode, "Bio out_of_scope claim should exit 0. Output: " + result.output);
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            String verdict = result.extractJsonField("verdict");
            assertEquals("out_of_scope", verdict, "Verdict should be 'out_of_scope' for CancerStage");
        }
    }

    // --- Crash and placeholder rejection (task 4.3 negative) ---

    @Nested
    @DisplayName("Crash and placeholder rejection")
    class CrashRejectionTests {

        @BeforeAll
        static void checkPrereqs() {
            Assumptions.assumeTrue(prerequisitesAvailable(),
                "Skipping CLI execution tests — shadow jar or Node.js not available");
        }

        @Test
        @DisplayName("No example command produces ACCESS_VIOLATION")
        void noAccessViolation() throws Exception {
            // Run a simple command and verify no crash
            ProcessResult result = runCommand("--version");
            assertEquals(0, result.exitCode, "Version command should exit 0");
            assertFalse(result.containsCrashOutput(), "Output must not contain crash text");
            assertFalse(result.output.contains("ACCESS_VIOLATION"), "No ACCESS_VIOLATION in any output");
        }

        @Test
        @DisplayName("Commands use npm launcher, not java -jar")
        void entryPointUsesNpmLauncher() {
            // Structural test: verify example.yaml commands reference npm launcher
            Path manifest = PROJECT_ROOT.resolve("examples/claim-verification/example.yaml");
            String content = readFileSafe(manifest);
            assertTrue(content.contains("node tools/npm/bin/owl4agents.js"),
                "Example scripts must reference npm launcher entry point");
            assertFalse(content.contains("java -jar"),
                "Example scripts must NOT reference java -jar");
        }
    }

    // --- Helper methods ---

    private String readFileSafe(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }
}