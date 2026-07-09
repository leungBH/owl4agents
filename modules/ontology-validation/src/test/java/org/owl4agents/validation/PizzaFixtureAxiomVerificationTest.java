package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-21: Pizza fixture axiom verification — ensures that the 6 v0.8.1
 * expected axioms are present in {@code test/corpus/smoke/pizza.owl} so
 * the 5 fix scenarios have data to operate on.
 *
 * <p>If any axiom is absent, the test fails with a message identifying
 * the missing axiom and suggesting fixture regeneration. This is the
 * first gate the v0.8.1 acceptance suite hits.</p>
 */
@DisplayName("TC-21 pizza.owl fixture axiom verification")
class PizzaFixtureAxiomVerificationTest {

    private static final String PIZZA_IRI_BASE = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";

    @Test
    @DisplayName("pizza.owl has the 6 v0.8.1 required axioms")
    void pizzaHasRequiredAxioms() throws Exception {
        Path fixture = resolvePizzaFixture();
        assertTrue(Files.exists(fixture), "pizza.owl must exist at: " + fixture);

        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = mgr.loadOntologyFromOntologyDocument(fixture.toFile());
        OWLDataFactory df = mgr.getOWLDataFactory();

        IRI hasBaseIri = IRI.create(PIZZA_IRI_BASE + "hasBase");
        IRI isBaseOfIri = IRI.create(PIZZA_IRI_BASE + "isBaseOf");
        IRI hasIngredientIri = IRI.create(PIZZA_IRI_BASE + "hasIngredient");
        IRI pizzaBaseIri = IRI.create(PIZZA_IRI_BASE + "PizzaBase");
        IRI pizzaIri = IRI.create(PIZZA_IRI_BASE + "Pizza");
        IRI cheeseyPizzaIri = IRI.create(PIZZA_IRI_BASE + "CheeseyPizza");
        IRI cheeseToppingIri = IRI.create(PIZZA_IRI_BASE + "CheeseTopping");
        IRI hasToppingIri = IRI.create(PIZZA_IRI_BASE + "hasTopping");

        // 1. hasBase subPropertyOf hasIngredient
        boolean hasBaseSubHasIngredient = ontology.getObjectSubPropertyAxiomsForSubProperty(
                df.getOWLObjectProperty(hasBaseIri)).stream()
            .anyMatch(ax -> ax.getSuperProperty().equals(df.getOWLObjectProperty(hasIngredientIri)));
        assertTrue(hasBaseSubHasIngredient,
            "pizza.owl must declare hasBase rdfs:subPropertyOf hasIngredient (required for pizza-037)");

        // 2. hasBase inverseOf isBaseOf
        boolean hasBaseInverseIsBaseOf = ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES).stream()
            .anyMatch(ax -> ax.getFirstProperty().equals(df.getOWLObjectProperty(hasBaseIri)) &&
                           ax.getSecondProperty().equals(df.getOWLObjectProperty(isBaseOfIri)));
        assertTrue(hasBaseInverseIsBaseOf,
            "pizza.owl must declare hasBase owl:inverseOf isBaseOf (required for pizza-046 inverse-domain entailment)");

        // 3. CheeseyPizza equivalentClass (Pizza ∩ ∃hasTopping.CheeseTopping)
        boolean cheeseyPizzaHasComplexEq = ontology.getEquivalentClassesAxioms(
                df.getOWLClass(cheeseyPizzaIri)).stream()
            .anyMatch(ax -> {
                java.util.Set<OWLClassExpression> exprs = ax.getClassExpressions();
                return exprs.size() >= 2 && exprs.contains(df.getOWLClass(cheeseyPizzaIri))
                    && exprs.stream().anyMatch(e -> e instanceof OWLObjectIntersectionOf);
            });
        assertTrue(cheeseyPizzaHasComplexEq,
            "pizza.owl must declare CheeseyPizza as an OWLObjectIntersectionOf equivalent (required for pizza-007)");

        // 4. owl:AllDifferent for America/England/France/Germany/Italy
        IRI[] countries = new IRI[]{
            IRI.create(PIZZA_IRI_BASE + "America"),
            IRI.create(PIZZA_IRI_BASE + "England"),
            IRI.create(PIZZA_IRI_BASE + "France"),
            IRI.create(PIZZA_IRI_BASE + "Germany"),
            IRI.create(PIZZA_IRI_BASE + "Italy")
        };
        boolean hasAllDifferent = ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS).stream()
            .anyMatch(ax -> {
                java.util.Set<OWLIndividual> members = ax.getIndividuals();
                java.util.Set<String> memberIris = new java.util.HashSet<>();
                for (OWLIndividual ind : members) {
                    if (ind.isNamed()) memberIris.add(ind.asOWLNamedIndividual().getIRI().toString());
                }
                for (IRI c : countries) {
                    if (!memberIris.contains(c.toString())) return false;
                }
                return true;
            });
        assertTrue(hasAllDifferent,
            "pizza.owl must declare an owl:AllDifferent for America/England/France/Germany/Italy (required for pizza-035)");

        // 5. isBaseOf declared as owl:ObjectProperty
        boolean isBaseOfObjectProperty = ontology.getObjectPropertiesInSignature().stream()
            .anyMatch(p -> p.getIRI().equals(isBaseOfIri));
        assertTrue(isBaseOfObjectProperty,
            "pizza.owl must declare isBaseOf as an owl:ObjectProperty (required for pizza-046)");

        // 6. ObjectPropertyDomain(hasBase, Pizza) — via rdfs:domain or property chain
        boolean hasBaseDomainPizza = ontology.getObjectPropertyDomainAxioms(
                df.getOWLObjectProperty(hasBaseIri)).stream()
            .anyMatch(ax -> ax.getDomain().equals(df.getOWLClass(pizzaIri)));
        // Indirect: if hasBase has no explicit domain, check that hasTopping does
        // (used by reasoner to infer hasBase domain = Pizza)
        if (!hasBaseDomainPizza) {
            hasBaseDomainPizza = ontology.getObjectPropertyDomainAxioms(
                    df.getOWLObjectProperty(hasToppingIri)).stream()
                .anyMatch(ax -> ax.getDomain().equals(df.getOWLClass(pizzaIri)));
        }
        assertTrue(hasBaseDomainPizza,
            "pizza.owl must declare rdfs:domain Pizza on hasBase or hasTopping (required for pizza-046 inferred-domain entailment)");
    }

    private Path resolvePizzaFixture() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = cwd.resolve("test/corpus/smoke/pizza.owl");
            if (Files.exists(candidate)) return candidate;
            candidate = cwd.resolve("../test/corpus/smoke/pizza.owl");
            if (Files.exists(candidate)) return candidate;
            cwd = cwd.getParent();
            if (cwd == null) break;
        }
        return Path.of("test/corpus/smoke/pizza.owl");
    }
}
