package org.owl4agents.storage;

import org.owl4agents.core.WorkspaceId;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the owl4agents home directory.
 * Supports a test override via system property for acceptance tests.
 */
public class HomeDirectoryResolver {

    private static final String OWL4AGENTS_HOME_PROPERTY = "owl4agents.home";
    private static final String OWL4AGENTS_HOME_ENV = "OWL4AGENTS_HOME";
    private static final String DEFAULT_HOME_DIR_NAME = ".owl4agents";

    private final Path overriddenHome;

    public HomeDirectoryResolver() {
        this.overriddenHome = null;
    }

    /**
     * Create a resolver with an explicit override for testing.
     */
    public HomeDirectoryResolver(Path overriddenHome) {
        this.overriddenHome = overriddenHome;
    }

    /**
     * Resolve the owl4agents home directory.
     * Priority: explicit override > system property > env var > user home/.owl4agents
     */
    public Path resolveHomeDirectory() {
        // 1. Explicit override (for testing)
        if (overriddenHome != null) {
            return overriddenHome;
        }

        // 2. System property override
        String propertyHome = System.getProperty(OWL4AGENTS_HOME_PROPERTY);
        if (propertyHome != null && !propertyHome.isBlank()) {
            return Path.of(propertyHome);
        }

        // 3. Environment variable override
        String envHome = System.getenv(OWL4AGENTS_HOME_ENV);
        if (envHome != null && !envHome.isBlank()) {
            return Path.of(envHome);
        }

        // 4. Default: user home/.owl4agents
        return Path.of(System.getProperty("user.home"), DEFAULT_HOME_DIR_NAME);
    }

    /**
     * Resolve a workspace directory under the owl4agents home.
     */
    public Path resolveWorkspaceDirectory(WorkspaceId workspaceId) {
        return resolveHomeDirectory()
            .resolve("workspaces")
            .resolve(workspaceId.name());
    }

    /**
     * Resolve the config file path.
     */
    public Path resolveConfigPath() {
        return resolveHomeDirectory().resolve("config.yaml");
    }
}