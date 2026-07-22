package org.owl4agents.toolcall.pipeline;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.AnswerVerificationReport;
import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.ClaimWorkflowResult;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.overlay.DynamicStateParser;
import org.owl4agents.overlay.EnvironmentSnapshot;
import org.owl4agents.overlay.OverlayOptions;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.overlay.TransientOntologyOverlayService;
import org.owl4agents.overlay.TransientOverlay;
import org.owl4agents.shacl.ShaclValidationOptions;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.shacl.ShaclValidationService;
import org.owl4agents.shacl.ShapeRegistry;
import org.owl4agents.toolcall.JsonSchemaPreValidator;
import org.owl4agents.toolcall.JsonSchemaViolation;
import org.owl4agents.toolcall.RiskLevel;
import org.owl4agents.toolcall.ToolCallExecutionStatus;
import org.owl4agents.toolcall.ToolCallValidationReport;
import org.owl4agents.toolcall.ToolContract;
import org.owl4agents.toolcall.ToolContractRegistry;
import org.owl4agents.toolcall.ValidationDecision;
import org.owl4agents.toolcall.decomposition.ClaimDecomposer;
import org.owl4agents.toolcall.decomposition.ToolCallClaimBatch;
import org.owl4agents.toolcall.decomposition.ToolCallClaimBatchAdapter;
import org.owl4agents.validation.ClaimWorkflowService;
import org.semanticweb.owlapi.model.OWLOntology;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 PL-001 / PL-002 / PL-003 / D12 / D14: The 10-stage
 * {@code ToolCallValidationPipeline}.
 *
 * <p>Executes the 10 fixed stages in order (Parse → Load Contract →
 * JSON Schema → Build Overlay → Claim Decomposition → OWL Batch →
 * SHACL → Risk Eval → Decision → Report). Each stage (1-9) applies a
 * short-circuit rule at its entry; when a stage short-circuits,
 * subsequent stages (except stage 10 REPORT) are skipped. Stage 10
 * ALWAYS executes so every pipeline run produces a complete
 * {@link ToolCallValidationReport}.</p>
 *
 * <p>The pipeline reuses services from Sections 3-6 of the v0.8.7
 * change (per the task description "MUST reuse services from Sections
 * 3, 4, 5, 6 — do NOT reimplement"):</p>
 * <ul>
 *   <li>Section 3 (SHACL): {@link ShaclValidationService},
 *       {@link ShapeRegistry} (stage 7)</li>
 *   <li>Section 4 (Overlay): {@link TransientOntologyOverlayService}
 *       (stage 4)</li>
 *   <li>Section 5 (ToolCall model): {@link ToolContractRegistry},
 *       {@link JsonSchemaPreValidator} (stages 2, 3)</li>
 *   <li>Section 6 (Claim decomposition): {@link ClaimDecomposer},
 *       {@link ToolCallClaimBatchAdapter} (stage 5)</li>
 *   <li>v0.5 (Claim workflow): {@link ClaimWorkflowService} via
 *       {@link ClaimWorkflowService#verifyBatchAgainstOntology} (stage 6)</li>
 * </ul>
 *
 * <p>The pipeline is the single service layer shared by both the MCP
 * tools (via {@code PipelineMcpTools}) and the CLI (via
 * {@code ToolCallValidateCommand}). The output JSON for the same fixture
 * is byte-for-byte identical between CLI and MCP (per spec "CLI and MCP
 * Service Contract Sharing").</p>
 *
 * <p>Thread-safety: the pipeline itself is stateless and safe to call
 * concurrently. Each invocation constructs its own
 * {@link PipelineContext} so there is no shared mutable state.</p>
 */
public final class ToolCallValidationPipeline {

    private final ToolContractRegistry toolContractRegistry;
    private final TransientOntologyOverlayService overlayService;
    private final ClaimWorkflowService claimWorkflowService;
    private final ShapeRegistry shapeRegistry;
    private final ShaclValidationService shaclValidationService;
    private final JsonSchemaPreValidator jsonSchemaValidator;
    private final ClaimDecomposer claimDecomposer;

    private ToolCallValidationPipeline(Builder b) {
        this.toolContractRegistry = b.toolContractRegistry;
        this.overlayService = b.overlayService;
        this.claimWorkflowService = b.claimWorkflowService;
        this.shapeRegistry = b.shapeRegistry;
        this.shaclValidationService = b.shaclValidationService;
        this.jsonSchemaValidator = b.jsonSchemaValidator != null
            ? b.jsonSchemaValidator : new JsonSchemaPreValidator();
        this.claimDecomposer = b.claimDecomposer != null
            ? b.claimDecomposer : new ClaimDecomposer();
    }

    /**
     * Validate a tool call candidate against the given base ontology
     * and dynamic environment snapshot.
     *
     * @param candidate        the parsed tool call candidate (stage 1
     *                        already completed by the caller; pass null
     *                        only when the raw JSON parse failed, in
     *                        which case use
     *                        {@link #validateRawJson(String, String, EnvironmentSnapshot)})
     * @param ontologyId       the base ontology ID to load for the overlay
     * @param snapshot         the dynamic environment snapshot; null
     *                        short-circuits at stage 4 (no overlay built)
     * @return a non-null {@link ToolCallValidationReport}; never throws
     */
    public ToolCallValidationReport validate(ToolCallCandidate candidate,
                                              String ontologyId,
                                              EnvironmentSnapshot snapshot) {
        PipelineContext ctx = new PipelineContext();
        try {
            // ── Stage 1: Parse ──
            stageParse(ctx, candidate);

            // ── Stage 2: Load Tool Contract ──
            if (!ctx.isShortCircuited()) {
                stageLoadContract(ctx, ontologyId);
            }

            // ── Stage 3: JSON Schema validation ──
            if (!ctx.isShortCircuited()) {
                stageJsonSchema(ctx);
            }

            // ── Stage 4: Build transient environment graph ──
            if (!ctx.isShortCircuited()) {
                stageBuildOverlay(ctx, ontologyId, snapshot);
            }

            // ── Stage 5: OWL claim decomposition ──
            if (!ctx.isShortCircuited()) {
                stageClaimDecomposition(ctx);
            }

            // ── Stage 6: OWL batch verification ──
            if (!ctx.isShortCircuited()) {
                stageOwlBatch(ctx, ontologyId);
            }

            // ── Stage 7: SHACL validation ──
            if (!ctx.isShortCircuited()) {
                stageShacl(ctx);
            }

            // ── Stage 8: Risk evaluation ──
            if (!ctx.isShortCircuited()) {
                stageRiskEval(ctx);
            }

            // ── Stage 9: Decision ──
            if (!ctx.isShortCircuited()) {
                stageDecision(ctx);
            }

            // ── Stage 10: Report (ALWAYS executes) ──
            return stageReport(ctx);
        } finally {
            ctx.releaseOverlay();
        }
    }

    /**
     * Validate a raw JSON tool call string (stage 1 parses it). Used by
     * the MCP tool when the candidate arrives as a JSON string.
     */
    public ToolCallValidationReport validateRawJson(String rawJson,
                                                     String ontologyId,
                                                     EnvironmentSnapshot snapshot) {
        ToolCallCandidate candidate = null;
        String parseError = null;
        if (rawJson == null || rawJson.isBlank()) {
            parseError = "rawJson is null or blank";
        } else {
            try {
                candidate = parseCandidate(rawJson);
            } catch (RuntimeException e) {
                parseError = e.getMessage();
            }
        }
        if (parseError != null) {
            // Short-circuit at stage 1.
            PipelineContext ctx = new PipelineContext();
            ShortCircuitStrategy.ShortCircuitResult r =
                ShortCircuitStrategy.forParseFailure(parseError).orElseThrow();
            applyShortCircuit(ctx, r);
            return stageReport(ctx);
        }
        return validate(candidate, ontologyId, snapshot);
    }

    /**
     * Validate a tool call candidate with pre-parsed RDF axioms as the
     * dynamic state. Used by the CLI when the {@code --state} argument
     * is a Turtle/JSON-LD/N-Triples file (per cli-interface spec
     * "toolcall validate accepts RDF state input").
     *
     * <p>Stage 4 uses the pre-parsed axioms directly instead of calling
     * {@link DynamicStateParser#parse(EnvironmentSnapshot)}. The
     * {@code snapshot} argument is still used for the report's
     * {@code stateVersion} field (snapshotId + checksum).</p>
     *
     * @param candidate        the parsed tool call candidate
     * @param ontologyId       the base ontology ID
     * @param snapshot         metadata-only snapshot (snapshotId + checksum);
     *                         devices/userContexts/pendingToolCalls may be empty
     * @param preParsedAxioms  the dynamic state axioms parsed from the RDF input
     * @return a non-null {@link ToolCallValidationReport}; never throws
     */
    public ToolCallValidationReport validateWithAxioms(ToolCallCandidate candidate,
                                                        String ontologyId,
                                                        EnvironmentSnapshot snapshot,
                                                        Collection<org.semanticweb.owlapi.model.OWLAxiom> preParsedAxioms) {
        PipelineContext ctx = new PipelineContext();
        try {
            // ── Stage 1: Parse ──
            stageParse(ctx, candidate);

            // ── Stage 2: Load Tool Contract ──
            if (!ctx.isShortCircuited()) {
                stageLoadContract(ctx, ontologyId);
            }

            // ── Stage 3: JSON Schema validation ──
            if (!ctx.isShortCircuited()) {
                stageJsonSchema(ctx);
            }

            // ── Stage 4: Build transient environment graph (with pre-parsed axioms) ──
            if (!ctx.isShortCircuited()) {
                stageBuildOverlayWithAxioms(ctx, ontologyId, snapshot, preParsedAxioms);
            }

            // ── Stage 5: OWL claim decomposition ──
            if (!ctx.isShortCircuited()) {
                stageClaimDecomposition(ctx);
            }

            // ── Stage 6: OWL batch verification ──
            if (!ctx.isShortCircuited()) {
                stageOwlBatch(ctx, ontologyId);
            }

            // ── Stage 7: SHACL validation ──
            if (!ctx.isShortCircuited()) {
                stageShacl(ctx);
            }

            // ── Stage 8: Risk evaluation ──
            if (!ctx.isShortCircuited()) {
                stageRiskEval(ctx);
            }

            // ── Stage 9: Decision ──
            if (!ctx.isShortCircuited()) {
                stageDecision(ctx);
            }

            // ── Stage 10: Report (ALWAYS executes) ──
            return stageReport(ctx);
        } finally {
            ctx.releaseOverlay();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Stage implementations
    // ────────────────────────────────────────────────────────────────────

    private void stageParse(PipelineContext ctx, ToolCallCandidate candidate) {
        long start = System.nanoTime();
        if (candidate == null) {
            ShortCircuitStrategy.forParseFailure("candidate is null")
                .ifPresent(r -> applyShortCircuit(ctx, r));
            ctx.recordTiming(PipelineStage.PARSE, elapsedMs(start));
            return;
        }
        ctx.setCandidate(candidate);
        ctx.recordTiming(PipelineStage.PARSE, elapsedMs(start));
    }

    private void stageLoadContract(PipelineContext ctx, String ontologyId) {
        long start = System.nanoTime();
        try {
            ToolCallCandidate candidate = ctx.candidate();
            String toolName = candidate.toolName();
            // Look up the contract.
            ServiceResult<ToolContract> r = toolContractRegistry.get(toolName);
            if (!r.isSuccess()) {
                ServiceResult.Error<ToolContract> err = (ServiceResult.Error<ToolContract>) r;
                List<String> known = toolContractRegistry.list().stream()
                    .map(ToolContract::toolName).toList();
                ShortCircuitStrategy.forUnknownToolName(toolName, known)
                    .ifPresent(sc -> {
                        applyShortCircuit(ctx, sc);
                        ctx.setError(err.error().code(), sc.reason());
                    });
                ctx.recordTiming(PipelineStage.LOAD_CONTRACT, elapsedMs(start));
                return;
            }
            ToolContract contract = ((ServiceResult.Success<ToolContract>) r).data();
            ctx.setContract(contract);

            // Per spec "Unregistered shapeSetId in contract short-circuits at
            // stage 2": verify all shapeSetIds referenced by the contract
            // are registered.
            for (String shapeSetId : contract.shapeSetIds()) {
                if (shapeRegistry != null) {
                    Optional<org.owl4agents.shacl.ShapeSet> opt = shapeRegistry.get(shapeSetId);
                    if (opt.isEmpty()) {
                        ShortCircuitStrategy.forUnregisteredShapeSetId(shapeSetId)
                            .ifPresent(sc -> {
                                applyShortCircuit(ctx, sc);
                                ctx.setError(sc.errorCode(), sc.reason());
                            });
                        ctx.recordTiming(PipelineStage.LOAD_CONTRACT, elapsedMs(start));
                        return;
                    }
                }
            }

            // Per spec "High-risk short-circuit override": check whether the
            // candidate is high-risk IMMEDIATELY after stage 2 loads the
            // contract. This flag is consulted by
            // PipelineContext.resolvedShortCircuitDecision() to force
            // REQUEST_CONFIRMATION for any subsequent short-circuit (except
            // SYSTEM_ERROR).
            boolean highRisk = RiskPolicy.isHighRisk(toolName, contract);
            ctx.setHighRisk(highRisk);
            ctx.setRiskLevel(RiskPolicy.effectiveRiskLevel(toolName, contract));

            ctx.recordTiming(PipelineStage.LOAD_CONTRACT, elapsedMs(start));
        } catch (RuntimeException e) {
            ShortCircuitStrategy.forParseFailure("stage 2 exception: " + e.getMessage())
                .ifPresent(sc -> applyShortCircuit(ctx, sc));
            ctx.recordTiming(PipelineStage.LOAD_CONTRACT, elapsedMs(start));
        }
    }

    private void stageJsonSchema(PipelineContext ctx) {
        long start = System.nanoTime();
        ToolContract contract = ctx.contract();
        ToolCallCandidate candidate = ctx.candidate();
        List<JsonSchemaViolation> violations = jsonSchemaValidator.validate(contract, candidate);
        ctx.setJsonSchemaViolations(violations);
        ShortCircuitStrategy.forJsonSchemaFailure(violations, jsonSchemaValidator)
            .ifPresent(sc -> {
                applyShortCircuit(ctx, sc);
                // Populate repairSpace for the AUTO_REPAIR/CLARIFY/REQUEST_CONFIRMATION path.
                for (JsonSchemaViolation v : violations) {
                    ctx.addRepair(suggestRepair(v));
                }
            });
        ctx.recordTiming(PipelineStage.JSON_SCHEMA, elapsedMs(start));
    }

    private void stageBuildOverlay(PipelineContext ctx, String ontologyId,
                                    EnvironmentSnapshot snapshot) {
        long start = System.nanoTime();
        if (snapshot == null) {
            ShortCircuitStrategy.forInvalidSnapshot("snapshot is null")
                .ifPresent(sc -> applyShortCircuit(ctx, sc));
            ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
            return;
        }
        ctx.setEnvironmentSnapshot(snapshot);

        // Parse the snapshot's dynamic state into axioms.
        Collection<org.semanticweb.owlapi.model.OWLAxiom> dynamicAxioms;
        try {
            dynamicAxioms = DynamicStateParser.parse(snapshot);
        } catch (RuntimeException e) {
            ShortCircuitStrategy.forDynamicStateParseFailure(e.getMessage())
                .ifPresent(sc -> applyShortCircuit(ctx, sc));
            ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
            return;
        }

        // Create the overlay.
        OntologyId ontId = new OntologyId(ontologyId);
        ServiceResult<TransientOverlay> r = overlayService.createOverlay(
            ontId, dynamicAxioms, OverlayOptions.defaults());
        if (!r.isSuccess()) {
            ServiceResult.Error<TransientOverlay> err =
                (ServiceResult.Error<TransientOverlay>) r;
            ShortCircuitStrategy.forOverlayCreationFailure(err.error())
                .ifPresent(sc -> {
                    applyShortCircuit(ctx, sc);
                    ctx.setError(err.error().code(), sc.reason());
                });
            ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
            return;
        }
        TransientOverlay overlay = ((ServiceResult.Success<TransientOverlay>) r).data();
        ctx.setOverlay(overlay);

        // Per spec "Unknown device short-circuits SHACL": if the candidate
        // targets an entity not present in the overlay, short-circuit at
        // stage 4 (technically the spec says stage 4 or 5; we do it here
        // because the overlay is now available for the entity lookup).
        ToolCallCandidate candidate = ctx.candidate();
        if (candidate.targetEntity().isPresent() && ctx.contract() != null
            && ctx.contract().targetsEntity()) {
            String targetEntity = candidate.targetEntity().get();
            if (!isEntityInOverlay(overlay.ontology(), targetEntity)) {
                ShortCircuitStrategy.forUnknownDevice(targetEntity)
                    .ifPresent(sc -> applyShortCircuit(ctx, sc));
                ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
                return;
            }
        }

        ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
    }

    /**
     * Stage 4 variant that uses pre-parsed RDF axioms (used by the CLI
     * when the {@code --state} argument is a Turtle/JSON-LD/N-Triples
     * file). The snapshot is still set on the context for the report's
     * {@code stateVersion} field, but the axioms come from the caller.
     */
    private void stageBuildOverlayWithAxioms(PipelineContext ctx, String ontologyId,
                                              EnvironmentSnapshot snapshot,
                                              Collection<org.semanticweb.owlapi.model.OWLAxiom> preParsedAxioms) {
        long start = System.nanoTime();
        if (snapshot == null) {
            ShortCircuitStrategy.forInvalidSnapshot("snapshot is null")
                .ifPresent(sc -> applyShortCircuit(ctx, sc));
            ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
            return;
        }
        ctx.setEnvironmentSnapshot(snapshot);

        Collection<org.semanticweb.owlapi.model.OWLAxiom> dynamicAxioms =
            preParsedAxioms == null ? List.of() : preParsedAxioms;

        // Create the overlay.
        OntologyId ontId = new OntologyId(ontologyId);
        ServiceResult<TransientOverlay> r = overlayService.createOverlay(
            ontId, dynamicAxioms, OverlayOptions.defaults());
        if (!r.isSuccess()) {
            ServiceResult.Error<TransientOverlay> err =
                (ServiceResult.Error<TransientOverlay>) r;
            ShortCircuitStrategy.forOverlayCreationFailure(err.error())
                .ifPresent(sc -> {
                    applyShortCircuit(ctx, sc);
                    ctx.setError(err.error().code(), sc.reason());
                });
            ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
            return;
        }
        TransientOverlay overlay = ((ServiceResult.Success<TransientOverlay>) r).data();
        ctx.setOverlay(overlay);

        // Per spec "Unknown device short-circuits SHACL": if the candidate
        // targets an entity not present in the overlay, short-circuit at
        // stage 4.
        ToolCallCandidate candidate = ctx.candidate();
        if (candidate.targetEntity().isPresent() && ctx.contract() != null
            && ctx.contract().targetsEntity()) {
            String targetEntity = candidate.targetEntity().get();
            if (!isEntityInOverlay(overlay.ontology(), targetEntity)) {
                ShortCircuitStrategy.forUnknownDevice(targetEntity)
                    .ifPresent(sc -> applyShortCircuit(ctx, sc));
                ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
                return;
            }
        }

        ctx.recordTiming(PipelineStage.BUILD_OVERLAY, elapsedMs(start));
    }

    private void stageClaimDecomposition(PipelineContext ctx) {
        long start = System.nanoTime();
        try {
            ToolCallClaimBatch batch = claimDecomposer.decompose(
                ctx.candidate(), ctx.contract());
            ctx.setClaimBatch(batch);
            ctx.recordTiming(PipelineStage.CLAIM_DECOMPOSITION, elapsedMs(start));
        } catch (RuntimeException e) {
            // Decomposition failure → SYSTEM_ERROR per spec table.
            ctx.shortCircuitAt(PipelineStage.CLAIM_DECOMPOSITION,
                ValidationDecision.SYSTEM_ERROR,
                "Claim decomposition failed: " + e.getMessage(),
                ToolCallExecutionStatus.ERROR);
            ctx.setError(ErrorCode.INVALID_ARGUMENTS, e.getMessage());
            ctx.recordTiming(PipelineStage.CLAIM_DECOMPOSITION, elapsedMs(start));
        }
    }

    private void stageOwlBatch(PipelineContext ctx, String ontologyId) {
        long start = System.nanoTime();
        try {
            ToolCallClaimBatch batch = ctx.claimBatch();
            if (batch == null || batch.isEmpty()) {
                // No claims to verify (e.g. query-only tool). Skip OWL batch
                // without short-circuiting — stage 9 will set EXECUTE.
                ctx.recordTiming(PipelineStage.OWL_BATCH, elapsedMs(start));
                return;
            }
            ToolCallClaimBatchAdapter adapter = new ToolCallClaimBatchAdapter(batch, ontologyId);
            org.owl4agents.core.model.ClaimBatchInput input = adapter.toClaimBatchInput();
            OWLOntology overlayOntology = ctx.overlay().ontology();
            ServiceResult<AnswerVerificationReport> r =
                claimWorkflowService.verifyBatchAgainstOntology(input, overlayOntology, ontologyId);
            if (!r.isSuccess()) {
                ServiceResult.Error<AnswerVerificationReport> err =
                    (ServiceResult.Error<AnswerVerificationReport>) r;
                // Distinguish reasoner timeout from other errors.
                if (err.error().code() == ErrorCode.REASONER_TIMEOUT) {
                    ShortCircuitStrategy.forReasonerTimeout()
                        .ifPresent(sc -> applyShortCircuit(ctx, sc));
                } else {
                    ctx.shortCircuitAt(PipelineStage.OWL_BATCH,
                        ValidationDecision.SYSTEM_ERROR,
                        "OWL batch verification failed: " + err.error().code()
                            + " " + err.error().message(),
                        ToolCallExecutionStatus.ERROR);
                    ctx.setError(err.error().code(), err.error().message());
                }
                ctx.recordTiming(PipelineStage.OWL_BATCH, elapsedMs(start));
                return;
            }
            AnswerVerificationReport report =
                ((ServiceResult.Success<AnswerVerificationReport>) r).data();
            ctx.setOwlReport(report);

            // Check for OWL contradiction (CONTRADICTED aggregate status).
            if (report.aggregateStatus() == AggregateAnswerStatus.CONTRADICTED) {
                String evidence = summarizeContradictions(report);
                ShortCircuitStrategy.forOwlContradiction(evidence)
                    .ifPresent(sc -> applyShortCircuit(ctx, sc));
            }

            ctx.recordTiming(PipelineStage.OWL_BATCH, elapsedMs(start));
        } catch (RuntimeException e) {
            // Treat unexpected exceptions as SYSTEM_ERROR.
            ctx.shortCircuitAt(PipelineStage.OWL_BATCH,
                ValidationDecision.SYSTEM_ERROR,
                "OWL batch verification exception: " + e.getMessage(),
                ToolCallExecutionStatus.ERROR);
            ctx.setError(ErrorCode.INVALID_ARGUMENTS, e.getMessage());
            ctx.recordTiming(PipelineStage.OWL_BATCH, elapsedMs(start));
        }
    }

    private void stageShacl(PipelineContext ctx) {
        long start = System.nanoTime();
        ToolContract contract = ctx.contract();
        if (contract == null || !contract.requiresShacl()) {
            // No SHACL shapes to apply — skip stage 7 without short-circuiting.
            ctx.recordTiming(PipelineStage.SHACL, elapsedMs(start));
            return;
        }
        if (shaclValidationService == null || shapeRegistry == null) {
            // SHACL services unavailable — skip without short-circuiting.
            ctx.recordTiming(PipelineStage.SHACL, elapsedMs(start));
            return;
        }

        // Convert overlay OWLOntology to Jena Model for SHACL.
        Model dataModel;
        try {
            dataModel = convertOntologyToJenaModel(ctx.overlay().ontology());
        } catch (RuntimeException e) {
            ctx.shortCircuitAt(PipelineStage.SHACL,
                ValidationDecision.SYSTEM_ERROR,
                "Failed to convert overlay to Jena Model: " + e.getMessage(),
                ToolCallExecutionStatus.ERROR);
            ctx.setError(ErrorCode.INVALID_ARGUMENTS, e.getMessage());
            ctx.recordTiming(PipelineStage.SHACL, elapsedMs(start));
            return;
        }

        // Apply each registered shapeSetId in order. Accumulate violations.
        List<org.owl4agents.shacl.ShaclViolation> allViolations = new ArrayList<>();
        List<org.owl4agents.shacl.ShaclViolation> allWarnings = new ArrayList<>();
        List<org.owl4agents.shacl.ShaclViolation> allInfos = new ArrayList<>();
        long totalElapsed = 0L;
        boolean conforms = true;
        Optional<String> shapeSetId = Optional.empty();
        for (String id : contract.shapeSetIds()) {
            ServiceResult<Model> resolveResult = shapeRegistry.resolve(id);
            if (!resolveResult.isSuccess()) {
                ShortCircuitStrategy.forShaclShapesLoadFailure(id)
                    .ifPresent(sc -> applyShortCircuit(ctx, sc));
                ctx.recordTiming(PipelineStage.SHACL, elapsedMs(start));
                return;
            }
            // The shapes are looked up by the validation service via the registry.
            ServiceResult<ShaclValidationReport> r =
                shaclValidationService.validateRegisteredShapes(
                    id, dataModel, ShaclValidationOptions.defaults());
            if (!r.isSuccess()) {
                ServiceResult.Error<ShaclValidationReport> err =
                    (ServiceResult.Error<ShaclValidationReport>) r;
                ctx.shortCircuitAt(PipelineStage.SHACL,
                    ValidationDecision.SYSTEM_ERROR,
                    "SHACL validation failed: " + err.error().code()
                        + " " + err.error().message(),
                    ToolCallExecutionStatus.ERROR);
                ctx.setError(err.error().code(), err.error().message());
                ctx.recordTiming(PipelineStage.SHACL, elapsedMs(start));
                return;
            }
            ShaclValidationReport report =
                ((ServiceResult.Success<ShaclValidationReport>) r).data();
            allViolations.addAll(report.violations());
            allWarnings.addAll(report.warnings());
            allInfos.addAll(report.infos());
            totalElapsed += report.elapsedMs();
            if (!report.conforms()) conforms = false;
            if (shapeSetId.isEmpty()) shapeSetId = report.shapeSetId();
        }

        ShaclValidationReport aggregate = new ShaclValidationReport(
            conforms, allViolations, allWarnings, allInfos, totalElapsed,
            shapeSetId, ShaclValidationReport.SCHEMA_VERSION);
        ctx.setShaclReport(aggregate);

        // Apply SHACL Violation short-circuit.
        ShortCircuitStrategy.forShaclViolation(aggregate)
            .ifPresent(sc -> {
                applyShortCircuit(ctx, sc);
                // Populate repairSpace from violation repairHints.
                for (org.owl4agents.shacl.ShaclViolation v : aggregate.violations()) {
                    if (v.repairHint() != null && !v.repairHint().isBlank()) {
                        ctx.addRepair(v.repairHint());
                    }
                }
            });

        ctx.recordTiming(PipelineStage.SHACL, elapsedMs(start));
    }

    private void stageRiskEval(PipelineContext ctx) {
        long start = System.nanoTime();
        // Stage 8 sets the riskLevel on the context (already set by stage 2
        // via RiskPolicy.effectiveRiskLevel). Here we just record timing
        // and check the high-risk gate one more time for clarity.
        // The actual decision override happens in stage 9.
        ToolCallCandidate candidate = ctx.candidate();
        ToolContract contract = ctx.contract();
        ctx.setHighRisk(RiskPolicy.isHighRisk(candidate.toolName(), contract));
        ctx.setRiskLevel(RiskPolicy.effectiveRiskLevel(candidate.toolName(), contract));
        ctx.recordTiming(PipelineStage.RISK_EVAL, elapsedMs(start));
    }

    private void stageDecision(PipelineContext ctx) {
        long start = System.nanoTime();
        // Stage 9: compute the final decision per the D14 decision matrix.
        // At this point the pipeline did NOT short-circuit (we're here),
        // so all stages 1-8 passed (or produced non-blocking warnings).
        ValidationDecision decision;

        if (ctx.shaclReport() != null && !ctx.shaclReport().violations().isEmpty()) {
            // SHACL Violation-severity present (this should have short-circuited
            // at stage 7, but if it didn't because the strategy decided
            // AUTO_REPAIR, we still don't allow EXECUTE).
            decision = ValidationDecision.REJECT;
        } else if (ctx.owlReport() != null
            && ctx.owlReport().aggregateStatus() == AggregateAnswerStatus.CONTRADICTED) {
            decision = ValidationDecision.REJECT;
        } else if (ctx.owlReport() != null
            && (ctx.owlReport().aggregateStatus() == AggregateAnswerStatus.INSUFFICIENT_EVIDENCE
            || ctx.owlReport().aggregateStatus() == AggregateAnswerStatus.OUT_OF_SCOPE
            || ctx.owlReport().aggregateStatus() == AggregateAnswerStatus.PARTIALLY_VERIFIED)) {
            decision = ValidationDecision.CLARIFY;
        } else {
            decision = ValidationDecision.EXECUTE;
        }

        // D14 high-risk override: force REQUEST_CONFIRMATION for high-risk
        // actions even when all stages passed.
        if (ctx.isHighRisk()) {
            decision = ValidationDecision.REQUEST_CONFIRMATION;
        }

        ctx.setDecision(decision);
        ctx.recordTiming(PipelineStage.DECISION, elapsedMs(start));
    }

    private ToolCallValidationReport stageReport(PipelineContext ctx) {
        long start = System.nanoTime();
        // If the pipeline short-circuited, use the resolved short-circuit
        // decision (which applies the high-risk override). Otherwise use
        // the stage 9 decision.
        ValidationDecision finalDecision = ctx.isShortCircuited()
            ? ctx.resolvedShortCircuitDecision()
            : ctx.decision();

        ToolCallExecutionStatus execStatus = computeExecutionStatus(ctx);
        String callId = ctx.candidate() != null ? ctx.candidate().callId() : "unknown";

        // Convert OWL report to owlClaimResults list of maps.
        List<Map<String, Object>> owlClaimResults = convertOwlReport(ctx.owlReport());

        // Convert SHACL violations to the report's list (warnings excluded
        // per spec "SHACL Warning does not block").
        List<org.owl4agents.shacl.ShaclViolation> shaclViolations = ctx.shaclReport() != null
            ? ctx.shaclReport().violations() : List.of();

        ToolCallValidationReport report = new ToolCallValidationReport(
            ToolCallValidationReport.SCHEMA_VERSION,
            callId,
            execStatus,
            finalDecision,
            ctx.riskLevel(),
            ctx.jsonSchemaViolations(),
            owlClaimResults,
            shaclViolations,
            ctx.stateVersion(),
            ctx.evidence(),
            ctx.repairSpace(),
            ctx.timing(),
            ctx.totalElapsedMs()
        );

        ctx.recordTiming(PipelineStage.REPORT, elapsedMs(start));
        // Re-build the report with the REPORT timing recorded.
        ToolCallValidationReport finalReport = new ToolCallValidationReport(
            report.schemaVersion(),
            report.callId(),
            report.executionStatus(),
            report.decision(),
            report.riskLevel(),
            report.jsonSchemaViolations(),
            report.owlClaimResults(),
            report.shaclViolations(),
            report.stateVersion(),
            report.evidence(),
            report.repairSpace(),
            ctx.timing(),
            ctx.totalElapsedMs()
        );
        return finalReport;
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private void applyShortCircuit(PipelineContext ctx,
                                    ShortCircuitStrategy.ShortCircuitResult sc) {
        ctx.shortCircuitAt(sc.stage(), sc.decision(), sc.reason(),
            sc.executionStatus() == null
                ? org.owl4agents.toolcall.ToolCallExecutionStatus.OK
                : sc.executionStatus().toReportStatus());
        if (sc.errorCode() != null) {
            ctx.setError(sc.errorCode(), sc.reason());
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private ToolCallExecutionStatus computeExecutionStatus(PipelineContext ctx) {
        if (ctx.isShortCircuited()) {
            // Prefer the explicit execution status captured at
            // short-circuit time (set by ShortCircuitStrategy via
            // applyShortCircuit). This correctly distinguishes, e.g.,
            // forUnknownToolName (REJECT + ERROR) from forOwlContradiction
            // (REJECT + OK) and forJsonSchemaFailure (AUTO_REPAIR + OK).
            if (ctx.shortCircuitExecutionStatus().isPresent()) {
                return ctx.shortCircuitExecutionStatus().get();
            }
            // Fallback for direct shortCircuitAt(stage, decision, reason)
            // callers that did not specify an execution status.
            Optional<ErrorCode> ec = ctx.errorCode();
            if (ec.isPresent() && ec.get() == ErrorCode.REASONER_TIMEOUT) {
                return ToolCallExecutionStatus.TIMEOUT;
            }
            if (ctx.shortCircuitDecision().isPresent()
                && ctx.shortCircuitDecision().get() == ValidationDecision.SYSTEM_ERROR) {
                return ToolCallExecutionStatus.ERROR;
            }
            // Non-SYSTEM_ERROR short-circuits are normal outcomes.
            return ToolCallExecutionStatus.OK;
        }
        return ToolCallExecutionStatus.OK;
    }

    private String suggestRepair(JsonSchemaViolation v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.violationType()).append(" at ").append(v.fieldPath());
        if (v.expectedValue().isPresent()) {
            sb.append(" (expected ").append(v.expectedValue().get()).append(")");
        }
        if (v.actualValue().isPresent()) {
            sb.append(" (got ").append(v.actualValue().get()).append(")");
        }
        sb.append(": ").append(v.message());
        return sb.toString();
    }

    private boolean isEntityInOverlay(OWLOntology ontology, String entityIri) {
        if (ontology == null || entityIri == null) return false;
        try {
            org.semanticweb.owlapi.model.IRI iri = org.semanticweb.owlapi.model.IRI.create(entityIri);
            return ontology.containsEntityInSignature(iri);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String summarizeContradictions(AnswerVerificationReport report) {
        if (report == null || report.claimResults() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ClaimWorkflowResult r : report.claimResults()) {
            if (r.verdict() == Verdict.CONTRADICTED) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("claim ").append(r.claimId()).append(" contradicted");
                if (r.diagnostics().isPresent()) {
                    sb.append(" (").append(r.diagnostics().get()).append(")");
                }
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> convertOwlReport(AnswerVerificationReport report) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (report == null || report.claimResults() == null) return out;
        for (ClaimWorkflowResult r : report.claimResults()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("claimId", r.claimId());
            m.put("claimType", r.claimType() != null ? r.claimType().jsonName() : null);
            m.put("required", r.required());
            m.put("verdict", r.verdict() != null ? r.verdict().jsonName() : null);
            m.put("unknownReason", r.unknownReason().orElse(null));
            m.put("diagnostics", r.diagnostics().orElse(null));
            out.add(m);
        }
        return out;
    }

    private Model convertOntologyToJenaModel(OWLOntology ontology) {
        Model model = ModelFactory.createDefaultModel();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            org.semanticweb.owlapi.formats.RDFXMLDocumentFormat format =
                new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
            ontology.getOWLOntologyManager().saveOntology(ontology, format, baos);
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            model.read(bais, null, "RDF/XML");
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert OWLOntology to Jena Model: " + e.getMessage(), e);
        }
        return model;
    }

    private ToolCallCandidate parseCandidate(String json) {
        // Lightweight parse: delegate to Gson and the ToolCallCandidate record.
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String callId = getStr(obj, "callId", null);
            String toolName = getStr(obj, "toolName", null);
            if (callId == null || callId.isBlank()) {
                throw new IllegalArgumentException("callId is required");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName is required");
            }
            java.util.Optional<String> requestId = getOptStr(obj, "requestId");
            java.util.Optional<String> userRequest = getOptStr(obj, "userRequest");
            java.util.Optional<String> targetEntity = getOptStr(obj, "targetEntity");
            java.util.Optional<String> requestedBy = getOptStr(obj, "requestedBy");
            java.util.Optional<String> timestamp = getOptStr(obj, "timestamp");
            java.util.Optional<String> environmentSnapshotId = getOptStr(obj, "environmentSnapshotId");
            java.util.Optional<String> sourceModel = getOptStr(obj, "sourceModel");
            java.util.Optional<String> sourceModelResponseId = getOptStr(obj, "sourceModelResponseId");
            Map<String, String> arguments = new LinkedHashMap<>();
            if (obj.has("arguments") && obj.get("arguments").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e :
                    obj.getAsJsonObject("arguments").entrySet()) {
                    arguments.put(e.getKey(), e.getValue().toString());
                }
            }
            return new ToolCallCandidate(
                callId, requestId, userRequest, toolName, targetEntity,
                arguments, requestedBy, timestamp, environmentSnapshotId,
                sourceModel, sourceModelResponseId
            );
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Failed to parse ToolCallCandidate JSON: " + e.getMessage(), e);
        }
    }

    private static String getStr(com.google.gson.JsonObject obj, String key, String dflt) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return dflt;
    }

    private static java.util.Optional<String> getOptStr(com.google.gson.JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            String s = obj.get(key).getAsString();
            return s == null || s.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(s);
        }
        return java.util.Optional.empty();
    }

    // ────────────────────────────────────────────────────────────────────
    // Builder
    // ────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ToolContractRegistry toolContractRegistry;
        private TransientOntologyOverlayService overlayService;
        private ClaimWorkflowService claimWorkflowService;
        private ShapeRegistry shapeRegistry;
        private ShaclValidationService shaclValidationService;
        private JsonSchemaPreValidator jsonSchemaValidator;
        private ClaimDecomposer claimDecomposer;

        public Builder toolContractRegistry(ToolContractRegistry r) {
            this.toolContractRegistry = r; return this;
        }

        public Builder overlayService(TransientOntologyOverlayService s) {
            this.overlayService = s; return this;
        }

        public Builder claimWorkflowService(ClaimWorkflowService s) {
            this.claimWorkflowService = s; return this;
        }

        public Builder shapeRegistry(ShapeRegistry r) {
            this.shapeRegistry = r; return this;
        }

        public Builder shaclValidationService(ShaclValidationService s) {
            this.shaclValidationService = s; return this;
        }

        public Builder jsonSchemaValidator(JsonSchemaPreValidator v) {
            this.jsonSchemaValidator = v; return this;
        }

        public Builder claimDecomposer(ClaimDecomposer d) {
            this.claimDecomposer = d; return this;
        }

        public ToolCallValidationPipeline build() {
            java.util.Objects.requireNonNull(toolContractRegistry,
                "toolContractRegistry is required");
            java.util.Objects.requireNonNull(overlayService,
                "overlayService is required");
            java.util.Objects.requireNonNull(claimWorkflowService,
                "claimWorkflowService is required");
            // shapeRegistry and shaclValidationService are optional — when
            // null, stage 7 is skipped (no SHACL validation runs).
            return new ToolCallValidationPipeline(this);
        }
    }
}
