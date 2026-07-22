package org.owl4agents.shacl;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * v0.8.7 SH-005 / D6 / D8: Jena-backed {@link ShaclValidationService}.
 *
 * <p>Delegates to {@link ShaclValidator} (Apache Jena 5.3.0) and converts
 * the resulting Jena {@link ValidationReport} into the unified
 * {@link ShaclValidationReport} model. The Jena object is never exposed
 * to the caller.</p>
 *
 * <p>SPARQL constraint timeouts are enforced via a daemon-thread executor
 * and {@code Future.get(timeout)}. When the timeout fires, the service
 * returns {@code SHACL_TIMEOUT}.</p>
 */
public class JenaShaclValidationService implements ShaclValidationService {

    private final ShapeRegistry shapeRegistry;
    private final ExecutorService executor;

    /**
     * Construct with a ShapeRegistry (required for validateRegisteredShapes).
     */
    public JenaShaclValidationService(ShapeRegistry shapeRegistry) {
        this.shapeRegistry = shapeRegistry;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "shacl-validation-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Construct with an explicit ShapeRegistry and executor (used by tests
     * to inject a synchronous executor).
     */
    public JenaShaclValidationService(ShapeRegistry shapeRegistry, ExecutorService executor) {
        this.shapeRegistry = shapeRegistry;
        this.executor = executor;
    }

    @Override
    public ServiceResult<ShaclValidationReport> validate(Model dataGraph, Model shapesGraph,
                                                         ShaclValidationOptions options) {
        if (dataGraph == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS, "dataGraph must not be null");
        }
        if (shapesGraph == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS, "shapesGraph must not be null");
        }
        ShaclValidationOptions opts = options == null ? ShaclValidationOptions.defaults() : options;
        long start = System.currentTimeMillis();
        try {
            Shapes shapes = Shapes.parse(shapesGraph);
            Graph dataGraphInner = dataGraph.getGraph();
            ValidationReport report = runWithTimeout(
                () -> ShaclValidator.get().validate(shapes, dataGraphInner), opts);
            long elapsed = System.currentTimeMillis() - start;
            ShaclValidationReport converted = convertReport(report, elapsed, Optional.empty(), opts);
            return ServiceResult.success(converted, null);
        } catch (RiotException e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "Failed to parse shapes graph: " + e.getMessage());
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                return ServiceResult.error(ErrorCode.SHACL_TIMEOUT,
                    "SHACL validation exceeded timeout of " + opts.timeout().toMillis() + "ms");
            }
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "SHACL validation failed: " + e.getMessage());
        } catch (TimeoutException e) {
            return ServiceResult.error(ErrorCode.SHACL_TIMEOUT,
                "SHACL validation exceeded timeout of " + opts.timeout().toMillis() + "ms");
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_MALFORMED,
                "SHACL validation failed: " + e.getMessage());
        }
    }

    @Override
    public ServiceResult<ShaclValidationReport> validateRegisteredShapes(String shapeSetId,
                                                                         Model dataGraph,
                                                                         ShaclValidationOptions options) {
        if (shapeSetId == null || shapeSetId.isBlank()) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND,
                "shape_set_id must not be blank");
        }
        if (dataGraph == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS, "dataGraph must not be null");
        }
        ShaclValidationOptions opts = options == null ? ShaclValidationOptions.defaults() : options;

        ServiceResult<Model> resolveResult = shapeRegistry.resolve(shapeSetId);
        if (!resolveResult.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<Model>) resolveResult).error());
        }
        Model shapesModel = ((ServiceResult.Success<Model>) resolveResult).data();

        long start = System.currentTimeMillis();
        try {
            Shapes shapes = Shapes.parse(shapesModel);
            Graph dataGraphInner = dataGraph.getGraph();
            ValidationReport report = runWithTimeout(
                () -> ShaclValidator.get().validate(shapes, dataGraphInner), opts);
            long elapsed = System.currentTimeMillis() - start;
            ShaclValidationReport converted = convertReport(report, elapsed,
                Optional.of(shapeSetId), opts);
            return ServiceResult.success(converted, null);
        } catch (TimeoutException e) {
            return ServiceResult.error(ErrorCode.SHACL_TIMEOUT,
                "SHACL validation exceeded timeout of " + opts.timeout().toMillis() + "ms");
        } catch (RiotException e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_LOAD_FAILED,
                "Failed to validate registered shapes '" + shapeSetId + "': " + e.getMessage());
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                return ServiceResult.error(ErrorCode.SHACL_TIMEOUT,
                    "SHACL validation exceeded timeout of " + opts.timeout().toMillis() + "ms");
            }
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_LOAD_FAILED,
                "Failed to validate registered shapes '" + shapeSetId + "': " + e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.SHACL_SHAPES_LOAD_FAILED,
                "Failed to validate registered shapes '" + shapeSetId + "': " + e.getMessage());
        }
    }

    // ── Helpers ──

    private boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof TimeoutException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private ValidationReport runWithTimeout(Callable<ValidationReport> task,
                                            ShaclValidationOptions opts)
            throws TimeoutException, ExecutionException, InterruptedException {
        long timeoutMs = opts.timeout().toMillis();
        Future<ValidationReport> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        }
    }

    /**
     * Convert a Jena {@link ValidationReport} to the unified
     * {@link ShaclValidationReport}. Partitions results by severity and
     * respects the includeWarnings/includeInfos flags.
     */
    private ShaclValidationReport convertReport(ValidationReport report,
                                                long elapsedMs,
                                                Optional<String> shapeSetId,
                                                ShaclValidationOptions opts) {
        List<ShaclViolation> violations = new ArrayList<>();
        List<ShaclViolation> warnings = new ArrayList<>();
        List<ShaclViolation> infos = new ArrayList<>();

        for (ReportEntry entry : report.getEntries()) {
            ShaclViolation v = convertEntry(entry);
            switch (v.severity()) {
                case Violation -> violations.add(v);
                case Warning -> {
                    if (opts.includeWarnings()) warnings.add(v);
                }
                case Info -> {
                    if (opts.includeInfos()) infos.add(v);
                }
            }
        }

        boolean conforms = violations.isEmpty();
        return new ShaclValidationReport(
            conforms,
            violations,
            warnings,
            infos,
            elapsedMs,
            shapeSetId,
            ShaclValidationReport.SCHEMA_VERSION
        );
    }

    private ShaclViolation convertEntry(ReportEntry entry) {
        String sourceShape = nullSafeNode(entry.source());
        String constraint = constraintComponentOf(entry);
        String focusNode = nullSafeNode(entry.focusNode());
        String resultPath = entry.resultPath() != null ? entry.resultPath().toString() : null;
        String value = entry.value() != null ? stringify(entry.value()) : null;
        Severity severity = severityFromJena(entry.severity());
        String message = entry.message() != null ? entry.message() : "";
        List<String> evidence = collectEvidence(entry);
        String repairHint = buildRepairHint(constraint, focusNode, resultPath, value, message);

        return new ShaclViolation(
            UUID.randomUUID().toString(),
            sourceShape,
            constraint,
            focusNode,
            resultPath,
            value,
            severity,
            message,
            evidence,
            repairHint
        );
    }

    private String nullSafeNode(Node n) {
        return n != null ? n.toString() : "";
    }

    private String constraintComponentOf(ReportEntry entry) {
        // Per Jena SHACL 5.3.0: sourceConstraintComponent() is the canonical
        // SHACL field (sh:sourceConstraintComponent) pointing to the constraint
        // component IRI (e.g., sh:MinCountConstraintComponent). When null,
        // fall back to entry.constraint().getComponent() which returns the
        // same IRI from the Constraint object itself. We normalize the IRI
        // from the long form (sh:MinCountConstraintComponent) to the short
        // form (sh:minCount) to match the spec's user-facing vocabulary.
        Node c = entry.sourceConstraintComponent();
        String iri = null;
        if (c != null) {
            iri = c.toString();
        } else {
            try {
                if (entry.constraint() != null) {
                    Node cc = entry.constraint().getComponent();
                    if (cc != null) iri = cc.toString();
                }
            } catch (RuntimeException ignored) {
                // Fall through to sourceConstraint fallback.
            }
        }
        if (iri == null) {
            Node sc = entry.sourceConstraint();
            if (sc != null) return sc.toString();
            return "";
        }
        return normalizeConstraintIri(iri);
    }

    /**
     * Normalize a SHACL constraint component IRI from the long form
     * (e.g., {@code http://www.w3.org/ns/shacl#MinCountConstraintComponent})
     * to the short form ({@code http://www.w3.org/ns/shacl#minCount}) which
     * matches the user-facing SHACL vocabulary used in shapes graphs.
     *
     * <p>SHACL's constraint component IRIs use CamelCase with a
     * "ConstraintComponent" suffix (e.g., {@code MinCountConstraintComponent},
     * {@code SPARQLConstraintComponent}). The corresponding shape predicates
     * use a lowercase-first-letter form (e.g., {@code minCount}) — except
     * for acronyms like SPARQL where the entire local name is lowercased
     * ({@code sparql}, not {@code sPARQL}).</p>
     */
    private String normalizeConstraintIri(String iri) {
        if (iri == null || iri.isBlank()) return "";
        int hashIdx = iri.lastIndexOf('#');
        int slashIdx = iri.lastIndexOf('/');
        int sep = Math.max(hashIdx, slashIdx);
        if (sep < 0 || sep == iri.length() - 1) return iri;
        String prefix = iri.substring(0, sep + 1);
        String local = iri.substring(sep + 1);
        if (local.endsWith("ConstraintComponent")) {
            local = local.substring(0, local.length() - "ConstraintComponent".length());
        }
        if (local.isEmpty()) return prefix;
        // Acronyms (all uppercase) are fully lowercased (SPARQL -> sparql).
        // Other names just lowercase the first letter (MinCount -> minCount).
        boolean allUpper = true;
        for (int i = 0; i < local.length(); i++) {
            if (!Character.isUpperCase(local.charAt(i))) {
                allUpper = false;
                break;
            }
        }
        if (allUpper) {
            return prefix + local.toLowerCase(java.util.Locale.ROOT);
        }
        return prefix + Character.toLowerCase(local.charAt(0)) + local.substring(1);
    }

    private String stringify(Node node) {
        if (node == null) return null;
        if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        }
        return node.toString();
    }

    private Severity severityFromJena(org.apache.jena.shacl.validation.Severity jenaSeverity) {
        if (jenaSeverity == null || jenaSeverity.level() == null) return Severity.Violation;
        Node level = jenaSeverity.level();
        if (!level.isURI()) return Severity.Violation;
        String uri = level.getURI();
        if (uri.endsWith("#Info") || uri.endsWith("/Info")) return Severity.Info;
        if (uri.endsWith("#Warning") || uri.endsWith("/Warning")) return Severity.Warning;
        return Severity.Violation;
    }

    /**
     * Collect at most 10 evidence triples from the entry's triple (Jena
     * exposes the validated triple when available). For SPARQL constraints
     * without a triple, we synthesize one from focusNode/resultPath/value.
     */
    private List<String> collectEvidence(ReportEntry entry) {
        List<String> triples = new ArrayList<>();
        Triple triple = entry.triple();
        if (triple != null) {
            triples.add(triple.getSubject() + " " + triple.getPredicate() + " " + triple.getObject());
        } else {
            String focus = nullSafeNode(entry.focusNode());
            String path = entry.resultPath() != null ? entry.resultPath().toString() : "";
            String value = entry.value() != null ? stringify(entry.value()) : "";
            if (!focus.isBlank()) {
                StringBuilder sb = new StringBuilder();
                sb.append(focus);
                if (!path.isBlank()) {
                    sb.append(' ').append(path);
                    if (!value.isBlank()) {
                        sb.append(' ').append(value);
                    }
                }
                triples.add(sb.toString());
            }
        }
        return triples.size() > 10 ? triples.subList(0, 10) : triples;
    }

    /**
     * Build a human-readable repair hint by constraint component. The hint
     * references the resultPath and focusNode per the spec scenario
     * "Repair hint by constraint component".
     */
    private String buildRepairHint(String constraint, String focusNode,
                                   String resultPath, String value, String message) {
        if (constraint == null || constraint.isBlank()) {
            return "Review SHACL violation: " + message;
        }
        String local = constraint.contains("#") ? constraint.substring(constraint.lastIndexOf('#') + 1)
            : constraint.contains("/") ? constraint.substring(constraint.lastIndexOf('/') + 1)
            : constraint;
        switch (local) {
            case "minCount":
                return "Increase count of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to at least " + (value != null ? value : "<required>");
            case "maxCount":
                return "Reduce count of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to at most " + (value != null ? value : "<allowed>");
            case "datatype":
                return "Change value of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to datatype " + (value != null ? value : "<required>");
            case "class":
                return "Ensure " + (resultPath != null ? resultPath + " value " : "")
                    + "on " + (focusNode != null ? focusNode : "<focus>")
                    + " is of type " + (value != null ? value : "<required>");
            case "nodeKind":
                return "Change node kind of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to " + (value != null ? value : "<required>");
            case "minInclusive":
                return "Increase value of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to at least " + (value != null ? value : "<required>");
            case "maxInclusive":
                return "Decrease value of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to at most " + (value != null ? value : "<required>");
            case "in":
                return "Set value of " + (resultPath != null ? resultPath : "<property>")
                    + " on " + (focusNode != null ? focusNode : "<focus>")
                    + " to one of the allowed enumeration values";
            case "sparql":
                return "Review SPARQL constraint: " + message;
            default:
                return "Fix " + local + " violation on "
                    + (focusNode != null ? focusNode : "<focus>")
                    + (resultPath != null ? " at " + resultPath : "");
        }
    }
}
