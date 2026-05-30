package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command for getting an ontology summary.
 */
@Command(name = "summary", description = "Get ontology summary: IRI, version, profile, entity counts.")
public class SummaryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        CliServiceFactory factory = new CliServiceFactory(workspaceName, null);

        // Find the canonical path from catalog
        var catalogResult = factory.getCatalogStore().findEntry(
            new org.owl4agents.core.WorkspaceId(workspaceName),
            new org.owl4agents.core.OntologyId(ontologyId));

        if (!catalogResult.isSuccess()) {
            var error = ((org.owl4agents.core.ServiceResult.Error) catalogResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }

        var entry = ((org.owl4agents.core.ServiceResult.Success<
            org.owl4agents.core.model.CatalogEntry>) catalogResult).data();

        var summaryResult = factory.getSummaryExtractor().extractSummary(
            new org.owl4agents.core.OntologyId(ontologyId), entry.canonicalPath());

        if (summaryResult.isSuccess()) {
            var summary = ((org.owl4agents.core.ServiceResult.Success<
                org.owl4agents.core.model.OntologySummary>) summaryResult).data();

            System.out.println("Ontology: " + summary.ontologyId().id());
            System.out.println("IRI: " + summary.ontologyIri());
            System.out.println("Version IRI: " + (summary.versionIri() != null ? summary.versionIri() : "(none)"));
            System.out.println("Imports: " + summary.imports());
            System.out.println("Profile: " + summary.profile().profiles());
            System.out.println("Entity counts:");
            var counts = summary.entityCounts();
            System.out.println("  Classes: " + counts.classes());
            System.out.println("  Object properties: " + counts.objectProperties());
            System.out.println("  Data properties: " + counts.dataProperties());
            System.out.println("  Annotation properties: " + counts.annotationProperties());
            System.out.println("  Individuals: " + counts.individuals());
            System.out.println("  Datatypes: " + counts.datatypes());
            return 0;
        } else {
            var error = ((org.owl4agents.core.ServiceResult.Error) summaryResult).error();
            System.err.println("Error: " + error.code().code() + " - " + error.message());
            return 1;
        }
    }
}