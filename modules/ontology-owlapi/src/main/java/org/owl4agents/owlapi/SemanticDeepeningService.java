package org.owl4agents.owlapi;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.stream.*;
import java.nio.file.*;

/**
 * Semantic-deepening service implementation.
 * Provides expanded semantic context: import closure, class restrictions,
 * property characteristics, equivalent/disjoint properties, datatype constraints,
 * literal validation, relation assertions, individual assertions,
 * membership checks, and relation checks.
 */
public class SemanticDeepeningService {

    private final String workspaceBasePath;

    public SemanticDeepeningService(String workspaceBasePath) {
        this.workspaceBasePath = workspaceBasePath;
    }

    // ── Import Closure ──

    public ServiceResult<ImportClosureResult> getImportClosure(OntologyId ontologyId) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            List<ImportEntry> imports = new ArrayList<>();
            List<String> cycleWarning = new ArrayList<>();

            // Direct imports
            Set<IRI> directImports = ontology.getDirectImportsDocuments();
            for (IRI importIRI : directImports) {
                imports.add(new ImportEntry(
                    importIRI.toString(), null, detectProfileFromIRI(importIRI), true, false));
            }

            // Transitively imported ontologies
            Set<IRI> allImports = ontology.getImportsDeclarations()
                .stream().map(OWLImportsDeclaration::getIRI).collect(Collectors.toSet());

            for (IRI importIRI : allImports) {
                if (!directImports.contains(importIRI)) {
                    imports.add(new ImportEntry(
                        importIRI.toString(), null, detectProfileFromIRI(importIRI), false, true));
                }
            }

            if (imports.isEmpty()) {
                return ServiceResult.success(
                    new ImportClosureResult(ontologyId.id(), imports, false, cycleWarning),
                    ResultMetadata.empty());
            }

            return ServiceResult.success(
                new ImportClosureResult(ontologyId.id(), imports, false, cycleWarning),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Class Restrictions ──

    public ServiceResult<ClassRestrictionsResult> getClassRestrictions(OntologyId ontologyId, String classIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlClass = df.getOWLClass(IRI.create(classIRI));

            // Check class exists
            if (!ontology.getClassesInSignature(Imports.INCLUDED).stream().anyMatch(c -> c.getIRI().toString().equals(classIRI))) {
                return ServiceResult.error(ErrorCode.CLASS_NOT_FOUND, "Class URI not found: " + classIRI);
            }

            List<ClassRestriction> restrictions = new ArrayList<>();

            // Get all subclass axioms involving this class
            for (OWLSubClassOfAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED)) {
                if (ax.getSubClass().equals(owlClass) && ax.getSuperClass() instanceof OWLRestriction) {
                    OWLRestriction restriction = (OWLRestriction) ax.getSuperClass();
                    ClassRestriction cr = parseRestriction(restriction, "explicit");
                    if (cr != null) restrictions.add(cr);
                }
            }

            // Get equivalent class axioms with restrictions
            for (OWLEquivalentClassesAxiom ax : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES, Imports.INCLUDED)) {
                for (OWLClassExpression ce : ax.getClassExpressions()) {
                    if (ce instanceof OWLRestriction) {
                        ClassRestriction cr = parseRestriction((OWLRestriction) ce, "explicit");
                        if (cr != null) restrictions.add(cr);
                    }
                }
            }

            if (restrictions.isEmpty()) {
                return ServiceResult.success(
                    new ClassRestrictionsResult(ontologyId.id(), classIRI, restrictions),
                    ResultMetadata.empty());
            }

            return ServiceResult.success(
                new ClassRestrictionsResult(ontologyId.id(), classIRI, restrictions),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    private ClassRestriction parseRestriction(OWLRestriction restriction, String source) {
        String onProperty = restriction.getProperty().toString();
        String restrictionType;
        String filler = null;
        Integer cardinality = null;

        if (restriction instanceof OWLObjectSomeValuesFrom) {
            restrictionType = "someValuesFrom";
            filler = ((OWLObjectSomeValuesFrom) restriction).getFiller().toString();
        } else if (restriction instanceof OWLObjectAllValuesFrom) {
            restrictionType = "allValuesFrom";
            filler = ((OWLObjectAllValuesFrom) restriction).getFiller().toString();
        } else if (restriction instanceof OWLObjectHasValue) {
            restrictionType = "hasValue";
            filler = ((OWLObjectHasValue) restriction).getFiller().toString();
        } else if (restriction instanceof OWLObjectMinCardinality) {
            restrictionType = "minCardinality";
            cardinality = ((OWLObjectMinCardinality) restriction).getCardinality();
            filler = ((OWLObjectMinCardinality) restriction).getFiller().toString();
        } else if (restriction instanceof OWLObjectMaxCardinality) {
            restrictionType = "maxCardinality";
            cardinality = ((OWLObjectMaxCardinality) restriction).getCardinality();
            filler = ((OWLObjectMaxCardinality) restriction).getFiller().toString();
        } else if (restriction instanceof OWLObjectExactCardinality) {
            restrictionType = "exactCardinality";
            cardinality = ((OWLObjectExactCardinality) restriction).getCardinality();
            filler = ((OWLObjectExactCardinality) restriction).getFiller().toString();
        } else if (restriction instanceof OWLDataSomeValuesFrom) {
            restrictionType = "datatypeSomeValuesFrom";
            filler = ((OWLDataSomeValuesFrom) restriction).getFiller().toString();
        } else if (restriction instanceof OWLDataAllValuesFrom) {
            restrictionType = "datatypeAllValuesFrom";
            filler = ((OWLDataAllValuesFrom) restriction).getFiller().toString();
        } else if (restriction instanceof OWLDataHasValue) {
            restrictionType = "datatypeHasValue";
            OWLLiteral literal = ((OWLDataHasValue) restriction).getFiller();
            filler = literal.getLiteral();
        } else if (restriction instanceof OWLDataMinCardinality) {
            restrictionType = "datatypeMinCardinality";
            cardinality = ((OWLDataMinCardinality) restriction).getCardinality();
        } else if (restriction instanceof OWLDataMaxCardinality) {
            restrictionType = "datatypeMaxCardinality";
            cardinality = ((OWLDataMaxCardinality) restriction).getCardinality();
        } else if (restriction instanceof OWLDataExactCardinality) {
            restrictionType = "datatypeExactCardinality";
            cardinality = ((OWLDataExactCardinality) restriction).getCardinality();
        } else {
            return null;
        }

        return new ClassRestriction(restrictionType, onProperty, filler, cardinality, source);
    }

    // ── Property Characteristics ──

    public ServiceResult<PropertyCharacteristicsResult> getPropertyCharacteristics(OntologyId ontologyId, String propertyIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            // Determine property type
            boolean isObjectProperty = ontology.getObjectPropertiesInSignature(Imports.INCLUDED).stream()
                .anyMatch(p -> p.getIRI().toString().equals(propertyIRI));
            boolean isDataProperty = ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream()
                .anyMatch(p -> p.getIRI().toString().equals(propertyIRI));

            if (!isObjectProperty && !isDataProperty) {
                return ServiceResult.error(ErrorCode.PROPERTY_NOT_FOUND, "Property URI not found: " + propertyIRI);
            }

            String propertyType = isObjectProperty ? "ObjectProperty" : "DataProperty";
            boolean functional = false, inverseFunctional = false, transitive = false,
                    symmetric = false, asymmetric = false, reflexive = false, irreflexive = false;

            if (isObjectProperty) {
                OWLObjectProperty owlProp = df.getOWLObjectProperty(IRI.create(propertyIRI));
                functional = EntitySearcher.isFunctional(owlProp, ontology);
                inverseFunctional = EntitySearcher.isInverseFunctional(owlProp, ontology);
                transitive = EntitySearcher.isTransitive(owlProp, ontology);
                symmetric = EntitySearcher.isSymmetric(owlProp, ontology);
                asymmetric = EntitySearcher.isAsymmetric(owlProp, ontology);
                reflexive = EntitySearcher.isReflexive(owlProp, ontology);
                irreflexive = EntitySearcher.isIrreflexive(owlProp, ontology);
            } else {
                OWLDataProperty owlProp = df.getOWLDataProperty(IRI.create(propertyIRI));
                functional = EntitySearcher.isFunctional(owlProp, ontology);
            }

            return ServiceResult.success(
                new PropertyCharacteristicsResult(ontologyId.id(), propertyIRI, propertyType,
                    functional, inverseFunctional, transitive, symmetric, asymmetric, reflexive, irreflexive, "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Equivalent Properties ──

    public ServiceResult<PropertyAxiomsResult> getEquivalentProperties(OntologyId ontologyId, String propertyIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            boolean isObjectProperty = ontology.getObjectPropertiesInSignature(Imports.INCLUDED).stream()
                .anyMatch(p -> p.getIRI().toString().equals(propertyIRI));
            boolean isDataProperty = ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream()
                .anyMatch(p -> p.getIRI().toString().equals(propertyIRI));

            if (!isObjectProperty && !isDataProperty) {
                return ServiceResult.error(ErrorCode.PROPERTY_NOT_FOUND, "Property URI not found: " + propertyIRI);
            }

            String propertyType = isObjectProperty ? "ObjectProperty" : "DataProperty";
            List<String> equivalents = new ArrayList<>();

            if (isObjectProperty) {
                OWLObjectProperty owlProp = df.getOWLObjectProperty(IRI.create(propertyIRI));
                for (OWLEquivalentObjectPropertiesAxiom ax : ontology.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES, Imports.INCLUDED)) {
                    if (ax.getProperties().contains(owlProp)) {
                        ax.getProperties().stream()
                            .filter(p -> !p.equals(owlProp) && p.isNamed())
                            .forEach(p -> equivalents.add(((OWLObjectProperty) p).getIRI().toString()));
                    }
                }
            } else {
                OWLDataProperty owlProp = df.getOWLDataProperty(IRI.create(propertyIRI));
                for (OWLEquivalentDataPropertiesAxiom ax : ontology.getAxioms(AxiomType.EQUIVALENT_DATA_PROPERTIES, Imports.INCLUDED)) {
                    if (ax.getProperties().contains(owlProp)) {
                        ax.getProperties().stream()
                            .filter(p -> !p.equals(owlProp))
                            .forEach(p -> equivalents.add(((OWLDataProperty) p).getIRI().toString()));
                    }
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), propertyIRI, propertyType, equivalents, "equivalent", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Disjoint Properties ──

    public ServiceResult<PropertyAxiomsResult> getDisjointProperties(OntologyId ontologyId, String propertyIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            boolean isObjectProperty = ontology.getObjectPropertiesInSignature(Imports.INCLUDED).stream()
                .anyMatch(p -> p.getIRI().toString().equals(propertyIRI));
            boolean isDataProperty = ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream()
                .anyMatch(p -> p.getIRI().toString().equals(propertyIRI));

            if (!isObjectProperty && !isDataProperty) {
                return ServiceResult.error(ErrorCode.PROPERTY_NOT_FOUND, "Property URI not found: " + propertyIRI);
            }

            String propertyType = isObjectProperty ? "ObjectProperty" : "DataProperty";
            List<String> disjoints = new ArrayList<>();

            if (isObjectProperty) {
                OWLObjectProperty owlProp = df.getOWLObjectProperty(IRI.create(propertyIRI));
                for (OWLDisjointObjectPropertiesAxiom ax : ontology.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES, Imports.INCLUDED)) {
                    if (ax.getProperties().contains(owlProp)) {
                        ax.getProperties().stream()
                            .filter(p -> !p.equals(owlProp) && p.isNamed())
                            .forEach(p -> disjoints.add(((OWLObjectProperty) p).getIRI().toString()));
                    }
                }
            } else {
                OWLDataProperty owlProp = df.getOWLDataProperty(IRI.create(propertyIRI));
                for (OWLDisjointDataPropertiesAxiom ax : ontology.getAxioms(AxiomType.DISJOINT_DATA_PROPERTIES, Imports.INCLUDED)) {
                    if (ax.getProperties().contains(owlProp)) {
                        ax.getProperties().stream()
                            .filter(p -> !p.equals(owlProp))
                            .forEach(p -> disjoints.add(((OWLDataProperty) p).getIRI().toString()));
                    }
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), propertyIRI, propertyType, disjoints, "disjoint", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Datatype Constraints ──

    public ServiceResult<DatatypeConstraintsResult> getDatatypeConstraints(OntologyId ontologyId, String datatypeIRI) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLDatatype owlDatatype = df.getOWLDatatype(IRI.create(datatypeIRI));

            // Check datatype exists in ontology
            boolean found = ontology.getDatatypesInSignature(Imports.INCLUDED).stream()
                .anyMatch(dt -> dt.getIRI().toString().equals(datatypeIRI));

            // Also check built-in XSD datatypes
            if (!found && !datatypeIRI.startsWith("http://www.w3.org/2001/XMLSchema#")) {
                return ServiceResult.error(ErrorCode.DATATYPE_NOT_FOUND, "Datatype URI not found: " + datatypeIRI);
            }

            String baseDatatypeIRI = null;
            List<DatatypeFacet> facets = new ArrayList<>();

            // Find datatype definition axioms
            for (OWLDatatypeDefinitionAxiom ax : ontology.getAxioms(AxiomType.DATATYPE_DEFINITION, Imports.INCLUDED)) {
                if (ax.getDatatype().equals(owlDatatype)) {
                    OWLDataRange dataRange = ax.getDataRange();
                    if (dataRange instanceof OWLDatatypeRestriction) {
                        OWLDatatypeRestriction restriction = (OWLDatatypeRestriction) dataRange;
                        baseDatatypeIRI = restriction.getDatatype().getIRI().toString();
                        for (OWLFacetRestriction facet : restriction.getFacetRestrictions()) {
                            facets.add(new DatatypeFacet(
                                facet.getFacet().getSymbolicForm(),
                                facet.getFacetValue().getLiteral()));
                        }
                    }
                }
            }

            if (facets.isEmpty() && datatypeIRI.startsWith("http://www.w3.org/2001/XMLSchema#")) {
                // Built-in XSD datatype with no local constraints
                return ServiceResult.error(ErrorCode.DATATYPE_NO_CONSTRAINTS,
                    "The specified datatype exists but has no defined facet constraints: " + datatypeIRI);
            }

            return ServiceResult.success(
                new DatatypeConstraintsResult(ontologyId.id(), datatypeIRI, baseDatatypeIRI, facets),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Literal Validation ──

    public ServiceResult<LiteralValidationResult> validateLiteral(OntologyId ontologyId, String literalValue, String datatypeIRI, Optional<String> propertyIRI) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLDatatype owlDatatype = df.getOWLDatatype(IRI.create(datatypeIRI));
            List<String> violations = new ArrayList<>();
            boolean valid = true;

            // Extract datatype constraints directly from loaded ontology
            String baseDatatypeIRI = null;
            List<DatatypeFacet> facets = new ArrayList<>();
            for (OWLDatatypeDefinitionAxiom ax : ontology.getAxioms(AxiomType.DATATYPE_DEFINITION, Imports.INCLUDED)) {
                if (ax.getDatatype().equals(owlDatatype)) {
                    OWLDataRange dataRange = ax.getDataRange();
                    if (dataRange instanceof OWLDatatypeRestriction restriction) {
                        baseDatatypeIRI = restriction.getDatatype().getIRI().toString();
                        for (OWLFacetRestriction facet : restriction.getFacetRestrictions()) {
                            facets.add(new DatatypeFacet(
                                facet.getFacet().getSymbolicForm(),
                                facet.getFacetValue().getLiteral()));
                        }
                    }
                }
            }

            // Validate against primitive type
            String baseType = (baseDatatypeIRI != null) ? baseDatatypeIRI : datatypeIRI;

            if (!validatePrimitiveType(literalValue, baseType)) {
                valid = false;
                violations.add("The value is not a valid " + baseType);
            }

            // Validate against facets
            for (DatatypeFacet facet : facets) {
                if (!validateFacet(literalValue, facet, baseType)) {
                    valid = false;
                    violations.add("The value violates " + facet.facetType() + " constraint " + facet.facetValue());
                }
            }

            // Validate against property range (if provided)
            if (propertyIRI.isPresent()) {
                String propIRI = propertyIRI.get();
                boolean hasRange = ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream()
                    .anyMatch(p -> p.getIRI().toString().equals(propIRI));

                if (!hasRange) {
                    // Property not found is not necessarily an error for validation
                    // But if found with no range, return PROPERTY_RANGE_NOT_FOUND
                    boolean propExists = ontology.getDataPropertiesInSignature(Imports.INCLUDED).stream()
                        .anyMatch(p -> p.getIRI().toString().equals(propIRI));
                    if (propExists) {
                        // Check if property has a range
                        boolean hasRangeDecl = ontology.getAxioms(AxiomType.DATA_PROPERTY_RANGE, Imports.INCLUDED).stream()
                            .anyMatch(ax -> ((OWLDataProperty) ax.getProperty()).getIRI().toString().equals(propIRI));
                        if (!hasRangeDecl) {
                            return ServiceResult.error(ErrorCode.PROPERTY_RANGE_NOT_FOUND,
                                "The specified property has no range declaration: " + propIRI);
                        }
                    }
                }
            }

            return ServiceResult.success(
                new LiteralValidationResult(literalValue, datatypeIRI, valid, violations),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    private boolean validatePrimitiveType(String value, String baseType) {
        if (baseType.contains("integer")) {
            try { Integer.parseInt(value); return true; }
            catch (NumberFormatException e) { return false; }
        } else if (baseType.contains("decimal") || baseType.contains("double") || baseType.contains("float")) {
            try { Double.parseDouble(value); return true; }
            catch (NumberFormatException e) { return false; }
        } else if (baseType.contains("boolean")) {
            return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
        } else if (baseType.contains("date")) {
            // Simplified date validation
            return value.matches("\\d{4}-\\d{2}-\\d{2}");
        }
        // String type always valid
        return true;
    }

    private boolean validateFacet(String value, DatatypeFacet facet, String baseType) {
        String facetType = facet.facetType();
        String facetValue = facet.facetValue();

        switch (facetType) {
            case "minInclusive":
            case ">=":
                return compareNumeric(value, facetValue) >= 0;
            case "maxInclusive":
            case "<=":
                return compareNumeric(value, facetValue) <= 0;
            case "minExclusive":
            case ">":
                return compareNumeric(value, facetValue) > 0;
            case "maxExclusive":
            case "<":
                return compareNumeric(value, facetValue) < 0;
            case "pattern":
                return value.matches(facetValue);
            case "length":
                return value.length() == Integer.parseInt(facetValue);
            case "minLength":
                return value.length() >= Integer.parseInt(facetValue);
            case "maxLength":
                return value.length() <= Integer.parseInt(facetValue);
            default:
                return true; // Unknown facet, skip validation
        }
    }

    private int compareNumeric(String value, String constraint) {
        try {
            double v = Double.parseDouble(value);
            double c = Double.parseDouble(constraint);
            return Double.compare(v, c);
        } catch (NumberFormatException e) {
            return 0; // Can't compare, assume valid
        }
    }

    // ── Relation Assertions ──

    public ServiceResult<PropertyAxiomsResult> findRelationsBetweenEntities(OntologyId ontologyId, String sourceIRI, String targetIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            // Check both entities exist
            boolean sourceFound = ontology.getIndividualsInSignature(Imports.INCLUDED).stream()
                .anyMatch(i -> i.getIRI().toString().equals(sourceIRI));
            boolean targetFound = ontology.getIndividualsInSignature(Imports.INCLUDED).stream()
                .anyMatch(i -> i.getIRI().toString().equals(targetIRI));

            if (!sourceFound) {
                return ServiceResult.error(ErrorCode.ENTITY_NOT_FOUND, "Source entity not found: " + sourceIRI);
            }
            if (!targetFound) {
                return ServiceResult.error(ErrorCode.ENTITY_NOT_FOUND, "Target entity not found: " + targetIRI);
            }

            List<String> relations = new ArrayList<>();
            OWLNamedIndividual sourceInd = df.getOWLNamedIndividual(IRI.create(sourceIRI));
            OWLNamedIndividual targetInd = df.getOWLNamedIndividual(IRI.create(targetIRI));

            // Find object property assertions connecting source to target
            for (OWLObjectPropertyAssertionAxiom ax : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION, Imports.INCLUDED)) {
                if (ax.getSubject().equals(sourceInd) && ax.getObject().equals(targetInd)) {
                    if (ax.getProperty().isNamed()) {
                        relations.add(((OWLObjectProperty) ax.getProperty()).getIRI().toString());
                    }
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), sourceIRI, "ObjectProperty", relations, "relations", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Individual Assertions ──

    public ServiceResult<PropertyAxiomsResult> getObjectPropertyAssertions(OntologyId ontologyId, String individualIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(individualIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Individual URI not found: " + individualIRI);
            }

            List<String> assertions = new ArrayList<>();
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(individualIRI));

            for (OWLObjectPropertyAssertionAxiom ax : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION, Imports.INCLUDED)) {
                if (ax.getSubject().equals(ind)) {
                    String propIRI = ax.getProperty().isNamed()
                        ? ((OWLObjectProperty) ax.getProperty()).getIRI().toString() : ax.getProperty().toString();
                    String objIRI = ax.getObject() instanceof OWLNamedIndividual
                        ? ((OWLNamedIndividual) ax.getObject()).getIRI().toString() : ax.getObject().toString();
                    assertions.add(propIRI + " -> " + objIRI);
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), individualIRI, "ObjectProperty", assertions, "object_assertions", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    public ServiceResult<PropertyAxiomsResult> getDataPropertyAssertions(OntologyId ontologyId, String individualIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(individualIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Individual URI not found: " + individualIRI);
            }

            List<String> assertions = new ArrayList<>();
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(individualIRI));

            for (OWLDataPropertyAssertionAxiom ax : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION, Imports.INCLUDED)) {
                if (ax.getSubject().equals(ind)) {
                    String propIRI = ((OWLDataProperty) ax.getProperty()).getIRI().toString();
                    assertions.add(propIRI + " -> " + ax.getObject().getLiteral());
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), individualIRI, "DataProperty", assertions, "data_assertions", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Same/Different Individuals ──

    public ServiceResult<PropertyAxiomsResult> getSameIndividuals(OntologyId ontologyId, String individualIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(individualIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Individual URI not found: " + individualIRI);
            }

            List<String> sameAs = new ArrayList<>();
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(individualIRI));

            for (OWLSameIndividualAxiom ax : ontology.getAxioms(AxiomType.SAME_INDIVIDUAL, Imports.INCLUDED)) {
                if (ax.getIndividuals().contains(ind)) {
                    ax.getIndividuals().stream().filter(i -> !i.equals(ind) && i instanceof OWLNamedIndividual)
                        .forEach(i -> sameAs.add(((OWLNamedIndividual) i).getIRI().toString()));
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), individualIRI, "Individual", sameAs, "sameAs", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    public ServiceResult<PropertyAxiomsResult> getDifferentIndividuals(OntologyId ontologyId, String individualIRI, boolean includeInferred) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

            if (!ontology.getIndividualsInSignature(Imports.INCLUDED).stream().anyMatch(i -> i.getIRI().toString().equals(individualIRI))) {
                return ServiceResult.error(ErrorCode.INDIVIDUAL_NOT_FOUND, "Individual URI not found: " + individualIRI);
            }

            List<String> differentFrom = new ArrayList<>();
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(individualIRI));

            // DifferentFrom axioms
            for (OWLDifferentIndividualsAxiom ax : ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS, Imports.INCLUDED)) {
                if (ax.getIndividuals().contains(ind)) {
                    ax.getIndividuals().stream().filter(i -> !i.equals(ind) && i instanceof OWLNamedIndividual)
                        .forEach(i -> differentFrom.add(((OWLNamedIndividual) i).getIRI().toString()));
                }
            }

            return ServiceResult.success(
                new PropertyAxiomsResult(ontologyId.id(), individualIRI, "Individual", differentFrom, "differentFrom", "explicit"),
                ResultMetadata.empty());

        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Private Helpers ──

    private OWLOntology loadOntology(OntologyId ontologyId) throws OWLOntologyCreationException {
        Path ontologyPath = Path.of(workspaceBasePath, "default", "ontologies", ontologyId.id(), "canonical", "ontology.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(ontologyPath.toFile());
    }

    private String detectProfileFromIRI(IRI iri) {
        String str = iri.toString();
        if (str.contains("OWL2EL")) return "OWL 2 EL";
        if (str.contains("OWL2DL")) return "OWL 2 DL";
        if (str.contains("OWL2QL")) return "OWL 2 QL";
        if (str.contains("OWL2RL")) return "OWL 2 RL";
        return null;
    }
}