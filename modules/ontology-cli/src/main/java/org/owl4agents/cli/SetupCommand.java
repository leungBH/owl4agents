package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

/**
 * CLI command for source-checkout environment setup and readiness checks.
 * Reports Java, Gradle wrapper, source layout, workspace, npm launcher,
 * and runtime jar status without modifying user data unless explicitly requested.
 * Supports --init for idempotent first-run workspace initialization.
 */
@Command(name = "setup", description = "Check and prepare the owl4agents source checkout environment.")
public class SetupCommand implements Callable<Integer> {

    @Option(names = {"--check"}, description = "Report environment status without modifying workspace files")
    private boolean checkOnly = false;

    @Option(names = {"--dry-run"}, description = "Report planned actions without modifying workspace files")
    private boolean dryRun = false;

    @Option(names = {"--init"}, description = "Initialize workspace and import onboarding fixtures (idempotent)")
    private boolean initMode = false;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Option(names = {"--home"}, description = "owl4agents home directory override")
    private String homeDirectory;

    @Option(names = {"--json"}, description = "Output as JSON")
    private boolean jsonOutput = false;

    private static final Gson gson = GsonFactory.createGson();

    @Override
    public Integer call() {
        if (initMode) {
            return runInit();
        }

        // If neither --check nor --dry-run is specified, default to --check behavior
        boolean isDryRun = dryRun;
        boolean isCheck = checkOnly || (!dryRun);

        SetupChecker checker = new SetupChecker(workspaceName, homeDirectory);
        SetupStatus status = checker.check();

        if (isDryRun) {
            SetupPlanner planner = new SetupPlanner(status);
            SetupPlan plan = planner.plan();
            if (jsonOutput) {
                System.out.println(gson.toJson(planToMap(plan)));
            } else {
                printPlan(plan);
            }
            return 0;
        }

        // --check mode: report status
        if (jsonOutput) {
            System.out.println(gson.toJson(statusToMap(status)));
        } else {
            printStatus(status);
        }

        if (!status.isReady()) {
            return 1;
        }
        return 0;
    }

    private int runInit() {
        List<InitStepResult> results = new ArrayList<>();
        CliServiceFactory factory = new CliServiceFactory(workspaceName, homeDirectory);
        java.nio.file.Path projectRoot = findProjectRoot();

        // Step 1: Initialize workspace (idempotent)
        results.add(initWorkspace(factory));

        // Step 2: Import Pizza fixture (idempotent — skip if already imported)
        results.add(importFixture(factory, projectRoot,
            "test/corpus/smoke/pizza.owl", "pizza"));

        // Step 3: Import v0.3 golden claim ontology (idempotent)
        results.add(importFixture(factory, projectRoot,
            "test/corpus/golden/v0.3-claim-verification.owl", "v0.3-claim-verification"));

        boolean allPassed = results.stream().allMatch(InitStepResult::passed);

        if (jsonOutput) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("overall", allPassed ? "PASS" : "FAIL");
            List<Map<String, Object>> stepList = new ArrayList<>();
            for (InitStepResult r : results) {
                Map<String, Object> stepMap = new LinkedHashMap<>();
                stepMap.put("step", r.stepName());
                stepMap.put("status", r.passed() ? "PASS" : "FAIL");
                stepMap.put("detail", r.detail());
                stepList.add(stepMap);
            }
            report.put("steps", stepList);
            System.out.println(gson.toJson(report));
        } else {
            System.out.println("owl4agents setup --init results:");
            for (InitStepResult r : results) {
                String marker = r.passed() ? "PASS" : "FAIL";
                System.out.println("  " + marker + "  " + r.stepName() + ": " + r.detail());
            }
            System.out.println();
            System.out.println("Overall: " + (allPassed ? "PASS" : "FAIL"));
        }

        return allPassed ? 0 : 1;
    }

    private InitStepResult initWorkspace(CliServiceFactory factory) {
        try {
            ServiceResult<Void> result = factory.getWorkspaceInitializer()
                .initializeIdempotent(factory.getWorkspaceId());
            if (result.isSuccess()) {
                return InitStepResult.pass("workspace_init", "Workspace '" + workspaceName + "' initialized.");
            } else {
                var error = ((ServiceResult.Error<Void>) result).error();
                return InitStepResult.fail("workspace_init",
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return InitStepResult.fail("workspace_init", "Exception: " + e.getMessage());
        }
    }

    private InitStepResult importFixture(CliServiceFactory factory,
        java.nio.file.Path projectRoot, String fixtureRelPath, String ontologyId) {
        if (projectRoot == null) {
            return InitStepResult.fail("import_" + ontologyId,
                "Cannot locate project root for fixture import.");
        }

        // Idempotent: skip if already imported
        ServiceResult<CatalogEntry> existing = factory.getCatalogStore()
            .findEntry(factory.getWorkspaceId(), new OntologyId(ontologyId));
        if (existing.isSuccess()) {
            CatalogEntry entry = ((ServiceResult.Success<CatalogEntry>) existing).data();
            return InitStepResult.pass("import_" + ontologyId,
                "Ontology '" + ontologyId + "' already imported (canonical: " + entry.canonicalPath() + "). Skipped.");
        }

        java.nio.file.Path fixturePath = projectRoot.resolve(fixtureRelPath);
        if (!java.nio.file.Files.exists(fixturePath)) {
            return InitStepResult.fail("import_" + ontologyId,
                "Required fixture missing: " + fixtureRelPath);
        }

        try {
            ServiceResult<Void> result = factory.getOntologyImporter()
                .importOntology(new OntologyId(ontologyId), fixturePath, factory.getWorkspaceId());
            if (result.isSuccess()) {
                return InitStepResult.pass("import_" + ontologyId,
                    "Imported '" + ontologyId + "' from " + fixtureRelPath);
            } else {
                var error = ((ServiceResult.Error<Void>) result).error();
                return InitStepResult.fail("import_" + ontologyId,
                    error.code().code() + " - " + error.message());
            }
        } catch (Exception e) {
            return InitStepResult.fail("import_" + ontologyId, "Exception: " + e.getMessage());
        }
    }

    private void printStatus(SetupStatus status) {
        System.out.println("owl4agents setup status:");
        for (SetupStep step : status.steps()) {
            String marker = step.passed() ? "PASS" : "FAIL";
            System.out.println("  " + marker + "  " + step.name() + ": " + step.detail());
        }
        System.out.println();
        if (status.isReady()) {
            System.out.println("Overall: READY");
        } else {
            System.out.println("Overall: NOT READY - see FAIL items above for remediation.");
        }
    }

    private void printPlan(SetupPlan plan) {
        System.out.println("owl4agents setup planned actions (dry run):");
        for (SetupAction action : plan.actions()) {
            System.out.println("  " + action.kind() + "  " + action.description());
        }
        if (plan.actions().isEmpty()) {
            System.out.println("  No actions needed - environment is ready.");
        }
    }

    private Map<String, Object> statusToMap(SetupStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("overall", status.isReady() ? "READY" : "NOT_READY");
        Map<String, Object> steps = new LinkedHashMap<>();
        for (SetupStep step : status.steps()) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("status", step.passed() ? "PASS" : "FAIL");
            stepMap.put("detail", step.detail());
            steps.put(step.name(), stepMap);
        }
        map.put("steps", steps);
        return map;
    }

    private Map<String, Object> planToMap(SetupPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dry_run", true);
        java.util.List<Map<String, Object>> actions = new java.util.ArrayList<>();
        for (SetupAction action : plan.actions()) {
            Map<String, Object> actionMap = new LinkedHashMap<>();
            actionMap.put("kind", action.kind());
            actionMap.put("description", action.description());
            actions.add(actionMap);
        }
        map.put("actions", actions);
        return map;
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

    /**
     * One step result in the setup --init workflow.
     */
    record InitStepResult(String stepName, boolean passed, String detail) {
        static InitStepResult pass(String stepName, String detail) {
            return new InitStepResult(stepName, true, detail);
        }
        static InitStepResult fail(String stepName, String detail) {
            return new InitStepResult(stepName, false, detail);
        }
    }
}