# Pizza Reasoning Example

This example demonstrates ontology import, summary, hierarchy exploration, property inspection, and reasoner classification using the public Pizza ontology.

## What it demonstrates

owl4agents can import a real-world ontology, summarize its structure, explore class and property hierarchies, and run a reasoner to classify inferred relationships:

- **Import**: Load an OWL ontology from file into the workspace catalog
- **Summary**: Get entity counts (classes, properties, individuals)
- **Class context**: Explore subclass hierarchy, restrictions, and equivalent classes
- **Classification**: Run a reasoner and get inferred taxonomy
- **Property context**: Inspect object property domain, range, and characteristics

## Prerequisites

- Java 22+ installed
- Shadow jar built: `.\gradlew.bat :modules:ontology-cli:shadowJar`
- Node.js 18+ installed

## Step-by-step commands

Run from the repository root:

### 1. Import the Pizza ontology

```bash
node npm/bin/owl4agents.js import test/corpus/smoke/pizza.owl pizza --workspace temp/examples/pizza-reasoning
```

Expected: exit code 0, ontology ID `pizza` registered in workspace.

### 2. Get ontology summary

```bash
node npm/bin/owl4agents.js summary pizza --workspace temp/examples/pizza-reasoning
```

Expected output snippet:

```json
{
  "ontologyId": "pizza",
  "classCount": 20,
  "objectPropertyCount": 10
}
```

### 3. Explore class context (Margherita pizza)

```bash
node npm/bin/owl4agents.js class-context pizza http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita --workspace temp/examples/pizza-reasoning
```

Expected: class hierarchy showing Margherita as a subclass of NamedPizza, with restrictions on toppings.

### 4. Run reasoner classification

```bash
node npm/bin/owl4agents.js classify pizza --workspace temp/examples/pizza-reasoning
```

Expected output snippet:

```json
{
  "reasonerName": "HermiT",
  "consistent": true
}
```

### 5. Explore property context (hasTopping)

```bash
node npm/bin/owl4agents.js property-context pizza http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping --workspace temp/examples/pizza-reasoning
```

Expected: property domain, range, and characteristics.

## Fixture attribution

- `test/corpus/smoke/pizza.owl` — Protégé Pizza Ontology, Stanford University, CC BY 4.0. Used for class hierarchy, object property, and restriction demonstrations.

## Troubleshooting

- **Missing pizza.owl:** Run `.\gradlew.bat :modules:ontology-cli:shadowJar` and verify that `test/corpus/smoke/pizza.owl` exists.
- **ACCESS_VIOLATION on Windows:** Use `node npm/bin/owl4agents.js` instead of `java -jar`.