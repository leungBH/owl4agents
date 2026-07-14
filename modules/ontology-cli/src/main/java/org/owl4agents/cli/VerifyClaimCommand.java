package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

/**
 * CLI command for v0.3 claim verification.
 * Verifies a structured claim against an ontology and returns a verdict.
 *
 * <p>v0.8.5: Updated for schema v2 output. The JSON output now includes
 * {@code schemaVersion}, {@code executionStatus}, {@code semanticVerdict},
 * {@code errorCode}, and {@code perStageTiming}. Text output displays
 * timeout/error status when {@code semanticVerdict} is null.
 */
@Command(name = "verify-claim", description = "Verify a structured claim against an ontology.")
public class VerifyClaimCommand implements Callable<Integer> {

    private static final String SCHEMA_VERSION = "claim-verification-result/2";

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--claim"}, required = true, description = "Claim JSON string or path to JSON file")
    private String claim;

    @Option(names = {"--reasoner"}, description = "Reasoner name (default: auto)")
    private String reasoner = "auto";

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    @Option(names = {"--timeout"}, description = "Timeout duration (e.g., 30s, 2m, 500ms)")
    private String timeout;

    private static final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // Parse claim from JSON string or file
        ClaimParser.ParseResult parseResult = ClaimParser.parseClaim(claim, reasoner);
        if (!parseResult.isSuccess()) {
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "error", parseResult.errorCode().code(),
                    "message", parseResult.errorMessage()
                )));
            } else {
                System.err.println("Error: " + parseResult.errorCode().code() + " - " + parseResult.errorMessage());
            }
            return 1;
        }
        Claim parsedClaim = parseResult.claim();

        // CLI ontologyId overrides claim JSON ontologyId
        Claim effectiveClaim = new Claim(
            parsedClaim.claimId(), parsedClaim.type(), ontologyId,
            parsedClaim.subject(), parsedClaim.predicate(), parsedClaim.object(),
            parsedClaim.reasoner(), parsedClaim.graphScope(), parsedClaim.options()
        );

        // Validate the claim
        ClaimValidator validator = new ClaimValidator();
        ServiceResult<Claim> validationResult = validator.validate(effectiveClaim);
        if (!validationResult.isSuccess()) {
            var error = ((ServiceResult.Error<Claim>) validationResult).error();
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

        Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

        // Verify the claim
        // v0.8.5: pass --timeout through to the service (task 10.8)
        Duration timeoutDuration = parseTimeout(timeout);
        ServiceResult<ClaimVerificationResult> result = timeoutDuration != null
            ? factory.getClaimVerificationService().verify(validClaim, timeoutDuration)
            : factory.getClaimVerificationService().verify(validClaim);

        if (result.isSuccess()) {
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            if (jsonOutput) {
                // v0.8.5 schema v2: add schemaVersion to the JSON output
                JsonObject json = gson.toJsonTree(data).getAsJsonObject();
                json.addProperty("schemaVersion", SCHEMA_VERSION);
                System.out.println(gson.toJson(json));
            } else {
                printTextOutput(data, ontologyId);
            }
            // Return non-zero exit code for errored results (timeout/error)
            return data.executionStatus() == ExecutionStatus.COMPLETED ? 0 : 2;
        } else {
            var error = ((ServiceResult.Error<ClaimVerificationResult>) result).error();
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

    private void printTextOutput(ClaimVerificationResult data, String ontologyId) {
        System.out.println("Claim verification for ontology '" + ontologyId + "':");
        System.out.println("  Claim ID: " + data.claimId());
        System.out.println("  Type: " + data.claimType().jsonName());

        if (data.executionStatus() == ExecutionStatus.COMPLETED) {
            Verdict verdict = data.verdict();
            System.out.println("  Verdict: " + (verdict != null ? verdict.jsonName() : "null"));
            if (data.unknownReason().isPresent()) {
                System.out.println("  Unknown reason: " + data.unknownReason().get().jsonName());
            }
            if (data.unknownExplanation().isPresent()) {
                System.out.println("  Unknown explanation: " + data.unknownExplanation().get());
            }
        } else {
            // v0.8.5: timeout or error — semanticVerdict is null
            System.out.println("  Execution status: " + data.executionStatus().jsonName());
            System.out.println("  Error code: " + data.errorCode()
                .map(ErrorCode::code).orElse("(none)"));
        }

        System.out.println("  Evidence items: " + data.evidence().size());
        for (EvidenceItem item : data.evidence()) {
            System.out.println("    - " + item.evidenceId() + ": " + item.kind().jsonName()
                + " [" + item.role() + "] " + item.value());
        }

        // v0.8.5: per-stage timing
        PerStageTiming timing = data.perStageTiming();
        if (timing != null && timing.totalMs() != null) {
            System.out.println("  Timing: total=" + timing.totalMs() + "ms"
                + (timing.axiomBuildMs() != null ? " axiomBuild=" + timing.axiomBuildMs() + "ms" : "")
                + (timing.sourceConsistencyMs() != null ? " sourceConsistency=" + timing.sourceConsistencyMs() + "ms" : "")
                + (timing.entailmentMs() != null ? " entailment=" + timing.entailmentMs() + "ms" : "")
                + (timing.consistencyCheckMs() != null ? " consistencyCheck=" + timing.consistencyCheckMs() + "ms" : ""));
        }
    }

    /**
     * v0.8.5: Parse human-readable duration strings (e.g., "30s", "2m", "500ms")
     * into {@link Duration}. Returns null if input is null/blank.
     * Supports: ns, us, ms, s, m, h, d.
     */
    private static Duration parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return null;
        }
        String trimmed = timeout.trim();
        // Try ISO-8601 first (e.g., PT30S, PT2M)
        if (trimmed.startsWith("PT")) {
            return Duration.parse(trimmed);
        }
        // Parse human-readable: <number><unit>
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d+)\\s*(ns|us|ms|s|m|h|d)$", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(trimmed);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "Invalid timeout format: '" + timeout + "'. Expected formats: 30s, 2m, 500ms, PT30S");
        }
        long value = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase();
        return switch (unit) {
            case "ns" -> Duration.ofNanos(value);
            case "us" -> Duration.ofNanos(value * 1000L);
            case "ms" -> Duration.ofMillis(value);
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            case "d" -> Duration.ofDays(value);
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }
}
