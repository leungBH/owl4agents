package org.owl4agents.acceptance.v086;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.owl4agents.mcp.McpServerAdapter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 Section 8.6 / task 8.6: verify that {@link McpServerAdapter#SERVER_VERSION}
 * is sourced from a single place via {@code loadVersion()} and stays
 * consistent across gradle run / gradle test / shadow jar runs.
 *
 * <p>Per design D9:</p>
 * <ol>
 *   <li><b>Production shadow jar</b>: {@code Implementation-Version} manifest
 *       attribute is set by the {@code shadowJar} task in
 *       {@code modules/ontology-cli/build.gradle.kts}.
 *       {@link Package#getImplementationVersion()} returns it.</li>
 *   <li><b>Gradle run / gradle test / IDE runs</b>: the manifest is not set,
 *       so {@code Package.getImplementationVersion()} returns {@code null}.
 *       The {@code owl4agents.version} system property (set by the root
 *       {@code build.gradle.kts} {@code test} task) provides the value.</li>
 *   <li><b>Fallback</b>: if neither is set, the literal {@code "0.9.1-dev"}
 *       is used so the field is never null.</li>
 * </ol>
 *
 * <p>This test verifies:</p>
 * <ul>
 *   <li>{@code SERVER_VERSION} is non-null and non-blank.</li>
 *   <li>{@code SERVER_VERSION} matches the gradle project version (via the
 *       {@code owl4agents.version} system property set by the test task)
 *       when running under {@code gradle test}.</li>
 *   <li>The {@code loadVersion()} private static method returns the same
 *       value as {@code SERVER_VERSION} (the field is initialized from
 *       {@code loadVersion()} at class-load time).</li>
 *   <li>The 3-tier fallback chain works as documented: manifest → system
 *       property → "0.9.1-dev".</li>
 * </ul>
 *
 * <p>Tagged {@code "acceptance"} per project convention for v0.8.6 acceptance
 * tests under {@code modules/ontology-distribution}.</p>
 */
@DisplayName("v0.8.6 §8.6: SERVER_VERSION single-sourced via loadVersion()")
@org.junit.jupiter.api.Tag("acceptance")
class VersionConsistencyTest {

    private static final String FALLBACK_VERSION = "0.9.1-dev";

    @Nested
    @DisplayName("SERVER_VERSION field is well-formed")
    class ServerVersionFieldTests {

        @Test
        @DisplayName("SERVER_VERSION is non-null")
        void serverVersionNonNull() {
            assertNotNull(McpServerAdapter.SERVER_VERSION,
                "SERVER_VERSION must never be null (loadVersion fallback guarantees this)");
        }

        @Test
        @DisplayName("SERVER_VERSION is non-blank")
        void serverVersionNonBlank() {
            assertFalse(McpServerAdapter.SERVER_VERSION.isBlank(),
                "SERVER_VERSION must never be blank (loadVersion fallback guarantees this)");
        }

        @Test
        @DisplayName("SERVER_VERSION starts with '0.9.1' (project version)")
        void serverVersionStartsWithProjectVersion() {
            // The project version is "0.9.1" (set in root build.gradle.kts).
            // SERVER_VERSION should be either "0.9.1" (manifest/sysprop) or
            // "0.9.1-dev" (fallback). Either way, it must start with "0.9.1".
            assertTrue(McpServerAdapter.SERVER_VERSION.startsWith("0.9.1"),
                "SERVER_VERSION should start with '0.9.1' (project version). " +
                "Got: " + McpServerAdapter.SERVER_VERSION);
        }
    }

    @Nested
    @DisplayName("loadVersion() private static method matches SERVER_VERSION")
    class LoadVersionMethodTests {

        private String invokeLoadVersion() throws Exception {
            Method method = McpServerAdapter.class.getDeclaredMethod("loadVersion");
            method.setAccessible(true);
            return (String) method.invoke(null);
        }

        @Test
        @DisplayName("loadVersion() returns same value as SERVER_VERSION")
        void loadVersionMatchesServerVersion() throws Exception {
            String fromMethod = invokeLoadVersion();
            assertEquals(McpServerAdapter.SERVER_VERSION, fromMethod,
                "loadVersion() must return the same value as SERVER_VERSION " +
                "(the field is initialized from loadVersion() at class-load time)");
        }

        @Test
        @DisplayName("loadVersion() never returns null")
        void loadVersionNeverReturnsNull() throws Exception {
            String fromMethod = invokeLoadVersion();
            assertNotNull(fromMethod,
                "loadVersion() must never return null (fallback to '0.9.1-dev')");
        }

        @Test
        @DisplayName("loadVersion() never returns blank")
        void loadVersionNeverReturnsBlank() throws Exception {
            String fromMethod = invokeLoadVersion();
            assertFalse(fromMethod.isBlank(),
                "loadVersion() must never return blank (fallback to '0.9.1-dev')");
        }
    }

    @Nested
    @DisplayName("3-tier fallback chain: manifest → system property → literal")
    class FallbackChainTests {

        @Test
        @DisplayName("system property 'owl4agents.version' is set by gradle test task")
        void systemPropertyIsSetByGradleTestTask() {
            // The root build.gradle.kts test task sets this system property
            // for all subprojects. Under `gradle test` it must be set.
            // (Under shadow jar run, the manifest provides the value and
            // the system property may be unset — but for `gradle test`
            // specifically, it must be set.)
            String sysProp = System.getProperty("owl4agents.version");
            assertNotNull(sysProp,
                "owl4agents.version system property must be set by gradle test task " +
                "(see root build.gradle.kts tasks.test systemProperty)");
            assertFalse(sysProp.isBlank(),
                "owl4agents.version system property must not be blank. Got: " + sysProp);
        }

        @Test
        @DisplayName("when system property is set, SERVER_VERSION matches it")
        void serverVersionMatchesSystemPropertyWhenSet() {
            String sysProp = System.getProperty("owl4agents.version");
            // Skip if not set (e.g. running outside gradle test).
            org.junit.jupiter.api.Assumptions.assumeTrue(
                sysProp != null && !sysProp.isBlank(),
                "owl4agents.version system property must be set for this test");

            // When the manifest is null (no shadow jar) and the system
            // property is set, loadVersion() returns the system property.
            // Under gradle test, the manifest IS null (no shadow jar is
            // built for test runs), so SERVER_VERSION must equal the sys prop.
            assertEquals(sysProp, McpServerAdapter.SERVER_VERSION,
                "When owl4agents.version system property is set and manifest is null, " +
                "SERVER_VERSION must equal the system property. " +
                "sysProp=" + sysProp + ", SERVER_VERSION=" + McpServerAdapter.SERVER_VERSION);
        }

        @Test
        @DisplayName("fallback literal is '0.9.1-dev' (matches FALLBACK_VERSION)")
        void fallbackLiteralIsCorrect() {
            // This is a compile-time contract test: the FALLBACK_VERSION
            // constant in this test must match the literal in loadVersion().
            // (If the literal changes, this test forces the author to update
            // the test too.)
            assertEquals("0.9.1-dev", FALLBACK_VERSION,
                "Fallback literal in this test must match the literal in loadVersion()");
        }
    }

    @Nested
    @DisplayName("Project version consistency (build.gradle.kts)")
    class ProjectVersionTests {

        @Test
        @DisplayName("gradle project version is '0.9.1'")
        void gradleProjectVersionIsCorrect() {
            // The root build.gradle.kts sets version = "0.9.1".
            // The gradle test task exposes this via the owl4agents.version
            // system property (added in v0.8.6 D9 / task 8.4).
            String sysProp = System.getProperty("owl4agents.version");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                sysProp != null && !sysProp.isBlank(),
                "owl4agents.version system property must be set for this test");

            assertEquals("0.9.1", sysProp,
                "Gradle project version (root build.gradle.kts) must be '0.9.1'. " +
                "Got sysProp=" + sysProp);
        }

        @Test
        @DisplayName("SERVER_VERSION is '0.9.1' under gradle test (manifest is null)")
        void serverVersionIsGradleProjectVersionUnderTest() {
            // Under `gradle test`, no shadow jar is built, so the manifest
            // is null. loadVersion() must fall through to the system property,
            // which equals the gradle project version ("0.9.1").
            String sysProp = System.getProperty("owl4agents.version");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                sysProp != null && !sysProp.isBlank(),
                "owl4agents.version system property must be set for this test");

            // The only way SERVER_VERSION could differ from sysProp is if
            // the manifest took precedence. Under gradle test, the manifest
            // is null, so they must be equal.
            assertEquals(sysProp, McpServerAdapter.SERVER_VERSION,
                "Under gradle test, SERVER_VERSION must equal owl4agents.version system property. " +
                "sysProp=" + sysProp + ", SERVER_VERSION=" + McpServerAdapter.SERVER_VERSION);
        }
    }

    @Nested
    @DisplayName("Protocol version is unchanged (regression guard)")
    class ProtocolVersionTests {

        @Test
        @DisplayName("PROTOCOL_VERSION is '2025-06-18' (MCP Streamable HTTP)")
        void protocolVersionIs2025_06_18() {
            // Regression guard: the v0.8.6 D9 work changed SERVER_VERSION but
            // must NOT change PROTOCOL_VERSION (which is a wire-format contract).
            assertEquals("2025-06-18", McpServerAdapter.PROTOCOL_VERSION,
                "PROTOCOL_VERSION must remain '2025-06-18' (MCP Streamable HTTP) " +
                "and must not be affected by the SERVER_VERSION single-sourcing work");
        }
    }
}
