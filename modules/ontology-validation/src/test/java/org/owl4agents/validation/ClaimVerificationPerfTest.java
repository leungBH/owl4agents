package org.owl4agents.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;

/**
 * v0.8.4 Section 9: Performance benchmark tests.
 *
 * <p>PERF-1: 80 claims total < 8s
 * <p>PERF-2: 3 rounds of 80 claims, no degradation
 * <p>PERF-3: single claim 100x, p50 < 50ms, p90 < 80ms, p99 < 100ms
 * <p>PERF-5: regression detection (p50 > 80ms → PERF_REGRESSION)
 */
@DisplayName("v0.8.4 Performance Benchmarks")
class ClaimVerificationPerfTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String ONTOLOGY_ID = "pizza";
    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private static ClaimVerificationService svc;
    private static List<Claim> claims;

    @BeforeAll
    static void setUp() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);
        ReasonerServiceImpl reasonerService = new ReasonerServiceImpl(catalog, WORKSPACE, "default", cache, escManager);
        ConsistencyAnalysisService consistencyService = new ConsistencyAnalysisService(
            reasonerService.getLifecycleManager(), WORKSPACE, cache, escManager);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(WORKSPACE, cache);
        svc = new ClaimVerificationService(
            reasonerService, consistencyService, deepeningService, catalog, new WorkspaceId("default"));

        claims = generate80Claims();
    }

    private static List<Claim> generate80Claims() {
        List<Claim> list = new ArrayList<>();
        // Pizza ontology class pairs for SUBCLASS claims
        String[][] subclassPairs = {
            {"CheeseyPizza", "Pizza"},
            {"Margherita", "Pizza"},
            {"Margherita", "CheeseyPizza"},
            {"American", "Pizza"},
            {"AmericanHot", "Pizza"},
            {"Cajun", "Pizza"},
            {"Capricciosa", "Pizza"},
            {"Caprese", "Pizza"},
            {"Fiorentina", "Pizza"},
            {"FourSeasons", "Pizza"},
            {"FruttiDiMare", "Pizza"},
            {"Giardiniera", "Pizza"},
            {"LaReine", "Pizza"},
            {"Mushroom", "Pizza"},
            {"Napoletana", "Pizza"},
            {"Parmense", "Pizza"},
            {"PolloAdAstra", "Pizza"},
            {"PrinceCarlo", "Pizza"},
            {"QuattroFormaggi", "Pizza"},
            {"Rosa", "Pizza"},
            {"Siciliana", "Pizza"},
            {"SloppyGiuseppe", "Pizza"},
            {"Soho", "Pizza"},
            {"UnclosedPizza", "Pizza"},
            {"VegetarianPizza", "Pizza"},
            {"Veneziana", "Pizza"},
        };

        String[][] disjointPairs = {
            {"Pizza", "PizzaTopping"},
            {"Pizza", "PizzaBase"},
            {"Pizza", "IceCream"},
            {"PizzaTopping", "PizzaBase"},
            {"Food", "IceCream"},
        };

        String[][] equivPairs = {
            {"CheeseyPizza", "CheesyPizza"},
            {"SpicyPizza", "SpicyPizzaEquivalent"},
        };

        // Generate 80 claims: 50 SUBCLASS + 20 DISJOINT + 10 EQUIV
        int id = 0;
        for (int i = 0; i < 50; i++) {
            String[] pair = subclassPairs[i % subclassPairs.length];
            list.add(new Claim("perf-" + (id++), ClaimType.SUBCLASS, ONTOLOGY_ID,
                new ClaimEntity("class", PIZZA_NS + pair[0]), null,
                new ClaimEntity("class", PIZZA_NS + pair[1]),
                Optional.empty(), Optional.empty(), Optional.empty()));
        }
        for (int i = 0; i < 20; i++) {
            String[] pair = disjointPairs[i % disjointPairs.length];
            list.add(new Claim("perf-" + (id++), ClaimType.DISJOINT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("class", PIZZA_NS + pair[0]), null,
                new ClaimEntity("class", PIZZA_NS + pair[1]),
                Optional.empty(), Optional.empty(), Optional.empty()));
        }
        for (int i = 0; i < 10; i++) {
            String[] pair = equivPairs[i % equivPairs.length];
            list.add(new Claim("perf-" + (id++), ClaimType.EQUIVALENT_CLASSES, ONTOLOGY_ID,
                new ClaimEntity("class", PIZZA_NS + pair[0]), null,
                new ClaimEntity("class", PIZZA_NS + pair[1]),
                Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return Collections.unmodifiableList(list);
    }

    private long[] runAllClaims() {
        long[] times = new long[claims.size()];
        for (int i = 0; i < claims.size(); i++) {
            long start = System.nanoTime();
            svc.verify(claims.get(i));
            times[i] = (System.nanoTime() - start) / 1_000_000; // ms
        }
        return times;
    }

    private double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    @Test
    @DisplayName("9.2 PERF-1: 80 claims total time < 8s")
    void perf1_80ClaimsUnder8s() {
        long[] times = runAllClaims();
        long total = 0;
        for (long t : times) total += t;

        System.out.printf("PERF-1: 80 claims total = %d ms (threshold 8000 ms)%n", total);
        assertTrue(total < 8000, "80 claims should complete in < 8s, got " + total + " ms");
    }

    @Test
    @DisplayName("9.4 PERF-2: 3 rounds of 80 claims, no degradation in rounds 2 & 3")
    void perf2_threeRoundsNoDegradation() {
        long[] round1 = runAllClaims();
        long[] round2 = runAllClaims();
        long[] round3 = runAllClaims();

        long total1 = 0, total2 = 0, total3 = 0;
        for (long t : round1) total1 += t;
        for (long t : round2) total2 += t;
        for (long t : round3) total3 += t;

        System.out.printf("PERF-2: Round 1 = %d ms, Round 2 = %d ms, Round 3 = %d ms%n",
            total1, total2, total3);

        // Rounds 2 and 3 should not be significantly slower than round 1
        // (allow 50% margin for GC jitter)
        assertTrue(total2 < total1 * 1.5,
            "Round 2 should not degrade: " + total2 + " vs " + total1);
        assertTrue(total3 < total1 * 1.5,
            "Round 3 should not degrade: " + total3 + " vs " + total1);
    }

    @Test
    @DisplayName("9.3 PERF-3: single claim 100x, p50 < 50ms, p90 < 80ms, p99 < 100ms")
    void perf3_singleClaim100Times() {
        // Use a simple SUBCLASS claim that should be fast (index hit)
        Claim claim = claims.get(0); // CheeseyPizza → Pizza

        // Warm up (first call includes reasoner init)
        svc.verify(claim);

        long[] times = new long[100];
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            svc.verify(claim);
            times[i] = (System.nanoTime() - start) / 1_000_000;
        }

        long[] sorted = times.clone();
        java.util.Arrays.sort(sorted);

        double p50 = percentile(sorted, 50);
        double p90 = percentile(sorted, 90);
        double p99 = percentile(sorted, 99);

        System.out.printf("PERF-3: p50=%.1f ms, p90=%.1f ms, p99=%.1f ms%n", p50, p90, p99);

        // 9.5: regression detection
        if (p50 > 80) {
            System.out.println("PERF_REGRESSION: p50 = " + p50 + " ms > 80 ms threshold");
        }

        assertTrue(p50 < 50, "p50 should be < 50ms, got " + p50);
        assertTrue(p90 < 80, "p90 should be < 80ms, got " + p90);
        assertTrue(p99 < 100, "p99 should be < 100ms, got " + p99);
    }
}
