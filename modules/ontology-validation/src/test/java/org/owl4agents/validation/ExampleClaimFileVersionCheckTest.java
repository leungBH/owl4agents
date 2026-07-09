package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-29: Example claim file v0.8.1 version check.
 *
 * <p>Verifies that the canonical agent-mcp example files reference v0.8.1
 * claim types ({@code different_individuals},
 * {@code object_property_subproperty}) and document the optional
 * {@code expression} field on {@code subject} / {@code object} for
 * complex class expressions.</p>
 */
@DisplayName("TC-29 example files reference v0.8.1 claim types")
class ExampleClaimFileVersionCheckTest {

    private static final Pattern V081 = Pattern.compile("0\\.8\\.1");
    private static final Pattern DIFFERENT_INDIVIDUALS = Pattern.compile(
        "different_individuals|DIFFERENT_INDIVIDUALS|individual.+differentFrom|individual.+different",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT_PROPERTY_SUBPROPERTY = Pattern.compile(
        "object_property_subproperty|OBJECT_PROPERTY_SUBPROPERTY|subPropertyOf|hasBase.+hasIngredient",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLEX_EXPRESSION = Pattern.compile(
        "expression|ObjectIntersectionOf|ObjectSomeValuesFrom|complex.+class.+expression",
        Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("agent-mcp example.yaml mentions v0.8.1 claim features")
    void agentMcpExampleMentionsV081Features() throws Exception {
        Path example = resolveExample();
        assertTrue(Files.exists(example), "agent-mcp/example.yaml must exist at: " + example);
        String content = Files.readString(example);

        assertTrue(V081.matcher(content).find(),
            "example.yaml must mention v0.8.1 version");

        assertTrue(DIFFERENT_INDIVIDUALS.matcher(content).find() ||
                   OBJECT_PROPERTY_SUBPROPERTY.matcher(content).find() ||
                   COMPLEX_EXPRESSION.matcher(content).find(),
            "example.yaml must reference at least one v0.8.1 claim type (different_individuals, " +
            "object_property_subproperty, or complex class expression)");

        // Verify the example is still valid YAML (basic check)
        assertTrue(content.contains("id:") && content.contains("title:"),
            "example.yaml must contain 'id:' and 'title:' fields (valid YAML structure)");
    }

    @Test
    @DisplayName("agent-mcp README.md mentions v0.8.1 claim features")
    void agentMcpReadmeMentionsV081Features() throws Exception {
        Path readme = resolveReadme();
        assertTrue(Files.exists(readme), "agent-mcp/README.md must exist at: " + readme);
        String content = Files.readString(readme);

        assertTrue(V081.matcher(content).find(),
            "README.md must mention v0.8.1 version");

        assertTrue(COMPLEX_EXPRESSION.matcher(content).find() ||
                   content.contains("different_individuals") ||
                   content.contains("object_property_subproperty"),
            "README.md must reference at least one v0.8.1 claim feature");
    }

    @Test
    @DisplayName("verify-claim-transcript.md uses v0.8.1 server version")
    void verifyClaimTranscriptUsesV081() throws Exception {
        Path transcript = resolveTranscript();
        if (!Files.exists(transcript)) {
            // Transcript may not exist in all configurations; skip
            return;
        }
        String content = Files.readString(transcript);
        assertTrue(content.contains("0.8.1"),
            "verify-claim-transcript.md must use server version 0.8.1 (got: "
                + firstMatch(content, V081) + ")");
    }

    private Path resolveExample() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = cwd.resolve("examples/agent-mcp/example.yaml");
            if (Files.exists(candidate)) return candidate;
            cwd = cwd.getParent();
            if (cwd == null) break;
        }
        return Path.of("examples/agent-mcp/example.yaml");
    }

    private Path resolveReadme() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = cwd.resolve("examples/agent-mcp/README.md");
            if (Files.exists(candidate)) return candidate;
            cwd = cwd.getParent();
            if (cwd == null) break;
        }
        return Path.of("examples/agent-mcp/README.md");
    }

    private Path resolveTranscript() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = cwd.resolve("examples/agent-mcp/transcripts/verify-claim-transcript.md");
            if (Files.exists(candidate)) return candidate;
            cwd = cwd.getParent();
            if (cwd == null) break;
        }
        return Path.of("examples/agent-mcp/transcripts/verify-claim-transcript.md");
    }

    private String firstMatch(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group() : "(no match)";
    }
}
