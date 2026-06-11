package org.owl4agents.benchmark;

import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;

/**
 * An experiment configuration for reproducible benchmark runs.
 *
 * Required fields: name, description, ontologyIds, questionSetPath, reasoners, outputPath.
 * Optional fields: timeoutPerQuestion, hallucinationDetection, edgeCasePolicy,
 *                  maxContextTokens, reportFormat, reportOutputPath.
 *
 * repeatCount is NOT supported — its presence triggers INVALID_EXPERIMENT_CONFIG.
 */
public record ExperimentConfig(
    String name,
    String description,
    List<String> ontologyIds,
    String questionSetPath,
    List<String> reasoners,
    String outputPath,
    int timeoutPerQuestion,
    boolean hallucinationDetection,
    EdgeCasePolicy edgeCasePolicy,
    OptionalInt maxContextTokens,
    ReportFormat reportFormat,
    Optional<String> reportOutputPath
) {

    /** Edge case handling policy for benchmark accuracy computation. */
    public enum EdgeCasePolicy {
        exclude,
        include
    }

    /** Report output format. */
    public enum ReportFormat {
        markdown,
        json,
        both
    }

    /** Convenience: defaults applied by ExperimentConfigParser. */
    public static final int DEFAULT_TIMEOUT_PER_QUESTION = 30;
    public static final boolean DEFAULT_HALLUCINATION_DETECTION = false;
    public static final EdgeCasePolicy DEFAULT_EDGE_CASE_POLICY = EdgeCasePolicy.exclude;
    public static final ReportFormat DEFAULT_REPORT_FORMAT = ReportFormat.markdown;
}