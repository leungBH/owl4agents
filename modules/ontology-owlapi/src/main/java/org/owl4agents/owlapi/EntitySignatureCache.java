package org.owl4agents.owlapi;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-ontology cache of entity IRIs and asserted axiom indices.
 *
 * <p>Builds an O(1) lookup structure from the ontology's signature
 * (Imports.EXCLUDED), collecting entities from BOTH explicit Declaration
 * axioms AND all other axioms that reference entities (e.g., SubClassOf,
 * ClassAssertion, ObjectPropertyAssertion). This correctly handles ontologies
 * where entities are used in axioms without explicit Declaration axioms —
 * per OWL 2 spec, an entity referenced in any axiom is part of the ontology
 * signature. Also builds SubClassOf and DisjointClasses indices for fast
 * asserted-axiom lookups in the claim verification hot path.</p>
 *
 * <p><b>v0.8.6 D4 (Cache Governance):</b> The four signature sets
 * ({@code declaredClassIRIs}, {@code declaredObjectPropertyIRIs},
 * {@code declaredDataPropertyIRIs}, {@code declaredIndividualIRIs}) were
 * migrated from unbounded {@link HashSet} to a single global bounded
 * Caffeine {@link Cache} ({@link #SIGNATURE_CACHE}). The cache is
 * <b>global</b> (single static instance, not per-ontology) — entity IRIs
 * from all loaded ontologies share the same 50K-entry LRU. With pizza +
 * HPO + Mondo loaded simultaneously, total class entries ≈ 62K which
 * exceeds 50K; Caffeine's W-TinyLFU eviction policy keeps hot entries
 * (e.g., pizza's actively-queried classes) while evicting cold ones.
 * Per-ontology structure ({@code oboPrefix}, {@code subClassOfIndex},
 * {@code disjointClassesIndex}) is retained on the instance.</p>
 */
public final class EntitySignatureCache {

    /** Kind tag for class IRIs in {@link #SIGNATURE_CACHE}. */
    private static final String KIND_CLASS = "class";
    /** Kind tag for object property IRIs in {@link #SIGNATURE_CACHE}. */
    private static final String KIND_OBJ_PROP = "objprop";
    /** Kind tag for data property IRIs in {@link #SIGNATURE_CACHE}. */
    private static final String KIND_DATA_PROP = "dataprop";
    /** Kind tag for individual IRIs in {@link #SIGNATURE_CACHE}. */
    private static final String KIND_INDIVIDUAL = "individual";

    /**
     * v0.8.6 D4: Global bounded LRU cache for entity signature IRIs.
     *
     * <p>Key format: {@code "<kind>|<iri>"} where {@code <kind>} is one of
     * {@code class}, {@code objprop}, {@code dataprop}, {@code individual}.
     * Value is always {@link Boolean#TRUE} (the cache is used as a set).</p>
     *
     * <p>Configuration:
     * <ul>
     *   <li>{@code maximumSize(50_000)} — bounds memory; with HPO (32K) +
     *       Mondo (30K) + pizza (115) loaded simultaneously, the W-TinyLFU
     *       policy retains hot entries.</li>
     *   <li>{@code expireAfterAccess(2h)} — entries not accessed within 2h
     *       are eligible for eviction (long-running benchmarks typically
     *       complete in under 2h; stale entries after ontology reload are
     *       invalidated explicitly via {@link #invalidateAll()}).</li>
     *   <li>{@code recordStats()} — exposes hit rate, eviction count, etc.
     *       via {@link #stats()} for the upcoming monitoring endpoint.</li>
     * </ul>
     */
    private static final Cache<String, Boolean> SIGNATURE_CACHE = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterAccess(Duration.ofHours(2))
        .recordStats()
        .build();

    private final String oboPrefix;
    private final Map<String, Set<String>> subClassOfIndex;
    private final Map<String, Set<String>> disjointClassesIndex;

    private EntitySignatureCache(String oboPrefix,
                                  Map<String, Set<String>> subClassOfIndex,
                                  Map<String, Set<String>> disjointClassesIndex) {
        this.oboPrefix = oboPrefix;
        this.subClassOfIndex = subClassOfIndex;
        this.disjointClassesIndex = disjointClassesIndex;
    }

    public static EntitySignatureCache build(OWLOntology ontology) {
        for (OWLDeclarationAxiom decl : ontology.getAxioms(AxiomType.DECLARATION, Imports.EXCLUDED)) {
            OWLEntity entity = decl.getEntity();
            String iri = entity.getIRI().toString();
            if (entity.isOWLClass()) {
                putSignature(KIND_CLASS, iri);
            } else if (entity.isOWLObjectProperty()) {
                putSignature(KIND_OBJ_PROP, iri);
            } else if (entity.isOWLDataProperty()) {
                putSignature(KIND_DATA_PROP, iri);
            } else if (entity.isOWLNamedIndividual()) {
                putSignature(KIND_INDIVIDUAL, iri);
            }
        }

        // Also collect entities from the ontology signature (Imports.EXCLUDED).
        // Per OWL 2 spec, an entity referenced in any axiom is part of the
        // signature, even without an explicit Declaration axiom. This catches
        // entities used in ClassAssertion, ObjectPropertyAssertion, SubClassOf,
        // etc. that lack explicit Declaration axioms.
        for (OWLClass cls : ontology.getClassesInSignature(Imports.EXCLUDED)) {
            putSignature(KIND_CLASS, cls.getIRI().toString());
        }
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            putSignature(KIND_OBJ_PROP, prop.getIRI().toString());
        }
        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            putSignature(KIND_DATA_PROP, prop.getIRI().toString());
        }
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature(Imports.EXCLUDED)) {
            putSignature(KIND_INDIVIDUAL, ind.getIRI().toString());
        }

        String oboPrefix = null;
        if (ontology.getOntologyID().getOntologyIRI().isPresent()) {
            String ontIri = ontology.getOntologyID().getOntologyIRI().get().toString();
            if (ontIri.startsWith("http://purl.obolibrary.org/obo/") && ontIri.endsWith(".owl")) {
                String fileName = ontIri.substring(ontIri.lastIndexOf('/') + 1);
                oboPrefix = fileName.substring(0, fileName.length() - ".owl".length()).toUpperCase();
            }
        }

        Map<String, Set<String>> subClassOfIndex = buildSubClassOfIndex(ontology);
        Map<String, Set<String>> disjointClassesIndex = buildDisjointClassesIndex(ontology);

        return new EntitySignatureCache(
            oboPrefix,
            Collections.unmodifiableMap(subClassOfIndex),
            Collections.unmodifiableMap(disjointClassesIndex)
        );
    }

    private static Map<String, Set<String>> buildSubClassOfIndex(OWLOntology ontology) {
        Map<String, Set<String>> index = new HashMap<>();
        for (OWLSubClassOfAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)) {
            if (ax.getSubClass() instanceof OWLClass subClass && ax.getSuperClass() instanceof OWLClass superClass) {
                index.computeIfAbsent(subClass.getIRI().toString(), k -> new HashSet<>())
                    .add(superClass.getIRI().toString());
            }
        }
        return index;
    }

    private static Map<String, Set<String>> buildDisjointClassesIndex(OWLOntology ontology) {
        Map<String, Set<String>> index = new HashMap<>();
        for (OWLDisjointClassesAxiom ax : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
            Set<OWLClass> disjointClasses = ax.getClassExpressionsAsList().stream()
                .filter(ce -> ce instanceof OWLClass)
                .map(ce -> (OWLClass) ce)
                .collect(java.util.stream.Collectors.toSet());
            for (OWLClass c1 : disjointClasses) {
                for (OWLClass c2 : disjointClasses) {
                    if (!c1.equals(c2)) {
                        index.computeIfAbsent(c1.getIRI().toString(), k -> new HashSet<>())
                            .add(c2.getIRI().toString());
                    }
                }
            }
        }
        return index;
    }

    public boolean contains(String kind, String iri) {
        if (iri == null || iri.isBlank()) return false;

        if (oboPrefix != null && !iri.contains("/" + oboPrefix + "_")) {
            return false;
        }

        String k = kind == null ? "" : kind.toLowerCase().replace("_", "");
        if (k.equals("class") || k.isEmpty()) {
            if (getSignature(KIND_CLASS, iri) != null) return true;
        }
        if (k.equals("objectproperty") || k.equals("property") || k.isEmpty()) {
            if (getSignature(KIND_OBJ_PROP, iri) != null) return true;
        }
        if (k.equals("dataproperty") || k.equals("property") || k.isEmpty()) {
            if (getSignature(KIND_DATA_PROP, iri) != null) return true;
        }
        if (k.equals("individual") || k.isEmpty()) {
            if (getSignature(KIND_INDIVIDUAL, iri) != null) return true;
        }
        return false;
    }

    public Set<String> getSuperClasses(String classIRI) {
        return subClassOfIndex.getOrDefault(classIRI, Collections.emptySet());
    }

    public Set<String> getDisjointClasses(String classIRI) {
        return disjointClassesIndex.getOrDefault(classIRI, Collections.emptySet());
    }

    // ── v0.8.6 D4: Global Caffeine cache helpers ────────────────────────

    /**
     * Put a signature entry into the global cache.
     *
     * @param kind one of {@code class}, {@code objprop}, {@code dataprop}, {@code individual}
     * @param iri  the entity IRI
     */
    private static void putSignature(String kind, String iri) {
        SIGNATURE_CACHE.put(cacheKey(kind, iri), Boolean.TRUE);
    }

    /**
     * Get a signature entry from the global cache.
     *
     * @param kind one of {@code class}, {@code objprop}, {@code dataprop}, {@code individual}
     * @param iri  the entity IRI
     * @return {@link Boolean#TRUE} if present, {@code null} otherwise
     */
    private static Boolean getSignature(String kind, String iri) {
        return SIGNATURE_CACHE.getIfPresent(cacheKey(kind, iri));
    }

    private static String cacheKey(String kind, String iri) {
        return kind + "|" + iri;
    }

    /**
     * v0.8.6 D4 / task 5.3: Invalidate all entries in the global signature
     * cache. Called from {@link org.owl4agents.reasoner.ReasonerServiceImpl#onOntologyReloaded}
     * and {@link org.owl4agents.reasoner.ReasonerServiceImpl#onAllOntologiesReloaded}
     * to ensure stale entries (from a reloaded ontology) do not produce
     * false-positive {@code contains} results.
     */
    public static void invalidateAll() {
        SIGNATURE_CACHE.invalidateAll();
    }

    /**
     * v0.8.6 D4 / task 5.4: Return aggregated {@link CacheStats} for the
     * global signature cache. Exposed for the upcoming monitoring endpoint
     * (v1.0.1) and for unit tests verifying hit rate and eviction count.
     *
     * @return the current snapshot of cache statistics
     */
    public static CacheStats stats() {
        return SIGNATURE_CACHE.stats();
    }

    /**
     * v0.8.6 D4: Return the estimated size of the global signature cache.
     * This is a near-O(1) approximation (Caffeine uses sampling). Used by
     * unit tests to assert LRU eviction behavior.
     *
     * @return the estimated number of entries currently in the cache
     */
    public static long estimatedSize() {
        return SIGNATURE_CACHE.estimatedSize();
    }

    /**
     * v0.8.6 D4: Force Caffeine maintenance (including pending evictions)
     * to run synchronously. Tests MUST call this before asserting on
     * {@link #estimatedSize()} or {@link #stats()} because Caffeine's
     * eviction is normally asynchronous — without {@code cleanUp()}, an
     * over-capacity cache may still report {@code estimatedSize > maximumSize}.
     */
    public static void cleanUp() {
        SIGNATURE_CACHE.cleanUp();
    }

    /**
     * v0.8.6 D4: Public test/helper entry point for inserting a signature
     * entry into the global cache. Used by LRU/stats unit tests to drive
     * eviction and hit-rate assertions without loading an ontology. Also
     * used by the v0.8.6 acceptance test (LongRunningStabilityTest) to
     * simulate sustained cache load.
     *
     * @param kind one of {@code class}, {@code objprop}, {@code dataprop}, {@code individual}
     * @param iri  the entity IRI
     */
    public static void put(String kind, String iri) {
        putSignature(kind, iri);
    }

    /**
     * v0.8.6 D4: Public test/helper entry point for reading a signature
     * entry from the global cache. Used by stats unit tests to register
     * hits/misses and by the v0.8.6 acceptance test to exercise the read
     * path under sustained load.
     *
     * @param kind one of {@code class}, {@code objprop}, {@code dataprop}, {@code individual}
     * @param iri  the entity IRI
     * @return {@link Boolean#TRUE} if present, {@code null} otherwise
     */
    public static Boolean get(String kind, String iri) {
        return getSignature(kind, iri);
    }
}
