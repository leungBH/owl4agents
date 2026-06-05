package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

/**
 * CLI command for onboarding smoke testing.
 * Verifies CLI, fixture import, reasoner availability, claim verification,
 * and optional MCP startup using real execution paths.
 * Defaults to a temporary workspace to avoid mutating user data.
 */
@Command(name = "smoke", description = "Run onboarding smoke test to verify owl4agents readiness.")
public class SmokeCommand implements Callable<Integer> {

    @Option(names = {"--workspace"}, description = "Workspace home directory path for smoke test (defaults to temporary workspace). If this path already ends with workspaces/<name>, the parent directory is used as the home directory automatically.")
    private String workspacePath;

    @Option(names = {"--mcp-readiness"}, description = "Include MCP readiness check (starts MCP as a bounded child process)")
    private boolean mcpReadiness = false;

    @Option(names = {"--timeout"}, description = "Timeout in seconds for MCP readiness check (default: 30)")
    private int timeoutSeconds = 30;

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        // Early parameter validation
        if (mcpReadiness && timeoutSeconds <= 0) {
            System.err.println("Error: Invalid timeout: " + timeoutSeconds + " seconds. Must be positive.");
            return 1;
        }

        List<SmokeStepResult> results = new ArrayList<>();

        // Resolve workspace path — --workspace is a home directory path.
        // If the user passes a path that already ends with workspaces/<name>,
        // auto-correct by using the parent as the home directory.
        String effectiveWorkspace;
        boolean isTemporary = false;
        if (workspacePath != null && !workspacePath.isBlank()) {
            effectiveWorkspace = normalizeWorkspacePath(workspacePath, "default");
        } else {
            // Use a temporary workspace
            try {
                java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("owl4agents-smoke-");
                effectiveWorkspace = tempDir.toString();
                isTemporary = true;
            } catch (java.io.IOException e) {
                System.err.println("Error: Cannot create temporary workspace: " + e.getMessage());
                return 1;
            }
        }

        // Run smoke steps
        CliServiceFactory factory = new CliServiceFactory("default", effectiveWorkspace);

        // Step 1: Initialize workspace
        results.add(runWorkspaceInit(factory));

        // Step 2: Import Pizza fixture
        java.nio.file.Path projectRoot = findProjectRoot();
        results.add(runImportFixture(factory, projectRoot,
            "test/corpus/smoke/pizza.owl", "pizza"));

        // Step 3: Import v0.3 golden claim ontology
        results.add(runImportFixture(factory, projectRoot,
            "test/corpus/golden/v0.3-claim-verification.owl", "v0.3-claim-verification"));

        // Step 4: List ontologies
        results.add(runListOntologies(factory));

        // Step 5: Summary
        results.add(runSummary(factory));

        // Step 6: List reasoners
        results.add(runListReasoners(factory));

        // Step 7: Run classification (needed for claim verification to produce supported verdicts)
        results.add(runClassification(factory));

        // Step 8: Verify supported claim (uses ontology_scope type, doesn't require reasoning)
        results.add(runClaimVerification(factory, projectRoot,
            "test/fixtures/v0.3/claim-smoke-supported.json", "supported", false));

        // Step 9: Verify out-of-scope claim
        results.add(runClaimVerification(factory, projectRoot,
            "test/fixtures/v0.3/claim-real-out-of-scope.json", "out_of_scope", false));

        // Step 10: MCP readiness (optional)
        if (mcpReadiness) {
            results.add(runMcpReadiness(effectiveWorkspace, projectRoot, timeoutSeconds));
        }

        // Clean up temporary workspace if we created one
        if (isTemporary) {
            try {
                java.nio.file.Path tempPath = java.nio.file.Path.of(effectiveWorkspace);
                // Delete temporary workspace files (best effort)
                deleteRecursively(tempPath);
            } catch (Exception e) {
                // Best effort cleanup — don't fail the smoke test on cleanup issues
            }
        }

        // Report results
        boolean allPassed = results.stream().allMatch(SmokeStepResult::passed);

        if (jsonOutput) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("overall", allPassed ? "PASS" : "FAIL");
            report.put("workspace", effectiveWorkspace);
            report.put("temporary_workspace", isTemporary);
            List<Map<String, Object>> stepList = new ArrayList<>();
            for (SmokeStepResult r : results) {
                Map<String, Object> stepMap = new LinkedHashMap<>();
                stepMap.put("step", r.stepName());
                stepMap.put("status", r.passed() ? "PASS" : "FAIL");
                stepMap.put("detail", r.detail());
                if (r.verdict() != null) stepMap.put("verdict", r.verdict());
                stepList.add(stepMap);
            }
            report.put("steps", stepList);
            System.out.println(gson.toJson(report));
        } else {
            System.out.println("owl4agents smoke test results:");
            System.out.println("  Workspace: " + effectiveWorkspace +
                (isTemporary ? " (temporary)" : ""));
            System.out.println();
            for (SmokeStepResult r : results) {
                String marker = r.passed() ? "PASS" : "FAIL";
                System.out.println("  " + marker + "  " + r.stepName() + ": " + r.detail());
                if (r.verdict() != null) {
                    System.out.println("        Verdict: " + r.verdict());
                }
            }
            System.out.println();
            System.out.println("Overall: " + (allPassed ? "PASS" : "FAIL"));
        }

        return allPassed ? 0 : 1;
    }

    private SmokeStepResult runWorkspaceInit(CliServiceFactory factory) {
        try {
            ServiceResult<Void> result = factory.getWorkspaceInitializer()
                .initializeIdempotent(factory.getWorkspaceId());
            if (result.isSuccess()) {
                return SmokeStepResult.pass("workspace_init", "Workspace initialized successfully.");
            } else {
                var error = ((ServiceResult.Error<Void>) result).error();
                return SmokeStepResult.fail("workspace_init",
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("workspace_init", "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runImportFixture(CliServiceFactory factory,
        java.nio.file.Path projectRoot, String fixtureRelPath, String ontologyId) {
        if (projectRoot == null) {
            return SmokeStepResult.fail("import_" + ontologyId,
                "Cannot locate project root for fixture import.");
        }
        java.nio.file.Path fixturePath = projectRoot.resolve(fixtureRelPath);
        if (!java.nio.file.Files.exists(fixturePath)) {
            return SmokeStepResult.fail("import_" + ontologyId,
                "Required fixture missing: " + fixtureRelPath);
        }
        try {
            ServiceResult<Void> result = factory.getOntologyImporter()
                .importOntology(new OntologyId(ontologyId), fixturePath, factory.getWorkspaceId());
            if (result.isSuccess()) {
                return SmokeStepResult.pass("import_" + ontologyId,
                    "Imported " + ontologyId + " from " + fixtureRelPath);
            } else {
                var error = ((ServiceResult.Error<Void>) result).error();
                return SmokeStepResult.fail("import_" + ontologyId,
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("import_" + ontologyId, "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runListOntologies(CliServiceFactory factory) {
        try {
            ServiceResult<List<CatalogEntry>> result = factory.getCatalogStore()
                .readCatalog(factory.getWorkspaceId());
            if (result.isSuccess()) {
                List<CatalogEntry> entries = ((ServiceResult.Success<List<CatalogEntry>>) result).data();
                return SmokeStepResult.pass("list_ontologies",
                    "Found " + entries.size() + " ontology(s) in catalog.");
            } else {
                var error = ((ServiceResult.Error<List<CatalogEntry>>) result).error();
                return SmokeStepResult.fail("list_ontologies",
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("list_ontologies", "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runSummary(CliServiceFactory factory) {
        try {
            OntologyId ontId = new OntologyId("pizza");
            // Find catalog entry for canonical path
            ServiceResult<CatalogEntry> catalogResult = factory.getCatalogStore()
                .findEntry(factory.getWorkspaceId(), ontId);
            if (!catalogResult.isSuccess()) {
                var catalogError = ((ServiceResult.Error<CatalogEntry>) catalogResult).error();
                return SmokeStepResult.fail("summary",
                    catalogError.code().code() + " - " + catalogError.message());
            }
            CatalogEntry entry = ((ServiceResult.Success<CatalogEntry>) catalogResult).data();

            ServiceResult<OntologySummary> result = factory.getSummaryExtractor()
                .extractSummary(ontId, entry.canonicalPath());
            if (result.isSuccess()) {
                OntologySummary data = ((ServiceResult.Success<OntologySummary>) result).data();
                var counts = data.entityCounts();
                return SmokeStepResult.pass("summary",
                    "Summary for pizza: classes=" + counts.classes() +
                    ", properties=" + counts.objectProperties() + ", individuals=" + counts.individuals());
            } else {
                var error = ((ServiceResult.Error<OntologySummary>) result).error();
                return SmokeStepResult.fail("summary",
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("summary", "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runListReasoners(CliServiceFactory factory) {
        try {
            ServiceResult<ReasonerListResult> result = factory.getReasonerService().listReasoners();
            if (result.isSuccess()) {
                ReasonerListResult data = ((ServiceResult.Success<ReasonerListResult>) result).data();
                return SmokeStepResult.pass("list_reasoners",
                    "Found " + data.reasoners().size() + " reasoner adapter(s).");
            } else {
                var error = ((ServiceResult.Error<ReasonerListResult>) result).error();
                return SmokeStepResult.fail("list_reasoners",
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("list_reasoners", "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runClassification(CliServiceFactory factory) {
        try {
            OntologyId ontId = new OntologyId("v0.3-claim-verification");
            ServiceResult<ClassificationResult> result = factory.getReasonerService()
                .classify(ontId, Optional.of("auto"));
            if (result.isSuccess()) {
                ClassificationResult data = ((ServiceResult.Success<ClassificationResult>) result).data();
                return SmokeStepResult.pass("classify",
                    "Classification complete with " + data.reasonerName() +
                    ": " + data.delta().size() + " inferred subclass entries.");
            } else {
                var error = ((ServiceResult.Error<ClassificationResult>) result).error();
                return SmokeStepResult.fail("classify",
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("classify", "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runClaimVerification(CliServiceFactory factory,
        java.nio.file.Path projectRoot, String fixtureRelPath, String expectedVerdict,
        boolean useInferredScope) {
        if (projectRoot == null) {
            return SmokeStepResult.fail("verify_" + expectedVerdict,
                "Cannot locate project root for claim fixture.");
        }
        java.nio.file.Path fixturePath = projectRoot.resolve(fixtureRelPath);
        if (!java.nio.file.Files.exists(fixturePath)) {
            return SmokeStepResult.fail("verify_" + expectedVerdict,
                "Required claim fixture missing: " + fixtureRelPath);
        }
        try {
            // Parse the claim fixture
            String claimContent = java.nio.file.Files.readString(fixturePath);
            ClaimParser.ParseResult parseResult = ClaimParser.parseClaim(claimContent, "auto");
            if (!parseResult.isSuccess()) {
                return SmokeStepResult.fail("verify_" + expectedVerdict,
                    parseResult.errorCode().code() + " - " + parseResult.errorMessage());
            }
            Claim parsedClaim = parseResult.claim();

            // Override graphScope to "inferred" for supported claims (reasoning has already been run)
            Claim effectiveClaim = parsedClaim;
            if (useInferredScope) {
                effectiveClaim = new Claim(
                    parsedClaim.claimId(), parsedClaim.type(), parsedClaim.ontologyId(),
                    parsedClaim.subject(), parsedClaim.predicate(), parsedClaim.object(),
                    parsedClaim.reasoner(), Optional.of(GraphScope.INFERRED), parsedClaim.options()
                );
            }

            // Validate
            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> validationResult = validator.validate(effectiveClaim);
            if (!validationResult.isSuccess()) {
                var error = ((ServiceResult.Error<Claim>) validationResult).error();
                return SmokeStepResult.fail("verify_" + expectedVerdict,
                    error.code().code() + " - " + error.message());
            }
            Claim validClaim = ((ServiceResult.Success<Claim>) validationResult).data();

            // Verify
            ServiceResult<ClaimVerificationResult> result =
                factory.getClaimVerificationService().verify(validClaim);
            if (result.isSuccess()) {
                ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
                String verdict = data.verdict().jsonName();
                boolean verdictMatches = verdict.equals(expectedVerdict);
                if (verdictMatches) {
                    return SmokeStepResult.pass("verify_" + expectedVerdict,
                        "Claim " + data.claimId() + " verdict=" + verdict +
                        " (expected: " + expectedVerdict + ")",
                        verdict);
                } else {
                    return SmokeStepResult.fail("verify_" + expectedVerdict,
                        "Claim " + data.claimId() + " verdict=" + verdict +
                        " but expected: " + expectedVerdict);
                }
            } else {
                var error = ((ServiceResult.Error<ClaimVerificationResult>) result).error();
                return SmokeStepResult.fail("verify_" + expectedVerdict,
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return SmokeStepResult.fail("verify_" + expectedVerdict,
                "Exception: " + e.getMessage());
        }
    }

    private SmokeStepResult runMcpReadiness(String workspace, java.nio.file.Path projectRoot,
        int timeoutSec) {
        // Validate timeout
        if (timeoutSec <= 0) {
            return SmokeStepResult.fail("mcp_readiness",
                "Invalid timeout: " + timeoutSec + " seconds. Must be positive.");
        }

        if (projectRoot == null) {
            return SmokeStepResult.fail("mcp_readiness",
                "Cannot locate project root for MCP readiness check.");
        }

        // Use the same command shape that mcp-config generates:
        // node <projectRoot>/npm/bin/owl4agents.js mcp --readonly
        // This verifies the path users will copy from mcp-config output.
        java.nio.file.Path launcherPath = projectRoot.resolve("npm").resolve("bin").resolve("owl4agents.js");
        if (!java.nio.file.Files.exists(launcherPath)) {
            return SmokeStepResult.fail("mcp_readiness",
                "npm launcher not found at " + launcherPath);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "node", launcherPath.toString(),
                "mcp", "--readonly"
            );
            pb.environment().put("OWL4AGENTS_RUNTIME",
                projectRoot.resolve("modules").resolve("ontology-cli")
                    .resolve("build").resolve("libs").resolve("owl4agents.jar").toString());
            pb.environment().put("OWL4AGENTS_HOME", workspace);
            pb.redirectErrorStream(true);

            Process mcpProcess = pb.start();

            // Send initialize request via stdin
            String initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"smoke\",\"version\":\"0.3.1\"}}}\n";
            mcpProcess.getOutputStream().write(initRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mcpProcess.getOutputStream().flush();

            // Read response with timeout
            StringBuilder responseBuilder = new StringBuilder();
            long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);

            // Read response lines
            byte[] buffer = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                int available = mcpProcess.getInputStream().available();
                if (available > 0) {
                    int read = mcpProcess.getInputStream().read(buffer, 0, Math.min(available, buffer.length));
                    if (read > 0) {
                        responseBuilder.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                    }
                } else {
                    Thread.sleep(200);
                }

                String response = responseBuilder.toString();
                // Check for valid JSON-RPC response containing "initialize" or "tools/list"
                if (response.contains("\"protocolVersion\"") || response.contains("\"capabilities\"")) {
                    mcpProcess.destroy();
                    return SmokeStepResult.pass("mcp_readiness",
                        "MCP initialize response received within " + timeoutSec + "s timeout.");
                }
                // Check for crash output
                if (response.contains("Exception") || response.contains("Error") ||
                    response.contains("Access violation") || response.contains("Segfault")) {
                    mcpProcess.destroyForcibly();
                    return SmokeStepResult.fail("mcp_readiness",
                        "MCP startup produced crash/error output: " +
                        response.substring(0, Math.min(200, response.length())));
                }
            }

            // Timeout reached
            mcpProcess.destroyForcibly();
            return SmokeStepResult.fail("mcp_readiness",
                "MCP readiness check timed out after " + timeoutSec + " seconds.");
        } catch (Exception e) {
            return SmokeStepResult.fail("mcp_readiness",
                "MCP readiness check failed: " + e.getMessage());
        }
    }

    private java.nio.file.Path findProjectRoot() {
        java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path dir = cwd;
        for (int i = 0; i < 10; i++) {
            if (java.nio.file.Files.exists(dir.resolve("build.gradle.kts")) &&
                java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
            if (dir == null) break;
        }
        return null;
    }

    private void deleteRecursively(java.nio.file.Path path) throws Exception {
        if (java.nio.file.Files.exists(path)) {
            java.nio.file.Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { java.nio.file.Files.delete(p); } catch (Exception ignored) {}
                });
        }
    }

    /**
     * Normalize a workspace path so that it works as a home directory.
     * If the path already ends with "workspaces/<workspaceName>", strip the suffix
     * so the parent directory is used as the home directory. This prevents
     * path doubling (e.g., /workspaces/default/workspaces/default/ontologies/...).
     */
    private String normalizeWorkspacePath(String path, String workspaceName) {
        java.nio.file.Path p = java.nio.file.Path.of(path);
        // Check if path ends with workspaces/<workspaceName>
        int nameCount = p.getNameCount();
        if (nameCount >= 2) {
            String lastSegment = p.getName(nameCount - 1).toString();
            String secondLast = p.getName(nameCount - 2).toString();
            if ("workspaces".equals(secondLast) && workspaceName.equals(lastSegment)) {
                // Strip the workspaces/<name> suffix — use the parent as home directory
                return p.getParent().getParent().toString();
            }
        }
        // Also handle single-segment case: path is just "default" (unlikely but defensive)
        return path;
    }
}