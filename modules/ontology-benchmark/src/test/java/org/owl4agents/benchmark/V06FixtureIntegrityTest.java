package org.owl4agents.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N5 + S3: Real-fixture validation for v0.6 question sets and configs.
 * Ensures fixture files are structurally correct and claim-type-diverse.
 */
@DisplayName("v0.6 fixture integrity tests")
class V06FixtureIntegrityTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    private static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts")) && Files.exists(dir.resolve("test"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static Path fixture(String relative) {
        return PROJECT_ROOT.resolve(relative);
    }

    @Nested
    @DisplayName("pizza-50.jsonl")
    class Pizza50Tests {

        @Test
        @DisplayName("Has exactly 50 non-blank lines")
        void has50Lines() throws Exception {
            List<String> lines = Files.readAllLines(fixture("test/fixtures/v0.6/question-sets/pizza-50.jsonl"));
            long count = lines.stream().filter(l -> !l.isBlank()).count();
            assertEquals(50, count, "pizza-50.jsonl must have 50 non-blank lines");
        }

        @Test
        @DisplayName("N5: Not all entries use claim type 'subclass'")
        void hasMultipleClaimTypes() throws Exception {
            List<String> lines = Files.readAllLines(fixture("test/fixtures/v0.6/question-sets/pizza-50.jsonl"));
            Set<String> claimTypes = new HashSet<>();
            for (String line : lines) {
                if (line.isBlank()) continue;
                // Extract "type":"subclass" etc. from claims array
                int idx = 0;
                while ((idx = line.indexOf("\"type\":\"", idx)) >= 0) {
                    int start = idx + "\"type\":\"".length();
                    int end = line.indexOf("\"", start);
                    if (end > start) {
                        claimTypes.add(line.substring(start, end));
                    }
                    idx = end;
                }
            }
            assertTrue(claimTypes.size() > 1,
                "pizza-50 should use multiple claim types, found: " + claimTypes);
        }

        @Test
        @DisplayName("All entries have required fields")
        void allEntriesHaveRequiredFields() throws Exception {
            List<String> lines = Files.readAllLines(fixture("test/fixtures/v0.6/question-sets/pizza-50.jsonl"));
            for (String line : lines) {
                if (line.isBlank()) continue;
                assertTrue(line.contains("\"questionId\""), "Missing questionId: " + line.substring(0, Math.min(80, line.length())));
                assertTrue(line.contains("\"claims\""), "Missing claims: " + line.substring(0, Math.min(80, line.length())));
                assertTrue(line.contains("\"expectedVerdict\""), "Missing expectedVerdict: " + line.substring(0, Math.min(80, line.length())));
            }
        }
    }

    @Nested
    @DisplayName("owl2bench-30.jsonl")
    class Owl2Bench30Tests {

        @Test
        @DisplayName("Has exactly 30 non-blank lines")
        void has30Lines() throws Exception {
            List<String> lines = Files.readAllLines(fixture("test/fixtures/v0.6/question-sets/owl2bench-30.jsonl"));
            long count = lines.stream().filter(l -> !l.isBlank()).count();
            assertEquals(30, count, "owl2bench-30.jsonl must have 30 non-blank lines");
        }
    }

    @Nested
    @DisplayName("out-of-scope-cross.jsonl")
    class OutOfScopeCrossTests {

        @Test
        @DisplayName("Contains out_of_scope verdicts")
        void hasOutOfScopeVerdicts() throws Exception {
            List<String> lines = Files.readAllLines(fixture("test/fixtures/v0.6/question-sets/out-of-scope-cross.jsonl"));
            assertTrue(lines.size() > 0, "File must not be empty");
            boolean hasOutOfScope = lines.stream().anyMatch(l -> l.contains("\"out_of_scope\""));
            assertTrue(hasOutOfScope, "Should contain out_of_scope verdicts");
        }
    }

}
