package org.owl4agents.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the source checkout environment for owl4agents readiness.
 * Validates Java, Gradle wrapper, source layout, workspace, npm launcher, and runtime jar.
 */
class SetupChecker {

    private final String workspaceName;
    private final String homeDirectory;

    SetupChecker(String workspaceName, String homeDirectory) {
        this.workspaceName = workspaceName;
        this.homeDirectory = homeDirectory;
    }

    SetupStatus check() {
        List<SetupStep> steps = new ArrayList<>();

        // 1. Check Java
        steps.add(checkJava());

        // 2. Check Gradle wrapper
        steps.add(checkGradleWrapper());

        // 3. Check source layout
        steps.add(checkSourceLayout());

        // 4. Check workspace writability
        steps.add(checkWorkspace());

        // 5. Check npm launcher
        steps.add(checkNpmLauncher());

        // 6. Check runtime jar
        steps.add(checkRuntimeJar());

        boolean ready = steps.stream().allMatch(SetupStep::passed);
        return new SetupStatus(steps, ready);
    }

    private SetupStep checkJava() {
        try {
            String javaHome = System.getenv("JAVA_HOME");
            String javaVersion;
            if (javaHome != null && !javaHome.isBlank()) {
                javaVersion = runVersionCheck(javaHome + "/bin/java" + (isWindows() ? ".exe" : ""));
            } else {
                javaVersion = runVersionCheck("java");
            }
            if (javaVersion != null && javaVersion.contains("22")) {
                return SetupStep.pass("java", "Java 22 found: " + javaVersion);
            } else if (javaVersion != null) {
                return SetupStep.fail("java", "Java found but not version 22: " + javaVersion +
                    ". owl4agents requires Java 22.");
            } else {
                return SetupStep.fail("java", "No Java executable found. " +
                    "Install Java 22 and set JAVA_HOME or add java to PATH.");
            }
        } catch (Exception e) {
            return SetupStep.fail("java", "Java check failed: " + e.getMessage());
        }
    }

    private SetupStep checkGradleWrapper() {
        // Check if gradlew exists in the source checkout
        String gradlew = isWindows() ? "gradlew.bat" : "gradlew";
        java.nio.file.Path projectRoot = findProjectRoot();
        if (projectRoot != null) {
            java.nio.file.Path gradlewPath = projectRoot.resolve(gradlew);
            if (java.nio.file.Files.exists(gradlewPath)) {
                return SetupStep.pass("gradle_wrapper", "Gradle wrapper found at " + gradlewPath);
            }
        }
        return SetupStep.fail("gradle_wrapper", "Gradle wrapper not found. " +
            "Ensure you are running from a valid owl4agents source checkout.");
    }

    private SetupStep checkSourceLayout() {
        java.nio.file.Path projectRoot = findProjectRoot();
        if (projectRoot == null) {
            return SetupStep.fail("source_layout", "Cannot determine project root directory.");
        }
        java.nio.file.Path modulesDir = projectRoot.resolve("modules");
        java.nio.file.Path npmDir = projectRoot.resolve("npm");
        boolean hasModules = java.nio.file.Files.exists(modulesDir);
        boolean hasNpm = java.nio.file.Files.exists(npmDir);
        if (hasModules && hasNpm) {
            return SetupStep.pass("source_layout", "Source layout valid: modules/ and npm/ found.");
        } else {
            String missing = (!hasModules ? "modules/ " : "") + (!hasNpm ? "npm/ " : "");
            return SetupStep.fail("source_layout", "Missing directories: " + missing.trim() +
                ". Ensure you are running from a valid owl4agents source checkout.");
        }
    }

    private SetupStep checkWorkspace() {
        try {
            org.owl4agents.storage.HomeDirectoryResolver resolver;
            if (homeDirectory != null) {
                resolver = new org.owl4agents.storage.HomeDirectoryResolver(java.nio.file.Path.of(homeDirectory));
            } else {
                resolver = new org.owl4agents.storage.HomeDirectoryResolver();
            }
            java.nio.file.Path home = resolver.resolveHomeDirectory();
            java.nio.file.Path workspaceDir = home.resolve("workspaces").resolve(workspaceName);

            if (java.nio.file.Files.exists(workspaceDir)) {
                return SetupStep.pass("workspace", "Workspace '" + workspaceName + "' exists at " + workspaceDir);
            } else {
                // Check if the nearest existing ancestor is writable — do NOT create directories
                java.nio.file.Path ancestor = workspaceDir.getParent();
                while (ancestor != null && !java.nio.file.Files.exists(ancestor)) {
                    ancestor = ancestor.getParent();
                }
                if (ancestor != null && java.nio.file.Files.isWritable(ancestor)) {
                    return SetupStep.pass("workspace", "Workspace '" + workspaceName + "' can be created at " + workspaceDir);
                } else if (ancestor != null) {
                    return SetupStep.fail("workspace", "Workspace ancestor directory is not writable: " + ancestor);
                }
                return SetupStep.fail("workspace", "No writable ancestor directory found for workspace path: " + workspaceDir);
            }
        } catch (Exception e) {
            return SetupStep.fail("workspace", "Workspace check failed: " + e.getMessage());
        }
    }

    private SetupStep checkNpmLauncher() {
        java.nio.file.Path projectRoot = findProjectRoot();
        if (projectRoot != null) {
            java.nio.file.Path launcherJs = projectRoot.resolve("npm").resolve("bin").resolve("owl4agents.js");
            java.nio.file.Path packageJson = projectRoot.resolve("npm").resolve("package.json");
            boolean hasLauncher = java.nio.file.Files.exists(launcherJs);
            boolean hasPackageJson = java.nio.file.Files.exists(packageJson);
            if (hasLauncher && hasPackageJson) {
                return SetupStep.pass("npm_launcher", "npm launcher found at " + launcherJs);
            } else {
                return SetupStep.fail("npm_launcher", "npm launcher not found. Missing: " +
                    (!hasLauncher ? "owl4agents.js " : "") + (!hasPackageJson ? "package.json " : ""));
            }
        }
        return SetupStep.fail("npm_launcher", "Cannot locate npm launcher without valid project root.");
    }

    private SetupStep checkRuntimeJar() {
        java.nio.file.Path projectRoot = findProjectRoot();
        if (projectRoot != null) {
            java.nio.file.Path jarPath = projectRoot.resolve("modules").resolve("ontology-cli")
                .resolve("build").resolve("libs").resolve("owl4agents.jar");
            if (java.nio.file.Files.exists(jarPath)) {
                long size;
                try { size = java.nio.file.Files.size(jarPath); } catch (java.io.IOException e) { size = 0; }
                return SetupStep.pass("runtime_jar", "Runtime jar found at " + jarPath +
                    " (" + formatSize(size) + ")");
            } else {
                return SetupStep.fail("runtime_jar", "Runtime jar not found at " + jarPath +
                    ". Run './gradlew :modules:ontology-cli:shadowJar' to build it.");
            }
        }
        return SetupStep.fail("runtime_jar", "Cannot locate runtime jar without valid project root.");
    }

    private String runVersionCheck(String javaCmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaCmd, "-version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            proc.waitFor();
            // Extract version line
            for (String line : output.lines().toList()) {
                if (line.contains("version")) {
                    return line.trim();
                }
            }
            return output.lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private java.nio.file.Path findProjectRoot() {
        // Try to find the project root by looking for build.gradle.kts
        // Start from current working directory and go up
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

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}