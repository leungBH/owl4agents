package org.owl4agents.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Parses experiment configuration YAML files into ExperimentConfig records.
 *
 * Validates required fields, applies defaults, and rejects unsupported fields
 * (e.g. repeatCount → INVALID_EXPERIMENT_CONFIG).
 *
 * Content-level validation of question set files is the responsibility of the
 * benchmark runner at execution time, not the config parser. The parser only
 * checks that the question set file exists, is readable, and the first line
 * is valid JSON.
 */
@SuppressWarnings("unchecked")
public class ExperimentConfigParser {

    /** Structured error from config parsing. */
    public record ConfigError(String code, String message, String diagnostic) {}

    /** Result of parsing: either a valid config or a structured error. */
    public record ParseResult(ExperimentConfig config, ConfigError error) {
        public boolean isSuccess() { return config != null && error == null; }
    }

    private static final String REPEATCOUNT_NOT_SUPPORTED =
        "repeatCount is not supported; benchmark reproducibility relies on deterministic outputs, not statistical averaging";

    /**
     * Parse a YAML config file into an ExperimentConfig.
     * Returns ParseResult with either a valid config or a ConfigError.
     */
    public ParseResult parse(String configPath) {
        Path path = Path.of(configPath);

        // 1. File existence check
        if (!Files.exists(path)) {
            return new ParseResult(null, new ConfigError(
                "CONFIG_NOT_FOUND",
                "Config file not found: " + configPath,
                "Verify the path is correct and the file exists"));
        }

        // 2. YAML parse
        Load yamlLoad = new Load(LoadSettings.builder().build());
        Object raw;
        try {
            String content = Files.readString(path);
            raw = yamlLoad.loadFromString(content);
        } catch (IOException e) {
            return new ParseResult(null, new ConfigError(
                "INVALID_EXPERIMENT_CONFIG",
                "Cannot read config file: " + e.getMessage(),
                "Verify file permissions"));
        } catch (Exception e) {
            return new ParseResult(null, new ConfigError(
                "INVALID_EXPERIMENT_CONFIG",
                "Malformed YAML: " + e.getMessage(),
                "Fix YAML syntax errors in the config file"));
        }

        if (!(raw instanceof Map)) {
            return new ParseResult(null, new ConfigError(
                "INVALID_EXPERIMENT_CONFIG",
                "YAML root must be a mapping, got: " + typeName(raw),
                "Config must be a YAML mapping with required fields"));
        }

        Map<String, Object> map = (Map<String, Object>) raw;

        // 3. Reject repeatCount
        if (map.containsKey("repeatCount")) {
            return new ParseResult(null, new ConfigError(
                "INVALID_EXPERIMENT_CONFIG",
                REPEATCOUNT_NOT_SUPPORTED,
                REPEATCOUNT_NOT_SUPPORTED));
        }

        // 4. Validate required fields
        String name = requireString(map, "name");
        String description = requireString(map, "description");
        String questionSetPath = requireString(map, "questionSetPath");
        String outputPath = requireString(map, "outputPath");
        List<String> ontologyIds = requireList(map, "ontologyIds");
        List<String> reasoners = requireList(map, "reasoners");

        if (name == null) return missingField("name");
        if (description == null) return missingField("description");
        if (questionSetPath == null) return missingField("questionSetPath");
        if (outputPath == null) return missingField("outputPath");
        if (ontologyIds == null) return invalidField("ontologyIds", "must be an array of strings");
        if (reasoners == null) return invalidField("reasoners", "must be an array of strings");

        // 5. Validate optional field types
        int timeoutPerQuestion = ExperimentConfig.DEFAULT_TIMEOUT_PER_QUESTION;
        if (map.containsKey("timeoutPerQuestion")) {
            Object val = map.get("timeoutPerQuestion");
            if (!(val instanceof Integer) || ((Integer) val) <= 0) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "timeoutPerQuestion must be a positive integer",
                    "Use a positive integer value for timeoutPerQuestion"));
            }
            timeoutPerQuestion = (Integer) val;
        }

        boolean hallucinationDetection = ExperimentConfig.DEFAULT_HALLUCINATION_DETECTION;
        if (map.containsKey("hallucinationDetection")) {
            Object val = map.get("hallucinationDetection");
            if (!(val instanceof Boolean)) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "hallucinationDetection must be a boolean (true/false)",
                    "Use true or false for hallucinationDetection, not: " + typeName(val)));
            }
            hallucinationDetection = (Boolean) val;
        }

        ExperimentConfig.EdgeCasePolicy edgeCasePolicy = ExperimentConfig.DEFAULT_EDGE_CASE_POLICY;
        if (map.containsKey("edgeCasePolicy")) {
            Object val = map.get("edgeCasePolicy");
            if (!(val instanceof String)) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "edgeCasePolicy must be 'exclude' or 'include'",
                    "Use exclude or include for edgeCasePolicy"));
            }
            String policyStr = (String) val;
            try {
                edgeCasePolicy = ExperimentConfig.EdgeCasePolicy.valueOf(policyStr);
            } catch (IllegalArgumentException e) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "Invalid edgeCasePolicy: " + policyStr + " (must be exclude or include)",
                    "Use exclude or include for edgeCasePolicy"));
            }
        }

        OptionalInt maxContextTokens = OptionalInt.empty();
        if (map.containsKey("maxContextTokens")) {
            Object val = map.get("maxContextTokens");
            if (!(val instanceof Integer) || ((Integer) val) <= 0) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "maxContextTokens must be a positive integer",
                    "Use a positive integer for maxContextTokens"));
            }
            maxContextTokens = OptionalInt.of((Integer) val);
        }

        ExperimentConfig.ReportFormat reportFormat = ExperimentConfig.DEFAULT_REPORT_FORMAT;
        if (map.containsKey("reportFormat")) {
            Object val = map.get("reportFormat");
            if (!(val instanceof String)) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "reportFormat must be 'markdown', 'json', or 'both'",
                    "Use markdown, json, or both for reportFormat"));
            }
            String formatStr = (String) val;
            try {
                reportFormat = ExperimentConfig.ReportFormat.valueOf(formatStr);
            } catch (IllegalArgumentException e) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "Invalid reportFormat: " + formatStr + " (must be markdown, json, or both)",
                    "Use markdown, json, or both for reportFormat"));
            }
        }

        Optional<String> reportOutputPath = Optional.empty();
        if (map.containsKey("reportOutputPath")) {
            Object val = map.get("reportOutputPath");
            if (!(val instanceof String)) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "reportOutputPath must be a string",
                    "Use a string path for reportOutputPath"));
            }
            reportOutputPath = Optional.of((String) val);
        }

        // 6. Question set format check (first line only)
        ParseResult formatCheck = checkQuestionSetFormat(questionSetPath);
        if (!formatCheck.isSuccess()) {
            return formatCheck;
        }

        return new ParseResult(new ExperimentConfig(
            name,
            description,
            ontologyIds,
            questionSetPath,
            reasoners,
            outputPath,
            timeoutPerQuestion,
            hallucinationDetection,
            edgeCasePolicy,
            maxContextTokens,
            reportFormat,
            reportOutputPath
        ), null);
    }

    /**
     * Check that question set file exists, is readable, and first line is valid JSON.
     * This is a format confirmation only, NOT full content validation.
     */
    private ParseResult checkQuestionSetFormat(String questionSetPath) {
        Path qsPath = Path.of(questionSetPath);

        if (!Files.exists(qsPath)) {
            return new ParseResult(null, new ConfigError(
                "QUESTION_SET_NOT_FOUND",
                "Question set file not found: " + questionSetPath,
                "Verify the questionSetPath is correct"));
        }

        try {
            String firstLine = Files.lines(qsPath).findFirst().orElse(null);
            if (firstLine == null || firstLine.isBlank()) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "Question set file is empty or has no valid first line: " + questionSetPath,
                    "Question set must be a non-empty JSONL file"));
            }
            // Validate first line is parseable JSON
            try {
                com.google.gson.JsonParser.parseString(firstLine);
            } catch (Exception e) {
                return new ParseResult(null, new ConfigError(
                    "INVALID_EXPERIMENT_CONFIG",
                    "Question set first line is not valid JSON: " + e.getMessage(),
                    "Question set must be a JSONL file with valid JSON on each line"));
            }
        } catch (IOException e) {
            return new ParseResult(null, new ConfigError(
                "INVALID_EXPERIMENT_CONFIG",
                "Cannot read question set file: " + e.getMessage(),
                "Verify file permissions for: " + questionSetPath));
        }

        return new ParseResult(new ExperimentConfig(
            "format-check-passed", "", List.of(), questionSetPath,
            List.of(), "", 30, false,
            ExperimentConfig.DEFAULT_EDGE_CASE_POLICY,
            OptionalInt.empty(),
            ExperimentConfig.DEFAULT_REPORT_FORMAT,
            Optional.empty()
        ), null);
    }

    private String requireString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof String) return (String) val;
        return null; // type mismatch → will be caught by null check
    }

    private List<String> requireList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List) {
            List<?> list = (List<?>) val;
            // Verify all elements are strings
            for (Object elem : list) {
                if (!(elem instanceof String)) return null;
            }
            return (List<String>) list;
        }
        return null; // not a list or missing
    }

    private ParseResult missingField(String field) {
        return new ParseResult(null, new ConfigError(
            "INVALID_EXPERIMENT_CONFIG",
            "Missing required field: " + field,
            "Add the required field '" + field + "' to the config"));
    }

    private ParseResult invalidField(String field, String reason) {
        return new ParseResult(null, new ConfigError(
            "INVALID_EXPERIMENT_CONFIG",
            "Invalid field " + field + ": " + reason,
            "Fix the field '" + field + "' in the config"));
    }

    private String typeName(Object val) {
        if (val == null) return "null";
        return val.getClass().getSimpleName();
    }
}