package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.Optional;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.LiteralValidationResult;

/**
 * CLI command for literal validation against a datatype.
 */
@Command(name = "validate-literal", description = "Validate a literal value against a datatype and its facet constraints.")
public class ValidateLiteralCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--literal"}, required = true, description = "Literal value to validate")
    private String literalValue;

    @Option(names = {"--datatype"}, required = true, description = "Datatype IRI to validate against")
    private String datatypeIri;

    @Option(names = {"--property"}, description = "Data property IRI for range validation")
    private String propertyIri;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        OntologyId ontId = new OntologyId(ontologyId);

        Optional<String> propertyOptional = Optional.ofNullable(propertyIri);

        ServiceResult<LiteralValidationResult> result = factory.getSemanticDeepeningService()
            .validateLiteral(ontId, literalValue, datatypeIri, propertyOptional);

        if (result.isSuccess()) {
            LiteralValidationResult data = ((ServiceResult.Success<LiteralValidationResult>) result).data();
            System.out.println("Literal validation for ontology '" + ontologyId + "':");
            System.out.println("  Literal value: " + data.literalValue());
            System.out.println("  Datatype: " + data.datatypeIRI());
            System.out.println("  Valid: " + data.valid());
            if (!data.violations().isEmpty()) {
                System.out.println("  Violations:");
                for (String violation : data.violations()) {
                    System.out.println("    - " + violation);
                }
            }
            return 0;
        } else {
            var error = ((ServiceResult.Error<LiteralValidationResult>) result).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}