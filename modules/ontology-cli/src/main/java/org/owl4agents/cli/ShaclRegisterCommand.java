package org.owl4agents.cli;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.shacl.ShapeSet;
import org.owl4agents.shacl.ShapeRegistry;
import org.owl4agents.shacl.FileShapeRegistry;
import org.owl4agents.shacl.ShaclJsonSerializer;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * v0.8.7 SH-005 / D7: {@code owl4agents shacl-register} CLI command.
 *
 * <p>Registers a SHACL shapes file as a trusted ShapeSet. The MCP
 * {@code ontology_validate_shacl} tool references the registered id;
 * inline shapes are rejected at the protocol layer.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   owl4agents shacl-register &lt;id&gt; &lt;shapes.ttl&gt;
 *       [--force] [--domain &lt;domain&gt;] [--requires-inference] [--json]
 * </pre>
 */
@Command(
    name = "shacl-register",
    description = "Register a SHACL shapes file as a trusted ShapeSet " +
                  "(MCP tools reference shapes by id; inline shapes are rejected)."
)
public class ShaclRegisterCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ShapeSet identifier (e.g. 'smart-home-core')")
    private String id;

    @Parameters(index = "1", description = "Path to the SHACL shapes file (Turtle/RDF/XML/JSON-LD)")
    private String shapesFilePath;

    @Option(names = {"--force"}, description = "Overwrite an existing ShapeSet with a different checksum")
    private boolean force = false;

    @Option(names = {"--domain"}, description = "Domain tag for the ShapeSet (default: 'default')")
    private String domain = "default";

    @Option(names = {"--requires-inference"},
        description = "Mark ShapeSet as requiring inferred facts for its sh:sparql constraints")
    private boolean requiresInference = false;

    @Option(names = {"--json"}, description = "Emit JSON output to stdout")
    private boolean json = false;

    @Override
    public Integer call() {
        ShapeRegistry registry = new FileShapeRegistry();
        ServiceResult<ShapeSet> result = registry.register(
            id, Path.of(shapesFilePath), domain, force, requiresInference);

        if (result.isSuccess()) {
            ShapeSet ss = ((ServiceResult.Success<ShapeSet>) result).data();
            if (json) {
                var gson = GsonFactory.createGson();
                System.out.println(gson.toJson(ShaclJsonSerializer.shapeSetToMapWithSourcePath(ss)));
            } else {
                System.out.println("ShapeSet '" + ss.id() + "' registered successfully.");
                System.out.println("  domain:            " + ss.domain());
                System.out.println("  version:           " + ss.version());
                System.out.println("  sourcePath:        " + ss.sourcePath());
                System.out.println("  checksum (SHA256): " + ss.checksum());
                System.out.println("  trusted:           " + ss.trusted());
                System.out.println("  enabled:           " + ss.enabled());
                System.out.println("  requiresInference: " + ss.requiresInference());
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<ShapeSet>) result).error();
            if (json) {
                var gson = GsonFactory.createGson();
                System.out.println(gson.toJson(java.util.Map.of(
                    "status", "error",
                    "error", java.util.Map.of(
                        "code", error.code().code(),
                        "message", error.message()))));
            } else {
                System.err.println("Error: " + error.code().code() + " - " + error.message());
            }
            return 1;
        }
    }
}
