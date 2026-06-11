package org.owl4agents.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Reads benchmark result JSONL files, deserializing each line
 * into a BenchmarkResultLine record. Handles Verdict jsonName
 * deserialization and Optional<String> fields.
 */
public class BenchmarkResultReader {

    private static final Gson gson = GsonFactory.builder()
        .registerTypeAdapter(Verdict.class, new VerdictAdapter())
        .create();

    /**
     * Read benchmark result lines from a JSONL file.
     * Summary lines (type: "summary") are skipped.
     */
    public List<BenchmarkResultLine> readResults(Path jsonlPath) throws IOException {
        List<String> lines = Files.readAllLines(jsonlPath);
        List<BenchmarkResultLine> results = new ArrayList<>();

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            // Skip summary lines
            if (line.contains("\"type\":\"summary\"") || line.contains("\"type\": \"summary\"")) continue;

            BenchmarkResultLine resultLine = gson.fromJson(line, BenchmarkResultLine.class);
            if (resultLine != null) {
                results.add(resultLine);
            }
        }

        return results;
    }

    /**
     * Read the summary line from a JSONL file.
     * Returns null if no summary line is found.
     */
    public BenchmarkResultSummary readSummary(Path jsonlPath) throws IOException {
        List<String> lines = Files.readAllLines(jsonlPath);

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            if (line.contains("\"type\":\"summary\"") || line.contains("\"type\": \"summary\"")) {
                return gson.fromJson(line, BenchmarkResultSummary.class);
            }
        }

        return null;
    }

    // ── Gson adapters ──

    /** Deserializes Verdict using jsonName ("supported" → SUPPORTED). */
    private static class VerdictAdapter extends TypeAdapter<Verdict> {
        @Override
        public void write(JsonWriter out, Verdict value) throws IOException {
            out.value(value.jsonName());
        }

        @Override
        public Verdict read(JsonReader in) throws IOException {
            String name = in.nextString();
            return Verdict.fromJsonName(name);
        }
    }
}