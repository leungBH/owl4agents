package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7.8: Skill lint checks for placeholder text, missing references,
 * stale command/tool names, local-only paths, and unsafe verdict policy.
 * Also validates task 7.9 (no fabrication instructions) and 7.10
 * (portable paths and real fixture ontology IDs).
 */
@DisplayName("v0.5 skill pack lint tests")
class SkillLintTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final Path SKILLS_BASE = PROJECT_ROOT.resolve("agent-skills");

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

    // ── Placeholder text checks (task 7.8) ──

    @Nested
    @DisplayName("No placeholder text in skills")
    class PlaceholderTextTests {

        private final List<String> forbiddenPlaceholders = List.of(
            "TODO", "FIXME", "stub", "not implemented",
            "/path/to/", "<your-", "your-ontology-id", "YOUR_ONTOLOGY_ID"
        );

        @Test
        @DisplayName("Skills contain no forbidden placeholder text")
        void noForbiddenPlaceholders() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            for (Path file : skillFiles) {
                // Only check SKILL.md and policy reference files, not the top-level README
                if (file.getFileName().toString().equals("README.md")
                    && file.getParent().equals(SKILLS_BASE)) continue;

                String content = Files.readString(file);
                for (String placeholder : forbiddenPlaceholders) {
                    assertFalse(content.contains(placeholder),
                        file + " contains forbidden placeholder: '" + placeholder + "'");
                }
            }
        }
    }

    // ── Missing references check (task 7.8) ──

    @Nested
    @DisplayName("Skills reference existing shared policy files")
    class MissingReferencesTests {

        @Test
        @DisplayName("All cross-references in skills point to existing files")
        void crossReferencesExist() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            for (Path file : skillFiles) {
                String content = Files.readString(file);
                // Check markdown-style relative links [text](path)
                // Extract paths like ../_shared/references/verdict-policy.md
                java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
                java.util.regex.Matcher matcher = linkPattern.matcher(content);
                while (matcher.find()) {
                    String linkPath = matcher.group(2);
                    if (linkPath.startsWith("http") || linkPath.startsWith("#")) continue;
                    Path resolved = file.getParent().resolve(linkPath).normalize();
                    assertTrue(Files.exists(resolved),
                        file + " references non-existent file: '" + linkPath + "' (resolved to: " + resolved + ")");
                }
            }
        }
    }

    // ── Stale command/tool names check (task 7.8) ──

    @Nested
    @DisplayName("Skills use current command and tool names")
    class StaleCommandNamesTests {

        @Test
        @DisplayName("SKILL.md files use current CLI commands")
        void currentCliCommands() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            boolean anySkillHasCommand = false;
            for (Path file : skillFiles) {
                if (file.getFileName().toString().equals("SKILL.md")) {
                    String content = Files.readString(file);
                    String skillName = file.getParent().getFileName().toString();
                    boolean hasCommand;
                    if (skillName.equals("owl4agents-ontology-scope-check")) {
                        // Scope check uses v0.2/v0.3 commands (list, scope, search, missing-entities)
                        hasCommand = content.contains("owl4agents list")
                            || content.contains("owl4agents scope")
                            || content.contains("owl4agents search")
                            || content.contains("ontology_search_entities")
                            || content.contains("ontology_detect_missing_entities");
                    } else {
                        // Claim verification and evidence-grounded answer use v0.5 commands
                        hasCommand = content.contains("verify-answer")
                            || content.contains("evidence-context")
                            || content.contains("review-answer");
                    }
                    assertTrue(hasCommand,
                        file + " should reference relevant CLI commands");
                    anySkillHasCommand = true;
                }
            }
            assertTrue(anySkillHasCommand, "At least one SKILL.md file must exist and reference CLI commands");
        }

        @Test
        @DisplayName("Skills use current v0.5 MCP tool names")
        void currentMcpToolNames() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            for (Path file : skillFiles) {
                String content = Files.readString(file);
                if (content.contains("ontology_verify") || content.contains("ontology_review") || content.contains("ontology_build")) {
                    // Should use v0.5 batch tool names, not v0.3 single-claim tool names exclusively
                    assertTrue(content.contains("ontology_verify_claims_batch")
                        || content.contains("ontology_build_evidence_context")
                        || content.contains("ontology_review_answer_claims")
                        || content.contains("ontology_verify_claim"),
                        file + " should reference valid MCP tool names");
                }
            }
        }
    }

    // ── Local-only paths check (task 7.8) ──

    @Nested
    @DisplayName("Skills use portable paths")
    class PortablePathsTests {

        @Test
        @DisplayName("Skills do not contain absolute local paths")
        void noAbsoluteLocalPaths() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            for (Path file : skillFiles) {
                String content = Files.readString(file);
                // Check for Windows absolute paths like C:\ or D:\
                assertFalse(java.util.regex.Pattern.compile("[A-Z]:\\\\").matcher(content).find(),
                    file + " contains absolute Windows path");
                // Check for Unix absolute paths like /home/ or /Users/
                assertFalse(content.contains("/home/") || content.contains("/Users/"),
                    file + " contains absolute Unix path");
            }
        }
    }

    // ── Unsafe verdict policy check (task 7.8) ──

    @Nested
    @DisplayName("Skills do not contain unsafe verdict policy")
    class UnsafeVerdictPolicyTests {

        @Test
        @DisplayName("Skills do not instruct agents to accept UNKNOWN as verified")
        void noUnsafeVerdictPolicy() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            for (Path file : skillFiles) {
                String content = Files.readString(file);
                assertFalse(content.contains("treat unknown as verified")
                    || content.contains("accept UNKNOWN as true")
                    || content.contains("assume unknown means true"),
                    file + " contains unsafe verdict policy");
            }
        }
    }

    // ── No fabrication instructions (task 7.9) ──

    @Nested
    @DisplayName("Skills do not instruct agents to fabricate")
    class NoFabricationTests {

        private final List<String> fabricationPhrases = List.of(
            "fabricate claims", "fabricate evidence", "fabricate IRIs",
            "fabricate citations", "make up evidence", "invent evidence",
            "create your own IRI", "guess the IRI"
        );

        @Test
        @DisplayName("Skills explicitly prohibit fabrication")
        void prohibitFabrication() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            boolean foundProhibition = false;
            for (Path file : skillFiles) {
                String content = Files.readString(file);
                // Check for "Do not fabricate" directives
                if (content.contains("Do not fabricate")) {
                    foundProhibition = true;
                }
                // Check that fabrication phrases only appear in prohibitions
                // (preceded by "Do not", "never", "must not", etc.)
                for (String phrase : fabricationPhrases) {
                    int idx = content.indexOf(phrase);
                    while (idx >= 0) {
                        // Check preceding context for prohibition markers
                        String preceding = content.substring(Math.max(0, idx - 30), idx);
                        boolean isProhibition = preceding.toLowerCase().contains("do not")
                            || preceding.toLowerCase().contains("never")
                            || preceding.toLowerCase().contains("must not")
                            || preceding.toLowerCase().contains("should not");
                        assertTrue(isProhibition,
                            file + " contains fabrication phrase '" + phrase + "' not in a prohibition context. Preceding text: '" + preceding + "'");
                        idx = content.indexOf(phrase, idx + 1);
                    }
                }
            }
            assertTrue(foundProhibition,
                "At least one skill file must contain 'Do not fabricate' directive");
        }

        @Test
        @DisplayName("Skills contain anti-fabrication warnings")
        void containAntiFabricationWarnings() throws Exception {
            // At least the shared evidence-citation-policy must contain "Do not fabricate"
            Path citationPolicy = SKILLS_BASE.resolve("_shared/references/evidence-citation-policy.md");
            if (Files.exists(citationPolicy)) {
                String content = Files.readString(citationPolicy);
                assertTrue(content.contains("Do not fabricate"),
                    "evidence-citation-policy.md must contain 'Do not fabricate' directive");
            }
        }
    }

    // ── Portable paths and real fixture ontology IDs (task 7.10) ──

    @Nested
    @DisplayName("Skill examples use portable paths and real fixture IDs")
    class PortablePathsAndFixtureIdsTests {

        @Test
        @DisplayName("Skills reference real fixture examples")
        void referenceRealFixtures() throws Exception {
            // Check that at least one SKILL.md references test/fixtures/v0.5/
            List<Path> skillFiles = listSkillFiles();
            boolean foundFixtureReference = false;
            for (Path file : skillFiles) {
                if (file.getFileName().toString().equals("SKILL.md")) {
                    String content = Files.readString(file);
                    if (content.contains("test/fixtures/v0.5")) {
                        foundFixtureReference = true;
                        break;
                    }
                }
            }
            assertTrue(foundFixtureReference,
                "At least one SKILL.md should reference test/fixtures/v0.5/ fixture examples");
        }

        @Test
        @DisplayName("Skills use ontology fixture ID 'pizza-ontology' in examples")
        void useFixtureOntologyId() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            boolean foundOntologyId = false;
            for (Path file : skillFiles) {
                if (file.getFileName().toString().equals("SKILL.md")) {
                    String content = Files.readString(file);
                    if (content.contains("pizza-ontology")) {
                        foundOntologyId = true;
                        break;
                    }
                }
            }
            assertTrue(foundOntologyId,
                "At least one SKILL.md should use 'pizza-ontology' fixture ID in examples");
        }
    }

    // ── Skill directory structure (task 7.1) ──

    @Nested
    @DisplayName("agent-skills directory structure is correct")
    class DirectoryStructureTests {

        @Test
        @DisplayName("agent-skills/ directory exists")
        void skillsDirectoryExists() {
            assertTrue(Files.exists(SKILLS_BASE), "agent-skills/ directory must exist");
        }

        @Test
        @DisplayName("agent-skills/README.md exists")
        void readmeExists() {
            assertTrue(Files.exists(SKILLS_BASE.resolve("README.md")),
                "agent-skills/README.md must exist");
        }

        @Test
        @DisplayName("Shared references directory exists")
        void sharedReferencesExist() {
            assertTrue(Files.exists(SKILLS_BASE.resolve("_shared/references")),
                "agent-skills/_shared/references/ must exist");
        }

        @Test
        @DisplayName("Verdict policy reference exists")
        void verdictPolicyExists() {
            assertTrue(Files.exists(SKILLS_BASE.resolve("_shared/references/verdict-policy.md")),
                "agent-skills/_shared/references/verdict-policy.md must exist");
        }

        @Test
        @DisplayName("Claim verification SKILL.md exists")
        void claimVerificationSkillExists() {
            assertTrue(Files.exists(SKILLS_BASE.resolve("owl4agents-claim-verification/SKILL.md")),
                "agent-skills/owl4agents-claim-verification/SKILL.md must exist");
        }

        @Test
        @DisplayName("Evidence-grounded answer SKILL.md exists")
        void evidenceGroundedAnswerSkillExists() {
            assertTrue(Files.exists(SKILLS_BASE.resolve("owl4agents-evidence-grounded-answer/SKILL.md")),
                "agent-skills/owl4agents-evidence-grounded-answer/SKILL.md must exist");
        }

        @Test
        @DisplayName("Ontology scope check SKILL.md exists")
        void ontologyScopeCheckSkillExists() {
            assertTrue(Files.exists(SKILLS_BASE.resolve("owl4agents-ontology-scope-check/SKILL.md")),
                "agent-skills/owl4agents-ontology-scope-check/SKILL.md must exist");
        }
    }

    // ── Skill links to shared verdict policy (task 7.6) ──

    @Nested
    @DisplayName("Skills link to shared verdict policy instead of duplicating")
    class SharedPolicyLinkTests {

        @Test
        @DisplayName("SKILL.md files reference shared verdict-policy.md")
        void skillsReferenceVerdictPolicy() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            for (Path file : skillFiles) {
                if (file.getFileName().toString().equals("SKILL.md")) {
                    String content = Files.readString(file);
                    assertTrue(content.contains("verdict-policy.md"),
                        file + " should reference shared verdict-policy.md");
                }
            }
        }

        @Test
        @DisplayName("SKILL.md files do not contain divergent verdict definitions")
        void noDivergentVerdictDefinitions() throws Exception {
            List<Path> skillFiles = listSkillFiles();
            // Only the shared verdict-policy should define verdict semantics
            for (Path file : skillFiles) {
                if (file.getFileName().toString().equals("SKILL.md")) {
                    String content = Files.readString(file);
                    // SKILL.md files should reference verdict-policy.md, not define their own
                    // They should not contain standalone verdict tables that contradict the shared policy
                    assertFalse(content.contains("| Verdict | Meaning |")
                        && !content.contains("verdict-policy.md"),
                        file + " should reference verdict-policy.md, not duplicate verdict definitions");
                }
            }
        }
    }

    // ── Helper ──

    private List<Path> listSkillFiles() throws Exception {
        try (Stream<Path> walk = Files.walk(SKILLS_BASE)) {
            return walk
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .filter(p -> Files.isRegularFile(p))
                .toList();
        }
    }
}