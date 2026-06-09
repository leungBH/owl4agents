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
 * CLI command for v0.5 answer review.
 * Verifies a batch of claims and builds an evidence context with
 * policy-dependent handling guidance for agents.
 *
 * Returns the same factual report shape as verify-answer plus
 * policy-dependent agent-facing handling guidance.
 */
@Command(name = "review-answer", description = "Review an answer by verifying claims and building evidence context with policy guidance.")
public class ReviewAnswerCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--claims"}, required = true, description = "Claims batch JSON string or path to JSON file")
    private String claimsInput;

    @Option(names = {"--max-context-tokens"}, description = "Maximum context tokens budget (0 = no truncation)")
    private int maxContextTokens = 0;

    @Option(names = {"--policy"}, description = "Review policy: strict (default), conservative, or report-only")
    private String policy = "strict";

    @Option(names = {"--out"}, description = "Write report JSON to file instead of stdout")
    private String outputPath;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    private static final java.util.Set<String> VALID_POLICIES = java.util.Set.of("strict", "conservative", "report-only");

    @Override
    public Integer call() {
        // Validate policy
        if (!VALID_POLICIES.contains(policy)) {
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "error", "INVALID_INPUT",
                    "message", "Unsupported policy: '" + policy + "'. Supported policies: strict, conservative, report-only"
                )));
            } else {
                System.err.println("Error: Unsupported policy: '" + policy + "'. Supported policies: strict, conservative, report-only");
            }
            return 1;
        }

        // Validate maxContextTokens
        if (maxContextTokens < 0) {
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "error", "INVALID_INPUT",
                    "message", "maxContextTokens must be >= 0, got: " + maxContextTokens
                )));
            } else {
                System.err.println("Error: maxContextTokens must be >= 0, got: " + maxContextTokens);
            }
            return 1;
        }

        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // Parse claims batch
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
                    "diagnostics", Map.of("input", "JSON parsing produced null.")
                )));
            } else {
                System.err.println("Error: JSON parsing produced null.");
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

        // Verify the batch (same factual report as verify-answer)
        ServiceResult<AnswerVerificationReport> verifyResult =
            factory.getClaimWorkflowService().verifyBatch(batch, ontologyId);

        if (!verifyResult.isSuccess()) {
            var error = ((ServiceResult.Error<AnswerVerificationReport>) verifyResult).error();
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

        AnswerVerificationReport report = ((ServiceResult.Success<AnswerVerificationReport>) verifyResult).data();

        // Build evidence context
        EvidenceContextBuilder builder = new EvidenceContextBuilder();
        EvidenceContext context = builder.buildContext(report, maxContextTokens);

        // Add policy-dependent handling guidance
        java.util.List<String> handlingGuidance = buildHandlingGuidance(report.aggregateStatus(), policy);

        if (jsonOutput) {
            Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("report", report);
            output.put("evidenceContext", context);
            output.put("policy", policy);
            output.put("handlingGuidance", handlingGuidance);

            String outputJson = gson.toJson(output);

            // Output: write to file (--out) or stdout
            if (outputPath != null && !outputPath.isBlank()) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(outputPath), outputJson);
                    System.out.println("Report written to " + outputPath
                        + " — aggregate status: " + report.aggregateStatus().jsonName()
                        + " — policy: " + policy);
                } catch (Exception e) {
                    System.err.println("Error: Cannot write report to " + outputPath + ": " + e.getMessage());
                    return 1;
                }
            } else {
                System.out.println(outputJson);
            }
        } else {
            // When --out is specified in non-JSON mode, write JSON to file and print summary
            if (outputPath != null && !outputPath.isBlank()) {
                Map<String, Object> output = new java.util.LinkedHashMap<>();
                output.put("report", report);
                output.put("evidenceContext", context);
                output.put("policy", policy);
                output.put("handlingGuidance", handlingGuidance);
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(outputPath), gson.toJson(output));
                    System.out.println("Report written to " + outputPath
                        + " — aggregate status: " + report.aggregateStatus().jsonName()
                        + " — policy: " + policy);
                } catch (Exception e) {
                    System.err.println("Error: Cannot write report to " + outputPath + ": " + e.getMessage());
                    return 1;
                }
            } else {
                System.out.println("Answer review for ontology '" + ontologyId + "' (policy: " + policy + "):");
                System.out.println("  Aggregate status: " + report.aggregateStatus().jsonName());
                System.out.println("  Answer ID: " + report.answerId());
                System.out.println("  Claims: " + report.claimResults().size());
                System.out.println("  Evidence context claims: " + context.claims().size());
                System.out.println("  Omitted claims: " + context.omittedClaimCount());
                System.out.println("  Handling guidance:");
                for (String guidance : handlingGuidance) {
                    System.out.println("    - " + guidance);
                }
                System.out.println("  Agent instructions:");
                for (String instruction : context.agentInstructions()) {
                    System.out.println("    - " + instruction);
                }
            }
        }
        return 0;
    }

    /**
     * Build policy-dependent handling guidance based on aggregate status.
     */
    private java.util.List<String> buildHandlingGuidance(AggregateAnswerStatus status, String policy) {
        java.util.List<String> guidance = new java.util.ArrayList<>();

        if ("strict".equals(policy)) {
            guidance.add("Policy: strict — do not present any claim as fact unless it is supported by ontology evidence.");
            if (status == AggregateAnswerStatus.CONTRADICTED) {
                guidance.add("The answer must be rejected — at least one required claim is contradicted.");
            } else if (status == AggregateAnswerStatus.INSUFFICIENT_EVIDENCE) {
                guidance.add("The answer cannot be confirmed — at least one required claim lacks evidence. State limitations clearly.");
            } else if (status == AggregateAnswerStatus.PARTIALLY_VERIFIED) {
                guidance.add("Only present supported claims as verified. Explicitly mark out-of-scope claims as unverified.");
            } else if (status == AggregateAnswerStatus.OUT_OF_SCOPE) {
                guidance.add("No claims can be verified — all required claims reference entities outside the ontology.");
            }
        } else if ("conservative".equals(policy)) {
            guidance.add("Policy: conservative — prefer caution. Only cite explicitly verified claims.");
            if (status == AggregateAnswerStatus.VERIFIED) {
                guidance.add("All required claims are supported, but verify each optional claim independently before citing.");
            } else if (status != AggregateAnswerStatus.INVALID_INPUT) {
                guidance.add("Not all claims are fully verified. Present only supported claims and clearly state limitations.");
            }
        } else if ("report-only".equals(policy)) {
            guidance.add("Policy: report-only — provide the factual report without judgment. The agent decides how to use the evidence.");
        }

        return guidance;
    }
}