package org.owl4agents.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for DEFECT-023: Gson must serialize Optional<String> fields
 * without throwing InaccessibleObjectException on JDK 17+.
 *
 * Root cause: v0.6 Gson builders did not register OptionalTypeAdapterFactory,
 * which was the fix for DEFECT-006 in v0.5. This test ensures all v0.6 Gson
 * instances handle Optional fields correctly.
 */
@DisplayName("DEFECT-023 regression: Optional<String> serialization")
class Defect023RegressionTest {

    @Nested
    @DisplayName("GsonFactory handles Optional fields")
    class GsonFactoryTests {

        @Test
        @DisplayName("GsonFactory.createGson() serializes Optional.empty() as null")
        void optionalEmptySerializesAsNull() {
            Gson gson = GsonFactory.createGson();
            String json = gson.toJson(new TestRecord("id1", Optional.empty()));
            assertTrue(json.contains("\"error\":null"),
                "Optional.empty() must serialize as null, not throw InaccessibleObjectException");
        }

        @Test
        @DisplayName("GsonFactory.createGson() serializes Optional.of(value) as value")
        void optionalOfSerializesAsValue() {
            Gson gson = GsonFactory.createGson();
            String json = gson.toJson(new TestRecord("id1", Optional.of("TIMEOUT")));
            assertTrue(json.contains("\"error\":\"TIMEOUT\""),
                "Optional.of() must serialize as the inner value");
        }

        @Test
        @DisplayName("GsonFactory.builder().setPrettyPrinting() handles Optional fields")
        void gsonBuilderWithPrettyPrintingHandlesOptional() {
            Gson gson = GsonFactory.builder().setPrettyPrinting().create();
            // This is exactly what BenchmarkReportGenerator does — previously crashed
            String json = gson.toJson(new TestRecord("q1", Optional.of("TIMEOUT")));
            assertTrue(json.contains("\"error\""),
                "Pretty-printing Gson must handle Optional fields without crash");
            assertTrue(json.contains("TIMEOUT"),
                "Optional value must appear in output");
        }

        @Test
        @DisplayName("GsonFactory deserializes null as Optional.empty()")
        void nullDeserializesAsOptionalEmpty() {
            Gson gson = GsonFactory.createGson();
            String json = "{\"id\":\"id1\",\"error\":null}";
            TestRecord record = gson.fromJson(json, TestRecord.class);
            assertNotNull(record);
            assertTrue(record.error().isEmpty(),
                "null must deserialize as Optional.empty()");
        }

        @Test
        @DisplayName("GsonFactory deserializes non-null as Optional.of(value)")
        void nonNullDeserializesAsOptionalOf() {
            Gson gson = GsonFactory.createGson();
            String json = "{\"id\":\"id1\",\"error\":\"TIMEOUT\"}";
            TestRecord record = gson.fromJson(json, TestRecord.class);
            assertNotNull(record);
            assertTrue(record.error().isPresent(),
                "non-null must deserialize as Optional.of()");
            assertEquals("TIMEOUT", record.error().get());
        }

        @Test
        @DisplayName("BenchmarkResultLine with Optional error serializes without crash")
        void benchmarkResultLineSerializesWithoutCrash() {
            // This directly tests the crash from DEFECT-023:
            // BenchmarkResultLine has Optional<String> error field
            Gson gson = GsonFactory.builder().setPrettyPrinting().create();

            BenchmarkResultLine line = new BenchmarkResultLine(
                "q1", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true,
                50, Optional.of("approved"), Optional.of("TIMEOUT"), false);

            String json = gson.toJson(line);
            assertNotNull(json, "BenchmarkResultLine must serialize without InaccessibleObjectException");
            assertTrue(json.contains("\"error\""),
                "Optional error field must appear in JSON output");
        }

        @Test
        @DisplayName("BenchmarkResultLine with Optional.empty() error serializes")
        void benchmarkResultLineEmptyErrorSerializes() {
            Gson gson = GsonFactory.builder().setPrettyPrinting().create();

            BenchmarkResultLine line = new BenchmarkResultLine(
                "q1", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true,
                50, Optional.of("approved"), Optional.empty(), false);

            // The core DEFECT-023 fix: this must NOT throw InaccessibleObjectException
            String json = gson.toJson(line);
            assertNotNull(json, "BenchmarkResultLine with Optional.empty() must serialize without crash");
            assertTrue(json.contains("\"error\""),
                "Optional error field must appear in JSON output");
            // Optional.empty() serializes as null with serializeNulls(),
            // or may be omitted — both are valid; crash-free is the key requirement
        }
    }

    /** Test record with Optional field to verify serialization. */
    record TestRecord(String id, Optional<String> error) {}
}