package org.owl4agents.cli;

import org.owl4agents.benchmark.BenchmarkResultLine;
import org.owl4agents.benchmark.BenchmarkService;
import org.owl4agents.benchmark.BenchmarkQuestionSetValidator;
import org.owl4agents.benchmark.ExperimentConfig;
import org.owl4agents.benchmark.ExperimentConfigParser;
import org.owl4agents.core.util.GsonFactory;
import org.owl4agents.validation.ClaimWorkflowService;

import com.google.gson.Gson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI subcommand: benchmark-run
 * Runs a benchmark experiment from a YAML config file,
 * producing JSONL results to stdout or a specified output file.
 */
@Command(
    name = "benchmark-run",
    description = "Run a benchmark experiment from a YAML config file."
)
public class BenchmarkRunCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "YAML config file path")
    private String configPath;

    @Option(names = {"--out"}, description = "Output file path (default: stdout)")
    private String outputPath;

    @Option(names = {"--workspace"}, description = "Workspace name (default: 'default')")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON instead of JSONL")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        ClaimWorkflowService workflowService = factory.getClaimWorkflowService();
        BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
        BenchmarkService benchmarkService = new BenchmarkService(workflowService, validator);

        // Parse config — parser expects a file path
        ExperimentConfigParser parser = new ExperimentConfigParser();
        ExperimentConfigParser.ParseResult parseResult = parser.parse(configPath);
        if (!parseResult.isSuccess()) {
            ExperimentConfigParser.ConfigError error = parseResult.error();
            System.err.println("Invalid experiment config:");
            System.err.println("  " + error.code() + ": " + error.diagnostic());
            return 1;
        }

        ExperimentConfig config = parseResult.config();

        // Run benchmark
        BenchmarkService.BenchmarkRunResult runResult = benchmarkService.run(config);

        // Format output
        String output;
        if (jsonOutput) {
            Gson gson = GsonFactory.builder().setPrettyPrinting().create();
            output = gson.toJson(runResult);
        } else {
            // JSONL format: one JSON line per result, then summary line
            StringBuilder sb = new StringBuilder();
            Gson compactGson = GsonFactory.createGson();
            for (BenchmarkResultLine line : runResult.lines()) {
                sb.append(compactGson.toJson(line)).append("\n");
            }
            sb.append(compactGson.toJson(runResult.summary())).append("\n");
            output = sb.toString();
        }

        // Write output
        if (outputPath != null) {
            try {
                Files.writeString(Path.of(outputPath), output);
            } catch (java.io.IOException e) {
                System.err.println("Cannot write output file: " + e.getMessage());
                return 1;
            }
        } else {
            System.out.print(output);
        }

        return 0;
    }
}