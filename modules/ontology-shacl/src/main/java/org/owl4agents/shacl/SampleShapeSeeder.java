package org.owl4agents.shacl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * v0.8.7 Issue #4: Seeds a sample SHACL shape set file into the shapes
 * directory on first startup.
 *
 * <p>New users previously saw an empty {@code ~/.owl4agents/shapes/}
 * directory with no {@code registry.json}, which left the SHACL
 * validation stage of the Tool Call Pipeline without any shapes to
 * apply. This seeder copies {@code basic-validation.ttl} from the
 * classpath resource {@code /sample-shapes/} so users have a working
 * starting point.</p>
 *
 * <p>The seeder does <strong>not</strong> auto-register the shape set
 * in {@code registry.json}. The user must run the {@code shacl-register}
 * CLI command to register the seeded shape set. This keeps the seeding
 * non-destructive: it only drops a file on disk without touching the
 * registry state.</p>
 *
 * <p>Idempotent: safe to call multiple times. The seeder only writes
 * when {@code registry.json} is absent from the shapes directory, and
 * never overwrites an existing {@code basic-validation.ttl} file.</p>
 */
public final class SampleShapeSeeder {

    /** Classpath resource directory holding the sample shape file. */
    static final String RESOURCE_DIR = "sample-shapes";

    /** Sample shape file name. */
    static final String SAMPLE_SHAPE = "basic-validation.ttl";

    /** Registry file used by {@link FileShapeRegistry} as the gate. */
    static final String REGISTRY_FILE = "registry.json";

    private SampleShapeSeeder() {
        // Utility class — no instances.
    }

    /**
     * Seed the shapes directory with a sample shape file if no
     * {@code registry.json} is present. The shape set is NOT
     * auto-registered; the user must run {@code shacl-register} to
     * register it. Existing files are never overwritten.
     *
     * @param shapesDir the target shapes directory (typically
     *                  {@code ~/.owl4agents/shapes/})
     */
    public static void seedIfEmpty(Path shapesDir) {
        if (shapesDir == null) {
            return;
        }

        // Gate: skip seeding if a registry.json already exists (the user
        // has already set up shape sets).
        Path registryFile = shapesDir.resolve(REGISTRY_FILE);
        if (Files.exists(registryFile)) {
            return;
        }

        // Create the directory if it does not exist.
        try {
            if (!Files.isDirectory(shapesDir)) {
                Files.createDirectories(shapesDir);
            }
        } catch (IOException e) {
            System.err.println("[SampleShapeSeeder] WARN: cannot create directory "
                + shapesDir + ": " + e.getMessage());
            return;
        }

        Path dest = shapesDir.resolve(SAMPLE_SHAPE);
        // Defensive: never overwrite an existing file.
        if (Files.exists(dest)) {
            return;
        }

        String resourcePath = RESOURCE_DIR + "/" + SAMPLE_SHAPE;
        try (InputStream is = SampleShapeSeeder.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.err.println("[SampleShapeSeeder] WARN: sample resource not found on classpath: "
                    + resourcePath);
                return;
            }
            // Read with UTF-8 and write with UTF-8 so the Turtle
            // content is preserved regardless of platform default
            // encoding.
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(dest, content, StandardCharsets.UTF_8);
            System.err.println("[SampleShapeSeeder] Seeded sample shape file '"
                + SAMPLE_SHAPE + "' into " + shapesDir
                + ". Run shacl-register to register it as a ShapeSet.");
        } catch (IOException e) {
            System.err.println("[SampleShapeSeeder] WARN: failed to copy "
                + SAMPLE_SHAPE + " into " + shapesDir + ": " + e.getMessage());
        }
    }
}
