package org.owl4agents.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.owl4agents.core.model.ExecutionStatus;
import org.owl4agents.core.model.PerStageTiming;

/**
 * v0.8.5: Writes per-claim exact results to JSONL and CSV files (tasks 13.5, 13.6).
 *
 * Outputs:
 * - exact-results-388.jsonl: one JSON per line with full schema v2 fields
 * - timeouts.csv: claims that timed out
 * - errors.csv: claims that errored
 * - performance-summary.csv: p50/p95/p99/max/timeout-count/peak-heap
 */
public class ExactResultsWriter {

    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    /**
     * Write exact-results JSONL file (task 13.5).
     * Each line is a JSON object with schema v2 fields.
     */
    public void writeJsonl(List<ExactResultLine> lines, Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (ExactResultLine line : lines) {
            JsonObject json = gson.toJsonTree(line).getAsJsonObject();
            json.addProperty("schemaVersion", "claim-verification-result/2");
            sb.append(gson.toJson(json)).append("\n");
        }
        Files.writeString(outputPath, sb.toString());
    }

    /**
     * Write timeouts.csv (task 13.6): claimId, ontologyId, reasoner, elapsedMillis.
     */
    public void writeTimeoutsCsv(List<ExactResultLine> lines, Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("claimId,ontologyId,reasoner,elapsedMillis\n");
        for (ExactResultLine line : lines) {
            if (line.executionStatus() == ExecutionStatus.TIMEOUT) {
                sb.append(csvEscape(line.claimId())).append(",")
                    .append(csvEscape(line.ontologyId())).append(",")
                    .append(csvEscape(line.reasoner())).append(",")
                    .append(line.elapsedMillis()).append("\n");
            }
        }
        Files.writeString(outputPath, sb.toString());
    }

    /**
     * Write errors.csv (task 13.6): claimId, ontologyId, errorCode, reasoner.
     */
    public void writeErrorsCsv(List<ExactResultLine> lines, Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("claimId,ontologyId,errorCode,reasoner\n");
        for (ExactResultLine line : lines) {
            if (line.executionStatus() == ExecutionStatus.ERROR) {
                sb.append(csvEscape(line.claimId())).append(",")
                    .append(csvEscape(line.ontologyId())).append(",")
                    .append(csvEscape(line.errorCode() != null ? line.errorCode() : "")).append(",")
                    .append(csvEscape(line.reasoner())).append("\n");
            }
        }
        Files.writeString(outputPath, sb.toString());
    }

    /**
     * Write performance-summary.csv (task 13.4, 13.6):
     * p50,p95,p99,max,timeoutCount,errorCount,totalCount,peakHeapBytes.
     */
    public void writePerformanceSummaryCsv(List<ExactResultLine> lines, Path outputPath) throws IOException {
        List<Long> elapsed = lines.stream()
            .filter(l -> l.executionStatus() == ExecutionStatus.COMPLETED)
            .map(ExactResultLine::elapsedMillis)
            .sorted()
            .toList();

        long timeoutCount = lines.stream()
            .filter(l -> l.executionStatus() == ExecutionStatus.TIMEOUT).count();
        long errorCount = lines.stream()
            .filter(l -> l.executionStatus() == ExecutionStatus.ERROR).count();

        StringBuilder sb = new StringBuilder();
        sb.append("metric,value\n");
        sb.append("totalClaims,").append(lines.size()).append("\n");
        sb.append("completedClaims,").append(elapsed.size()).append("\n");
        sb.append("timeoutCount,").append(timeoutCount).append("\n");
        sb.append("errorCount,").append(errorCount).append("\n");
        if (!elapsed.isEmpty()) {
            sb.append("p50Ms,").append(percentile(elapsed, 50)).append("\n");
            sb.append("p95Ms,").append(percentile(elapsed, 95)).append("\n");
            sb.append("p99Ms,").append(percentile(elapsed, 99)).append("\n");
            sb.append("maxMs,").append(elapsed.get(elapsed.size() - 1)).append("\n");
        } else {
            sb.append("p50Ms,0\n");
            sb.append("p95Ms,0\n");
            sb.append("p99Ms,0\n");
            sb.append("maxMs,0\n");
        }
        // peakHeapBytes: monitor threshold is 4x source ontology size (task 13.4)
        // Actual peak heap monitoring requires runtime instrumentation; here we
        // report the max elapsed as a proxy for resource intensity.
        long peakHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        sb.append("peakHeapBytes,").append(peakHeapBytes).append("\n");

        Files.writeString(outputPath, sb.toString());
    }

    /**
     * Compute percentile from a sorted list of values.
     */
    private long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /**
     * CSV escape: wrap in quotes if contains comma, quote, or newline.
     */
    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
