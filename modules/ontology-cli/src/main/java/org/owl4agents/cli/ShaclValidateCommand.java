package org.owl4agents.cli;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.shacl.FileShapeRegistry;
import org.owl4agents.shacl.JenaShaclValidationService;
import org.owl4agents.shacl.ShaclValidationOptions;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.shacl.ShaclValidationService;
import org.owl4agents.shacl.ShaclJsonSerializer;
import org.owl4agents.shacl.ShapeRegistry;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * v0.8.7 SH-005: {@code owl4agents shacl-validate} CLI command.
 *
 * <p>Loads a data graph and a shapes graph (or registered shape_set_id),
 * delegates to {@link ShaclValidationService}, and prints a JSON
 * {@link ShaclValidationReport} on stdout.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   owl4agents shacl-validate
 *       --data &lt;data.ttl&gt;
 *       --shapes &lt;shapes.ttl | shape_set_id&gt;
 *       [--options &lt;options.json&gt;]
 *       [--output json]
 * </pre>
 */
@Command(
    name = "shacl-validate",
    description = "Validate an RDF data graph against SHACL shapes " +
                  "(file path or registered shape_set_id)."
)
public class ShaclValidateCommand implements Callable<Integer> {

    @Option(names = {"--data"}, description = "Path to the data graph (Turtle/RDF/XML/JSON-LD)", required = true)
    private String dataPath;

    @Option(names = {"--shapes"},
        description = "Path to the shapes graph OR a registered shape_set_id",
        required = true)
    private String shapesArg;

    @Option(names = {"--options"},
        description = "JSON object with includeWarnings, includeInfos, timeout fields")
    private String optionsJson;

    @Option(names = {"--output"}, description = "Output format (only 'json' supported)")
    private String output = "json";

    @Override
    public Integer call() {
        // Load the data graph.
        if (!Files.exists(Path.of(dataPath))) {
            System.err.println("Error: data file not found: " + dataPath);
            return 1;
        }
        Model dataModel;
        try {
            dataModel = RDFDataMgr.loadModel(dataPath);
        } catch (RuntimeException e) {
            System.err.println("Error: failed to parse data file: " + e.getMessage());
            return 1;
        }

        // Parse options.
        ShaclValidationOptions opts = parseOptions(optionsJson);

        // Build the validation service (always with a ShapeRegistry so
        // --shapes <id> works without an extra flag).
        ShapeRegistry registry = new FileShapeRegistry();
        ShaclValidationService service = new JenaShaclValidationService(registry);

        // Decide inline vs registered by checking whether shapesArg is a path.
        ServiceResult<ShaclValidationReport> result;
        if (Files.exists(Path.of(shapesArg))) {
            // Inline shapes file.
            Model shapesModel;
            try {
                shapesModel = RDFDataMgr.loadModel(shapesArg);
            } catch (RuntimeException e) {
                System.err.println("Error: failed to parse shapes file: " + e.getMessage());
                return 1;
            }
            result = service.validate(dataModel, shapesModel, opts);
        } else {
            // Treat shapesArg as a registered shape_set_id.
            result = service.validateRegisteredShapes(shapesArg, dataModel, opts);
        }

        if (result.isSuccess()) {
            ShaclValidationReport report = ((ServiceResult.Success<ShaclValidationReport>) result).data();
            var gson = GsonFactory.createGson();
            System.out.println(gson.toJson(ShaclJsonSerializer.reportToMap(report)));
            // Exit code: 0 = conforms, 2 = violations present (so scripts can
            // distinguish conforms=true/false without parsing JSON).
            return report.conforms() ? 0 : 2;
        } else {
            var error = ((ServiceResult.Error<ShaclValidationReport>) result).error();
            var gson = GsonFactory.createGson();
            System.out.println(gson.toJson(Map.of(
                "status", "error",
                "error", Map.of(
                    "code", error.code().code(),
                    "message", error.message()))));
            return 1;
        }
    }

    /**
     * Parse the --options JSON string into {@link ShaclValidationOptions}.
     * Recognized fields: includeWarnings, includeInfos, timeout (seconds
     * or ISO-8601 duration). Missing fields fall back to defaults.
     */
    @SuppressWarnings("unchecked")
    private ShaclValidationOptions parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return ShaclValidationOptions.defaults();
        }
        try {
            var gson = GsonFactory.createGson();
            Map<String, Object> map = gson.fromJson(json, Map.class);
            return ShaclValidationOptions.fromMap(map);
        } catch (Exception e) {
            // Fall back to defaults on parse error.
            return ShaclValidationOptions.defaults();
        }
    }
}
