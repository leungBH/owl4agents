package org.owl4agents.core;

/**
 * Represents a workspace identifier.
 * Workspaces are named collections of imported ontologies under the owl4agents home directory.
 */
public record WorkspaceId(String name) {

    public static final WorkspaceId DEFAULT = new WorkspaceId("default");

    public WorkspaceId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name must not be null or blank");
        }
    }
}