# verify_claim 系统性实体声明 bug 报告

报告日期：2026-07-22
报告人：owl4agents 实验验证团队
影响版本：owl4agents v0.8.8（部署于 http://10.67.82.218:8083/mcp）
严重程度：高（阻塞验证实验，导致大量 claim 误判为 out_of_scope）

## 1. 问题描述

ontology_verify_claim 和 ontology_verify_claims_batch 工具的 isEntityDeclared 检查存在系统性 bug。当传入一个结构化 claim 时，即使 claim 中引用的实体（class/individual/property）确实存在于本体中，isEntityDeclared 检查仍返回 false，导致 verdict 误判为 out_of_scope。

### 核心矛盾

ontology_search_entities 工具能找到实体，ontology_sparql_ask 能确认实体存在，ontology_get_class_context 能返回实体的完整上下文（包括 superclasses、disjointClasses 等），但 ontology_verify_claim 报告 "Subject or object is not declared in ontology"。

## 2. 影响范围

| 本体 | 影响程度 | 说明 |
|------|----------|------|
| pizza | 全部 112 个 claim 受影响 | 所有实体返回 out_of_scope |
| hpo | 部分实体受影响 | HP_0000002 正常返回 supported；HP_0000003 返回 out_of_scope |
| mondo | 部分实体可能受影响 | MONDO_0000004 测试正常返回 supported（需更多测试） |
| sosa | 无法测试 | 本体未加载到远程服务器 |

## 3. 复现步骤

### 环境要求

- owl4agents v0.8.8 MCP 服务（http://10.67.82.218:8083/mcp）
- 已加载 pizza 本体（ontologyId=pizza）
- 已加载 hpo 本体（ontologyId=hpo）

### 步骤 1：确认实体存在（正常）

向 MCP 发送 ontology_search_entities 请求：

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ontology_search_entities",
    "arguments": {
      "ontology_id": "pizza",
      "query": "Margherita",
      "limit": 5
    }
  },
  "id": 5
}
```

预期返回（正常）：
```json
{
  "totalResults": 1,
  "results": [{
    "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita",
    "type": "class",
    "score": 0.9,
    "label": "Margherita"
  }]
}
```

### 步骤 2：用 SPARQL 确认实体存在（正常）

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ontology_sparql_ask",
    "arguments": {
      "ontology_id": "pizza",
      "query": "ASK WHERE { <http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita> a <http://www.w3.org/2002/07/owl#Class> }"
    }
  },
  "id": 15
}
```

预期返回（正常）：
```json
{"result": true}
```

### 步骤 3：用 get_class_context 确认实体存在（正常）

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ontology_get_class_context",
    "arguments": {
      "ontology_id": "pizza",
      "entity_iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita"
    }
  },
  "id": 12
}
```

预期返回（正常，截断）：
```json
{
  "superclasses": ["http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza"],
  "disjointClasses": ["http://www.co-ode.org/ontologies/pizza/pizza.owl#American", ...]
}
```

### 步骤 4：用 verify_claim 验证（bug 复现）

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ontology_verify_claim",
    "arguments": {
      "ontology_id": "pizza",
      "claim": {
        "id": "pizza-sc-001-c1",
        "type": "subclass",
        "subject": {
          "kind": "class",
          "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita"
        },
        "predicate": "subClassOf",
        "object": {
          "kind": "class",
          "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza"
        }
      },
      "reasoner": "auto"
    }
  },
  "id": 4
}
```

实际返回（bug）：
```json
{
  "semanticVerdict": "out_of_scope",
  "claimType": "subclass",
  "unknownReason": "missing_entity",
  "unknownExplanation": "Subject or object is not declared in ontology 'pizza'. Offending subject: http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita Offending object: http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza"
}
```

预期返回（正确）：
```json
{
  "semanticVerdict": "supported",
  "claimType": "subclass",
  "evidence": [...]
}
```

### 步骤 5：用 detect_missing_entities 确认（bug 复现）

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ontology_detect_missing_entities",
    "arguments": {
      "ontology_id": "pizza",
      "claim": {
        "id": "test1",
        "type": "subclass",
        "subject": {"kind": "class", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita"},
        "predicate": "subClassOf",
        "object": {"kind": "class", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza"}
      }
    }
  },
  "id": 10
}
```

实际返回（bug）：
```json
{
  "matched": [],
  "missing": [
    {"searchTerm": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita", "matchedIRI": null, "kind": "class"},
    {"searchTerm": "http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza", "matchedIRI": null, "kind": "class"},
    {"searchTerm": "subClassOf", "matchedIRI": null, "kind": "property"}
  ]
}
```

注意：predicate 字段 "subClassOf" 也被当作实体搜索了，matchedIRI 为 null。

## 4. hpo 本体的部分复现

hpo 本体中部分实体正常，部分不正常：

| 实体 IRI | search_entities | verify_claim | 说明 |
|----------|-----------------|--------------|------|
| HP_0000002 | 找到 | supported（正常） | 实体声明检查通过 |
| HP_0000003 | 找到（label: Multicystic kidney dysplasia） | out_of_scope（bug） | 实体声明检查失败 |
| HP_0000005 | 未测试 | out_of_scope（bug） | 实体声明检查失败 |
| HP_0001507 | 找到 | supported（正常，作为 hpo-001 的 object） | 实体声明检查通过 |

## 5. 根因分析

### v0.8.3 引入的严格声明检查

根据 CHANGELOG v0.8.3（2026-07-10）的 R1 修复：

> ConsistencyAnalysisService.isEntityDeclared() previously used Imports.INCLUDED, allowing cross-ontology entities to pass the scope pre-check. Now uses Imports.EXCLUDED for signature queries and adds a getDeclarationAxioms(entity) check to verify the entity has an explicit Declaration axiom in the ontology.

### v0.8.4 引入的 EntitySignatureCache

根据 CHANGELOG v0.8.4（2026-07-11）：

> Decision 4: EntitySignatureCache — New EntitySignatureCache class provides O(1) entity signature lookups with OBO namespace checking, matching v0.8.3 isEntityDeclared() dual-check semantics.

### 可能的 bug 原因

1. EntitySignatureCache 构建不完整：如果 canonical ontology.owl 文件缺少某些实体的 Declaration axioms，EntitySignatureCache 不会包含这些实体，导致 isEntityDeclared 返回 false。

2. canonical ontology 文件问题：远程服务器加载的可能是经过预处理的 canonical ontology.owl 文件，而非完整的源本体文件。预处理可能移除了部分 Declaration axioms。

3. OBO namespace checking bug：EntitySignatureCache 的 OBO namespace checking 可能错误地过滤了某些实体。pizza 本体使用 co-ode.org namespace（非 OBO），可能导致 namespace 检查失败。

4. predicate 误解析为实体：detect_missing_entities 将 predicate 字段 "subClassOf" 当作 property 实体搜索，说明 claim 解析逻辑可能有 bug。

## 6. 修复建议

### 建议 1：修复 isEntityDeclared 检查逻辑

isEntityDeclared 应该使用 OWL API 的 signature 检查（ontology.getSignature()），而不是仅依赖 getDeclarationAxioms(entity)。一个实体如果在 ontology 的 signature 中（通过 Imports.EXCLUDED），就应该被认为是已声明的。

```java
// 建议的修复
public boolean isEntityDeclared(OWLOntology ontology, OWLEntity entity) {
    // 1. 检查 signature（Imports.EXCLUDED）
    if (ontology.containsEntityInSignature(entity, Imports.EXCLUDED)) {
        return true;
    }
    // 2. 检查 Declaration axioms（向后兼容）
    if (!ontology.getDeclarationAxioms(entity).isEmpty()) {
        return true;
    }
    // 3. 对于 OBO 本体，检查 OBO namespace
    // ...
    return false;
}
```

### 建议 2：修复 EntitySignatureCache 构建

确保 EntitySignatureCache 从 ontology.getSignature(Imports.EXCLUDED) 构建完整的实体集合，而不是仅从 Declaration axioms 构建。

### 建议 3：修复 predicate 误解析

detect_missing_entities 不应将 predicate 字段 "subClassOf" 当作实体搜索。predicate 是 claim 的结构字段，不是本体实体。

### 建议 4：添加 canonical ontology 验证

在加载 canonical ontology.owl 文件时，验证文件的 signature 是否与源本体一致。如果 canonical 文件缺少 Declaration axioms，应该回退到源本体文件。

### 建议 5：统一 verdict 词汇

verify_claims_batch 的 aggregateStatus 使用 "verified" 而 verify_claim 的 semanticVerdict 使用 "supported"。应统一使用相同的 verdict 词汇（建议用 "supported"，与论文和文档一致）。

## 7. 复现文件

以下文件包含在 zip 包的 repro_files 目录中：

| 文件 | 说明 |
|------|------|
| pizza.owl | Pizza 本体源文件（RDF/XML 格式，含 Declaration axioms） |
| pizza-112.jsonl | Pizza 验证集（112 个 claim） |
| hpo-60.jsonl | HPO 验证集（60 个 claim） |
| mondo-60.jsonl | Mondo 验证集（60 个 claim） |
| sosa-84.jsonl | SOSA 验证集（84 个 claim） |
| reproduce_commands.ps1 | PowerShell 脚本，自动执行上述复现步骤 |
| run_v088_experiment.ps1 | 完整实验运行脚本 |

## 8. 补充：MCP 上传本体能力

当前 v0.8.8 的 MCP 服务不支持通过 MCP 工具上传本体。HTTP 端点仅有：
- POST /mcp（MCP JSON-RPC）
- GET /mcp（SSE）
- GET /info
- GET /

建议添加 ontology_import 工具或 HTTP 上传端点，允许通过 MCP 上传本体文件。
