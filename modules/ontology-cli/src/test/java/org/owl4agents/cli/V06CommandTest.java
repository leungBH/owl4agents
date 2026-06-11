package org.owl4agents.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI acceptance tests for v0.6 commands: benchmark-run, eval-qa, context-batch.
 * Tests cover missing files, empty files, and malformed input handling.
 */
@DisplayName("CLI v0.6 command tests")
class V06CommandTest {

    @TempDir
    Path tempDir;

    /**
     * Helper to set a private field on a command object via reflection.
     */
    private static void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    // --- BenchmarkRunCommand tests ---

    @Nested
    @DisplayName("BenchmarkRunCommand")
    class BenchmarkRunCommandTests {

        @Test
        @DisplayName("Missing config file returns exit code 1")
        void benchmarkRunMissingConfigReturns1() throws ReflectiveOperationException {
            Path nonexistent = tempDir.resolve("does-not-exist.yaml");
            BenchmarkRunCommand cmd = new BenchmarkRunCommand();
            setField(cmd, "configPath", nonexistent.toString());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
        }

        @Test
        @DisplayName("Malformed YAML config returns exit code 1")
        void benchmarkRunMalformedConfigReturns1() throws ReflectiveOperationException, IOException {
            Path malformed = tempDir.resolve("malformed.yaml");
            Files.writeString(malformed, "{{{{not: valid: yaml: [[[}}}}");

            BenchmarkRunCommand cmd = new BenchmarkRunCommand();
            setField(cmd, "configPath", malformed.toString());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
        }
    }

    // --- EvalQaCommand tests ---

    @Nested
    @DisplayName("EvalQaCommand")
    class EvalQaCommandTests {

        @Test
        @DisplayName("Missing results file returns exit code 1")
        void evalQaMissingFileReturns1() throws ReflectiveOperationException {
            Path nonexistent = tempDir.resolve("does-not-exist.jsonl");
            EvalQaCommand cmd = new EvalQaCommand();
            setField(cmd, "resultsPath", nonexistent.toString());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
        }

        @Test
        @DisplayName("Empty results file returns exit code 1")
        void evalQaEmptyFileReturns1() throws ReflectiveOperationException, IOException {
            Path empty = tempDir.resolve("empty.jsonl");
            Files.writeString(empty, "");

            EvalQaCommand cmd = new EvalQaCommand();
            setField(cmd, "resultsPath", empty.toString());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
        }

        @Test
        @DisplayName("JSONL with only summary lines (no result lines) returns exit code 1")
        void evalQaMalformedJsonlReturns1() throws ReflectiveOperationException, IOException {
            Path malformed = tempDir.resolve("malformed.jsonl");
            // Lines that the reader will parse but skip (summary lines), producing an empty results list
            Files.writeString(malformed, "{\"type\":\"summary\",\"accuracy\":0.0}\n");

            EvalQaCommand cmd = new EvalQaCommand();
            setField(cmd, "resultsPath", malformed.toString());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
        }
    }

    // --- ContextBatchCommand tests ---

    @Nested
    @DisplayName("ContextBatchCommand")
    class ContextBatchCommandTests {

        @Test
        @DisplayName("Missing question set file returns exit code 1")
        void contextBatchMissingFileReturns1() throws ReflectiveOperationException {
            Path nonexistent = tempDir.resolve("does-not-exist.jsonl");
            ContextBatchCommand cmd = new ContextBatchCommand();
            setField(cmd, "questionSetPath", nonexistent.toString());
            setField(cmd, "ontologyId", "test-ontology");

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
        }
    }
}
