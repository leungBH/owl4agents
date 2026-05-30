package org.owl4agents.query;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;

import java.util.*;

/**
 * Readonly SPARQL query execution over imported ontology graph scopes.
 * Supports SELECT, ASK, CONSTRUCT, and DESCRIBE query forms.
 * In Jena 5.x, timeout is enforced through result limits and
 * try-with-resources lifecycle management rather than QueryExecution.setTimeout.
 */
public class SparqlExecutor {

    private final SparqlValidator validator;
    private long defaultTimeoutMs = 30000;
    private int defaultResultLimit = 1000;

    public SparqlExecutor() {
        this.validator = new SparqlValidator();
    }

    public SparqlExecutor(long timeoutMs, int resultLimit) {
        this.validator = new SparqlValidator();
        this.defaultTimeoutMs = timeoutMs;
        this.defaultResultLimit = resultLimit;
    }

    /**
     * Execute a readonly SPARQL SELECT query.
     */
    public ServiceResult<SelectResult> executeSelect(OntologyId ontologyId, String sparqlQuery,
                                                      Model jenaModel, GraphScope scope) {
        // Check for update operations first
        if (validator.isUpdateQuery(sparqlQuery)) {
            return ServiceResult.error(ServiceError.readonlyViolation(
                validator.getUpdateOperationType(sparqlQuery)));
        }

        // Validate the query
        ServiceResult<ValidationResult> validation = validator.validate(sparqlQuery);
        if (!validation.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<ValidationResult>) validation).error());
        }

        ValidationResult vr = ((ServiceResult.Success<ValidationResult>) validation).data();
        if (!"SELECT".equals(vr.queryForm())) {
            return ServiceResult.error(ServiceError.invalidSparql(
                "Expected SELECT query but got " + vr.queryForm()));
        }

        try {
            Query query = QueryFactory.create(sparqlQuery);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, jenaModel)) {
                ResultSet results = qexec.execSelect();
                List<String> variables = results.getResultVars();
                List<Map<String, BindingValue>> bindings = new ArrayList<>();

                int count = 0;
                boolean truncated = false;
                while (results.hasNext() && count < defaultResultLimit) {
                    QuerySolution solution = results.next();
                    Map<String, BindingValue> binding = new LinkedHashMap<>();
                    for (String var : variables) {
                        RDFNode node = solution.get(var);
                        if (node != null) {
                            binding.put(var, convertRdfNode(node));
                        }
                    }
                    bindings.add(binding);
                    count++;
                }

                // Check if there are more results
                if (results.hasNext()) {
                    truncated = true;
                }

                SelectResult result = new SelectResult("SELECT", variables, bindings, bindings.size(), truncated);
                return ServiceResult.success(result, ResultMetadata.explicit(ontologyId));
            }

        } catch (QueryParseException e) {
            return ServiceResult.error(ServiceError.invalidSparql(e.getMessage()));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                return ServiceResult.error(ServiceError.queryTimeout((int) defaultTimeoutMs, (int) defaultTimeoutMs));
            }
            return ServiceResult.error(ServiceError.invalidSparql("Execution error: " + e.getMessage()));
        }
    }

    /**
     * Execute a readonly SPARQL ASK query.
     */
    public ServiceResult<AskResult> executeAsk(OntologyId ontologyId, String sparqlQuery,
                                                Model jenaModel, GraphScope scope) {
        if (validator.isUpdateQuery(sparqlQuery)) {
            return ServiceResult.error(ServiceError.readonlyViolation(
                validator.getUpdateOperationType(sparqlQuery)));
        }

        ServiceResult<ValidationResult> validation = validator.validate(sparqlQuery);
        if (!validation.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<ValidationResult>) validation).error());
        }

        try {
            Query query = QueryFactory.create(sparqlQuery);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, jenaModel)) {
                boolean result = qexec.execAsk();
                AskResult askResult = new AskResult("ASK", result);
                return ServiceResult.success(askResult, ResultMetadata.explicit(ontologyId));
            }

        } catch (QueryParseException e) {
            return ServiceResult.error(ServiceError.invalidSparql(e.getMessage()));
        } catch (Exception e) {
            return ServiceResult.error(ServiceError.invalidSparql("Execution error: " + e.getMessage()));
        }
    }

    /**
     * Execute a readonly SPARQL CONSTRUCT query.
     */
    public ServiceResult<ConstructResult> executeConstruct(OntologyId ontologyId, String sparqlQuery,
                                                            Model jenaModel, GraphScope scope) {
        if (validator.isUpdateQuery(sparqlQuery)) {
            return ServiceResult.error(ServiceError.readonlyViolation(
                validator.getUpdateOperationType(sparqlQuery)));
        }

        ServiceResult<ValidationResult> validation = validator.validate(sparqlQuery);
        if (!validation.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<ValidationResult>) validation).error());
        }

        try {
            Query query = QueryFactory.create(sparqlQuery);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, jenaModel)) {
                Model resultModel = qexec.execConstruct();

                List<TripleValue> triples = convertModelToTriples(resultModel);
                boolean truncated = triples.size() > defaultResultLimit;
                if (truncated) {
                    triples = triples.subList(0, defaultResultLimit);
                }

                ConstructResult result = new ConstructResult("CONSTRUCT", triples, triples.size(), truncated);
                return ServiceResult.success(result, ResultMetadata.explicit(ontologyId));
            }

        } catch (QueryParseException e) {
            return ServiceResult.error(ServiceError.invalidSparql(e.getMessage()));
        } catch (Exception e) {
            return ServiceResult.error(ServiceError.invalidSparql("Execution error: " + e.getMessage()));
        }
    }

    /**
     * Execute a readonly SPARQL DESCRIBE query.
     */
    public ServiceResult<DescribeResult> executeDescribe(OntologyId ontologyId, String sparqlQuery,
                                                          Model jenaModel, GraphScope scope) {
        if (validator.isUpdateQuery(sparqlQuery)) {
            return ServiceResult.error(ServiceError.readonlyViolation(
                validator.getUpdateOperationType(sparqlQuery)));
        }

        ServiceResult<ValidationResult> validation = validator.validate(sparqlQuery);
        if (!validation.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<ValidationResult>) validation).error());
        }

        try {
            Query query = QueryFactory.create(sparqlQuery);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, jenaModel)) {
                Model resultModel = qexec.execDescribe();

                List<TripleValue> triples = convertModelToTriples(resultModel);
                boolean truncated = triples.size() > defaultResultLimit;
                if (truncated) {
                    triples = triples.subList(0, defaultResultLimit);
                }

                DescribeResult result = new DescribeResult("DESCRIBE", triples, triples.size(), truncated);
                return ServiceResult.success(result, ResultMetadata.explicit(ontologyId));
            }

        } catch (QueryParseException e) {
            return ServiceResult.error(ServiceError.invalidSparql(e.getMessage()));
        } catch (Exception e) {
            return ServiceResult.error(ServiceError.invalidSparql("Execution error: " + e.getMessage()));
        }
    }

    private BindingValue convertRdfNode(RDFNode node) {
        if (node.isURIResource()) {
            return new BindingValue(node.asResource().getURI(), null, BindingValue.TYPE_URI);
        } else if (node.isLiteral()) {
            org.apache.jena.rdf.model.Literal literal = node.asLiteral();
            String datatype = literal.getDatatypeURI();
            return new BindingValue(literal.getString(), datatype, BindingValue.TYPE_LITERAL);
        } else {
            // Blank node
            return new BindingValue(node.toString(), null, BindingValue.TYPE_BLANK);
        }
    }

    private List<TripleValue> convertModelToTriples(Model model) {
        List<TripleValue> triples = new ArrayList<>();
        for (Statement stmt : model.listStatements().toList()) {
            String subject = stmt.getSubject().isURIResource() ?
                stmt.getSubject().getURI() : stmt.getSubject().toString();
            String predicate = stmt.getPredicate().getURI();
            String object;
            String objectDatatype = null;

            if (stmt.getObject().isURIResource()) {
                object = stmt.getObject().asResource().getURI();
            } else if (stmt.getObject().isLiteral()) {
                org.apache.jena.rdf.model.Literal lit = stmt.getObject().asLiteral();
                object = lit.getString();
                objectDatatype = lit.getDatatypeURI();
            } else {
                object = stmt.getObject().toString();
            }

            triples.add(new TripleValue(subject, predicate, object, objectDatatype));
        }
        return triples;
    }
}