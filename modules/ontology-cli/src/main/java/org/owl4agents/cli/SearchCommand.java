package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.SearchResult;
import org.owl4agents.core.model.SearchMatch;
import org.owl4agents.retrieval.EntitySearchService;

import java.util.concurrent.Callable;

/**
 * CLI command for entity search.
 */
@Command(name = "search", description = "Search ontology entities by label, IRI, or alias.")
public class SearchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Search query text")
    private String query;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        try {
            CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
            OntologyId ontId = new OntologyId(ontologyId);

            // Create search service with loaded ontology index
            EntitySearchService searchService = factory.createEntitySearchService(ontId);

            // Execute search
            ServiceResult<SearchResult> result = searchService.search(query);
            if (!result.isSuccess()) {
                var error = ((ServiceResult.Error<SearchResult>) result).error();
                System.err.println("Error: " + error.code().code() + " - " + error.message());
                return 1;
            }

            SearchResult searchResult = ((ServiceResult.Success<SearchResult>) result).data();
            System.out.println("Search results for '" + query + "' in ontology '" + ontologyId + "':");
            System.out.println("Found " + searchResult.totalResults() + " results\n");

            for (SearchMatch match : searchResult.results()) {
                System.out.println("  " + (match.label() != null ? match.label() : match.prefixedName()));
                System.out.println("    IRI: " + match.iri());
                System.out.println("    Type: " + match.type().jsonName());
                System.out.println("    Score: " + match.score());
                System.out.println("    Match: " + match.matchReason());
                System.out.println();
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}