package org.owl4agents.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;
import org.owl4agents.retrieval.QaContextService;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI command for QA context generation.
 */
@Command(name = "context", description = "Generate ontology-grounded QA context for a question.")
public class ContextCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Ontology ID")
    private String ontologyId;

    @Parameters(index = "1", description = "Question text")
    private String question;

    @Option(names = {"--max-entities"}, description = "Maximum number of matched entities")
    private Integer maxEntities;

    @Option(names = {"--max-depth"}, description = "Maximum context depth")
    private Integer maxDepth;

    @Option(names = {"--workspace"}, description = "Workspace name")
    private String workspaceName = "default";

    @Override
    public Integer call() {
        try {
            CliServiceFactory factory = new CliServiceFactory(workspaceName, null);
            OntologyId ontId = new OntologyId(ontologyId);

            // Create QA context service
            QaContextService qaService = factory.createQaContextService(ontId);

            // Generate context
            ServiceResult<QaContext> result = qaService.generateContext(
                question,
                Optional.ofNullable(maxEntities),
                Optional.ofNullable(maxDepth)
            );

            if (!result.isSuccess()) {
                var error = ((ServiceResult.Error<QaContext>) result).error();
                System.err.println("Error: " + error.code().code() + " - " + error.message());
                return 1;
            }

            QaContext ctx = ((ServiceResult.Success<QaContext>) result).data();

            // Print warnings
            if (!ctx.warnings().isEmpty()) {
                for (QaWarning warning : ctx.warnings()) {
                    System.out.println("Warning: " + warning.message());
                }
                System.out.println();
            }

            // Print matched entities
            System.out.println("Question: " + question);
            System.out.println("Matched entities: " + ctx.matchedEntities().size());
            for (SearchMatch match : ctx.matchedEntities()) {
                System.out.println("  - " + (match.label() != null ? match.label() : match.iri())
                    + " (" + match.type().jsonName() + ")");
            }
            System.out.println();

            // Print class context
            if (!ctx.classContext().isEmpty()) {
                System.out.println("Class context:");
                for (ClassContext cc : ctx.classContext()) {
                    System.out.println("  " + cc.label() + " (" + cc.iri() + ")");
                    if (!cc.directSuperclasses().isEmpty()) {
                        System.out.println("    Superclasses: " + cc.directSuperclasses());
                    }
                    if (!cc.directSubclasses().isEmpty()) {
                        System.out.println("    Subclasses: " + cc.directSubclasses());
                    }
                }
            }

            // Print property context
            if (!ctx.propertyContext().isEmpty()) {
                System.out.println("Property context:");
                for (ObjectPropertyContext pc : ctx.propertyContext()) {
                    System.out.println("  " + pc.label() + " (" + pc.iri() + ")");
                    if (!pc.domain().isEmpty()) {
                        System.out.println("    Domain: " + pc.domain());
                    }
                    if (!pc.range().isEmpty()) {
                        System.out.println("    Range: " + pc.range());
                    }
                }
            }

            // Print individual context
            if (!ctx.individualContext().isEmpty()) {
                System.out.println("Individual context:");
                for (IndividualContext ic : ctx.individualContext()) {
                    System.out.println("  " + ic.label() + " (" + ic.iri() + ")");
                    if (!ic.explicitTypes().isEmpty()) {
                        System.out.println("    Types: " + ic.explicitTypes());
                    }
                }
            }

            // Print natural language context
            System.out.println();
            System.out.println("Generated context:");
            System.out.println(ctx.naturalLanguageContext());

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}