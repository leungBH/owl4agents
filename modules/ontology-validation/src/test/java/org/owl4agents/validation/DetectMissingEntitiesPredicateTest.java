package org.owl4agents.validation;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.MissingEntityResult;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * Task 7.4 unit test: verify that {@link EvidenceGroundingService#detectMissingEntities}
 * does NOT search reserved structural keywords (e.g., {@code "subClassOf"},
 * {@code "type"}, {@code "equivalentClasses"}) as property entities, while IRI
 * predicates ARE searched.
 *
 * <p><b>v0.9.0 D3:</b> Reserved predicates (21 OWL 2 structural keywords) are
 * claim structural fields, not ontology entities. They MUST be skipped by the
 * predicate search so that a claim like {@code predicate="subClassOf"} does
 * not report "subClassOf" as a missing property entity.</p>
 */
@DisplayName("v0.9.0 D3 / task 7.4: detectMissingEntities skips reserved predicates")
class DetectMissingEntitiesPredicateTest {

    private StubReasonerService stubReasoner;
    private EvidenceGroundingService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        // ConsistencyAnalysisService with deprecated 3-arg constructor
        // (manager=null) — detectMissingEntities does not depend on the
        // cache; it calls isEntityDeclared which falls back to stream scan
        // against "dummy-path" (returns false, simulating no ontology loaded).
        service = new EvidenceGroundingService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path")
        );
    }

    private Claim claimWithPredicate(String claimId, String predicate) {
        return new Claim(claimId, ClaimType.SUBCLASS, "test-ontology",
            new ClaimEntity("class", "http://ex.org/A"), predicate,
            new ClaimEntity("class", "http://ex.org/B"),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private List<String> allEntityIRIs(MissingEntityResult result) {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (MissingEntityResult.EntityMatch m : result.matched()) all.add(m.searchTerm());
        for (MissingEntityResult.EntityMatch m : result.ambiguous()) all.add(m.searchTerm());
        for (MissingEntityResult.EntityMatch m : result.missing()) all.add(m.searchTerm());
        for (MissingEntityResult.EntityMatch m : result.outOfScope()) all.add(m.searchTerm());
        return all;
    }

    @Test
    @DisplayName("Reserved keyword 'subClassOf' is NOT searched as a property entity")
    void subClassOfNotSearchedAsEntity() {
        Claim claim = claimWithPredicate("c1", "subClassOf");
        ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
        assertTrue(result.isSuccess());
        MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();

        List<String> all = allEntityIRIs(data);
        assertFalse(all.contains("subClassOf"),
            "'subClassOf' must NOT appear in any entity list (matched/ambiguous/missing/outOfScope). "
                + "Found in: " + all);
    }

    @Test
    @DisplayName("All 21 reserved keywords are NOT searched as entities")
    void allReservedPredicatesNotSearched() {
        String[] reserved = {
            "subClassOf", "equivalentClasses", "disjointClasses",
            "subPropertyOf", "equivalentProperties", "propertyDisjointWith",
            "type", "domain", "range", "inverseOf",
            "hasKey", "hasValue", "allValuesFrom", "someValuesFrom",
            "cardinality", "minCardinality", "maxCardinality",
            "differentFrom", "sameAs", "propertyChainAxiom", "hasSelf"
        };
        for (String predicate : reserved) {
            Claim claim = claimWithPredicate("c-" + predicate, predicate);
            ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
            assertTrue(result.isSuccess(), "detectMissingEntities must succeed for predicate=" + predicate);
            MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();

            List<String> all = allEntityIRIs(data);
            assertFalse(all.contains(predicate),
                "Reserved keyword '" + predicate + "' must NOT appear in any entity list. "
                    + "Found in: " + all);
        }
    }

    @Test
    @DisplayName("IRI predicate 'http://ex.org/hasFoo' IS searched as a property entity")
    void iriPredicateIsSearched() {
        // An IRI predicate that is not an OWL builtin and not in the reserved
        // set MUST be searched. Since "dummy-path" has no ontology loaded,
        // isEntityDeclared returns false and getInferredFacts returns no facts
        // → the predicate IRI appears in the missing list.
        String iriPredicate = "http://ex.org/hasFoo";
        Claim claim = claimWithPredicate("c2", iriPredicate);
        ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
        assertTrue(result.isSuccess());
        MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();

        List<String> missing = data.missing().stream()
            .map(MissingEntityResult.EntityMatch::searchTerm)
            .toList();
        assertTrue(missing.contains(iriPredicate),
            "IRI predicate '" + iriPredicate + "' MUST be searched and appear in missing list "
                + "(since dummy-path has no ontology). missing=" + missing);
    }

    @Test
    @DisplayName("OWL builtin IRI 'http://www.w3.org/2002/07/owl#... ' is NOT searched")
    void owlBuiltinIriNotSearched() {
        // OWL builtin IRIs (e.g., owl:subClassOf) are skipped by the
        // startsWith("http://www.w3.org/2002/07/owl#") guard.
        String owlBuiltin = "http://www.w3.org/2002/07/owl#subClassOf";
        Claim claim = claimWithPredicate("c3", owlBuiltin);
        ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
        assertTrue(result.isSuccess());
        MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();

        List<String> all = allEntityIRIs(data);
        assertFalse(all.contains(owlBuiltin),
            "OWL builtin IRI '" + owlBuiltin + "' must NOT be searched as a property entity. "
                + "Found in: " + all);
    }

    @Test
    @DisplayName("Blank/null predicate is NOT searched")
    void blankOrNullPredicateNotSearched() {
        // null predicate
        Claim nullClaim = new Claim("c4a", ClaimType.SUBCLASS, "test-ontology",
            new ClaimEntity("class", "http://ex.org/A"), null,
            new ClaimEntity("class", "http://ex.org/B"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<MissingEntityResult> nullResult = service.detectMissingEntities(nullClaim);
        assertTrue(nullResult.isSuccess());
        MissingEntityResult nullData = ((ServiceResult.Success<MissingEntityResult>) nullResult).data();
        // Only subject and object should be in entity lists (2 entities)
        assertEquals(2, allEntityIRIs(nullData).size(),
            "Null predicate must contribute 0 entities; only subject+object counted.");

        // blank predicate
        Claim blankClaim = new Claim("c4b", ClaimType.SUBCLASS, "test-ontology",
            new ClaimEntity("class", "http://ex.org/A"), "   ",
            new ClaimEntity("class", "http://ex.org/B"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<MissingEntityResult> blankResult = service.detectMissingEntities(blankClaim);
        assertTrue(blankResult.isSuccess());
        MissingEntityResult blankData = ((ServiceResult.Success<MissingEntityResult>) blankResult).data();
        assertEquals(2, allEntityIRIs(blankData).size(),
            "Blank predicate must contribute 0 entities; only subject+object counted.");
    }

    @Test
    @DisplayName("https:// IRI predicate IS searched (https variant)")
    void httpsIriPredicateIsSearched() {
        String httpsPredicate = "https://example.org/hasBar";
        Claim claim = claimWithPredicate("c5", httpsPredicate);
        ServiceResult<MissingEntityResult> result = service.detectMissingEntities(claim);
        assertTrue(result.isSuccess());
        MissingEntityResult data = ((ServiceResult.Success<MissingEntityResult>) result).data();

        List<String> missing = data.missing().stream()
            .map(MissingEntityResult.EntityMatch::searchTerm)
            .toList();
        assertTrue(missing.contains(httpsPredicate),
            "https:// IRI predicate MUST be searched. missing=" + missing);
    }
}
