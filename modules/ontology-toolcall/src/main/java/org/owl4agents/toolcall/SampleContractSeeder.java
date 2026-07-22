package org.owl4agents.toolcall;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.8.7 Issue #4: Seeds sample {@link ToolContract} files into the
 * contracts directory on first startup.
 *
 * <p>New users previously saw an empty {@code ~/.owl4agents/contracts/}
 * directory, which caused the Tool Call Pipeline to always return
 * {@code decision=reject} because no contract matched the invoked tool
 * name. This seeder copies three read-only, low-risk sample contracts
 * ({@code ontology_verify_claim}, {@code ontology_classify},
 * {@code ontology_check_consistency}) from the classpath resource
 * {@code /sample-contracts/} so the pipeline has working defaults.</p>
 *
 * <p>Idempotent: safe to call multiple times. The seeder only writes
 * when the target directory is empty (or does not exist), and never
 * overwrites existing files. Subsequent calls after the first seed are
 * no-ops.</p>
 */
public final class SampleContractSeeder {

    /** Classpath resource directory holding the sample contract files. */
    static final String RESOURCE_DIR = "sample-contracts";

    /** Sample contract file names, in load order. */
    static final String[] SAMPLE_CONTRACTS = {
        "ontology_verify_claim.json",
        "ontology_classify.json",
        "ontology_check_consistency.json"
    };

    private SampleContractSeeder() {
        // Utility class — no instances.
    }

    /**
     * Seed the contracts directory with sample contract files if it is
     * empty (or does not exist). Existing files are never overwritten.
     *
     * @param contractsDir the target contracts directory (typically
     *                     {@code ~/.owl4agents/contracts/})
     */
    public static void seedIfEmpty(Path contractsDir) {
        if (contractsDir == null) {
            return;
        }

        // Create the directory if it does not exist.
        try {
            if (!Files.isDirectory(contractsDir)) {
                Files.createDirectories(contractsDir);
            }
        } catch (IOException e) {
            System.err.println("[SampleContractSeeder] WARN: cannot create directory "
                + contractsDir + ": " + e.getMessage());
            return;
        }

        // Gate: only seed when the directory is empty (no .json files).
        if (!isEmptyOfContracts(contractsDir)) {
            return;
        }

        List<String> seeded = new ArrayList<>();
        for (String sample : SAMPLE_CONTRACTS) {
            Path dest = contractsDir.resolve(sample);
            // Defensive: never overwrite an existing file.
            if (Files.exists(dest)) {
                continue;
            }
            String resourcePath = RESOURCE_DIR + "/" + sample;
            try (InputStream is = SampleContractSeeder.class.getClassLoader()
                    .getResourceAsStream(resourcePath)) {
                if (is == null) {
                    System.err.println("[SampleContractSeeder] WARN: sample resource not found on classpath: "
                        + resourcePath);
                    continue;
                }
                // Read with UTF-8 and write with UTF-8 so the contract
                // content is preserved regardless of platform default
                // encoding.
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(dest, content, StandardCharsets.UTF_8);
                seeded.add(sample);
            } catch (IOException e) {
                System.err.println("[SampleContractSeeder] WARN: failed to copy "
                    + sample + " into " + contractsDir + ": " + e.getMessage());
            }
        }

        if (!seeded.isEmpty()) {
            System.err.println("[SampleContractSeeder] Seeded " + seeded.size()
                + " sample contract(s) into " + contractsDir + ": " + seeded);
        }
    }

    /**
     * Return {@code true} if the directory contains no {@code .json}
     * contract files.
     */
    private static boolean isEmptyOfContracts(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.noneMatch(p -> p.toString().endsWith(".json"));
        } catch (IOException e) {
            // If we cannot list the directory, treat it as empty so we
            // attempt the seed (the copy will fail loudly if needed).
            return true;
        }
    }
}
