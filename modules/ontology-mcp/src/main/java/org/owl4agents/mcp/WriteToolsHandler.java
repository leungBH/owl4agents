package org.owl4agents.mcp;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.TemporaryOntologyFactory;
import org.owl4agents.reasoner.write.AuditEntry;
import org.owl4agents.reasoner.write.AuditLog;
import org.owl4agents.reasoner.write.OntologyEditService;
import org.owl4agents.reasoner.write.VersionHistoryStore;
import org.owl4agents.reasoner.write.VersionSnapshot;
import org.owl4agents.reasoner.write.WriteTransaction;
import org.owl4agents.reasoner.write.WriteTransactionService;
import org.owl4agents.shacl.ShaclValidationOptions;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.shacl.ShaclValidationService;
import org.owl4agents.shacl.ShapeRegistry;
import org.owl4agents.shacl.ShapeSet;
import org.owl4agents.shacl.ShaclJsonSerializer;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * v0.9.1 mcp-write-tools-expansion: dispatcher for the 8 transactional write
 * tools and the 3 new readonly observation tools (diff / version_history /
 * audit_log).
 *
 * <p>D1: caller-supplied transaction_id (UUID), lazy creation on first write.
 * D2: SHACL-on-commit orchestrated here; {@link WriteTransactionService#commit}
 * does NOT call SHACL.
 * D3: SHACL-on-commit reuses {@link ShapeRegistry} (operator-controlled).
 * Agents cannot upload inline SHACL. Violation blocks commit, keeps the
 * transaction open. Warning/Info do not block.
 * D6: 3 readonly tools observe committed state only.</p>
 *
 * <p>Return shape: {@code Map.of("status", "success", "data", ...)} on success
 * or {@code Map.of("status", "error", "error", Map.of("code", ..., "message",
 * ..., "details", ...))} on error — identical to {@link OntologyImportToolHandler}.</p>
 */
public final class WriteToolsHandler {

    private final WriteTransactionService writeTransactionService;
    private final OntologyEditService editService;
    private final VersionHistoryStore versionHistoryStore;
    private final AuditLog auditLog;
    private final ShaclValidationService shaclValidationService;
    private final ShapeRegistry shapeRegistry;
    private final OntologyCache ontologyCache;
    private final CatalogStore catalogStore;

    public WriteToolsHandler(WriteTransactionService writeTransactionService,
                             OntologyEditService editService,
                             VersionHistoryStore versionHistoryStore,
                             AuditLog auditLog,
                             ShaclValidationService shaclValidationService,
                             ShapeRegistry shapeRegistry,
                             OntologyCache ontologyCache,
                             CatalogStore catalogStore,
                             HomeDirectoryResolver homeResolver,
                             WorkspaceId workspaceId,
                             String allowedRootsCsv) {
        this.writeTransactionService = writeTransactionService;
        this.editService = editService;
        this.versionHistoryStore = versionHistoryStore;
        this.auditLog = auditLog;
        this.shaclValidationService = shaclValidationService;
        this.shapeRegistry = shapeRegistry;
        this.ontologyCache = ontologyCache;
        this.catalogStore = catalogStore;
    }

    /**
     * Dispatch a tool call. Recognizes the 8 write tools and the 3 readonly
     * observation tools registered in v0.9.1.
     */
    public Map<String, Object> execute(String toolName, Map<String, Object> args) {
        try {
            return switch (toolName) {
                case "ontology_add_axiom" -> doAddAxiom(args);
                case "ontology_remove_axiom" -> doRemoveAxiom(args);
                case "ontology_edit_entity" -> doEditEntity(args);
                case "ontology_create_class" -> doCreateClass(args);
                case "ontology_merge" -> doMerge(args);
                case "ontology_commit" -> doCommit(args);
                case "ontology_rollback" -> doRollback(args);
                case "ontology_rollback_to_version" -> doRollbackToVersion(args);
                case "ontology_diff" -> doDiff(args);
                case "ontology_version_history" -> doVersionHistory(args);
                case "ontology_audit_log" -> doAuditLog(args);
                default -> errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
                    "Unknown write tool: " + toolName));
            };
        } catch (Exception e) {
            return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
                "Tool '" + toolName + "' threw: " + e.getMessage()));
        }
    }

    // ── 8 write tools ──

    private Map<String, Object> doAddAxiom(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        Object axiom = args.get("axiom");
        String author = strArg(args, "author");
        ServiceResult<Map<String, Object>> r = editService.addAxiom(oid, txId, axiom, author);
        return resultToResponse(r, "add_axiom");
    }

    private Map<String, Object> doRemoveAxiom(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        Object axiom = args.get("axiom");
        String author = strArg(args, "author");
        ServiceResult<Map<String, Object>> r = editService.removeAxiom(oid, txId, axiom, author);
        return resultToResponse(r, "remove_axiom");
    }

    private Map<String, Object> doEditEntity(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        String entity = strArg(args, "entity");
        String label = strArg(args, "label");
        String comment = strArg(args, "comment");
        Object annotations = args.get("annotations");
        String author = strArg(args, "author");
        ServiceResult<Map<String, Object>> r =
            editService.editEntity(oid, txId, entity, label, comment, annotations, author);
        return resultToResponse(r, "edit_entity");
    }

    private Map<String, Object> doCreateClass(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        String cls = strArg(args, "class");
        Object sup = args.get("super");
        String author = strArg(args, "author");
        ServiceResult<Map<String, Object>> r =
            editService.createClass(oid, txId, cls, sup, author);
        return resultToResponse(r, "create_class");
    }

    private Map<String, Object> doMerge(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        String source = strArg(args, "source");
        String author = strArg(args, "author");
        ServiceResult<Map<String, Object>> r = editService.merge(oid, txId, source, author);
        return resultToResponse(r, "merge");
    }

    private Map<String, Object> doCommit(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        String message = strArg(args, "message");
        String author = strArg(args, "author");

        // Step 1: pre-check conflict (TRANSACTION_CONFLICT) before SHACL.
        ServiceResult<Void> conflict = writeTransactionService.checkConflict(txId);
        if (!conflict.isSuccess()) {
            return errorResponse(((ServiceResult.Error<Void>) conflict).error());
        }
        WriteTransaction tx = writeTransactionService.getTransaction(txId);
        if (tx == null) {
            return errorResponse(ServiceError.of(ErrorCode.TRANSACTION_NOT_FOUND));
        }

        // Step 2: SHACL-on-commit (D3). Reuse ShapeRegistry; run every
        // registered ShapeSet against the staging ontology converted to a
        // Jena Model. If no shapes are registered, commit succeeds (audit
        // detail records "no shapes registered"). Violations block commit;
        // warnings/infos do not.
        StringBuilder detailBuilder = new StringBuilder();
        List<Map<String, Object>> warningReports = new ArrayList<>();
        boolean hasViolation = false;
        Map<String, Object> violationReport = null;
        try {
            org.apache.jena.rdf.model.Model stagingModel = toJenaModel(tx.stagingOntology());
            List<ShapeSet> shapeSets = shapeRegistry.list();
            if (shapeSets.isEmpty()) {
                detailBuilder.append("no shapes registered");
            }
            for (ShapeSet ss : shapeSets) {
                ServiceResult<ShaclValidationReport> sr =
                    shaclValidationService.validateRegisteredShapes(
                        ss.id(), stagingModel, ShaclValidationOptions.defaults());
                if (!sr.isSuccess()) {
                    // Treat SHACL service error as a Violation (fail-closed).
                    hasViolation = true;
                    violationReport = Map.of(
                        "shapeSetId", ss.id(),
                        "error", ((ServiceResult.Error<ShaclValidationReport>) sr).error().message());
                    detailBuilder.append("shacl error on ").append(ss.id()).append("; ");
                    break;
                }
                ShaclValidationReport report = ((ServiceResult.Success<ShaclValidationReport>) sr).data();
                if (!report.violations().isEmpty()) {
                    hasViolation = true;
                    violationReport = ShaclJsonSerializer.reportToMap(report);
                    violationReport.put("shapeSetId", ss.id());
                    detailBuilder.append("violation on ").append(ss.id()).append("; ");
                    break;
                }
                if (!report.warnings().isEmpty()) {
                    Map<String, Object> w = ShaclJsonSerializer.reportToMap(report);
                    w.put("shapeSetId", ss.id());
                    warningReports.add(w);
                }
            }
        } catch (Exception e) {
            // Conversion / SHACL error → fail-closed: treat as violation.
            hasViolation = true;
            violationReport = Map.of("error", "SHACL-on-commit failed: " + e.getMessage());
            detailBuilder.append("shacl conversion error: ").append(e.getMessage());
        }

        if (hasViolation) {
            // Audit + return COMMIT_SHACL_VIOLATION; transaction stays open.
            auditLog.append(oid, new AuditEntry(
                null, Instant.now(), oid.id(), txId,
                "commit", author, null, null, null, "shacl_violation",
                detailBuilder.toString()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("shaclReport", violationReport == null ? Map.of() : violationReport);
            return errorResponse(ServiceError.of(ErrorCode.COMMIT_SHACL_VIOLATION,
                "Commit blocked by SHACL severity=Violation; transaction remains open.",
                details));
        }

        // Step 3: persist via WriteTransactionService.commit (no SHACL inside).
        ServiceResult<VersionSnapshot> cr =
            writeTransactionService.commit(txId, message, author);
        if (!cr.isSuccess()) {
            return errorResponse(((ServiceResult.Error<VersionSnapshot>) cr).error());
        }
        VersionSnapshot snap = ((ServiceResult.Success<VersionSnapshot>) cr).data();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", txId);
        data.put("versionId", snap.versionId());
        data.put("axiomCount", snap.axiomCount());
        data.put("entityCount", snap.entityCount());
        data.put("contentChecksum", snap.contentChecksum());
        data.put("parentVersionId", snap.parentVersionId());
        if (!warningReports.isEmpty()) data.put("warnings", warningReports);
        return Map.of("status", "success", "data", data);
    }

    private Map<String, Object> doRollback(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String txId = strArg(args, "transaction_id");
        if (txId == null) return missingArg("transaction_id");
        String author = strArg(args, "author");
        ServiceResult<Void> r = writeTransactionService.rollback(txId, author);
        if (!r.isSuccess()) {
            return errorResponse(((ServiceResult.Error<Void>) r).error());
        }
        return Map.of("status", "success", "data", Map.of(
            "transactionId", txId,
            "stagedAxiomCount", 0));
    }

    private Map<String, Object> doRollbackToVersion(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String versionId = strArg(args, "version_id");
        if (versionId == null) return missingArg("version_id");
        String author = strArg(args, "author");
        ServiceResult<VersionSnapshot> r =
            writeTransactionService.rollbackToVersion(oid, versionId, author);
        if (!r.isSuccess()) {
            return errorResponse(((ServiceResult.Error<VersionSnapshot>) r).error());
        }
        VersionSnapshot snap = ((ServiceResult.Success<VersionSnapshot>) r).data();
        return Map.of("status", "success", "data", Map.of(
            "versionId", snap.versionId(),
            "restoredFrom", versionId,
            "axiomCount", snap.axiomCount(),
            "entityCount", snap.entityCount(),
            "contentChecksum", snap.contentChecksum()));
    }

    // ── 3 readonly observation tools ──

    private Map<String, Object> doDiff(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        String from = strArg(args, "from", "committed");
        String to = strArg(args, "to", "committed");

        ServiceResult<OWLOntology> fromResult = resolveOntologyView(oid, from);
        if (!fromResult.isSuccess()) {
            return errorResponse(((ServiceResult.Error<OWLOntology>) fromResult).error());
        }
        ServiceResult<OWLOntology> toResult = resolveOntologyView(oid, to);
        if (!toResult.isSuccess()) {
            return errorResponse(((ServiceResult.Error<OWLOntology>) toResult).error());
        }
        OWLOntology fromOnt = ((ServiceResult.Success<OWLOntology>) fromResult).data();
        OWLOntology toOnt = ((ServiceResult.Success<OWLOntology>) toResult).data();

        Set<String> fromAxioms = axiomStringSet(fromOnt);
        Set<String> toAxioms = axiomStringSet(toOnt);
        Set<String> added = new TreeSet<>(toAxioms);
        added.removeAll(fromAxioms);
        Set<String> removed = new TreeSet<>(fromAxioms);
        removed.removeAll(toAxioms);

        return Map.of("status", "success", "data", Map.of(
            "ontologyId", oid.id(),
            "from", from,
            "to", to,
            "added", new ArrayList<>(added),
            "removed", new ArrayList<>(removed),
            "addedCount", added.size(),
            "removedCount", removed.size()));
    }

    private Map<String, Object> doVersionHistory(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        int limit = intArg(args, "limit", 50);
        ServiceResult<List<VersionSnapshot>> r =
            versionHistoryStore.listHistory(oid, limit);
        if (!r.isSuccess()) {
            return errorResponse(((ServiceResult.Error<List<VersionSnapshot>>) r).error());
        }
        List<VersionSnapshot> snaps = ((ServiceResult.Success<List<VersionSnapshot>>) r).data();
        List<Map<String, Object>> out = snaps.stream()
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("versionId", s.versionId());
                m.put("createdAt", s.createdAt().toString());
                m.put("author", s.author());
                m.put("parentVersionId", s.parentVersionId());
                m.put("changeSummary", s.changeSummary());
                m.put("axiomCount", s.axiomCount());
                m.put("entityCount", s.entityCount());
                m.put("contentChecksum", s.contentChecksum());
                return m;
            })
            .collect(Collectors.toList());
        return Map.of("status", "success", "data", Map.of(
            "ontologyId", oid.id(),
            "versions", out,
            "count", out.size()));
    }

    private Map<String, Object> doAuditLog(Map<String, Object> args) {
        OntologyId oid = parseOntologyId(args);
        if (oid == null) return missingOntologyId();
        Instant from = parseInstant(strArg(args, "from"));
        Instant to = parseInstant(strArg(args, "to"));
        String op = strArg(args, "op");
        String transactionId = strArg(args, "transaction");
        List<Map<String, Object>> entries =
            auditLog.queryAsMaps(oid, from, to, op, transactionId);
        return Map.of("status", "success", "data", Map.of(
            "ontologyId", oid.id(),
            "entries", entries,
            "count", entries.size()));
    }

    // ── Helpers ──

    /**
     * v0.9.1 Section 6.4: Post-import audit + baseline snapshot hook.
     *
     * Called by McpServerAdapter (or CliServiceFactory's import path) after
     * ontology_import returns. On success, creates a baseline
     * VersionSnapshot (parentVersionId=null) and appends an audit entry
     * with operation=import result=ok. On failure, appends an audit entry
     * with operation=import result=rejected and the error detail.
     *
     * Import result payload shape is unchanged — this hook only records
     * side-effects. Failures inside this hook (e.g. snapshot I/O error)
     * are swallowed so they never break the import flow.
     *
     * @param ontologyIdStr target ontology ID
     * @param success       whether the import succeeded
     * @param detail        error message on failure, or null on success
     * @param author        caller identity (default "mcp")
     */
    public void recordImportOutcome(String ontologyIdStr, boolean success,
                                    String detail, String author) {
        if (ontologyIdStr == null || ontologyIdStr.isBlank()) {
            return;
        }
        OntologyId oid = new OntologyId(ontologyIdStr);
        String effectiveAuthor = (author == null || author.isBlank()) ? "mcp" : author;
        String versionId = null;

        if (success) {
            // Create a baseline VersionSnapshot from the committed ontology.
            // parentVersionId=null marks this as the import baseline (never pruned).
            try {
                OWLOntology committed = ontologyCache.getOrCreate(oid);
                ServiceResult<VersionSnapshot> snap = versionHistoryStore.createSnapshot(
                    oid, committed, effectiveAuthor, null, "Initial import");
                if (snap.isSuccess()) {
                    versionId = ((ServiceResult.Success<VersionSnapshot>) snap).data().versionId();
                }
            } catch (Exception ignored) {
                // Snapshot failure must NOT break the import flow.
            }
        }

        AuditEntry entry = new AuditEntry(
            null,                       // auditId auto-generated
            Instant.now(),             // timestamp
            oid.id(),                  // ontologyId
            null,                       // transactionId (imports are non-transactional)
            "import",                   // operation
            effectiveAuthor,           // author
            null,                       // before (no prior state tracked here)
            null,                       // after
            versionId,                  // versionId (null on failure or snapshot error)
            success ? "ok" : "rejected",
            detail                      // detail (error message on failure)
        );
        auditLog.append(oid, entry);
    }

    /**
     * Resolve an ontology view for the diff tool. Accepts:
     * "committed" (default), a versionId, or "transaction:&lt;id&gt;".
     */
    private ServiceResult<OWLOntology> resolveOntologyView(OntologyId oid, String ref) {
        if (ref == null || ref.isBlank() || "committed".equals(ref)) {
            try {
                return ServiceResult.success(ontologyCache.getOrCreate(oid),
                    org.owl4agents.core.ResultMetadata.empty());
            } catch (Exception e) {
                return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND,
                    "Cannot load committed ontology: " + e.getMessage());
            }
        }
        if (ref.startsWith("transaction:")) {
            String txId = ref.substring("transaction:".length());
            WriteTransaction tx = writeTransactionService.getTransaction(txId);
            if (tx == null) {
                return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
            }
            return ServiceResult.success(tx.stagingOntology(),
                org.owl4agents.core.ResultMetadata.empty());
        }
        // Otherwise: versionId.
        ServiceResult<VersionSnapshot> found = versionHistoryStore.findSnapshot(oid, ref);
        if (!found.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<VersionSnapshot>) found).error());
        }
        VersionSnapshot snap = ((ServiceResult.Success<VersionSnapshot>) found).data();
        try {
            java.nio.file.Path blob = versionHistoryStore.resolveBlobPath(oid, snap.snapshotPath());
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            return ServiceResult.success(
                m.loadOntologyFromOntologyDocument(blob.toFile()),
                org.owl4agents.core.ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.VERSION_NOT_FOUND,
                "Cannot load snapshot blob: " + e.getMessage());
        }
    }

    private Set<String> axiomStringSet(OWLOntology ont) {
        Set<String> out = new TreeSet<>();
        for (OWLAxiom ax : ont.getAxioms()) {
            out.add(ax.toString());
        }
        return out;
    }

    /**
     * Convert an OWLOntology to a Jena Model (RDF/XML round-trip).
     * Pattern matches McpServerAdapter line 2590.
     */
    private org.apache.jena.rdf.model.Model toJenaModel(OWLOntology ontology) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RDFXMLDocumentFormat format = new RDFXMLDocumentFormat();
        ontology.getOWLOntologyManager().saveOntology(ontology, format, baos);
        org.apache.jena.rdf.model.Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
            model.read(bais, null, "RDF/XML");
        }
        return model;
    }

    private OntologyId parseOntologyId(Map<String, Object> args) {
        Object v = args.get("ontology_id");
        if (v == null) return null;
        String s = v.toString();
        if (s.isBlank()) return null;
        return new OntologyId(s);
    }

    private static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static String strArg(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> missingOntologyId() {
        return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
            "ontology_id is required"));
    }

    private static Map<String, Object> missingArg(String name) {
        return errorResponse(ServiceError.of(ErrorCode.INVALID_ARGUMENTS,
            name + " is required"));
    }

    private static Map<String, Object> resultToResponse(ServiceResult<Map<String, Object>> r, String op) {
        if (r.isSuccess()) {
            return Map.of("status", "success",
                "data", ((ServiceResult.Success<Map<String, Object>>) r).data());
        }
        return errorResponse(((ServiceResult.Error<Map<String, Object>>) r).error());
    }

    private static Map<String, Object> errorResponse(ServiceError error) {
        Map<String, Object> errorObj = new HashMap<>();
        errorObj.put("code", error.code().code());
        errorObj.put("message", error.message());
        if (!error.details().isEmpty()) {
            errorObj.put("details", error.details());
        }
        return Map.of("status", "error", "error", errorObj);
    }
}
