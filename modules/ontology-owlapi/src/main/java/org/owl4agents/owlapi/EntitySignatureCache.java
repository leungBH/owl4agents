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
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * <p><b>v0.9.0 D1 (Per-ontology Cache):</b> The signature storage was
 * migrated from a single global static Caffeine cache to a
 * <b>per-ontology</b> Caffeine {@link Cache} instance held by each
 * {@code EntitySignatureCache} object. Each instance's cache is sized
 * independently as {@code max(1000, classCount * 2)} so that loading large
 * ontologies (e.g., HPO ~32K classes, Mondo ~30K classes) cannot evict
 * entries from a small ontology's cache (e.g., Pizza ~115 classes). This
 * fixes the v0.8.8 P0 regression where Pizza's 100 claims all returned
 * {@code out_of_scope} because the global 50K-entry W-TinyLFU cache evicted
 * Pizza's cold entries under HPO+Mondo pressure. Per-ontology structure
 * ({@code oboPrefix}, {@code subClassOfIndex}, {@code disjointClassesIndex})
 * is retained on the instance.</p>
 */
public final class EntitySignatureCache {

    private static final Logger LOG = Logger.getLogger(EntitySignatureCache.class.getName());

    /** Kind tag for class IRIs in {@link #signatureCache}. */
    private static final String KIND_CLASS = "class";
    /** Kind tag for object property IRIs in {@link #signatureCache}. */
    private static final String KIND_OBJ_PROP = "objprop";
    /** Kind tag for data property IRIs in {@link #signatureCache}. */
    private static final String KIND_DATA_PROP = "dataprop";
    /** Kind tag for individual IRIs in {@link #signatureCache}. */
    private static final String KIND_INDIVIDUAL = "individual";

    /** Minimum cache capacity floor (ensures small ontologies still have headroom). */
    private static final int MIN_CACHE_SIZE = 1000;

    /**
     * v0.9.0 D1: Per-ontology bounded LRU cache for this ontology's entity
     * signature IRIs.
     *
     * <p>Key format: {@code "<kind>|<iri>"} where {@code <kind>} is one of
     * {@code class}, {@code objprop}, {@code dataprop}, {@code individual}.
     * Value is always {@link Boolean#TRUE} (the cache is used as a set).</p>
     *
     * <p>Configuration:
     * <ul>
     *   <li>{@code maximumSize(max(1000, classCount * 2))} — bounds memory
     *       per ontology; scales with ontology size so all entities fit.</li>
     *   <li>{@code expireAfterAccess(2h)} — entries not accessed within 2h
     *       are eligible for eviction.</li>
     *   <li>{@code recordStats()} — exposes per-ontology hit rate, eviction
     *       count, etc. via {@link #stats()}.</li>
     * </ul>
     */
    private final Cache<String, Boolean> signatureCache;

    private final String oboPrefix;
    private final Map<String, Set<String>> subClassOfIndex;
    private final Map<String, Set<String>> disjointClassesIndex;

    private EntitySignatureCache(Cache<String, Boolean> signatureCache,
                                  String oboPrefix,
                                  Map<String, Set<String>> subClassOfIndex,
                                  Map<String, Set<String>> disjointClassesIndex) {
        this.signatureCache = signatureCache;
        this.oboPrefix = oboPrefix;
        this.subClassOfIndex = subClassOfIndex;
        this.disjointClassesIndex = disjointClassesIndex;
    }

    public static EntitySignatureCache build(OWLOntology ontology) {
        int classCount = ontology.getClassesInSignature(Imports.EXCLUDED).size();
        int maxEntries = Math.max(MIN_CACHE_SIZE, classCount * 2);
        Cache<String, Boolean> cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterAccess(Duration.ofHours(2))
            .recordStats()
            .build();

        for (OWLDeclarationAxiom decl : ontology.getAxioms(AxiomType.DECLARATION, Imports.EXCLUDED)) {
            OWLEntity entity = decl.getEntity();
            String iri = entity.getIRI().toString();
            if (entity.isOWLClass()) {
                putSignature(cache, KIND_CLASS, iri);
            } else if (entity.isOWLObjectProperty()) {
                putSignature(cache, KIND_OBJ_PROP, iri);
            } else if (entity.isOWLDataProperty()) {
                putSignature(cache, KIND_DATA_PROP, iri);
            } else if (entity.isOWLNamedIndividual()) {
                putSignature(cache, KIND_INDIVIDUAL, iri);
            }
        }

        // Also collect entities from the ontology signature (Imports.EXCLUDED).
        // Per OWL 2 spec, an entity referenced in any axiom is part of the
        // signature, even without an explicit Declaration axiom. This catches
        // entities used in ClassAssertion, ObjectPropertyAssertion, SubClassOf,
        // etc. that lack explicit Declaration axioms.
        for (OWLClass cls : ontology.getClassesInSignature(Imports.EXCLUDED)) {
            putSignature(cache, KIND_CLASS, cls.getIRI().toString());
        }
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            putSignature(cache, KIND_OBJ_PROP, prop.getIRI().toString());
        }
        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            putSignature(cache, KIND_DATA_PROP, prop.getIRI().toString());
        }
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature(Imports.EXCLUDED)) {
            putSignature(cache, KIND_INDIVIDUAL, ind.getIRI().toString());
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

        EntitySignatureCache instance = new EntitySignatureCache(
            cache,
            oboPrefix,
            Collections.unmodifiableMap(subClassOfIndex),
            Collections.unmodifiableMap(disjointClassesIndex)
        );
        LOG.info("EntitySignatureCache built: oboPrefix=" + oboPrefix
            + " cacheSize=" + cache.estimatedSize()
            + " subClassOfEntries=" + subClassOfIndex.size()
            + " disjointEntries=" + disjointClassesIndex.size()
            + " ontologyIRI=" + (ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : "(none)"));
        return instance;
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

        // v0.9.0 fix: RESTORED the oboPrefix check with correct contains()
        // logic. The original check incorrectly used startsWith() on the full
        // IRI, which rejected valid MONDO_/HP_ entities (their IRIs start with
        // "http://", not "MONDO_"). The v0.9.0 "fix" removed the check entirely,
        // which caused a regression: upper-level ontology entities (BFO_, RO_,
        // IAO_, UBERON_, CL_) that appear in the ontology signature were
        // incorrectly accepted, making claims that should be out_of_scope
        // return supported/unknown/contradicted instead.
        //
        // The correct check uses contains("/" + oboPrefix + "_") to match
        // OBO-style IRIs like http://purl.obolibrary.org/obo/MONDO_0000005.
        // When oboPrefix is null (e.g., pizza, sosa), the check is skipped.
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
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("EntitySignatureCache.contains MISS: kind=" + kind + " iri=" + iri
                + " oboPrefix=" + oboPrefix + " cacheSize=" + signatureCache.estimatedSize());
        }
        return false;
    }

    public Set<String> getSuperClasses(String classIRI) {
        return subClassOfIndex.getOrDefault(classIRI, Collections.emptySet());
    }

    public Set<String> getDisjointClasses(String classIRI) {
        return disjointClassesIndex.getOrDefault(classIRI, Collections.emptySet());
    }

    // ── v0.9.0 D1: Per-ontology Caffeine cache helpers ──────────────────

    /**
     * Put a signature entry into this ontology's cache.
     *
     * @param cache the per-ontology cache instance
     * @param kind  one of {@code class}, {@code objprop}, {@code dataprop}, {@code individual}
     * @param iri   the entity IRI
     */
    private static void putSignature(Cache<String, Boolean> cache, String kind, String iri) {
        cache.put(cacheKey(kind, iri), Boolean.TRUE);
    }

    /**
     * Get a signature entry from this ontology's cache.
     *
     * @param kind one of {@code class}, {@code objprop}, {@code dataprop}, {@code individual}
     * @param iri  the entity IRI
     * @return {@link Boolean#TRUE} if present, {@code null} otherwise
     */
    private Boolean getSignature(String kind, String iri) {
        return signatureCache.getIfPresent(cacheKey(kind, iri));
    }

    private static String cacheKey(String kind, String iri) {
        return kind + "|" + iri;
    }

    /**
     * v0.9.0 D1: Invalidate all entries in this ontology's signature cache.
     * Called from {@link EntitySignatureCacheManager#onOntologyReloaded} and
     * {@link EntitySignatureCacheManager#onAllOntologiesReloaded} to ensure
     * stale entries (from a reloaded ontology) do not produce false-positive
     * {@code contains} results. Other ontologies' caches are unaffected.
     */
    public void invalidate() {
        signatureCache.invalidateAll();
    }

    /**
     * v0.9.0 D1: Return per-ontology {@link CacheStats} for this ontology's
     * signature cache. Exposed for monitoring and unit tests verifying hit
     * rate and eviction count per ontology.
     *
     * @return the current snapshot of this ontology's cache statistics
     */
    public CacheStats stats() {
        return signatureCache.stats();
    }

    /**
     * v0.9.0 D1: Return the estimated size of this ontology's signature
     * cache. This is a near-O(1) approximation (Caffeine uses sampling). Used
     * by unit tests to assert LRU eviction behavior per ontology.
     *
     * @return the estimated number of entries currently in this ontology's cache
     */
    public long estimatedSize() {
        return signatureCache.estimatedSize();
    }

    /**
     * v0.9.0 D1: Force Caffeine maintenance (including pending evictions) to
     * run synchronously for this ontology's cache. Tests MUST call this
     * before asserting on {@link #estimatedSize()} or {@link #stats()}
     * because Caffeine's eviction is normally asynchronous — without
     * {@code cleanUp()}, an over-capacity cache may still report
     * {@code estimatedSize > maximumSize}.
     */
    public void cleanUp() {
        signatureCache.cleanUp();
    }
}
