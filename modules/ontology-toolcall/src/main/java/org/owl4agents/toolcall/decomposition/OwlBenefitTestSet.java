package org.owl4agents.toolcall.decomposition;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * v0.8.7 CL-004: Loader for the OWL Independent Benefit Test Suite — a
 * collection of {@code >= 100} test cases that can only be resolved by OWL
 * reasoning (not by SHACL, not by JSON Schema, not by lookup).
 *
 * <p>The test suite is stored as a JSON resource at
 * {@code src/test/resources/owl-benefit-test-set.json} (canonical location
 * per the {@code claim-decomposition} spec "OWL Independent Benefit Test
 * Suite"). Each case demonstrates that the reasoner infers a fact necessary
 * for validation that is not explicitly asserted in the data — e.g.
 * {@code device1 rdf:type CoolingOnlyDevice}, the ontology declares
 * {@code CoolingOnlyDevice subclassOf HVACDevice} and
 * {@code HVACDevice subclassOf (hasCapability some TemperatureControl)},
 * and the validator must infer {@code device1 hasCapability some
 * TemperatureControl} to verify the capability claim.</p>
 *
 * <p>The loader is intentionally minimal: it parses the JSON file into a
 * list of {@link OwlBenefitTestCase} records and validates that the suite
 * contains at least 100 cases. The actual OWL verification of each case is
 * performed by the pipeline (stage 6) — this loader only ensures the test
 * set is well-formed and accessible.</p>
 */
public final class OwlBenefitTestSet {

    /** Canonical classpath location of the test set resource. */
    public static final String DEFAULT_RESOURCE_PATH = "owl-benefit-test-set.json";

    /** Minimum number of cases required by the spec. */
    public static final int MIN_REQUIRED_CASES = 100;

    private final List<OwlBenefitTestCase> cases;
    private final String sourcePath;

    private OwlBenefitTestSet(List<OwlBenefitTestCase> cases, String sourcePath) {
        this.cases = Collections.unmodifiableList(new ArrayList<>(cases));
        this.sourcePath = sourcePath;
    }

    /**
     * Load the test set from the canonical classpath resource
     * ({@link #DEFAULT_RESOURCE_PATH}).
     *
     * @return the loaded {@link OwlBenefitTestSet}; never {@code null}
     * @throws IOException if the resource cannot be read
     * @throws IllegalStateException if the suite contains fewer than
     *         {@link #MIN_REQUIRED_CASES} cases or is malformed
     */
    public static OwlBenefitTestSet loadFromClasspath() throws IOException {
        return loadFromClasspath(DEFAULT_RESOURCE_PATH);
    }

    /**
     * Load the test set from a specific classpath resource.
     *
     * @param resourcePath the classpath resource path
     * @return the loaded {@link OwlBenefitTestSet}; never {@code null}
     * @throws IOException if the resource cannot be read
     * @throws IllegalStateException if the suite contains fewer than
     *         {@link #MIN_REQUIRED_CASES} cases or is malformed
     */
    public static OwlBenefitTestSet loadFromClasspath(String resourcePath) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        try (InputStream is = OwlBenefitTestSet.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("OWL benefit test set resource not found on classpath: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return parse(reader, resourcePath);
            }
        }
    }

    /**
     * Load the test set from a file path (used by CLI tools that want to
     * point at an alternative fixture).
     *
     * @param file the file path to load
     * @return the loaded {@link OwlBenefitTestSet}; never {@code null}
     * @throws IOException if the file cannot be read
     * @throws IllegalStateException if the suite contains fewer than
     *         {@link #MIN_REQUIRED_CASES} cases or is malformed
     */
    public static OwlBenefitTestSet loadFromFile(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return parse(new StringReader(content), file.toString());
    }

    /**
     * Parse the test set from a {@link Reader}.
     */
    private static OwlBenefitTestSet parse(Reader reader, String sourcePath) throws IOException {
        JsonElement root = JsonParser.parseReader(reader);
        if (root == null || !root.isJsonObject()) {
            throw new IllegalStateException("OWL benefit test set " + sourcePath
                + " must be a JSON object with a 'cases' array");
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("cases") || !obj.get("cases").isJsonArray()) {
            throw new IllegalStateException("OWL benefit test set " + sourcePath
                + " must contain a 'cases' JSON array");
        }
        JsonArray arr = obj.getAsJsonArray("cases");
        List<OwlBenefitTestCase> cases = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                throw new IllegalStateException("Test case at index " + i + " in " + sourcePath
                    + " must be a JSON object");
            }
            cases.add(parseCase(el.getAsJsonObject(), i));
        }
        if (cases.size() < MIN_REQUIRED_CASES) {
            throw new IllegalStateException("OWL benefit test set " + sourcePath
                + " contains only " + cases.size() + " cases; spec requires at least "
                + MIN_REQUIRED_CASES);
        }
        return new OwlBenefitTestSet(cases, sourcePath);
    }

    private static OwlBenefitTestCase parseCase(JsonObject obj, int index) {
        String id = obj.has("id") ? obj.get("id").getAsString() : ("case-" + (index + 1));
        String category = obj.has("category") ? obj.get("category").getAsString() : "unknown";
        String description = obj.has("description") ? obj.get("description").getAsString() : "";
        String assertedFact = obj.has("assertedFact") ? obj.get("assertedFact").getAsString() : "";
        String ontologyAxiom = obj.has("ontologyAxiom") ? obj.get("ontologyAxiom").getAsString() : "";
        String inferredFact = obj.has("inferredFact") ? obj.get("inferredFact").getAsString() : "";
        String expectedVerdict = obj.has("expectedVerdict") ? obj.get("expectedVerdict").getAsString() : "supported";
        String requiredCapability = obj.has("requiredCapability") ? obj.get("requiredCapability").getAsString() : "";
        String targetEntity = obj.has("targetEntity") ? obj.get("targetEntity").getAsString() : "";
        boolean lookupOnlyFails = !obj.has("lookupOnlyFails") || obj.get("lookupOnlyFails").getAsBoolean();
        return new OwlBenefitTestCase(
            id, category, description, assertedFact, ontologyAxiom,
            inferredFact, expectedVerdict, requiredCapability, targetEntity,
            lookupOnlyFails
        );
    }

    /**
     * The list of test cases (immutable).
     */
    public List<OwlBenefitTestCase> cases() {
        return cases;
    }

    /**
     * Number of cases in the suite.
     */
    public int size() {
        return cases.size();
    }

    /**
     * Whether the suite meets the {@link #MIN_REQUIRED_CASES} threshold.
     */
    public boolean meetsMinimumSize() {
        return cases.size() >= MIN_REQUIRED_CASES;
    }

    /**
     * The source path or resource name this set was loaded from.
     */
    public String sourcePath() {
        return sourcePath;
    }

    /**
     * Filter the suite by category name.
     */
    public List<OwlBenefitTestCase> casesByCategory(String category) {
        List<OwlBenefitTestCase> out = new ArrayList<>();
        for (OwlBenefitTestCase c : cases) {
            if (c.category().equalsIgnoreCase(category)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Single OWL benefit test case.
     *
     * @param id                 unique case identifier (e.g. {@code "case-001"})
     * @param category           claim category (capability / disjointness /
     *                           transitivity / permission / etc.)
     * @param description        human-readable description of what the case
     *                           demonstrates
     * @param assertedFact       the fact explicitly asserted in the data
     *                           (e.g. {@code "device1 rdf:type CoolingOnlyDevice"})
     * @param ontologyAxiom      the ontology axiom needed for inference
     *                           (e.g. {@code "CoolingOnlyDevice subclassOf HVACDevice"})
     * @param inferredFact       the fact the reasoner must infer
     *                           (e.g. {@code "device1 hasCapability some TemperatureControl"})
     * @param expectedVerdict    expected verdict from the OWL claim verifier
     *                           ({@code "supported"} / {@code "contradicted"} /
     *                           {@code "unknown"} / {@code "out_of_scope"})
     * @param requiredCapability the capability required by the contract, if any
     *                           (used for capability entailment cases)
     * @param targetEntity       the target entity IRI in the case
     *                           (e.g. {@code "http://example.org#device1"})
     * @param lookupOnlyFails    whether a non-reasoning (lookup-only) baseline
     *                           would fail to verify this case (true by default
     *                           per the spec: each case demonstrates that OWL
     *                           reasoning is necessary)
     */
    public record OwlBenefitTestCase(
        String id,
        String category,
        String description,
        String assertedFact,
        String ontologyAxiom,
        String inferredFact,
        String expectedVerdict,
        String requiredCapability,
        String targetEntity,
        boolean lookupOnlyFails
    ) {
        public OwlBenefitTestCase {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("OwlBenefitTestCase.id must not be blank");
            }
            if (category == null) category = "unknown";
            if (description == null) description = "";
            if (assertedFact == null) assertedFact = "";
            if (ontologyAxiom == null) ontologyAxiom = "";
            if (inferredFact == null) inferredFact = "";
            if (expectedVerdict == null) expectedVerdict = "supported";
            if (requiredCapability == null) requiredCapability = "";
            if (targetEntity == null) targetEntity = "";
        }
    }
}
