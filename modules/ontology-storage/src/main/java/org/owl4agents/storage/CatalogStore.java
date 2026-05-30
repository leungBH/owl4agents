package org.owl4agents.storage;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read/write operations for the ontology catalog.
 * The catalog records imported ontology entries with ontology ID,
 * display name, source path, canonical path, import timestamp,
 * and metadata path.
 */
public class CatalogStore {

    private final HomeDirectoryResolver homeResolver;

    public CatalogStore(HomeDirectoryResolver homeResolver) {
        this.homeResolver = homeResolver;
    }

    /**
     * Read all catalog entries for a workspace.
     */
    public ServiceResult<List<CatalogEntry>> readCatalog(WorkspaceId workspaceId) {
        Path catalogPath = homeResolver.resolveWorkspaceDirectory(workspaceId)
            .resolve("catalog.json");

        if (!Files.exists(catalogPath)) {
            return ServiceResult.error(
                org.owl4agents.core.ErrorCode.ONTOLOGY_NOT_FOUND,
                "Workspace catalog not found for workspace '" + workspaceId.name() + "'."
            );
        }

        try {
            String content = Files.readString(catalogPath);
            List<CatalogEntry> entries = parseCatalogEntries(content, workspaceId);
            return ServiceResult.success(entries,
                org.owl4agents.core.ResultMetadata.explicit(new OntologyId(workspaceId.name())));
        } catch (IOException e) {
            return ServiceResult.error(
                org.owl4agents.core.ErrorCode.IMPORT_FAILED,
                "Failed to read catalog: " + e.getMessage());
        }
    }

    /**
     * Add a catalog entry for an imported ontology.
     */
    public ServiceResult<Void> addEntry(WorkspaceId workspaceId, CatalogEntry entry) {
        Path catalogPath = homeResolver.resolveWorkspaceDirectory(workspaceId)
            .resolve("catalog.json");

        try {
            // Read existing catalog, add entry, write back
            List<CatalogEntry> entries = new ArrayList<>();
            if (Files.exists(catalogPath)) {
                entries.addAll(parseCatalogEntries(Files.readString(catalogPath), workspaceId));
            }

            // Check for duplicate ontology ID
            Optional<CatalogEntry> existing = entries.stream()
                .filter(e -> e.ontologyId().equals(entry.ontologyId()))
                .findFirst();
            if (existing.isPresent()) {
                return ServiceResult.error(
                    org.owl4agents.core.ErrorCode.IMPORT_FAILED,
                    "Ontology ID '" + entry.ontologyId().id() + "' already exists in the catalog."
                );
            }

            entries.add(entry);
            writeCatalog(catalogPath, entries);

            return ServiceResult.success(null,
                org.owl4agents.core.ResultMetadata.explicit(entry.ontologyId()));
        } catch (IOException e) {
            return ServiceResult.error(
                org.owl4agents.core.ErrorCode.IMPORT_FAILED,
                "Failed to write catalog entry: " + e.getMessage());
        }
    }

    /**
     * Find a catalog entry by ontology ID.
     */
    public ServiceResult<CatalogEntry> findEntry(WorkspaceId workspaceId, OntologyId ontologyId) {
        ServiceResult<List<CatalogEntry>> catalogResult = readCatalog(workspaceId);
        if (!catalogResult.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<List<CatalogEntry>>) catalogResult).error());
        }

        List<CatalogEntry> entries = ((ServiceResult.Success<List<CatalogEntry>>) catalogResult).data();
        Optional<CatalogEntry> found = entries.stream()
            .filter(e -> e.ontologyId().equals(ontologyId))
            .findFirst();

        if (found.isEmpty()) {
            return ServiceResult.error(ServiceError.ontologyNotFound(ontologyId));
        }

        return ServiceResult.success(found.get(),
            org.owl4agents.core.ResultMetadata.explicit(ontologyId));
    }

    /**
     * Check if a file path is within the workspace catalog (safety check).
     */
    public boolean isCatalogedPath(WorkspaceId workspaceId, Path filePath) {
        ServiceResult<List<CatalogEntry>> catalogResult = readCatalog(workspaceId);
        if (!catalogResult.isSuccess()) {
            return false;
        }

        List<CatalogEntry> entries = ((ServiceResult.Success<List<CatalogEntry>>) catalogResult).data();
        return entries.stream().anyMatch(e ->
            e.sourcePath().equals(filePath) || e.canonicalPath().equals(filePath)
        );
    }

    private List<CatalogEntry> parseCatalogEntries(String content, WorkspaceId workspaceId) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject root = gson.fromJson(content, com.google.gson.JsonObject.class);

            if (root == null || !root.has("entries")) {
                return new ArrayList<>();
            }

            com.google.gson.JsonArray entriesArray = root.getAsJsonArray("entries");
            List<CatalogEntry> entries = new ArrayList<>();

            for (com.google.gson.JsonElement element : entriesArray) {
                com.google.gson.JsonObject entryObj = element.getAsJsonObject();

                String ontologyIdStr = entryObj.has("ontologyId") ? entryObj.get("ontologyId").getAsString() : "";
                String displayName = entryObj.has("displayName") ? entryObj.get("displayName").getAsString() : "";
                String sourcePathStr = entryObj.has("sourcePath") ? entryObj.get("sourcePath").getAsString() : "";
                String canonicalPathStr = entryObj.has("canonicalPath") ? entryObj.get("canonicalPath").getAsString() : "";
                String importTimestampStr = entryObj.has("importTimestamp") ? entryObj.get("importTimestamp").getAsString() : "";
                String metadataPathStr = entryObj.has("metadataPath") ? entryObj.get("metadataPath").getAsString() : "";

                entries.add(new CatalogEntry(
                    new OntologyId(ontologyIdStr),
                    displayName,
                    Path.of(sourcePathStr),
                    Path.of(canonicalPathStr),
                    Instant.parse(importTimestampStr),
                    Path.of(metadataPathStr)
                ));
            }

            return entries;
        } catch (Exception e) {
            // If JSON parsing fails, return empty list
            return new ArrayList<>();
        }
    }

    private void writeCatalog(Path catalogPath, List<CatalogEntry> entries) throws IOException {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

        com.google.gson.JsonArray entriesArray = new com.google.gson.JsonArray();
        for (CatalogEntry entry : entries) {
            com.google.gson.JsonObject entryObj = new com.google.gson.JsonObject();
            entryObj.addProperty("ontologyId", entry.ontologyId().id());
            entryObj.addProperty("displayName", entry.displayName());
            entryObj.addProperty("sourcePath", entry.sourcePath().toString());
            entryObj.addProperty("canonicalPath", entry.canonicalPath().toString());
            entryObj.addProperty("importTimestamp", entry.importTimestamp().toString());
            entryObj.addProperty("metadataPath", entry.metadataPath().toString());
            entriesArray.add(entryObj);
        }

        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("entries", entriesArray);

        Files.writeString(catalogPath, gson.toJson(root));
    }
}
