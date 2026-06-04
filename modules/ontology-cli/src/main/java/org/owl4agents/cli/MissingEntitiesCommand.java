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
 * CLI command for v0.3 missing entity detection.
 * Detects matched, ambiguous, missing, and out-of-scope entities in a claim.
 */
@Command(name = "missing-entities", description = "Detect missing or ambiguous entities referenced in a claim.")
public class MissingEntitiesCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--claim"}, description = "Claim JSON string or path to JSON file (uses claim entities as search terms)")
    private String claim;

    @Option(names = {"--terms"}, description = "Terms JSON string or path to JSON file (list of entity IRIs to search)")
    private String terms;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // If --claim is provided, parse it
        Claim claimObj = null;
        ClaimParser.ParseResult parseResult = null;
        if (claim != null) {
            parseResult = ClaimParser.parseClaim(claim);
            if (!parseResult.isSuccess()) {
                System.err.println("Error: " + parseResult.errorCode().code() + " - " + parseResult.errorMessage());
                return 1;
            }
            Claim parsedClaim = parseResult.claim();
            // CLI ontologyId overrides claim JSON ontologyId
            claimObj = new Claim(
                parsedClaim.claimId(), parsedClaim.type(), ontologyId,
                parsedClaim.subject(), parsedClaim.predicate(), parsedClaim.object(),
                parsedClaim.reasoner(), parsedClaim.graphScope(), parsedClaim.options()
            );
        } else if (terms != null) {
            claimObj = buildClaimFromTerms(terms, ontologyId);
        }

        if (claimObj == null) {
            System.err.println("Error: INVALID_CLAIM_SCHEMA - Provide --claim or --terms with valid JSON.");
            return 1;
        }

        ServiceResult<MissingEntityResult> result = factory.getEvidenceGroundingService().detectMissingEntities(claimObj);

        if (result.isSuccess()) {
            MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();
            if (jsonOutput) {
                System.out.println(gson.toJson(data));
            } else {
                System.out.println("Missing entity detection for ontology '" + ontologyId + "':");
                System.out.println("  Matched: " + data.matched().size());
                for (MissingEntityResult.EntityMatch m : data.matched()) {
                    System.out.println("    - " + m.searchTerm() + " → " + m.matchedIRI().orElse("N/A") + " (" + m.kind().orElse("N/A") + ")");
                }
                System.out.println("  Ambiguous: " + data.ambiguous().size());
                for (MissingEntityResult.EntityMatch m : data.ambiguous()) {
                    System.out.println("    - " + m.searchTerm() + " → " + m.matchedIRI().orElse("N/A") + " (" + m.kind().orElse("N/A") + ")");
                }
                System.out.println("  Missing: " + data.missing().size());
                for (MissingEntityResult.EntityMatch m : data.missing()) {
                    System.out.println("    - " + m.searchTerm() + " (" + m.kind().orElse("N/A") + ")");
                }
                System.out.println("  Out of scope: " + data.outOfScope().size());
                for (MissingEntityResult.EntityMatch m : data.outOfScope()) {
                    System.out.println("    - " + m.searchTerm() + " (" + m.kind().orElse("N/A") + ")");
                }
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<MissingEntityResult>) result).error();
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

    private Claim buildClaimFromTerms(String termsInput, String ontId) {
        try {
            String json = termsInput;
            if (!termsInput.trim().startsWith("[") && !termsInput.trim().startsWith("\"")) {
                try {
                    json = java.nio.file.Files.readString(java.nio.file.Path.of(termsInput));
                } catch (Exception e) {
                    return null;
                }
            }

            // Parse as array of IRIs and use the first two as subject/object
            String[] iris = gson.fromJson(json, String[].class);
            if (iris == null || iris.length == 0) return null;

            ClaimEntity subject = new ClaimEntity("class", iris[0]);
            ClaimEntity object = iris.length > 1 ? new ClaimEntity("class", iris[1]) : null;

            return new Claim("missing-entities-check", ClaimType.SUBCLASS, ontId,
                subject, null, object,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        } catch (Exception e) {
            return null;
        }
    }
}