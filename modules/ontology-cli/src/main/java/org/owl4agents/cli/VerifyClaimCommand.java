package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Map;

import com.google.gson.Gson;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

/**
 * CLI command for v0.3 claim verification.
 * Verifies a structured claim against an ontology and returns a verdict.
 */
@Command(name = "verify-claim", description = "Verify a structured claim against an ontology.")
public class VerifyClaimCommand implements Callable<Integer> {

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
        ServiceResult<ClaimVerificationResult> result = factory.getClaimVerificationService().verify(validClaim);

        if (result.isSuccess()) {
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            if (jsonOutput) {
                System.out.println(gson.toJson(data));
            } else {
                System.out.println("Claim verification for ontology '" + ontologyId + "':");
                System.out.println("  Claim ID: " + data.claimId());
                System.out.println("  Type: " + data.claimType().jsonName());
                System.out.println("  Verdict: " + data.verdict().jsonName());
                if (data.unknownReason().isPresent()) {
                    System.out.println("  Unknown reason: " + data.unknownReason().get().jsonName());
                }
                if (data.unknownExplanation().isPresent()) {
                    System.out.println("  Unknown explanation: " + data.unknownExplanation().get());
                }
                System.out.println("  Evidence items: " + data.evidence().size());
                for (EvidenceItem item : data.evidence()) {
                    System.out.println("    - " + item.evidenceId() + ": " + item.kind().jsonName() + " [" + item.role() + "] " + item.value());
                }
            }
            return 0;
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
}