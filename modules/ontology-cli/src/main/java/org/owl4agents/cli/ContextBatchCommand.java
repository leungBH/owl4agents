package org.owl4agents.cli;

import org.owl4agents.benchmark.BenchmarkQuestionSetValidator;
import org.owl4agents.benchmark.ContextBatchService;
import org.owl4agents.benchmark.ContextBatchService.ContextBatchEntry;
import org.owl4agents.benchmark.ContextBatchService.ContextBatchResult;
import org.owl4agents.core.util.GsonFactory;
import org.owl4agents.validation.ClaimWorkflowService;
import org.owl4agents.validation.EvidenceContextBuilder;

import com.google.gson.Gson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI subcommand: context-batch
 * Processes a JSONL question set, builds per-question EvidenceContext
 * with character-based truncation metadata.
 */
@Command(
    name = "context-batch",
    description = "Build per-question evidence context from a JSONL question set."
)
public class ContextBatchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "JSONL question set file path")
    private String questionSetPath;

    @Option(names = {"--ontology"}, required = true, description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--max-context-tokens"}, description = "Maximum context tokens budget (0 = no truncation)")
    private int maxContextTokens = 0;

    @Option(names = {"--out"}, description = "Output file path (default: stdout)")
    private String outputPath;

    @Option(names = {"--workspace"}, description = "Workspace name (default: 'default')")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON instead of JSONL")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        // Check question set exists
        if (!Files.exists(Path.of(questionSetPath))) {
            System.err.println("Question set not found: " + questionSetPath);
            return 1;
        }

        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        ClaimWorkflowService workflowService = factory.getClaimWorkflowService();
        EvidenceContextBuilder contextBuilder = factory.getEvidenceContextBuilder();
        BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
        ContextBatchService batchService = new ContextBatchService(workflowService, contextBuilder, validator);

        // Process batch
        ContextBatchResult result = batchService.processBatch(questionSetPath, ontologyId, maxContextTokens);

        // Format output
        String output;
        Gson gson = GsonFactory.builder().setPrettyPrinting().create();
        if (jsonOutput) {
            output = gson.toJson(result);
        } else {
            // JSONL format: one JSON line per entry
            StringBuilder sb = new StringBuilder();
            Gson compactGson = GsonFactory.createGson();
            for (ContextBatchEntry entry : result.entries()) {
                sb.append(compactGson.toJson(entry)).append("\n");
            }
            // Errors as separate lines
            for (String error : result.errors()) {
                sb.append(compactGson.toJson(Map.of("type", "error", "message", error))).append("\n");
            }
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