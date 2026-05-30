package org.owl4agents.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * Logs MCP tool calls with timestamp, tool name, ontology ID when present,
 * result status, and error code.
 * Appends entries to the workspace MCP log file in JSONL format.
 */
public class McpToolCallLogger {

    private final Path logFilePath;

    public McpToolCallLogger(String logFilePath) {
        this.logFilePath = logFilePath != null ? Path.of(logFilePath) : null;
    }

    /**
     * Log an MCP tool call.
     */
    public void logCall(Instant timestamp, String toolName, String ontologyId,
                        String resultStatus, String errorCode) {
        String entry = String.format("{\"timestamp\":\"%s\",\"tool\":\"%s\",\"ontologyId\":\"%s\",\"resultStatus\":\"%s\",\"errorCode\":\"%s\"}%n",
            timestamp.toString(),
            toolName,
            ontologyId != null ? ontologyId : "",
            resultStatus,
            errorCode != null ? errorCode : "");

        if (logFilePath != null) {
            try {
                Files.createDirectories(logFilePath.getParent());
                Files.writeString(logFilePath, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // Log write failure should not break the tool call
                System.err.println("Warning: Failed to write MCP log entry: " + e.getMessage());
            }
        }
    }
}