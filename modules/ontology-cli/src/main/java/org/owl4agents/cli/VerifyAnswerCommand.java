package org.owl4agents.cli;

import org.owl4agents.validation.*;
import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * CLI command for v0.5 batch claim verification.
 * Verifies a batch of structured claims against an ontology and returns
 * an answer verification report with aggregate status.
 */
@Command(name = "verify-answer", description = "Verify a batch of structured claims against an ontology.")
public class VerifyAnswerCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--claims"}, required = true, description = "Claims batch JSON string or path to JSON file")
    private String claimsInput;

    @Option(names = {"--out"}, description = "Write report JSON to file instead of stdout")
    private String outputPath;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // Parse claims batch from JSON string or file
        String json = claimsInput;
        if (!claimsInput.trim().startsWith("{")) {
            try {
                json = java.nio.file.Files.readString(java.nio.file.Path.of(claimsInput));
            } catch (Exception e) {
                if (jsonOutput) {
                    System.out.println(gson.toJson(Map.of(
                        "aggregateStatus", "invalid_input",
                        "diagnostics", Map.of("input", "Failed to read claims file: " + claimsInput)
                    )));
                } else {
                    System.err.println("Error: Failed to read claims file: " + claimsInput);
                }
                return 1;
            }
        }

        Map<String, Object> map;
        try {
            map = gson.fromJson(json, MAP_TYPE);
        } catch (Exception e) {
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "aggregateStatus", "invalid_input",
                    "diagnostics", Map.of("input", "Failed to parse JSON: " + e.getMessage())
                )));
            } else {
                System.err.println("Error: Failed to parse claims JSON: " + e.getMessage());
            }
            return 1;
        }

        if (map == null) {
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "aggregateStatus", "invalid_input",
                    "diagnostics", Map.of("input", "JSON parsing produced null — input may be empty or malformed.")
                )));
            } else {
                System.err.println("Error: JSON parsing produced null — input may be empty or malformed.");
            }
            return 1;
        }

        // Validate the batch
        ClaimBatchValidator validator = new ClaimBatchValidator();
        ClaimBatchValidator.BatchValidationResult validationResult = validator.validateMap(map);

        if (!validationResult.isSuccess()) {
            ClaimBatchValidator.BatchValidationResult.Error errorResult =
                (ClaimBatchValidator.BatchValidationResult.Error) validationResult;
            if (jsonOutput) {
                Map<String, Object> errorJson = new java.util.LinkedHashMap<>();
                errorJson.put("aggregateStatus", errorResult.aggregateStatus().jsonName());
                errorJson.put("diagnostics", errorResult.diagnostics().stream()
                    .map(d -> Map.of("field", d.field(), "reason", d.reason()))
                    .toList());
                System.out.println(gson.toJson(errorJson));
            } else {
                System.err.println("Error: " + errorResult.aggregateStatus().jsonName());
                for (ClaimBatchValidator.FieldDiagnostic diag : errorResult.diagnostics()) {
                    System.err.println("  " + diag.field() + ": " + diag.reason());
                }
            }
            return 1;
        }

        ClaimBatchInput batch = ((ClaimBatchValidator.BatchValidationResult.Success) validationResult).batch();

        // Verify the batch
        ServiceResult<AnswerVerificationReport> result =
            factory.getClaimWorkflowService().verifyBatch(batch, ontologyId);

        if (result.isSuccess()) {
            AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) result).data();
            String reportJson = gson.toJson(report);

            // Output: write to file (--out) or stdout
            if (outputPath != null && !outputPath.isBlank()) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(outputPath), reportJson);
                    System.out.println("Report written to " + outputPath
                        + " — aggregate status: " + report.aggregateStatus().jsonName());
                } catch (Exception e) {
                    System.err.println("Error: Cannot write report to " + outputPath + ": " + e.getMessage());
                    return 1;
                }
            } else if (jsonOutput) {
                System.out.println(reportJson);
            } else {
                System.out.println("Answer verification for ontology '" + ontologyId + "':");
                System.out.println("  Answer ID: " + report.answerId());
                System.out.println("  Aggregate status: " + report.aggregateStatus().jsonName());
                for (ClaimWorkflowResult cr : report.claimResults()) {
                    System.out.println("  Claim " + cr.claimId() + ": "
                        + cr.claimType().jsonName() + " → " + cr.verdict().jsonName()
                        + (cr.required() ? " (required)" : " (optional)")
                        + (cr.unknownReason().isPresent() ? " reason: " + cr.unknownReason().get() : ""));
                    System.out.println("    Evidence items: " + cr.evidence().size());
                }
                if (report.summary().isPresent()) {
                    AnswerVerificationReport.VerdictSummary s = report.summary().get();
                    System.out.println("  Summary: supported=" + s.supportedCount()
                        + " contradicted=" + s.contradictedCount()
                        + " unknown=" + s.unknownCount()
                        + " out_of_scope=" + s.outOfScopeCount());
                }
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<AnswerVerificationReport>) result).error();
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "error", error.code().code(),
                    "message", error.message()
                )));
            } else {
                System.err.println("Error: " + error.code().code() + " - " + error.message());
            }
            return 1;
        }
    }
}