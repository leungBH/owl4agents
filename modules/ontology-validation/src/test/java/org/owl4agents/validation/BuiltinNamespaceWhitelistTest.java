package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-24: built-in namespace whitelist.
 *
 * <p>Verifies that {@code ClaimVerificationService.isEntityInOntology} returns
 * true for IRIs in the four built-in namespaces ({@code xsd:}, {@code rdf:},
 * {@code rdfs:}, {@code owl:}) without consulting the ontology signature.
 * This dual defense is what keeps {@code DATA_PROPERTY_DOMAIN} /
 * {@code DATA_PROPERTY_RANGE} claims whose object is {@code xsd:string} from
 * being misclassified as out-of-scope (ISSUE-01 fix).</p>
 */
@DisplayName("TC-24 Built-in namespace whitelist")
class BuiltinNamespaceWhitelistTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String HAS_TOPPING = PIZZA_NS + "hasTopping";

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private StubReasonerService stubReasoner;
    private ClaimVerificationService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService().withRealOntology(WORKSPACE);
        service = new ClaimVerificationService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), WORKSPACE),
            new SemanticDeepeningService(WORKSPACE),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    @Test
    @DisplayName("TC-24a: xsd:string is in the whitelist and passes pre-check")
    void xsdStringPassesPrecheck() {
        stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
        // xsd:string is in the whitelist so the claim is NOT out-of-scope
        Claim claim = new Claim("c1", ClaimType.DATA_PROPERTY_DOMAIN, "pizza",
            new ClaimEntity("class", PIZZA), "domain",
            new ClaimEntity("datatype", "http://www.w3.org/2001/XMLSchema#string"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
            "DATA_PROPERTY_DOMAIN with xsd:string object must NOT be out-of-scope due to whitelist");
    }

    @Test
    @DisplayName("TC-24b: rdf:type is in the whitelist")
    void rdfTypeInWhitelist() {
        stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
        Claim claim = new Claim("c2", ClaimType.DATA_PROPERTY_DOMAIN, "pizza",
            new ClaimEntity("class", PIZZA), "domain",
            new ClaimEntity("datatype", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict());
    }

    @Test
    @DisplayName("TC-24c: rdfs:label is in the whitelist")
    void rdfsLabelInWhitelist() {
        stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
        Claim claim = new Claim("c3", ClaimType.DATA_PROPERTY_DOMAIN, "pizza",
            new ClaimEntity("class", PIZZA), "domain",
            new ClaimEntity("datatype", "http://www.w3.org/2000/01/rdf-schema#label"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        assertNotEquals(Verdict.OUT_OF_SCOPE, ((ServiceResult.Success<ClaimVerificationResult>) result).data().verdict());
    }

    @Test
    @DisplayName("TC-24d: owl:Thing is in the whitelist")
    void owlThingInWhitelist() {
        stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
        Claim claim = new Claim("c4", ClaimType.DATA_PROPERTY_DOMAIN, "pizza",
            new ClaimEntity("class", PIZZA), "domain",
            new ClaimEntity("datatype", "http://www.w3.org/2002/07/owl#Thing"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        assertNotEquals(Verdict.OUT_OF_SCOPE, ((ServiceResult.Success<ClaimVerificationResult>) result).data().verdict());
    }

    @Test
    @DisplayName("TC-24e: external IRI outside the built-in namespaces is NOT in the whitelist")
    void externalIriNotInWhitelist() {
        Claim claim = new Claim("c5", ClaimType.SUBCLASS, "pizza",
            new ClaimEntity("class", "http://example.org/external#Virus"), "subClassOf",
            new ClaimEntity("class", PIZZA),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        assertEquals(Verdict.OUT_OF_SCOPE, ((ServiceResult.Success<ClaimVerificationResult>) result).data().verdict(),
            "External IRI must be out-of-scope");
    }
}
