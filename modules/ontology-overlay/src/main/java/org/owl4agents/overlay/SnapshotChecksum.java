package org.owl4agents.overlay;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * v0.8.7 OV-004 / D11: Computes a canonical SHA256 checksum over a
 * collection of {@link OWLAxiom}s.
 *
 * <p>The checksum is the SHA256 of the canonical N-Triples serialization
 * of the axiom set, sorted by subject-predicate-object, with blank node
 * IDs replaced by a stable placeholder so the checksum is invariant to
 * blank node identity (per OV-004 "Same state produces same checksum").</p>
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Create an empty in-memory {@link OWLOntology} and add the axioms.</li>
 *   <li>Serialize the ontology to N-Triples (no syntax sugar, one triple
 *       per line).</li>
 *   <li>Strip trailing whitespace and {@code .} from each line, then
 *       replace blank node IDs ({@code _:b0}, {@code _:b1}, …) with the
 *       placeholder {@code _:b}.</li>
 *   <li>Sort the normalized lines lexicographically by UTF-16 code unit.</li>
 *   <li>Concatenate the sorted lines with {@code \n} separators, append
 *       a trailing {@code \n}, encode as UTF-8, and compute SHA256.</li>
 * </ol>
 *
 * <p>The resulting checksum is a 64-character lowercase hex string.</p>
 */
public final class SnapshotChecksum {

    /** Placeholder substituted for all blank node IDs before sorting. */
    public static final String BLANK_NODE_PLACEHOLDER = "_:b";

    private static final Pattern BLANK_NODE_PATTERN = Pattern.compile("_:[A-Za-z0-9_]+");

    private SnapshotChecksum() {
        // utility class
    }

    /**
     * Compute the canonical SHA256 checksum of the supplied axioms.
     *
     * @param axioms the axiom set; must not be null (may be empty)
     * @return 64-character lowercase hex SHA256
     */
    public static String compute(Collection<OWLAxiom> axioms) {
        if (axioms == null) {
            throw new IllegalArgumentException("axioms must not be null");
        }
        String ntriples = serializeToNTriples(axioms);
        List<String> lines = normalizeLines(ntriples);
        lines.sort(Comparator.naturalOrder());
        String canonical = String.join("\n", lines) + "\n";
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verify that the supplied axioms' computed checksum matches the
     * expected checksum.
     *
     * @param axioms         the axiom set to recompute the checksum over
     * @param expectedChecksum the expected 64-char hex SHA256
     * @return true if the recomputed checksum equals the expected one
     *         (case-insensitive hex comparison)
     */
    public static boolean matches(Collection<OWLAxiom> axioms, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            return false;
        }
        String actual = compute(axioms);
        return actual.equalsIgnoreCase(expectedChecksum.trim());
    }

    // ── Internal helpers ──

    private static String serializeToNTriples(Collection<OWLAxiom> axioms) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try {
            OWLOntology ontology = manager.createOntology();
            if (!axioms.isEmpty()) {
                manager.addAxioms(ontology, axioms);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manager.saveOntology(ontology, new NTriplesDocumentFormat(), out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (OWLOntologyCreationException | OWLOntologyStorageException e) {
            throw new IllegalStateException("Failed to serialize axioms to N-Triples: " + e.getMessage(), e);
        }
    }

    private static List<String> normalizeLines(String ntriples) {
        List<String> lines = new ArrayList<>();
        for (String raw : ntriples.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // Strip trailing '.' (N-Triples statement terminator) — we re-add
            // it as part of the canonical form on join.
            if (line.endsWith(".")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            if (line.isEmpty()) continue;
            // Replace blank node IDs with a stable placeholder.
            line = BLANK_NODE_PATTERN.matcher(line).replaceAll(BLANK_NODE_PLACEHOLDER);
            // Re-append the terminator so the canonical form is valid N-Triples.
            lines.add(line + " .");
        }
        return lines;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
