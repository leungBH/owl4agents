package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ValidationResult;

import java.util.concurrent.Callable;

/**
 * CLI command for SPARQL query validation and execution.
 */
@Command(name = "query", description = "Validate or execute SPARQL queries.")
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--validate"}, description = "Validate the SPARQL query without execution")
    private boolean validateOnly = false;

    @Option(names = {"--select"}, description = "SPARQL SELECT query text")
    private String selectQuery;

    @Option(names = {"--ask"}, description = "SPARQL ASK query text")
    private String askQuery;

    @Option(names = {"--construct"}, description = "SPARQL CONSTRUCT query text")
    private String constructQuery;

    @Option(names = {"--describe"}, description = "SPARQL DESCRIBE query text")
    private String describeQuery;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
        org.owl4agents.query.SparqlValidator validator = factory.getSparqlValidator();

        String query = determineQuery();
        if (query == null) {
            System.err.println("Error: No query provided. Use --select, --ask, --construct, --describe, or --validate.");
            return 1;
        }

        // Check readonly safety first
        org.owl4agents.query.SparqlSafetyGuard safety = factory.getSparqlSafetyGuard();
        ServiceResult<Void> safetyResult = safety.checkReadonly(query);
        if (!safetyResult.isSuccess()) {
            var error = ((ServiceResult.Error<Void>) safetyResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        // Validate the query
        ServiceResult<ValidationResult> validationResult = validator.validate(query);
        if (validateOnly) {
            if (validationResult.isSuccess()) {
                var vr = ((ServiceResult.Success<ValidationResult>) validationResult).data();
                System.out.println("Query is valid. Form: " + vr.queryForm());
                return 0;
            } else {
                var error = ((ServiceResult.Error<ValidationResult>) validationResult).error();
                System.err.println("Query is invalid: " + error.message());
                return 1;
            }
        }

        if (!validationResult.isSuccess()) {
            var error = ((ServiceResult.Error<ValidationResult>) validationResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        // Execution requires loading ontology and converting to Jena model
        try {
            OntologyId ontId = new OntologyId(ontologyId);
            org.apache.jena.rdf.model.Model jenaModel = factory.createJenaModel(ontId);
            org.owl4agents.query.SparqlExecutor executor = factory.getSparqlExecutor();

            // Determine query form and execute
            var vr = ((ServiceResult.Success<ValidationResult>) validationResult).data();
            String queryForm = vr.queryForm();

            switch (queryForm) {
                case "SELECT" -> {
                    var result = executor.executeSelect(ontId, query, jenaModel, org.owl4agents.core.GraphScope.EXPLICIT);
                    if (result.isSuccess()) {
                        var sr = ((ServiceResult.Success<org.owl4agents.core.model.SelectResult>) result).data();
                        System.out.println("Variables: " + sr.variables());
                        System.out.println("Results: " + sr.totalBindings());
                        for (var binding : sr.bindings()) {
                            System.out.println("  " + binding);
                        }
                        if (sr.truncated()) {
                            System.out.println("(Results truncated)");
                        }
                    } else {
                        var error = ((ServiceResult.Error<?>) result).error();
                        System.err.println("Error: " + error.code().code() + " - " + error.message());
                        return 1;
                    }
                }
                case "ASK" -> {
                    var result = executor.executeAsk(ontId, query, jenaModel, org.owl4agents.core.GraphScope.EXPLICIT);
                    if (result.isSuccess()) {
                        var ar = ((ServiceResult.Success<org.owl4agents.core.model.AskResult>) result).data();
                        System.out.println("Result: " + ar.result());
                    } else {
                        var error = ((ServiceResult.Error<?>) result).error();
                        System.err.println("Error: " + error.code().code() + " - " + error.message());
                        return 1;
                    }
                }
                case "CONSTRUCT" -> {
                    var result = executor.executeConstruct(ontId, query, jenaModel, org.owl4agents.core.GraphScope.EXPLICIT);
                    if (result.isSuccess()) {
                        var cr = ((ServiceResult.Success<org.owl4agents.core.model.ConstructResult>) result).data();
                        System.out.println("Triples: " + cr.totalTriples());
                        for (var triple : cr.triples()) {
                            System.out.println("  " + triple.subject() + " " + triple.predicate() + " " + triple.object());
                        }
                        if (cr.truncated()) {
                            System.out.println("(Results truncated)");
                        }
                    } else {
                        var error = ((ServiceResult.Error<?>) result).error();
                        System.err.println("Error: " + error.code().code() + " - " + error.message());
                        return 1;
                    }
                }
                case "DESCRIBE" -> {
                    var result = executor.executeDescribe(ontId, query, jenaModel, org.owl4agents.core.GraphScope.EXPLICIT);
                    if (result.isSuccess()) {
                        var dr = ((ServiceResult.Success<org.owl4agents.core.model.DescribeResult>) result).data();
                        System.out.println("Triples: " + dr.totalTriples());
                        for (var triple : dr.triples()) {
                            System.out.println("  " + triple.subject() + " " + triple.predicate() + " " + triple.object());
                        }
                        if (dr.truncated()) {
                            System.out.println("(Results truncated)");
                        }
                    } else {
                        var error = ((ServiceResult.Error<?>) result).error();
                        System.err.println("Error: " + error.code().code() + " - " + error.message());
                        return 1;
                    }
                }
                default -> {
                    System.err.println("Unsupported query form: " + queryForm);
                    return 1;
                }
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            return 1;
        }
    }

    private String determineQuery() {
        if (selectQuery != null) return selectQuery;
        if (askQuery != null) return askQuery;
        if (constructQuery != null) return constructQuery;
        if (describeQuery != null) return describeQuery;
        return null;
    }
}