package org.owl4agents.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;

/**
 * Generates Markdown and JSON benchmark reports from result JSONL
 * without re-executing benchmarks. Both formats contain:
 * experiment name, config summary, per-question verdicts, accuracy,
 * false support rate (separate section), unresolved rate (separate section, NOT combined),
 * verification coverage, unknown rate, out-of-scope rate,
 * 4x4 confusion matrix, reasoner comparison, disagreement classification (5 columns),
 * and reproducibility metadata (including commitHash, questionSetName,
 * ontologyIds, totalExecutionDuration).
 *
 * Static reasoner version mapping derived from Gradle dependencies.
 */
public class BenchmarkReportGenerator {

    /** Static reasoner version mapping from Gradle dependencies. */
    public static final Map<String, String> REASONER_VERSIONS = Map.of(
        "hermit", "1.4.5.519",
        "elk", "0.6.0",
        "openllet", "2.6.5"
    );

    /** Current owl4agents version — matches the Gradle project version. */
    private static final String OWL4AGENTS_VERSION = "0.6.0";

    /** Reasoner disagreement classification. */
    public enum DisagreementClassification {
        REASONER_DIFFERENCE,
        BOTH_WRONG_VS_EXPECTED
    }

    /** Structured disagreement entry for per-question report. */
    public record DisagreementEntry(
        String questionId,
        String reasonerAVerdict,
        String reasonerBVerdict,
        String expectedVerdict,
        String classification
    ) {}

    private final Gson gson = GsonFactory.builder().setPrettyPrinting().create();

    /**
     * Generate a Markdown report from benchmark results.
     */
    public String generateMarkdownReport(ExperimentConfig config,
                                          List<BenchmarkResultLine> results,
                                          BenchmarkResultSummary summary,
                                          ConfusionMatrix confusionMatrix) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("# Benchmark Report: ").append(config.name()).append("\n\n");
        sb.append("**Description**: ").append(config.description()).append("\n\n");

        // Config summary
        sb.append("## Configuration\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| Ontologies | ").append(config.ontologyIds()).append(" |\n");
        sb.append("| Reasoners | ").append(config.reasoners()).append(" |\n");
        sb.append("| Timeout | ").append(config.timeoutPerQuestion()).append("s |\n");
        sb.append("| Hallucination Detection | ").append(config.hallucinationDetection()).append(" |\n");
        sb.append("| Edge Case Policy | ").append(config.edgeCasePolicy()).append(" |\n\n");

        // Summary metrics
        sb.append("## Summary Metrics\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Total Questions | ").append(summary.totalQuestions()).append(" |\n");
        sb.append("| Accuracy | ").append(formatPercent(summary.accuracy())).append(" |\n");
        sb.append("| Verification Coverage | ").append(formatPercent(summary.verificationCoverage())).append(" |\n");
        sb.append("| Unknown Rate | ").append(formatPercent(summary.unknownRate())).append(" |\n");
        sb.append("| Out-of-Scope Rate | ").append(formatPercent(summary.outOfScopeRate())).append(" |\n\n");

        // False support rate (SEPARATE section)
        sb.append("## False Support Rate\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| False Support Rate | ").append(formatPercent(summary.falseSupportRate())).append(" |\n");
        sb.append("| False Supported Count | ").append(summary.falseSupportedCount()).append(" |\n\n");

        // Unresolved rate (SEPARATE section, NOT combined)
        sb.append("## Unresolved Rate\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Unresolved Rate | ").append(formatPercent(summary.unresolvedRate())).append(" |\n");
        sb.append("| False Unknown Count | ").append(summary.falseUnknownCount()).append(" |\n\n");

        // Per-verdict counts
        sb.append("## Per-Verdict Counts\n\n");
        sb.append("| Verdict | Count |\n|---|---|\n");
        for (Map.Entry<Verdict, Integer> entry : summary.perVerdictCounts().entrySet()) {
            sb.append("| ").append(entry.getKey().jsonName()).append(" | ").append(entry.getValue()).append(" |\n");
        }
        sb.append("\n");

        // 4x4 confusion matrix
        sb.append("## Confusion Matrix\n\n");
        sb.append("| Expected | Actual: out_of_scope | supported | contradicted | unknown |\n|---|---|---|---|---|\n");
        for (Verdict expected : Verdict.values()) {
            sb.append("| ").append(expected.jsonName()).append(" | ");
            for (Verdict actual : Verdict.values()) {
                sb.append(confusionMatrix.matrix().get(expected).get(actual)).append(" | ");
            }
            sb.append("\n");
        }
        sb.append("\n");

        // Reasoner comparison (when multi-reasoner)
        if (config.reasoners().size() > 1) {
            sb.append("## Reasoner Comparison\n\n");
            sb.append("| Reasoner | Version | Total Time (ms) |\n|---|---|---|\n");
            for (Map.Entry<String, Long> entry : summary.perReasonerTiming().entrySet()) {
                String version = REASONER_VERSIONS.getOrDefault(entry.getKey(), "unknown");
                sb.append("| ").append(entry.getKey()).append(" | ").append(version)
                    .append(" | ").append(entry.getValue()).append(" |\n");
            }
            sb.append("\n");

            // Disagreement classification — 5 columns per spec
            List<DisagreementEntry> disagreements = findDisagreements(results, config.reasoners());
            if (!disagreements.isEmpty()) {
                sb.append("### Reasoner Disagreements\n\n");
                sb.append("| QuestionId | Reasoner A | Reasoner B | Expected | Classification |\n|---|---|---|---|---|\n");
                for (DisagreementEntry d : disagreements) {
                    sb.append("| ").append(d.questionId()).append(" | ")
                        .append(d.reasonerAVerdict()).append(" | ")
                        .append(d.reasonerBVerdict()).append(" | ")
                        .append(d.expectedVerdict()).append(" | ")
                        .append(d.classification()).append(" |\n");
                }
                sb.append("\n");
            }
        }

        // Per-question verdicts table — 9 columns per spec
        sb.append("## Per-Question Verdicts\n\n");
        sb.append("| QuestionId | Ontology | Reasoner | Expected | Actual | Match | Time (ms) | ReviewStatus | Error |\n|---|---|---|---|---|---|---|---|---|\n");
        for (BenchmarkResultLine line : results) {
            String reviewStatusStr = line.reviewStatus().orElse("—");
            String errorStr = line.error().orElse("—");
            sb.append("| ").append(line.questionId()).append(" | ")
                .append(line.ontologyId()).append(" | ")
                .append(line.reasoner()).append(" | ")
                .append(line.expectedVerdict().jsonName()).append(" | ")
                .append(line.actualVerdict().jsonName()).append(" | ")
                .append(line.verdictMatch()).append(" | ")
                .append(line.elapsedMs()).append(" | ")
                .append(reviewStatusStr).append(" | ")
                .append(errorStr).append(" |\n");
        }
        sb.append("\n");

        // Reproducibility metadata — includes all spec-required fields
        sb.append("## Reproducibility\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| owl4agents version | ").append(getVersion()).append(" |\n");
        sb.append("| Commit Hash | ").append(getCommitHash()).append(" |\n");
        sb.append("| Java version | ").append(System.getProperty("java.version")).append(" |\n");
        sb.append("| OS | ").append(System.getProperty("os.name")).append(" |\n");
        sb.append("| Timestamp | ").append(java.time.Instant.now()).append(" |\n");
        sb.append("| Config | ").append(config.name()).append(" |\n");
        sb.append("| Question Set | ").append(extractFileName(config.questionSetPath())).append(" |\n");
        sb.append("| Ontology IDs | ").append(config.ontologyIds()).append(" |\n");
        sb.append("| Total Execution Duration | ").append(formatDuration(computeTotalDuration(summary))).append(" |\n");
        for (Map.Entry<String, String> entry : REASONER_VERSIONS.entrySet()) {
            sb.append("| ").append(entry.getKey()).append(" version | ").append(entry.getValue()).append(" |\n");
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Generate a JSON report from benchmark results.
     */
    public String generateJsonReport(ExperimentConfig config,
                                      List<BenchmarkResultLine> results,
                                      BenchmarkResultSummary summary,
                                      ConfusionMatrix confusionMatrix) {
        Map<String, Object> report = new LinkedHashMap<>();

        report.put("experimentName", config.name());
        report.put("description", config.description());
        report.put("config", Map.of(
            "ontologyIds", config.ontologyIds(),
            "reasoners", config.reasoners(),
            "timeoutPerQuestion", config.timeoutPerQuestion(),
            "hallucinationDetection", config.hallucinationDetection(),
            "edgeCasePolicy", config.edgeCasePolicy().name()
        ));

        // Summary metrics
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalQuestions", summary.totalQuestions());
        metrics.put("accuracy", summary.accuracy());
        metrics.put("verificationCoverage", summary.verificationCoverage());
        metrics.put("unknownRate", summary.unknownRate());
        metrics.put("outOfScopeRate", summary.outOfScopeRate());
        metrics.put("falseSupportRate", summary.falseSupportRate());
        metrics.put("falseSupportedCount", summary.falseSupportedCount());
        metrics.put("unresolvedRate", summary.unresolvedRate());
        metrics.put("falseUnknownCount", summary.falseUnknownCount());
        report.put("metrics", metrics);

        // Per-verdict counts
        Map<String, Integer> perVerdict = new LinkedHashMap<>();
        for (Map.Entry<Verdict, Integer> entry : summary.perVerdictCounts().entrySet()) {
            perVerdict.put(entry.getKey().jsonName(), entry.getValue());
        }
        metrics.put("perVerdictCounts", perVerdict);

        // 4x4 confusion matrix (nested JSON object)
        Map<String, Object> matrixMap = new LinkedHashMap<>();
        for (Map.Entry<Verdict, Map<Verdict, Integer>> rowEntry : confusionMatrix.matrix().entrySet()) {
            Map<String, Integer> inner = new LinkedHashMap<>();
            for (Map.Entry<Verdict, Integer> colEntry : rowEntry.getValue().entrySet()) {
                inner.put(colEntry.getKey().jsonName(), colEntry.getValue());
            }
            matrixMap.put(rowEntry.getKey().jsonName(), inner);
        }
        report.put("confusionMatrix", matrixMap);

        // Per-question verdicts (verdictMatch as JSON boolean)
        List<Map<String, Object>> perQuestion = new ArrayList<>();
        for (BenchmarkResultLine line : results) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("questionId", line.questionId());
            q.put("ontologyId", line.ontologyId());
            q.put("reasoner", line.reasoner());
            q.put("expectedVerdict", line.expectedVerdict().jsonName());
            q.put("actualVerdict", line.actualVerdict().jsonName());
            q.put("match", line.verdictMatch()); // JSON boolean, NOT string
            q.put("elapsedMs", line.elapsedMs());
            q.put("reviewStatus", line.reviewStatus().orElse(null));
            q.put("edgeCase", line.edgeCase());
            line.error().ifPresent(e -> q.put("error", e));
            perQuestion.add(q);
        }
        report.put("perQuestionVerdicts", perQuestion);

        // Reasoner comparison
        if (config.reasoners().size() > 1) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            Map<String, Object> versions = new LinkedHashMap<>();
            for (String reasoner : config.reasoners()) {
                versions.put(reasoner, REASONER_VERSIONS.getOrDefault(reasoner, "unknown"));
            }
            comparison.put("versions", versions);
            comparison.put("timing", summary.perReasonerTiming());
            // Disagreements as structured objects
            List<DisagreementEntry> disagreements = findDisagreements(results, config.reasoners());
            List<Map<String, String>> disagreementMaps = new ArrayList<>();
            for (DisagreementEntry d : disagreements) {
                Map<String, String> dm = new LinkedHashMap<>();
                dm.put("questionId", d.questionId());
                dm.put("reasonerAVerdict", d.reasonerAVerdict());
                dm.put("reasonerBVerdict", d.reasonerBVerdict());
                dm.put("expectedVerdict", d.expectedVerdict());
                dm.put("classification", d.classification());
                disagreementMaps.add(dm);
            }
            comparison.put("disagreements", disagreementMaps);
            report.put("reasonerComparison", comparison);
        }

        // Reproducibility — includes all spec-required fields
        Map<String, Object> reproducibility = new LinkedHashMap<>();
        reproducibility.put("owl4agentsVersion", getVersion());
        reproducibility.put("commitHash", getCommitHash());
        reproducibility.put("javaVersion", System.getProperty("java.version"));
        reproducibility.put("os", System.getProperty("os.name"));
        reproducibility.put("timestamp", java.time.Instant.now().toString());
        reproducibility.put("configFileName", config.name());
        reproducibility.put("questionSetName", extractFileName(config.questionSetPath()));
        reproducibility.put("ontologyIds", config.ontologyIds());
        reproducibility.put("reasonerVersions", REASONER_VERSIONS);
        reproducibility.put("totalExecutionDurationMs", computeTotalDuration(summary));
        report.put("reproducibility", reproducibility);

        return gson.toJson(report);
    }

    /**
     * Find reasoner disagreements with full 5-column detail per spec.
     * Each entry includes: questionId, reasoner A verdict, reasoner B verdict,
     * expected verdict, classification label.
     */
    List<DisagreementEntry> findDisagreements(List<BenchmarkResultLine> results, List<String> reasoners) {
        // Group by questionId, find where reasoners differ
        Map<String, List<BenchmarkResultLine>> byQuestion = new LinkedHashMap<>();
        for (BenchmarkResultLine line : results) {
            byQuestion.computeIfAbsent(line.questionId(), k -> new ArrayList<>()).add(line);
        }

        List<DisagreementEntry> disagreements = new ArrayList<>();
        for (Map.Entry<String, List<BenchmarkResultLine>> entry : byQuestion.entrySet()) {
            List<BenchmarkResultLine> lines = entry.getValue();
            if (lines.size() < 2) continue;

            // Check if verdicts differ across reasoners
            Verdict first = lines.get(0).actualVerdict();
            boolean differ = lines.stream().anyMatch(l -> l.actualVerdict() != first);

            if (differ) {
                String questionId = entry.getKey();
                boolean atLeastOneMatchesExpected = lines.stream()
                    .anyMatch(l -> l.verdictMatch());
                String classification = atLeastOneMatchesExpected
                    ? "reasoner_difference" : "both_wrong_vs_expected";
                disagreements.add(new DisagreementEntry(
                    questionId,
                    lines.get(0).actualVerdict().jsonName(),
                    lines.get(1).actualVerdict().jsonName(),
                    lines.get(0).expectedVerdict().jsonName(),
                    classification
                ));
            }
        }

        return disagreements;
    }

    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes + "m " + remainingSeconds + "s";
    }

    private long computeTotalDuration(BenchmarkResultSummary summary) {
        long total = 0;
        for (Long ms : summary.perReasonerTiming().values()) {
            total += ms;
        }
        return total;
    }

    private String extractFileName(String path) {
        if (path == null) return "unknown";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String getVersion() {
        return OWL4AGENTS_VERSION;
    }

    private String getCommitHash() {
        // Fallback: return "unknown" when git info is not available at runtime
        // In CI/CD environments, this can be overridden via environment variable
        String envHash = System.getenv("OWL4AGENTS_COMMIT_HASH");
        if (envHash != null && !envHash.isBlank()) return envHash;
        return "unknown";
    }
}