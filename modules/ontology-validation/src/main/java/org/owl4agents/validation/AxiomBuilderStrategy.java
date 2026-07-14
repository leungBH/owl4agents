package org.owl4agents.validation;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.Claim;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Strategy interface for building an {@link OWLAxiom} from a {@link Claim}.
 *
 * <p>Implementations are registered with {@link ClaimAxiomBuilder#register}
 * and dispatched by {@link ClaimType}. This enables v1.0.0 to register
 * additional claim types (CLASS_RESTRICTION, PROPERTY_CHAIN, etc.) without
 * modifying the core builder class.
 *
 * @see ClaimAxiomBuilder
 */
@FunctionalInterface
public interface AxiomBuilderStrategy {

    /**
     * Build the OWL axiom from a structured claim.
     *
     * @param ontology the source ontology (for IRI resolution and data factory)
     * @param claim    the structured claim
     * @return {@code ServiceResult.success(axiom)} or
     *         {@code ServiceResult.error(CLAIM_AXIOM_BUILD_FAILED, ...)}
     */
    ServiceResult<OWLAxiom> build(OWLOntology ontology, Claim claim);
}
