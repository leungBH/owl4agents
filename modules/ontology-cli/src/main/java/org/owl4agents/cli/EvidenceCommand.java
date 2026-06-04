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
 * CLI command for v0.3 evidence path retrieval.
 * Returns the evidence path for a verified claim.
 */
@Command(name = "evidence", description = "Get evidence path for a verified claim.")
public class EvidenceCommand implements Callable<Integer> {

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

        // Validate
        ClaimValidator validator = new ClaimValidator();
        ServiceResult<Claim> validationResult = validator.validate(effectiveClaim);
        if (!validationResult.isSuccess()) {
            var error = ((ServiceResult.Error<Claim>) validationResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

        // Verify then get evidence path
        ServiceResult<ClaimVerificationResult> verifyResult = factory.getClaimVerificationService().verify(validClaim);
        if (!verifyResult.isSuccess()) {
            var error = ((ServiceResult.Error<ClaimVerificationResult>) verifyResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        ClaimVerificationResult verification = ((ServiceResult.Success<ClaimVerificationResult>) verifyResult).data();

        ServiceResult<EvidencePath> result = factory.getEvidenceGroundingService().getEvidencePath(validClaim, verification);

        if (result.isSuccess()) {
            EvidencePath data = ((ServiceResult.Success<EvidencePath>) result).data();
            if (jsonOutput) {
                System.out.println(gson.toJson(data));
            } else {
                System.out.println("Evidence path for claim '" + validClaim.claimId() + "':");
                System.out.println("  Verdict: " + verification.verdict().jsonName());
                System.out.println("  Items: " + data.items().size() + " of " + data.totalAvailable() + " available");
                System.out.println("  Truncated: " + data.truncated());
                for (EvidenceItem item : data.items()) {
                    System.out.println("    - " + item.evidenceId() + ": " + item.kind().jsonName() + " [" + item.role() + "] " + item.value());
                }
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<EvidencePath>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}