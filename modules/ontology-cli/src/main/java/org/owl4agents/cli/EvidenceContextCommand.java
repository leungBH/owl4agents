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
 * CLI command for v0.5 evidence context generation.
 * Supports two input modes:
 *   Primary:  evidence-context <ontologyId> --claims <file>  — verify claims then build context (one-step)
 *   Convenience: evidence-context --report <json-or-file>    — build context from a pre-generated report
 * Both modes produce the same output format.
 */
@Command(name = "evidence-context",
    description = "Build compact evidence context from claim verification results for LLM agent prompts.")
public class EvidenceContextCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Ontology ID (required when using --claims)")
    private String ontologyId;

    @Option(names = {"--claims"}, description = "Claims batch JSON string or path (primary mode: verifies claims then builds context)")
    private String claimsInput;

    @Option(names = {"--report"}, description = "Answer verification report JSON or file path (convenience mode: builds context from existing report)")
    private String reportInput;

    @Option(names = {"--max-context-tokens"}, description = "Maximum context tokens budget (0 = no truncation)")
    private int maxContextTokens = 0;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    @Override
    public Integer call() {
        // Validate input mode: either --claims+ontologyId or --report must be provided
        if (claimsInput != null && reportInput != null) {
            return errorExit("Cannot specify both --claims and --report. Choose one input mode.", "INVALID_INPUT");
        }
        if (claimsInput == null && reportInput == null) {
            return errorExit("Either --claims (with ontology ID) or --report is required.", "INVALID_INPUT");
        }
        if (claimsInput != null && ontologyId == null) {
            return errorExit("Ontology ID is required when using --claims mode.", "INVALID_INPUT");
        }

        // Validate maxContextTokens
        if (maxContextTokens < 0) {
            return errorExit("maxContextTokens must be >= 0, got: " + maxContextTokens, "INVALID_INPUT");
        }

        AnswerVerificationReport report;

        if (claimsInput != null) {
            // Primary mode: verify claims then build context
            report = verifyClaimsAndBuildReport();
            if (report == null) return 1; // error already printed
        } else {
            // Convenience mode: parse existing report
            report = parseReport();
            if (report == null) return 1; // error already printed
        }

        // Build evidence context
        EvidenceContextBuilder builder = new EvidenceContextBuilder();
        EvidenceContext context = builder.buildContext(report, maxContextTokens);

        if (jsonOutput) {
            System.out.println(gson.toJson(context));
        } else {
            System.out.println("Evidence context for answer '" + context.answerId() + "':");
            System.out.println("  Status: " + context.status().jsonName());
            System.out.println("  Claims: " + context.claims().size());
            System.out.println("  Omitted claims: " + context.omittedClaimCount());
            for (EvidenceContext.ClaimContextEntry entry : context.claims()) {
                System.out.println("  Claim " + entry.id() + ": " + entry.verdict().jsonName()
                    + " — " + entry.claimText()
                    + " (evidence: " + entry.evidence().size()
                    + ", omitted: " + entry.omittedEvidenceCount() + ")");
            }
            System.out.println("  Agent instructions:");
            for (String instruction : context.agentInstructions()) {
                System.out.println("    - " + instruction);
            }
        }
        return 0;
    }

    /**
     * Primary mode: verify claims batch against ontology, return the report.
     * Returns null on error (error already printed to stdout/stderr).
     */
    private AnswerVerificationReport verifyClaimsAndBuildReport() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // Parse claims batch from JSON string or file
        String json = claimsInput;
        if (!claimsInput.trim().startsWith("{")) {
            try {
                json = java.nio.file.Files.readString(java.nio.file.Path.of(claimsInput));
            } catch (Exception e) {
                return null; // error already printed by errorExit
            }
        }

        Map<String, Object> map;
        try {
            map = gson.fromJson(json, MAP_TYPE);
        } catch (Exception e) {
            errorExit("Failed to parse claims JSON: " + e.getMessage(), "INVALID_INPUT");
            return null;
        }

        if (map == null) {
            errorExit("Claims JSON parsing produced null.", "INVALID_INPUT");
            return null;
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
            return null;
        }

        ClaimBatchInput batch = ((ClaimBatchValidator.BatchValidationResult.Success) validationResult).batch();

        // Verify the batch
        ServiceResult<AnswerVerificationReport> result =
            factory.getClaimWorkflowService().verifyBatch(batch, ontologyId);

        if (!result.isSuccess()) {
            var error = ((ServiceResult.Error<AnswerVerificationReport>) result).error();
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "error", error.code().code(),
                    "message", error.message()
                )));
            } else {
                System.err.println("Error: " + error.code().code() + " - " + error.message());
            }
            return null;
        }

        return ((ServiceResult.Success<AnswerVerificationReport>) result).data();
    }

    /**
     * Convenience mode: parse an existing report from JSON string or file.
     * Returns null on error (error already printed to stdout/stderr).
     */
    private AnswerVerificationReport parseReport() {
        String json = reportInput;
        if (!reportInput.trim().startsWith("{")) {
            try {
                json = java.nio.file.Files.readString(java.nio.file.Path.of(reportInput));
            } catch (Exception e) {
                errorExit("Failed to read report file: " + reportInput, "INVALID_INPUT");
                return null;
            }
        }

        AnswerVerificationReport report;
        try {
            report = gson.fromJson(json, AnswerVerificationReport.class);
        } catch (Exception e) {
            errorExit("Failed to parse report JSON: " + e.getMessage(), "INVALID_INPUT");
            return null;
        }

        if (report == null) {
            errorExit("Report JSON parsing produced null.", "INVALID_INPUT");
            return null;
        }

        return report;
    }

    /**
     * Print an error message and return exit code 1.
     */
    private int errorExit(String message, String code) {
        if (jsonOutput) {
            System.out.println(gson.toJson(Map.of("error", code, "message", message)));
        } else {
            System.err.println("Error: " + message);
        }
        return 1;
    }
}