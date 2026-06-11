package org.owl4agents.cli;

import org.owl4agents.benchmark.BenchmarkResultLine;
import org.owl4agents.benchmark.BenchmarkResultReader;
import org.owl4agents.benchmark.QaEvaluationService;
import org.owl4agents.benchmark.QaEvaluationService.QaEvaluation;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI subcommand: eval-qa
 * Computes QA evaluation metrics from a benchmark result JSONL file.
 * Metrics include accuracy, false support rate, unresolved rate,
 * verification coverage, and a 4x4 confusion matrix.
 */
@Command(
    name = "eval-qa",
    description = "Compute QA evaluation metrics from benchmark result JSONL."
)
public class EvalQaCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Benchmark result JSONL file path")
    private String resultsPath;

    @Option(names = {"--out"}, description = "Output file path (default: stdout)")
    private String outputPath;

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        Path path = Path.of(resultsPath);
        if (!Files.exists(path)) {
            System.err.println("Results file not found: " + resultsPath);
            return 1;
        }

        // Read results
        BenchmarkResultReader reader = new BenchmarkResultReader();
        List<BenchmarkResultLine> results;
        try {
            results = reader.readResults(path);
        } catch (IOException e) {
            System.err.println("Cannot read results file: " + e.getMessage());
            return 1;
        }

        if (results.isEmpty()) {
            System.err.println("Results file contains no result lines (empty results).");
            return 1;
        }

        // Evaluate
        QaEvaluationService service = new QaEvaluationService();
        QaEvaluation evaluation = service.evaluate(results);

        // Format output
        Gson gson = GsonFactory.builder().setPrettyPrinting().create();
        String output = gson.toJson(evaluation);

        // Write output
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), output);
            } catch (IOException e) {
                System.err.println("Cannot write output file: " + e.getMessage());
                return 1;
            }
        } else {
            System.out.println(output);
        }

        return 0;
    }
}