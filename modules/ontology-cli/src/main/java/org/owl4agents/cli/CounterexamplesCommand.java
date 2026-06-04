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
 * CLI command for v0.3 counterexample search.
 * Returns counterexamples for a contradicted claim.
 */
@Command(name = "counterexamples", description = "Find counterexamples for a contradicted claim.")
public class CounterexamplesCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--claim"}, required = true, description = "Claim JSON string or path to JSON file")
    private String claim;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        ClaimParser.ParseResult parseResult = ClaimParser.parseClaim(claim);
        if (!parseResult.isSuccess()) {
            System.err.println("Error: " + parseResult.errorCode().code() + " - " + parseResult.errorMessage());
            return 1;
        }
        Claim parsedClaim = parseResult.claim();

        // CLI ontologyId overrides claim JSON ontologyId
        Claim effectiveClaim = new Claim(
            parsedClaim.claimId(), parsedClaim.type(), ontologyId,
            parsedClaim.subject(), parsedClaim.predicate(), parsedClaim.object(),
            parsedClaim.reasoner(), parsedClaim.graphScope(), parsedClaim.options()
        );

        ClaimValidator validator = new ClaimValidator();
        ServiceResult<Claim> validationResult = validator.validate(effectiveClaim);
        if (!validationResult.isSuccess()) {
            var error = ((ServiceResult.Error<Claim>) validationResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

        ServiceResult<ClaimVerificationResult> verifyResult = factory.getClaimVerificationService().verify(validClaim);
        if (!verifyResult.isSuccess()) {
            var error = ((ServiceResult.Error<ClaimVerificationResult>) verifyResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

        ServiceResult<java.util.List<EvidenceItem>> result = factory.getEvidenceGroundingService().findCounterexamples(validClaim, verification);

        if (result.isSuccess()) {
            java.util.List<EvidenceItem> counterexamples = ((ServiceResult.Success<java.util.List<EvidenceItem>>) result).data();
            if (jsonOutput) {
                System.out.println(gson.toJson(Map.of(
                    "claimId", validClaim.claimId(),
                    "verdict", verification.verdict().jsonName(),
                    "counterexamples", counterexamples
                )));
            } else {
                System.out.println("Counterexamples for claim '" + validClaim.claimId() + "':");
                System.out.println("  Verdict: " + verification.verdict().jsonName());
                System.out.println("  Counterexamples found: " + counterexamples.size());
                for (EvidenceItem item : counterexamples) {
                    System.out.println("    - " + item.evidenceId() + ": " + item.kind().jsonName() + " " + item.value());
                }
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<java.util.List<EvidenceItem>>) result).error();
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