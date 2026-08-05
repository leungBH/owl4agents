package org.owl4agents.reasoner.write;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * v0.9.1 mcp-write-tools-expansion D5: Append-only audit log per ontology.
 *
 * <p>Storage layout (per ontology):</p>
 * <pre>
 *   &lt;workspace&gt;/ontologies/&lt;id&gt;/audit.jsonl          (current)
 *   &lt;workspace&gt;/ontologies/&lt;id&gt;/audit.&lt;timestamp&gt;.jsonl  (rotated)
 * </pre>
 *
 * <p>Rotation triggers when the current {@code audit.jsonl} size exceeds
 * {@code owl4agents.write.audit.maxBytes} (default 100 MB). At most
 * {@code owl4agents.write.audit.maxFiles} (default 10) rotated files are
 * retained; oldest is deleted first. Whole-file deletion only — entries
 * within the current {@code audit.jsonl} are never deleted, preserving
 * the append-only guarantee across rotations.</p>
 *
 * <p>When {@code owl4agents.write.audit.enabled=false} (testing only),
 * {@link #append} is a no-op but the tool still succeeds.</p>
 */
public final class AuditLog {

    private static final String CURRENT_FILE = "audit.jsonl";
    private static final String ROTATED_PREFIX = "audit.";
    private static final String ROTATED_SUFFIX = ".jsonl";
    private static final DateTimeFormatter ROTATION_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS")
            .withZone(ZoneId.of("UTC"));

    private final Path workspaceOntologiesRoot;
    private final boolean enabled;
    private final long maxBytes;
    private final int maxFiles;
    private final Gson gson;

    /**
     * @param workspaceBasePath absolute path to the workspace root directory
     * @param workspaceName     workspace name (e.g. {@code "default"})
     */
    public AuditLog(String workspaceBasePath, String workspaceName) {
        this.workspaceOntologiesRoot = Path.of(workspaceBasePath, workspaceName, "ontologies");
        this.enabled = parseBooleanProperty("owl4agents.write.audit.enabled", true);
        this.maxBytes = parseLongProperty("owl4agents.write.audit.maxBytes", 104_857_600L);
        this.maxFiles = parseIntProperty("owl4agents.write.audit.maxFiles", 10);
        this.gson = GsonFactory.createGson();
    }

    /**
     * Test constructor with explicit configuration.
     */
    public AuditLog(String workspaceBasePath, String workspaceName,
                    boolean enabled, long maxBytes, int maxFiles) {
        this.workspaceOntologiesRoot = Path.of(workspaceBasePath, workspaceName, "ontologies");
        this.enabled = enabled;
        this.maxBytes = maxBytes;
        this.maxFiles = maxFiles;
        this.gson = GsonFactory.createGson();
    }

    /**
     * Append a single audit entry as one JSON line. No-op when disabled.
     */
    public synchronized void append(OntologyId ontologyId, AuditEntry entry) {
        if (!enabled || ontologyId == null || entry == null) {
            return;
        }
        try {
            Path auditFile = resolveAuditFile(ontologyId);
            Files.createDirectories(auditFile.getParent());
            String json = gson.toJson(entry.toMap()) + System.lineSeparator();
            Files.writeString(auditFile, json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            rotateIfNeeded(ontologyId);
        } catch (IOException e) {
            // Audit failures MUST NOT break the write tool call.
            // Future enhancement: log to stderr via SLF4J once available.
        }
    }

    /**
     * Query audit entries for an ontology with optional filters.
     * Reads the current file plus all retained rotated files.
     * Returns chronological order (oldest first).
     */
    public synchronized List<AuditEntry> query(OntologyId ontologyId,
                                                Instant from,
                                                Instant to,
                                                String operation,
                                                String transactionId) {
        List<AuditEntry> results = new ArrayList<>();
        if (ontologyId == null) {
            return results;
        }
        Path auditFile = resolveAuditFile(ontologyId);
        List<Path> files = new ArrayList<>(listRotatedFiles(ontologyId));
        // Add the current file last (newest entries) if it exists.
        // When the last append triggered a rotation, the current file may
        // not exist yet — rotated files still hold all prior entries.
        if (Files.exists(auditFile)) {
            files.add(auditFile);
        }
        for (Path file : files) {
            try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                lines.forEach(line -> {
                    if (line.isBlank()) {
                        return;
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = gson.fromJson(line, Map.class);
                        if (map == null) {
                            return;
                        }
                        AuditEntry entry = mapToEntry(map);
                        if (!matchesFilter(entry, from, to, operation, transactionId)) {
                            return;
                        }
                        // Defensive: skip entries whose ontologyId doesn't match
                        // (rotated files should never mix ontologies, but the
                        // guarantee is enforced here too).
                        if (!ontologyId.id().equals(entry.ontologyId())) {
                            return;
                        }
                        results.add(entry);
                    } catch (Exception ignored) {
                        // skip malformed line
                    }
                });
            } catch (IOException ignored) {
                // skip unreadable file
            }
        }
        results.sort(Comparator.comparing(AuditEntry::timestamp));
        return results;
    }

    /**
     * Convert an AuditEntry to a Map for tool-level JSON responses.
     */
    public List<Map<String, Object>> queryAsMaps(OntologyId ontologyId,
                                                  Instant from,
                                                  Instant to,
                                                  String operation,
                                                  String transactionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEntry e : query(ontologyId, from, to, operation, transactionId)) {
            out.add(e.toMap());
        }
        return out;
    }

    private Path resolveAuditFile(OntologyId ontologyId) {
        return workspaceOntologiesRoot
            .resolve(ontologyId.id())
            .resolve(CURRENT_FILE);
    }

    private List<Path> listRotatedFiles(OntologyId ontologyId) {
        Path dir = workspaceOntologiesRoot.resolve(ontologyId.id());
        List<Path> rotated = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return rotated;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                // Exclude the current file (audit.jsonl) which also matches
                // the prefix/suffix pattern — only true rotated files have
                // a timestamp between "audit." and ".jsonl".
                return name.startsWith(ROTATED_PREFIX) && name.endsWith(ROTATED_SUFFIX)
                    && !name.equals(CURRENT_FILE);
            }).sorted().forEach(rotated::add);
        } catch (IOException ignored) {
            // best-effort
        }
        return rotated;
    }

    private void rotateIfNeeded(OntologyId ontologyId) {
        Path current = resolveAuditFile(ontologyId);
        try {
            long size = Files.size(current);
            if (size <= maxBytes) {
                return;
            }
            String timestamp = ROTATION_TIMESTAMP.format(Instant.now());
            Path rotated = current.resolveSibling(ROTATED_PREFIX + timestamp + ROTATED_SUFFIX);
            // If the timestamped name collides (rapid successive rotations within
            // the same millisecond), append a numeric suffix.
            int suffix = 1;
            while (Files.exists(rotated)) {
                rotated = current.resolveSibling(
                    ROTATED_PREFIX + timestamp + "-" + suffix + ROTATED_SUFFIX);
                suffix++;
            }
            Files.move(current, rotated);
            enforceMaxFiles(ontologyId);
        } catch (IOException ignored) {
            // best-effort: leave the file un-rotated if move fails
        }
    }

    private void enforceMaxFiles(OntologyId ontologyId) {
        List<Path> rotated = listRotatedFiles(ontologyId);
        // sorted ascending = oldest first (timestamp-prefix sortable)
        while (rotated.size() > maxFiles) {
            Path oldest = rotated.remove(0);
            try {
                Files.deleteIfExists(oldest);
            } catch (IOException ignored) {
                // best-effort: if delete fails, stop pruning to avoid
                // unbounded rotation churn on transient FS errors.
                break;
            }
        }
    }

    private boolean matchesFilter(AuditEntry entry,
                                  Instant from,
                                  Instant to,
                                  String operation,
                                  String transactionId) {
        if (from != null && entry.timestamp().isBefore(from)) {
            return false;
        }
        if (to != null && entry.timestamp().isAfter(to)) {
            return false;
        }
        if (operation != null && !operation.isBlank()
            && !operation.equals(entry.operation())) {
            return false;
        }
        if (transactionId != null && !transactionId.isBlank()) {
            if (entry.transactionId() == null
                || !transactionId.equals(entry.transactionId())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private AuditEntry mapToEntry(Map<String, Object> m) {
        String auditId = (String) m.get("auditId");
        Instant timestamp = parseInstant(m.get("timestamp"));
        String ontologyId = (String) m.get("ontologyId");
        String transactionId = (String) m.get("transactionId");
        String operation = (String) m.get("operation");
        String author = (String) m.get("author");
        Object before = m.get("before");
        Object after = m.get("after");
        String versionId = (String) m.get("versionId");
        String result = (String) m.get("result");
        String detail = (String) m.get("detail");
        return new AuditEntry(auditId, timestamp, ontologyId, transactionId,
            operation, author, before, after, versionId, result, detail);
    }

    private static Instant parseInstant(Object raw) {
        if (raw == null) {
            return Instant.now();
        }
        if (raw instanceof Instant i) {
            return i;
        }
        try {
            return Instant.parse(raw.toString());
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static boolean parseBooleanProperty(String key, boolean def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private static long parseLongProperty(String key, long def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseIntProperty(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
