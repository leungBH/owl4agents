package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests verifying v0.3 fixtures are present and have valid content.
 * Missing required fixtures MUST fail loudly, per test/contracts/acceptance-report/contracts.md.
 */
@DisplayName("v0.3 fixture acceptance tests")
class FixtureAcceptanceTest {

    // Resolve paths relative to the project root, regardless of where Gradle runs tests
    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final Path FIXTURES_BASE = PROJECT_ROOT.resolve("test/fixtures/v0.3");
    private static final Path GOLDEN_CORPUS_BASE = PROJECT_ROOT.resolve("test/corpus/golden");
    private static final Path CONTRACTS_BASE = PROJECT_ROOT.resolve("test/contracts/claim-verification");

    private static Path findProjectRoot() {
        // Start from the user.dir system property (set by Gradle to the project root
        // when running tests from a specific module)
        String userDir = System.getProperty("user.dir");
        Path cwd = userDir != null ? Path.of(userDir).toAbsolutePath() : Path.of("").toAbsolutePath();
        Path dir = cwd;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts")) && Files.exists(dir.resolve("test"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        // Fallback: use cwd
        return cwd;
    }

    // --- Required fixture existence tests ---

    @Nested
    @DisplayName("Required fixtures present")
    class RequiredFixtureTests {

        @Test
        @DisplayName("Supported claim fixture exists and is non-empty")
        void supportedClaimFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-supported.json");
            assertTrue(Files.exists(fixture), "Required fixture missing: " + fixture);
            assertFalse(isEmptyJson(fixture), "Required fixture is empty: " + fixture);
        }

        @Test
        @DisplayName("Contradicted claim fixture exists and is non-empty")
        void contradictedClaimFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-contradicted.json");
            assertTrue(Files.exists(fixture), "Required fixture missing: " + fixture);
            assertFalse(isEmptyJson(fixture), "Required fixture is empty: " + fixture);
        }

        @Test
        @DisplayName("Unknown claim fixture exists and is non-empty")
        void unknownClaimFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-unknown.json");
            assertTrue(Files.exists(fixture), "Required fixture missing: " + fixture);
            assertFalse(isEmptyJson(fixture), "Required fixture is empty: " + fixture);
        }

        @Test
        @DisplayName("Out-of-scope claim fixture exists and is non-empty")
        void outOfScopeClaimFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-out-of-scope.json");
            assertTrue(Files.exists(fixture), "Required fixture missing: " + fixture);
            assertFalse(isEmptyJson(fixture), "Required fixture is empty: " + fixture);
        }

        @Test
        @DisplayName("Malformed claim fixture exists (negative test fixture)")
        void malformedClaimFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-malformed.json");
            assertTrue(Files.exists(fixture), "Required negative fixture missing: " + fixture);
        }

        @Test
        @DisplayName("Unsupported claim type fixture exists (negative test fixture)")
        void unsupportedTypeFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-unsupported-type.json");
            assertTrue(Files.exists(fixture), "Required negative fixture missing: " + fixture);
        }

        @Test
        @DisplayName("Unknown ontology fixture exists (negative test fixture)")
        void unknownOntologyFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-unknown-ontology.json");
            assertTrue(Files.exists(fixture), "Required negative fixture missing: " + fixture);
        }

        @Test
        @DisplayName("Golden ontology fixture exists and is non-empty")
        void goldenOntologyFixturePresent() {
            Path fixture = GOLDEN_CORPUS_BASE.resolve("v0.3-claim-verification.owl");
            assertTrue(Files.exists(fixture), "Required golden ontology missing: " + fixture);
            assertFalse(isEmptyFile(fixture), "Required golden ontology is empty: " + fixture);
        }

        @Test
        @DisplayName("Claim verification contracts file exists")
        void claimVerificationContractsPresent() {
            Path contract = CONTRACTS_BASE.resolve("contracts.md");
            assertTrue(Files.exists(contract), "Required contract missing: " + contract);
        }

        @Test
        @DisplayName("v0.3.1 onboarding smoke claim fixture exists and is non-empty")
        void smokeSupportedClaimFixturePresent() {
            Path fixture = FIXTURES_BASE.resolve("claim-smoke-supported.json");
            assertTrue(Files.exists(fixture), "Required v0.3.1 onboarding fixture missing: " + fixture);
            assertFalse(isEmptyJson(fixture), "Required v0.3.1 onboarding fixture is empty: " + fixture);
        }

        @Test
        @DisplayName("Pizza ontology smoke fixture exists and is non-empty")
        void pizzaFixturePresent() {
            Path fixture = PROJECT_ROOT.resolve("test/corpus/smoke/pizza.owl");
            assertTrue(Files.exists(fixture), "Required Pizza ontology fixture missing: " + fixture);
            assertFalse(isEmptyFile(fixture), "Required Pizza ontology fixture is empty: " + fixture);
        }

        @Test
        @DisplayName("v0.3.1 acceptance contract exists")
        void v031AcceptanceContractPresent() {
            Path contract = PROJECT_ROOT.resolve("test/contracts/v031-acceptance/contracts.md");
            assertTrue(Files.exists(contract), "Required v0.3.1 acceptance contract missing: " + contract);
        }
    }

    // --- Fixture content validation tests ---

    @Nested
    @DisplayName("Fixture content validation")
    class FixtureContentTests {

        @Test
        @DisplayName("Supported claim fixture contains SUBCLASS type")
        void supportedClaimContainsSubclassType() {
            Path fixture = FIXTURES_BASE.resolve("claim-supported.json");
            String content = readFile(fixture);
            assertTrue(content.contains("subclass"), "Supported fixture must contain subclass claim type");
            assertTrue(content.contains("claimId"), "Supported fixture must contain claimId");
            assertTrue(content.contains("ontologyId"), "Supported fixture must contain ontologyId");
        }

        @Test
        @DisplayName("Contradicted claim fixture contains DISJOINT_CLASSES type")
        void contradictedClaimContainsDisjointType() {
            Path fixture = FIXTURES_BASE.resolve("claim-contradicted.json");
            String content = readFile(fixture);
            assertTrue(content.contains("disjoint_classes"), "Contradicted fixture must contain disjoint_classes claim type");
        }

        @Test
        @DisplayName("Unknown claim fixture contains SUBCLASS with sparse entities")
        void unknownClaimContainsSparseEntities() {
            Path fixture = FIXTURES_BASE.resolve("claim-unknown.json");
            String content = readFile(fixture);
            assertTrue(content.contains("subclass"), "Unknown fixture must contain subclass claim type");
        }

        @Test
        @DisplayName("Out-of-scope claim fixture contains ONTOLOGY_SCOPE type")
        void outOfScopeClaimContainsScopeType() {
            Path fixture = FIXTURES_BASE.resolve("claim-out-of-scope.json");
            String content = readFile(fixture);
            assertTrue(content.contains("ontology_scope"), "Out-of-scope fixture must contain ontology_scope claim type");
        }

        @Test
        @DisplayName("Real out-of-scope fixture uses undeclared entity IRI")
        void realOutOfScopeFixtureUsesUndeclaredEntity() {
            Path fixture = FIXTURES_BASE.resolve("claim-real-out-of-scope.json");
            assertTrue(Files.exists(fixture), "Required real out-of-scope fixture missing: " + fixture);
            assertFalse(isEmptyJson(fixture), "Real out-of-scope fixture is empty: " + fixture);
            String content = readFile(fixture);
            assertTrue(content.contains("ontology_scope"), "Real out-of-scope fixture must contain ontology_scope claim type");
            assertTrue(content.contains("DeliveryPrice"), "Real out-of-scope fixture must reference DeliveryPrice (undeclared entity)");
        }

        @Test
        @DisplayName("Malformed claim fixture is intentionally incomplete")
        void malformedClaimIsIncomplete() {
            Path fixture = FIXTURES_BASE.resolve("claim-malformed.json");
            String content = readFile(fixture);
            assertFalse(content.contains("claimId"), "Malformed fixture must NOT contain claimId");
        }

        @Test
        @DisplayName("Unsupported type fixture uses verify_answer")
        void unsupportedTypeFixtureUsesVerifyAnswer() {
            Path fixture = FIXTURES_BASE.resolve("claim-unsupported-type.json");
            String content = readFile(fixture);
            assertTrue(content.contains("verify_answer"), "Unsupported type fixture must use verify_answer type");
        }

        @Test
        @DisplayName("Golden ontology contains required test classes")
        void goldenOntologyContainsRequiredClasses() {
            Path fixture = GOLDEN_CORPUS_BASE.resolve("v0.3-claim-verification.owl");
            String content = readFile(fixture);
            assertTrue(content.contains("Animal"), "Golden ontology must define Animal class");
            assertTrue(content.contains("Dog"), "Golden ontology must define Dog class");
            assertTrue(content.contains("Cat"), "Golden ontology must define Cat class");
            assertTrue(content.contains("Mammal"), "Golden ontology must define Mammal class");
        }

        @Test
        @DisplayName("Golden ontology contains disjoint axiom for contradicted test")
        void goldenOntologyContainsDisjointAxiom() {
            Path fixture = GOLDEN_CORPUS_BASE.resolve("v0.3-claim-verification.owl");
            String content = readFile(fixture);
            assertTrue(content.contains("disjointWith"), "Golden ontology must contain disjointWith axiom");
        }

        @Test
        @DisplayName("Golden ontology contains sparse unknown entities")
        void goldenOntologyContainsSparseUnknownEntities() {
            Path fixture = GOLDEN_CORPUS_BASE.resolve("v0.3-claim-verification.owl");
            String content = readFile(fixture);
            assertTrue(content.contains("Fish"), "Golden ontology must define Fish class (sparse unknown)");
            assertTrue(content.contains("Goldfish"), "Golden ontology must define Goldfish class (sparse unknown)");
        }

        @Test
        @DisplayName("Golden ontology contains out-of-scope entity")
        void goldenOntologyContainsOutOfScopeEntity() {
            Path fixture = GOLDEN_CORPUS_BASE.resolve("v0.3-claim-verification.owl");
            String content = readFile(fixture);
            assertTrue(content.contains("UnconnectedThing"), "Golden ontology must define UnconnectedThing (out-of-scope)");
        }
    }

    // --- Helper methods ---

    private boolean isEmptyJson(Path path) {
        try {
            String content = Files.readString(path).trim();
            return content.isEmpty() || content.equals("{}") || content.equals("[]");
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isEmptyFile(Path path) {
        try {
            return Files.readString(path).trim().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            fail("Cannot read required fixture: " + path + " — " + e.getMessage());
            return "";
        }
    }
}