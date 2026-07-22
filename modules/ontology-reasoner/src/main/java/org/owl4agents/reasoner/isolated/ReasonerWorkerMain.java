package org.owl4agents.reasoner.isolated;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.owl4agents.reasoner.AutoReasonerSelector;
import org.owl4agents.reasoner.ELKAdapter;
import org.owl4agents.reasoner.HermiTAdapter;
import org.owl4agents.reasoner.OpenlletAdapter;
import org.owl4agents.reasoner.OWLReasonerAdapter;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * v0.8.7 REL-001 / D15: Child JVM entry point for the
 * {@link IsolatedReasonerWorker}.
 *
 * <p>Reads JSON requests from stdin line-by-line, dispatches to the
 * appropriate reasoner (HermiT/ELK/Openllet via the existing adapters),
 * writes JSON responses to stdout. Logs to stderr ONLY (never stdout)
 * — stdout is reserved for protocol messages per the reasoner-runtime
 * spec "Reasoner worker communication / JSON-RPC over stdio".</p>
 *
 * <p>Supported operations:</p>
 * <ul>
 *   <li>{@code ping} — health check; responds with {@code {pong: true}}.</li>
 *   <li>{@code classify} — full class hierarchy via
 *       {@link OWLReasonerAdapter#classify(String)}.</li>
 *   <li>{@code realize} — full realization via
 *       {@link OWLReasonerAdapter#realize(String)}.</li>
 *   <li>{@code checkConsistency} — consistency check via
 *       {@link OWLReasonerAdapter#checkConsistency(String)}.</li>
 *   <li>{@code getUnsatClasses} — unsatisfiable class set via
 *       {@link OWLReasonerAdapter#getUnsatClasses()}.</li>
 *   <li>{@code explain} — inconsistency explanation via
 *       {@link OWLReasonerAdapter#explainInconsistency(String)}.</li>
 *   <li>{@code checkConsistencyAfterAdding} — adds an axiom (parsed
 *       from functional syntax) and re-checks consistency.</li>
 * </ul>
 *
 * <h2>Ontology reload detection</h2>
 *
 * <p>Per the reasoner-runtime spec "Child JVM reloads ontology on file
 * change": the worker caches the loaded ontology keyed by
 * {@code ontologyPath} and detects file changes via mtime + size check
 * (consistent with {@code OntologyCache} behavior). When the file
 * changes, the worker reloads the ontology before processing the next
 * request.</p>
 *
 * <h2>Logging discipline</h2>
 *
 * <p>All logs go to stderr. stdout is reserved exclusively for JSON
 * protocol responses. A single response is written per request, on a
 * single line terminated by {@code \n}.</p>
 */
public final class ReasonerWorkerMain {

    private static final Logger LOG = Logger.getLogger("org.owl4agents.reasoner.isolated.worker");

    private static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create();

    /** Cached ontology: keyed by ontologyPath, invalidated on mtime/size change. */
    private static final Map<String, LoadedOntology> ontologyCache = new HashMap<>();

    /** Cached reasoner adapter, invalidated when the ontology is reloaded. */
    private static OWLReasonerAdapter cachedAdapter;
    private static String cachedOntologyPath;
    private static String cachedReasonerName;

    private ReasonerWorkerMain() {
    }

    /**
     * Entry point. Reads JSON requests from stdin (one per line) and
     * writes JSON responses to stdout (one per line).
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        LOG.info("reasoner worker started: pid=" + ProcessHandle.current().pid());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)), false)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String responseJson;
                try {
                    JsonObject requestJson = JsonParser.parseString(line).getAsJsonObject();
                    responseJson = handleRequest(requestJson);
                } catch (Exception e) {
                    responseJson = serializeResponse(IsolatedReasonerResponse.error(
                        "REASONER_WORKER_PROTOCOL_ERROR",
                        "Failed to parse or dispatch request: " + e.getMessage(),
                        0L));
                    LOG.log(Level.WARNING, "request parse/dispatch failed", e);
                }
                // Single line, terminated by \n. Flush after each write.
                writer.print(responseJson);
                writer.print('\n');
                writer.flush();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "stdin/stdout stream error", e);
            System.exit(1);
        }
        LOG.info("reasoner worker exiting normally");
    }

    /**
     * Dispatch a single request to the appropriate handler.
     */
    static String handleRequest(JsonObject requestJson) {
        long start = System.currentTimeMillis();
        IsolatedReasonerRequest req;
        try {
            req = parseRequest(requestJson);
        } catch (IllegalArgumentException e) {
            return serializeResponse(IsolatedReasonerResponse.error(
                "INVALID_ARGUMENTS",
                "Invalid request: " + e.getMessage(),
                System.currentTimeMillis() - start));
        }

        try {
            IsolatedReasonerResponse resp = dispatch(req, start);
            return serializeResponse(resp);
        } catch (OutOfMemoryError oom) {
            // Let the JVM exit via ExitOnOutOfMemoryError
            throw oom;
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "unhandled error in dispatch", t);
            return serializeResponse(IsolatedReasonerResponse.error(
                "REASONER_INTERNAL_ERROR",
                "Unhandled error: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                System.currentTimeMillis() - start));
        }
    }

    /**
     * Parse a JSON request into an {@link IsolatedReasonerRequest}.
     */
    static IsolatedReasonerRequest parseRequest(JsonObject json) {
        String ontologyPath = getAsString(json, "ontologyPath");
        String reasonerName = getAsString(json, "reasonerName");
        if (reasonerName == null || reasonerName.isBlank()) {
            reasonerName = "auto";
        }
        String operation = getAsString(json, "operation");
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        String axiom = getAsString(json, "axiom");
        long timeoutMs = json.has("timeoutMs") && json.get("timeoutMs").isJsonPrimitive()
            ? json.get("timeoutMs").getAsLong()
            : 30_000L;
        return new IsolatedReasonerRequest(ontologyPath, reasonerName, operation, axiom, timeoutMs);
    }

    /**
     * Dispatch the parsed request to the appropriate handler.
     */
    private static IsolatedReasonerResponse dispatch(IsolatedReasonerRequest req, long start) {
        String op = req.operation();
        switch (op) {
            case "ping":
                Map<String, Object> pong = new LinkedHashMap<>();
                pong.put("pong", true);
                pong.put("pid", ProcessHandle.current().pid());
                return IsolatedReasonerResponse.success(pong, System.currentTimeMillis() - start);
            case "classify":
                return handleClassify(req, start);
            case "realize":
                return handleRealize(req, start);
            case "checkConsistency":
                return handleCheckConsistency(req, start);
            case "getUnsatClasses":
                return handleGetUnsatClasses(req, start);
            case "explain":
                return handleExplain(req, start);
            case "checkConsistencyAfterAdding":
                return handleCheckConsistencyAfterAdding(req, start);
            default:
                return IsolatedReasonerResponse.error(
                    "INVALID_ARGUMENTS",
                    "Unknown operation: " + op,
                    System.currentTimeMillis() - start);
        }
    }

    private static IsolatedReasonerResponse handleClassify(IsolatedReasonerRequest req, long start) {
        try {
            OWLReasonerAdapter adapter = getAdapter(req);
            OWLOntology ontology = getLoadedOntology(req);
            var result = adapter.classify(extractOntologyId(req));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ontologyId", result.ontologyId());
            payload.put("reasoner", result.reasonerName());
            payload.put("completeHierarchyCount", result.completeHierarchy().size());
            payload.put("deltaCount", result.delta().size());
            // Include a small summary (first 100 entries) to keep payload bounded.
            List<Map<String, String>> sample = new ArrayList<>();
            int limit = Math.min(100, result.completeHierarchy().size());
            for (int i = 0; i < limit; i++) {
                var entry = result.completeHierarchy().get(i);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("subjectIRI", entry.subjectIRI());
                row.put("predicateIRI", entry.predicateIRI());
                row.put("objectIRI", entry.objectIRI());
                row.put("source", entry.source());
                sample.add(row);
            }
            payload.put("sample", sample);
            return IsolatedReasonerResponse.success(payload, System.currentTimeMillis() - start);
        } catch (OWLOntologyCreationException e) {
            return IsolatedReasonerResponse.error(
                "ONTOLOGY_NOT_FOUND",
                "Failed to load ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        } catch (UnsupportedOperationException e) {
            return IsolatedReasonerResponse.error(
                "REASONER_REJECTED_ONTOLOGY",
                "Reasoner rejected ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    private static IsolatedReasonerResponse handleRealize(IsolatedReasonerRequest req, long start) {
        try {
            OWLReasonerAdapter adapter = getAdapter(req);
            var result = adapter.realize(extractOntologyId(req));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ontologyId", result.ontologyId());
            payload.put("reasoner", result.reasonerName());
            payload.put("completeTypesCount", result.completeTypes().size());
            payload.put("deltaCount", result.delta().size());
            return IsolatedReasonerResponse.success(payload, System.currentTimeMillis() - start);
        } catch (OWLOntologyCreationException e) {
            return IsolatedReasonerResponse.error(
                "ONTOLOGY_NOT_FOUND",
                "Failed to load ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        } catch (UnsupportedOperationException e) {
            return IsolatedReasonerResponse.error(
                "REASONER_REJECTED_ONTOLOGY",
                "Reasoner rejected ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    private static IsolatedReasonerResponse handleCheckConsistency(IsolatedReasonerRequest req, long start) {
        try {
            OWLReasonerAdapter adapter = getAdapter(req);
            var result = adapter.checkConsistency(extractOntologyId(req));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ontologyId", result.ontologyId());
            payload.put("reasoner", result.reasonerName());
            payload.put("consistent", result.consistent());
            payload.put("unsatClassCount", result.unsatisfiableClassIRIs().size());
            payload.put("unsatClassIRIs", result.unsatisfiableClassIRIs());
            return IsolatedReasonerResponse.success(payload, System.currentTimeMillis() - start);
        } catch (OWLOntologyCreationException e) {
            return IsolatedReasonerResponse.error(
                "ONTOLOGY_NOT_FOUND",
                "Failed to load ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        } catch (UnsupportedOperationException e) {
            return IsolatedReasonerResponse.error(
                "REASONER_REJECTED_ONTOLOGY",
                "Reasoner rejected ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    private static IsolatedReasonerResponse handleGetUnsatClasses(IsolatedReasonerRequest req, long start) {
        try {
            OWLReasonerAdapter adapter = getAdapter(req);
            var unsat = adapter.getUnsatClasses();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("unsatClassIRIs", new ArrayList<>(unsat));
            payload.put("count", unsat.size());
            return IsolatedReasonerResponse.success(payload, System.currentTimeMillis() - start);
        } catch (OWLOntologyCreationException e) {
            return IsolatedReasonerResponse.error(
                "ONTOLOGY_NOT_FOUND",
                "Failed to load ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    private static IsolatedReasonerResponse handleExplain(IsolatedReasonerRequest req, long start) {
        try {
            OWLReasonerAdapter adapter = getAdapter(req);
            if (!adapter.supportsExplanation()) {
                return IsolatedReasonerResponse.error(
                    "EXPLANATION_NOT_SUPPORTED",
                    "Reasoner " + adapter.getName() + " does not support explanation",
                    System.currentTimeMillis() - start);
            }
            var explanation = adapter.explainInconsistency(extractOntologyId(req));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ontologyId", explanation.ontologyId());
            payload.put("explanation", explanation.toString());
            return IsolatedReasonerResponse.success(payload, System.currentTimeMillis() - start);
        } catch (OWLOntologyCreationException e) {
            return IsolatedReasonerResponse.error(
                "ONTOLOGY_NOT_FOUND",
                "Failed to load ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    private static IsolatedReasonerResponse handleCheckConsistencyAfterAdding(IsolatedReasonerRequest req, long start) {
        try {
            OWLOntology ontology = getLoadedOntology(req);
            OWLReasonerAdapter adapter = getAdapter(req);
            // Re-initialize the adapter on a fresh copy so we can add the axiom
            // without mutating the cached ontology.
            OWLOntologyManager mgr = ontology.getOWLOntologyManager();
            OWLOntology copy = mgr.copyOntology(ontology, org.semanticweb.owlapi.model.parameters.OntologyCopy.DEEP);
            OWLReasonerAdapter freshAdapter = createAdapter(req.reasonerName());
            freshAdapter.initialize(copy);
            if (req.axiom() != null && !req.axiom().isBlank()) {
                addAxiomFromFunctionalSyntax(copy, req.axiom());
            }
            var result = freshAdapter.checkConsistency(extractOntologyId(req));
            freshAdapter.shutdown();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ontologyId", result.ontologyId());
            payload.put("reasoner", result.reasonerName());
            payload.put("consistent", result.consistent());
            payload.put("unsatClassIRIs", result.unsatisfiableClassIRIs());
            return IsolatedReasonerResponse.success(payload, System.currentTimeMillis() - start);
        } catch (OWLOntologyCreationException e) {
            return IsolatedReasonerResponse.error(
                "ONTOLOGY_NOT_FOUND",
                "Failed to load ontology: " + e.getMessage(),
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            return IsolatedReasonerResponse.error(
                "REASONER_INTERNAL_ERROR",
                "checkConsistencyAfterAdding failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    /**
     * Add an axiom to the ontology, parsed from a simple functional-style
     * syntax: {@code ClassAssertion(<iri> <class-iri>)} or
     * {@code SubClassOf(<sub-iri> <super-iri>)}.
     */
    private static void addAxiomFromFunctionalSyntax(OWLOntology ontology, String axiomSpec) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        String trimmed = axiomSpec.trim();
        // Strip outer parens if present
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        // Detect "ClassAssertion(<iri> <class-iri>)" or "SubClassOf(<sub> <super>)"
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx <= 0) {
            throw new IllegalArgumentException("axiom must be '<SubjectIRI> <ObjectIRI>'");
        }
        String subject = trimmed.substring(0, spaceIdx).trim();
        String object = trimmed.substring(spaceIdx + 1).trim();
        if (subject.startsWith("<") && subject.endsWith(">")) {
            subject = subject.substring(1, subject.length() - 1);
        }
        if (object.startsWith("<") && object.endsWith(">")) {
            object = object.substring(1, object.length() - 1);
        }
        // Default to ClassAssertion for backward compatibility with simple
        // "<individual> <class>" functional-syntax shorthand.
        ontology.addAxiom(df.getOWLClassAssertionAxiom(
            df.getOWLClass(IRI.create(object)),
            df.getOWLNamedIndividual(IRI.create(subject))));
    }

    /**
     * Get (or reload) the ontology for this request. The ontology is
     * cached per {@code ontologyPath} and reloaded when the file's
     * mtime or size changes on disk.
     */
    private static OWLOntology getLoadedOntology(IsolatedReasonerRequest req)
            throws OWLOntologyCreationException {
        Path path = Path.of(req.ontologyPath());
        if (!Files.exists(path)) {
            throw new OWLOntologyCreationException("Ontology file not found: " + path);
        }
        long mtime;
        long size;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            mtime = attrs.lastModifiedTime().toMillis();
            size = attrs.size();
        } catch (IOException e) {
            throw new OWLOntologyCreationException("Failed to stat ontology file: " + path, e);
        }

        LoadedOntology cached = ontologyCache.get(req.ontologyPath());
        if (cached != null && cached.mtime == mtime && cached.size == size) {
            return cached.ontology;
        }

        // Load (or reload).
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = mgr.loadOntologyFromOntologyDocument(path.toFile());
        ontologyCache.put(req.ontologyPath(), new LoadedOntology(ontology, mtime, size));
        // Invalidate the cached adapter — the next getAdapter() will reinitialize.
        if (cachedAdapter != null) {
            try {
                cachedAdapter.shutdown();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            cachedAdapter = null;
            cachedOntologyPath = null;
            cachedReasonerName = null;
        }
        LOG.info("loaded ontology " + req.ontologyPath() + " (size=" + size + ", mtime=" + mtime + ")");
        return ontology;
    }

    /**
     * Get (or initialize) the reasoner adapter for this request. The
     * adapter is cached per (ontologyPath, reasonerName) tuple and
     * reinitialized when either changes or the ontology is reloaded.
     */
    private static OWLReasonerAdapter getAdapter(IsolatedReasonerRequest req)
            throws OWLOntologyCreationException {
        OWLOntology ontology = getLoadedOntology(req);
        String effectiveReasoner = resolveReasonerName(req.reasonerName(), ontology);
        if (cachedAdapter != null
                && req.ontologyPath().equals(cachedOntologyPath)
                && effectiveReasoner.equals(cachedReasonerName)) {
            return cachedAdapter;
        }
        // Shutdown the previous adapter if any.
        if (cachedAdapter != null) {
            try {
                cachedAdapter.shutdown();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            cachedAdapter = null;
        }
        OWLReasonerAdapter adapter = createAdapter(effectiveReasoner);
        adapter.initialize(ontology);
        cachedAdapter = adapter;
        cachedOntologyPath = req.ontologyPath();
        cachedReasonerName = effectiveReasoner;
        return adapter;
    }

    /**
     * Resolve "auto" to a concrete reasoner name via
     * {@link AutoReasonerSelector}. For explicit names (HermiT/ELK/Openllet),
     * return the name as-is.
     */
    private static String resolveReasonerName(String name, OWLOntology ontology) {
        if (name == null || name.isBlank() || "auto".equalsIgnoreCase(name)) {
            int classCount = ontology.getClassesInSignature().size();
            // Conservative profile guess: assume OWL 2 DL. The real
            // AutoReasonerSelector looks at the detected profile, but
            // for the isolated worker we keep it simple — the parent
            // process is responsible for accurate profile detection
            // and explicit reasoner selection.
            var sel = new AutoReasonerSelector();
            var result = sel.select("OWL 2 DL", false, classCount, false);
            return result.reasonerName() != null ? result.reasonerName() : "HermiT";
        }
        return name;
    }

    private static OWLReasonerAdapter createAdapter(String name) {
        if (name == null) {
            throw new IllegalArgumentException("reasoner name is null");
        }
        switch (name) {
            case "HermiT":
                return new HermiTAdapter();
            case "ELK":
                return new ELKAdapter();
            case "Openllet":
                return new OpenlletAdapter();
            default:
                throw new IllegalArgumentException("Unknown reasoner: " + name);
        }
    }

    private static String extractOntologyId(IsolatedReasonerRequest req) {
        Path p = Path.of(req.ontologyPath()).getFileName();
        if (p == null) return "unknown";
        String fileName = p.toString();
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    /**
     * Serialize a response to a single-line JSON string.
     */
    static String serializeResponse(IsolatedReasonerResponse response) {
        JsonObject root = new JsonObject();
        if (response.result() != null) {
            // Wrap the result object as JSON.
            JsonElement element = GSON.toJsonTree(response.result());
            root.add("result", element);
        } else {
            root.add("result", null);
        }
        root.addProperty("elapsedMs", response.elapsedMs());
        if (response.error() != null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", response.error().code());
            err.addProperty("message", response.error().message());
            root.add("error", err);
        } else {
            root.add("error", null);
        }
        // Compact, single-line, no HTML escaping.
        return GSON.toJson(root);
    }

    private static String getAsString(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive() && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return null;
    }

    /**
     * Capture stderr output for diagnostic purposes (used by tests that
     * spawn a real child JVM and want to read its stderr).
     */
    @SuppressWarnings("unused")
    private static String captureStderr(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** Cached loaded ontology + file metadata for mtime/size invalidation. */
    private record LoadedOntology(OWLOntology ontology, long mtime, long size) {}
}
