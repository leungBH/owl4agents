# owl4agents 功能全景（详细版）

> 适用版本：**v0.8.0**（2026-06-29 发布；2026-07-03 复测通过）
> 文档定位：从使用者视角**逐工具**梳理 owl4agents 的 56 个 MCP 工具与 50+ 个 CLI 命令，包括参数、响应、调用示例、典型场景。

---

## 目录

1. [项目概述](#1-项目概述)
2. [架构与模块](#2-架构与模块)
3. [MCP 工具详细文档](#3-mcp-工具详细文档) — 56 个工具分组
4. [CLI 命令详细文档](#4-cli-命令详细文档) — 50+ 个命令分组
5. [部署与集成](#5-部署与集成)
6. [推理机集成](#6-推理机集成)
7. [Claim 验证能力](#7-claim-验证能力)
8. [安全模型](#8-安全模型)
9. [测试覆盖与质量数据](#9-测试覆盖与质量数据)
10. [典型使用场景](#10-典型使用场景)
11. [已知限制](#11-已知限制)

---

## 1. 项目概述

`owl4agents` 是一个**本地优先的 OWL/RDF 本体运行时**，把成熟的语义网工具（OWL API、HermiT、ELK、Openllet、Apache Jena）封装成可被 LLM Agent 调用的 MCP 工具集合。

**目标用户**：
- 研究本体增强型 LLM Agent 的科研人员
- 给 Agent 接入领域知识图谱的工程师
- 维护本地 OWL/RDF 本体的知识工程师
- 想要可复现、可审计、可本地化的语义推理流水线的团队

**核心价值**：
- **本地化**：所有数据默认在 `~/.owl4agents/` 下，无需联网（除首次构建外）
- **可复现**：推理结果落盘到 `inferred-class-hierarchy.jsonl`、`inferred-types.jsonl` 等
- **可审计**：所有工具调用通过 `McpToolCallLogger` 留痕
- **只读安全**：MCP 服务器默认 `--readonly`，只暴露 56 个只读工具，写操作只能走 CLI
- **协议合规**：MCP Streamable HTTP（`2025-03-26` spec），可对接 Trae IDE、Claude Desktop、Cursor

---

## 2. 架构与模块

```
+------------------------------------------------------+
|                    npm 启动层                         |
|   tools/npm/bin/owl4agents.js（仅作启动器）           |
+-------------------------+----------------------------+
                          |  fork+exec
                          v
+------------------------------------------------------+
|                Java 22 运行时（owl4agents.jar）        |
|                                                      |
|  +-----------+   +-----------+   +-----------------+  |
|  | CLI 层    |   | MCP/HTTP  |   | 内部 API         |  |
|  | Picocli   |   | JSON-RPC  |   | OntologyService  |  |
|  +-----+-----+   +-----+-----+   +---------+-------+  |
|        |               |                  |          |
|        +-------+-------+------------------+          |
|                v                                     |
|  +----------------------------------------------+    |
|  |  服务层（11 个 module）                        |    |
|  |  Core / Storage / Owlapi / Query / Reasoner / |    |
|  |  Retrieval / Validation / Benchmark / Cli /   |    |
|  |  Mcp / Distribution                           |    |
|  +----------------------------------------------+    |
+------------------------------------------------------+
                          |
                          v
+------------------------------------------------------+
|          基础库                                        |
|   OWL API  ·  HermiT  ·  ELK  ·  Openllet  ·  Jena ARQ |
+------------------------------------------------------+
                          |
                          v
                ~/.owl4agents/workspaces/
                ├── catalog.json          # 本体注册表
                ├── workspace.yaml        # 工作区配置
                └── <workspace>/
                    └── ontologies/
                        └── <ontology_id>/
                            ├── source/             # 原始 OWL 文件
                            ├── inferred/           # 推理产物（class 层级、type 断言）
                            └── reasoning-report.json
```

**11 个模块清单**：

| 模块 | 职责 | 关键类 |
|---|---|---|
| **ontology-core** | 共享数据模型（`Claim`、`EntityId`、`ServiceResult`、所有 `Result` 记录类）+ JSON 工具 | `OntologyService`、`ClaimValidator`、`ErrorCode`、`ServiceError` |
| **ontology-storage** | Workspace / catalog 管理、Home 路径解析 | `WorkspaceInitializer`、`CatalogStore`、`HomeDirectoryResolver`、`OntologyImporter` |
| **ontology-owlapi** | OWL API 封装、OWL/RDF 加载、归一化、profile 检测 | `OwlapiOntologyLoader`、`ProfileDetector`、`SemanticDeepeningService` |
| **ontology-query** | Apache Jena ARQ 集成、SPARQL 安全、检索 | `SparqlExecutionService`、`EntitySearchService`、`QaContextService` |
| **ontology-reasoner** | HermiT / ELK / Openllet 适配、推理任务路由、推理产物落盘 | `ReasonerServiceImpl`、`ReasonerLifecycleManager`、`HermitAdapter`、`ElkAdapter`、`OpenlletAdapter` |
| **ontology-retrieval** | 实体上下文拼装、图谱邻域、QA 上下文 | `EntityContextService`、`GraphNeighborhoodService`、`QaContextService` |
| **ontology-validation** | Claim 验证、字面量校验、class/individual/关系检查 | `ClaimVerificationService`、`LiteralValidator`、`EntailmentChecker`、`ConsistencyAnalysisService`、`EvidenceGroundingService`、`ClaimWorkflowService`、`EvidenceContextBuilder`、`ClaimBatchValidator` |
| **ontology-benchmark** | 基准实验、QA 评估、批量上下文 | `BenchmarkService`、`QaEvaluationService`、`ContextBatchService`、`ExperimentConfigParser`、`BenchmarkQuestionSetValidator`、`BenchmarkReportGenerator` |
| **ontology-cli** | Picocli 命令适配（50+ 个子命令） | `Owl4AgentsCli`、`McpCommand`、`ImportCommand`、`VerifyClaimCommand` 等 |
| **ontology-mcp** | MCP 服务器（stdio / HTTP / SSE）、工具注册、调用日志 | `HttpMcpServer`、`McpServerAdapter`、`McpToolRegistry`、`McpSessionManager` |
| **ontology-distribution** | 跨版本端到端验收（V01..V07 acceptance suite） | `V03AcceptanceSuite` 等 |

---

## 3. MCP 工具详细文档

### 3.0 MCP 公共规范

**协议**：
- JSON-RPC 2.0 over HTTP（`POST /mcp`）+ 可选 SSE 长连接（`GET /mcp`，v0.8+）
- 内容协商：`Accept: application/json`（默认）/ `Accept: text/event-stream`（v0.8+）
- 会话标识：`Mcp-Session-Id: <uuid-v4>`（v0.8+ 必需；`initialize` 时由服务端返回）
- 调用方法：`tools/list`（列工具）、`tools/call`（调工具，参数 `name` + `arguments`）

**公共参数**：
- `ontology_id`（string，**必须**先 `import` 过该本体）：注册的 ontology id
- `reasoner`（string，可选，默认 `"auto"`）：`auto` / `hermit` / `elk` / `openllet`
- `include_inferred`（string，可选，默认 `"false"`）：是否包含推理事实；同时接受 JSON boolean `true`/`false`（D-004 修复）
- `workspace`（string，可选，默认 `"default"`）：workspace 名

**响应统一包装**（content[0].text 里是 JSON）：
```json
{ "status": "success", "data": { ... } }
// 或
{ "status": "error", "error": { "code": "ERROR_CODE", "message": "..." } }
```

**错误码**（节选自 `ErrorCode.java`）：
- `ONTOLOGY_NOT_FOUND`、`ONTOLOGY_NOT_READY`、`ENTITY_NOT_FOUND`
- `INVALID_CLAIM_SCHEMA`、`CLAIM_VERIFICATION_FAILED`
- `EVIDENCE_NOT_AVAILABLE`、`REASONER_NOT_FOUND`、`REASONER_INFEASIBLE`
- `SPARQL_VALIDATION_FAILED`、`SPARQL_SAFETY_VIOLATION`
- `READONLY_VIOLATION`、`BUDGET_EXCEEDED`、`DATATYPE_NO_FACETS`、`ONTOLOGY_CONSISTENT`

---

### 3.1 元数据 / 浏览（7 个工具）

#### `ontology_list`
- **描述**：列出已导入的所有本体
- **参数**：无
- **响应**：
  ```json
  {
    "ontologies": [
      {"ontologyId":"pizza", "displayName":"pizza.owl", "importTimestamp":"2026-07-03T..."},
      ...
    ]
  }
  ```

#### `ontology_summary`
- **描述**：返回 ontology IRI、profile、imports、entity counts
- **参数**：`ontology_id`
- **响应 keys**：`ontologyId, iri, version, profile, imports, entityCounts{classes,objectProperties,dataProperties,individuals,axioms}, format`

#### `ontology_get_metadata`
- **描述**：详细元数据（版本、创建者、注释、source path、canonical path、import timestamp）
- **参数**：`ontology_id`
- **响应 keys**：`ontologyId, iri, versionIri, sourcePath, canonicalPath, importTimestamp, lastModified`

#### `ontology_get_profile`
- **描述**：OWL profile（DL/EL/QL/RL）+ 违规项
- **参数**：`ontology_id`
- **响应**：
  ```json
  {
    "profile": "OWL_2_EL",
    "violations": [],
    "checks": {"inOWL2DL": false, "inOWL2EL": true, "inOWL2QL": true, "inOWL2RL": true}
  }
  ```

#### `ontology_list_graphs`
- **描述**：列出可查询的图谱作用域
- **参数**：`ontology_id`
- **响应**：
  ```json
  {"scopes": ["explicit", "inferred", "union"]}
  ```

#### `ontology_get_imports`
- **描述**：返回 import 闭包（直接 + 间接 imports）
- **参数**：`ontology_id`
- **响应**：
  ```json
  {"imports": [
    {"iri":"http://.../bfo.owl", "direct":true, "loaded":true},
    {"iri":"http://.../ro.owl", "direct":false, "loaded":true}
  ]}
  ```

#### `ontology_get_scope`
- **描述**：ontology 域覆盖、已知缺口、profile 限制、不支持的特性类型
- **参数**：`ontology_id`
- **响应 keys**：`domainCoverage, knownGaps[], profileLimitations, unsupportedFeatureTypes[]`

---

### 3.2 实体搜索与上下文（7 个工具）

#### `ontology_search_entities`
- **描述**：按 label / IRI / alias 搜索类、属性、个体
- **参数**：
  - `ontology_id`（必填）
  - `query`（必填，搜索词）
  - `type_filter`（可选，逗号分隔：`class,object_property,data_property,individual`）
  - `limit`（可选 int，默认 20）
- **响应**：
  ```json
  {
    "matches": [
      {"iri":"http://...#Margherita", "label":"Margherita", "type":"individual", "score":0.92, "snippet":"..."},
      ...
    ],
    "total": 12
  }
  ```

#### `ontology_get_entity_context`
- **描述**：通用实体上下文（labels、comments、所属类/属性/个体上下文、相关事实）
- **参数**：`ontology_id`, `entity_iri`
- **响应 keys**：`iri, label, comment, type, classContext?, propertyContext?, individualContext?, relatedFacts[]`

#### `ontology_get_class_context`
- **描述**：类的层级、equivalent、super/sub、disjoint、restrictions
- **参数**：`ontology_id`, `entity_iri`
- **响应**：
  ```json
  {
    "iri": "...", "label": "Pizza", "comment": "...",
    "superClasses": ["...#Food"],
    "equivalentClasses": [],
    "disjointClasses": ["...#Drink"],
    "restrictions": [
      {"property":"hasTopping", "type":"someValuesFrom", "value":"...#Topping", "cardinality":null}
    ]
  }
  ```

#### `ontology_get_object_property_context`
- **描述**：对象属性的 domain、range、hierarchy、inverse、characteristics
- **参数**：`ontology_id`, `entity_iri`
- **响应 keys**：`iri, label, comment, domain[], range[], superProperties[], subProperties[], inverseProperties[], characteristics{functional,transitive,symmetric,reflexive,irreflexive,asymmetric}`

#### `ontology_get_data_property_context`
- **描述**：数据属性的 domain、range、datatype、hierarchy
- **参数**：`ontology_id`, `entity_iri`
- **响应 keys**：`iri, label, comment, domain[], range{iri, label}, superProperties[], subProperties[]`

#### `ontology_get_individual_context`
- **描述**：个体的类型 + 对象属性断言 + 数据属性断言
- **参数**：`ontology_id`, `entity_iri`
- **响应**：
  ```json
  {
    "iri":"...#m1", "label":"Margherita1",
    "explicitTypes":[{"iri":"...#Margherita", "label":"Margherita"}],
    "objectPropertyAssertions":[{"property":"...#hasTopping", "target":"...#Mozzarella"}],
    "dataPropertyAssertions":[{"property":"...#hasPrice", "value":"8.5", "datatype":"...#decimal"}]
  }
  ```

#### `ontology_get_graph_neighborhood`
- **描述**：实体周围的图谱邻域
- **参数**：`ontology_id`, `entity_iri`, `depth`（可选 int，默认 1）
- **响应**：
  ```json
  {
    "center": "...#m1", "depth": 1,
    "nodes": [...], "edges": [...]
  }
  ```

---

### 3.3 SPARQL（5 个工具）

**公共参数**：`ontology_id`（必需；`validate_sparql` 除外），`query`（SPARQL 文本）

**安全约束**：所有 4 个执行工具经过 `SparqlSafetyGuard` 过滤，禁止以下关键字：`INSERT DATA`、`DELETE DATA`、`DELETE WHERE`、`LOAD`、`CLEAR`、`DROP`、`COPY`、`MOVE`、`ADD`、`CREATE`。

#### `ontology_validate_sparql`
- **描述**：解析 + 校验 SPARQL 查询（不执行）
- **参数**：`query`（必填），`ontology_id`（可选，校验时也会参考其命名图）
- **响应**：
  ```json
  {"valid": true, "queryType": "SELECT", "variables": ["s","p","o"]}
  // 失败时
  {"valid": false, "error": "Parse error at line 1: ..."}
  ```

#### `ontology_sparql_select`
- **描述**：执行只读 `SELECT` 查询
- **参数**：`ontology_id`, `query`, `graph_scope`（可选：`explicit`（默认）/ `inferred` / `union`）
- **响应**：
  ```json
  {
    "head": {"vars":["s","o"]},
    "results": {
      "bindings": [
        {"s":{"type":"uri","value":"...#m1"}, "o":{"type":"uri","value":"...#Pizza"}}
      ]
    }
  }
  ```

#### `ontology_sparql_ask`
- **描述**：执行 `ASK` 查询，返回布尔
- **参数**：`ontology_id`, `query`
- **响应**：`{"boolean": true}`

#### `ontology_sparql_construct`
- **描述**：执行 `CONSTRUCT` 查询，返回 RDF 三元组
- **参数**：`ontology_id`, `query`
- **响应**：
  ```json
  {"triples": [
    {"s":"...#m1", "p":"...#rdfType", "o":"...#Margherita"},
    ...
  ]}
  ```

#### `ontology_sparql_describe`
- **描述**：执行 `DESCRIBE` 查询，返回资源描述
- **参数**：`ontology_id`, `query`
- **响应**：`{"triples": [...]}`（同 CONSTRUCT）

---

### 3.4 QA 上下文（1 个工具）

#### `ontology_get_qa_context`
- **描述**：为 LLM 自然语言问题拼装本体上下文
- **参数**：
  - `ontology_id`（必填）
  - `question`（必填，自然语言问题）
  - `max_entities`（可选 int，默认 10）
  - `max_depth`（可选 int，默认 3）
  - `include_inferred`（可选 bool，默认 false）
- **响应**：
  ```json
  {
    "question": "Which toppings are related to pizza?",
    "matchedEntities": [
      {"iri":"...#Pizza", "label":"Pizza", "type":"class", "relevance":0.95}
    ],
    "contextText": "...compressed textual context for LLM prompt...",
    "tokens": 312,
    "warnings": []
  }
  ```

---

### 3.5 推理（12 个工具）

#### `ontology_list_reasoners`
- **描述**：列出已注册推理机 + 各自能力
- **参数**：无
- **响应**：
  ```json
  {
    "reasoners": [
      {"name":"HermiT", "profile":"OWL_2_DL", "capabilities":["consistency","classification","realization"]},
      {"name":"ELK", "profile":"OWL_2_EL", "capabilities":["consistency","classification"]},
      {"name":"Openllet", "profile":"OWL_2_DL", "capabilities":["consistency","classification","realization","explanation"]}
    ],
    "defaultReasoner": "auto"
  }
  ```

#### `ontology_run_reasoner`
- **描述**：跑选定推理任务（classify / realize / consistency / 全部）
- **参数**：`ontology_id`, `reasoner`（默认 `auto`），`tasks`（可选，逗号分隔：`classify,realize,consistency`，默认全部）
- **响应**：
  ```json
  {
    "reasoner": "HermiT",
    "tasksRun": ["consistency","classification"],
    "consistency": {"consistent": true, "timeMs": 234},
    "classification": {"timeMs": 567, "inferredHierarchyEntries": 42},
    "reportPath": "~/.owl4agents/.../reasoning-report.json"
  }
  ```

#### `ontology_check_consistency`
- **描述**：一致性检查
- **参数**：`ontology_id`, `reasoner`（默认 `auto`）
- **响应**：
  ```json
  {"consistent": true, "reasoner": "HermiT", "timeMs": 234}
  // 失败时
  {"consistent": false, "reasoner": "HermiT", "timeMs": 456, "unsatClassCount": 3}
  ```

#### `ontology_explain_inconsistency`
- **描述**：解释本体为何不一致（Openllet 专属）
- **参数**：`ontology_id`, `reasoner`（默认 `openllet`）
- **响应**：
  ```json
  {
    "inconsistent": true,
    "explanations": [
      {
        "axiomSet": ["A SubClassOf B", "A SubClassOf ComplementOf B"],
        "unsatClass": "...#A",
        "satisfiability": "unsatisfiable"
      }
    ]
  }
  // 当本体一致时
  {"inconsistent": false, "message": "Ontology is consistent; nothing to explain"}
  ```

#### `ontology_get_unsat_classes`
- **描述**：列出所有不可满足类的 URI
- **参数**：`ontology_id`
- **响应**：`{"unsatisfiableClasses": ["...#A", "...#B"], "count": 2}`

#### `ontology_explain_unsat_class`
- **描述**：解释某个类为何不可满足（Openllet 专属）
- **参数**：`ontology_id`, `class_uri`（必填），`reasoner`（默认 `openllet`）
- **响应**：
  ```json
  {
    "classIri": "...#A",
    "satisfiable": false,
    "explanations": [
      {
        "axiomSet": ["A SubClassOf ComplementOf A"],
        "explanationText": "Axioms force A to be both a subclass of itself and its complement"
      }
    ]
  }
  ```

#### `ontology_classify`
- **描述**：推理类层级（落盘 `inferred-class-hierarchy.jsonl`）
- **参数**：`ontology_id`, `reasoner`（默认 `auto`）
- **响应**：
  ```json
  {
    "reasoner": "HermiT",
    "timeMs": 1234,
    "inferredHierarchyEntries": 42,
    "outputPath": ".../inferred/inferred-class-hierarchy.jsonl"
  }
  ```

#### `ontology_realize_instances`
- **描述**：推理个体类型（落盘 `inferred-types.jsonl`）
- **参数**：`ontology_id`, `reasoner`（默认 `auto`）
- **响应**：
  ```json
  {
    "reasoner": "HermiT",
    "timeMs": 2345,
    "inferredTypesCount": 17,
    "outputPath": ".../inferred/inferred-types.jsonl"
  }
  ```

#### `ontology_get_inferred_facts`
- **描述**：返回某实体或图谱范围的推理事实
- **参数**：`ontology_id`, `entity_iri`（可选，不填则全图谱）
- **响应**：
  ```json
  {
    "entityIri": "...#m1",
    "inferredFacts": [
      {"axiom":"...#m1 rdf:type ...#Pizza", "derivedFrom":"rule:scm-cls", "confidence":1.0},
      {"axiom":"...#m1 rdf:type ...#Food", "derivedFrom":"hierarchy:Pizza⊑Food", "confidence":1.0}
    ]
  }
  ```

#### `ontology_get_reasoning_report`
- **描述**：读 `reasoning-report.json`
- **参数**：`ontology_id`
- **响应 keys**：`ontologyId, reasonerName, generatedAt, consistency, classification, realization, unsatisfiableClasses, axiomsCount, individualsCount`

#### `ontology_check_entailment`
- **描述**：检查某结构化公理是否被本体蕴含
- **参数**：
  - `ontology_id`
  - `axiom_type`（必填，如 `"SubClassOf"`、`"ClassAssertion"`、`"ObjectPropertyAssertion"`）
  - `axiom_args`（可选，dict，公理参数）
  - `reasoner`（默认 `auto`）
- **响应**：
  ```json
  {"entailed": true, "axiom": "...", "reasoner": "HermiT", "timeMs": 56}
  ```

#### `ontology_check_class_compatibility`
- **描述**：两个类是否相容 / 不相交 / 合起来不可满足
- **参数**：`ontology_id`, `class1_uri`, `class2_uri`
- **响应**：
  ```json
  {
    "compatible": false,
    "disjoint": true,
    "unsatisfiable": true,
    "explanation": "ClassA and ClassB are disjoint axioms; their union is unsatisfiable"
  }
  ```

#### `ontology_check_individual_membership`
- **描述**：个体是否属于某类（显式 / 推理）
- **参数**：`ontology_id`, `individual_uri`, `class_uri`, `reasoner`（默认 `auto`）
- **响应**：`{"member": true, "explicit": false, "inferred": true, "reasoner": "HermiT"}`

#### `ontology_check_relation_assertion`
- **描述**：对象属性断言是否成立
- **参数**：`ontology_id`, `source_individual_uri`, `property_uri`, `target_individual_uri`, `reasoner`（默认 `auto`）
- **响应**：`{"asserted": true, "explicit": true, "inferred": false, "reasoner": "HermiT"}`

#### `ontology_get_class_restrictions`
- **描述**：类的所有 restrictions（someValuesFrom / allValuesFrom / cardinality / hasValue）
- **参数**：`ontology_id`, `class_uri`, `include_inferred`（默认 false）
- **响应**：
  ```json
  {
    "classIri": "...#Pizza",
    "restrictions": [
      {"property":"...#hasTopping", "type":"someValuesFrom", "value":"...#Topping", "cardinality":null},
      {"property":"...#hasBase", "type":"allValuesFrom", "value":"...#Bread", "cardinality":null}
    ]
  }
  ```

#### `ontology_get_property_characteristics`
- **描述**：属性的特征（functional / transitive / symmetric / ...）
- **参数**：`ontology_id`, `property_uri`, `include_inferred`（默认 false）
- **响应**：
  ```json
  {
    "propertyIri": "...#hasParent",
    "characteristics": {"functional": false, "transitive": false, "symmetric": false, "asymmetric": true, "reflexive": false, "irreflexive": false}
  }
  ```

#### `ontology_get_equivalent_properties`
- **描述**：等价属性公理
- **参数**：`ontology_id`, `property_uri`, `include_inferred`（默认 false）
- **响应**：`{"propertyIri":"...#cost", "equivalentProperties":["...#price"]}`

#### `ontology_get_disjoint_properties`
- **描述**：不相交属性公理
- **参数**：`ontology_id`, `property_uri`, `include_inferred`（默认 false）
- **响应**：`{"propertyIri":"...#hasParent", "disjointProperties":["...#hasChild"]}`

#### `ontology_get_datatype_constraints`
- **描述**：datatype facet 约束（min / max / pattern / enumeration / length）
- **参数**：`ontology_id`, `datatype_uri`
- **响应**：
  ```json
  {
    "datatypeIri": "...#myInteger",
    "facets": [{"name":"minInclusive", "value":"0"}, {"name":"maxInclusive", "value":"100"}]
  }
  // 当 datatype 无 facet 时
  {"datatypeIri":"...#string", "facets": [], "message": "No facet constraints defined"}
  ```

#### `ontology_validate_literal`
- **描述**：字面量是否满足 datatype 约束
- **参数**：`ontology_id`, `literal_value`, `datatype_uri`, `property_uri`（可选）
- **响应**：
  ```json
  {"valid": true, "normalized": "50", "appliedFacets":["minInclusive","maxInclusive"]}
  // 失败时
  {"valid": false, "violations":[{"facet":"maxInclusive", "expected":"100", "actual":"150"}]}
  ```

#### `ontology_find_relations_between_entities`
- **描述**：找两实体间的对象属性关系
- **参数**：`ontology_id`, `source_entity_uri`, `target_entity_uri`（可选；不填则找所有出边）, `include_inferred`（默认 false）
- **响应**：
  ```json
  {
    "source":"...#m1", "target":"...#Mozzarella",
    "relations":[{"property":"...#hasTopping", "inferred":false}],
    "paths":[["...#m1","...#hasTopping","...#Mozzarella"]]
  }
  // 源不存在时
  {"error":"ENTITY_NOT_FOUND", "message":"Source entity ...#X not in ontology"}
  ```

#### `ontology_get_object_property_assertions`
- **描述**：个体的所有对象属性断言
- **参数**：`ontology_id`, `individual_uri`, `include_inferred`（默认 false）
- **响应**：
  ```json
  {
    "individualIri":"...#m1",
    "assertions":[{"property":"...#hasTopping", "target":"...#Mozzarella", "inferred":false}]
  }
  ```

#### `ontology_get_data_property_assertions`
- **描述**：个体的所有数据属性断言
- **参数**：`ontology_id`, `individual_uri`, `include_inferred`（默认 false）
- **响应**：
  ```json
  {
    "individualIri":"...#m1",
    "assertions":[{"property":"...#hasPrice", "value":"8.5", "datatype":"...#decimal", "inferred":false}]
  }
  ```

#### `ontology_get_same_individuals`
- **描述**：SameAs 链
- **参数**：`ontology_id`, `individual_uri`, `include_inferred`（默认 false）
- **响应**：`{"individualIri":"...#a", "sameAs":["...#a'","...#a''"]}`

#### `ontology_get_different_individuals`
- **描述**：DifferentFrom 链
- **参数**：`ontology_id`, `individual_uri`, `include_inferred`（默认 false）
- **响应**：`{"individualIri":"...#a", "differentFrom":["...#b","...#c"]}`

---

### 3.6 Claim 验证与证据（5 个工具，v0.3+）

**Claim JSON schema**（v0.5+）：
```json
{
  "claimId": "<唯一 id，必填>",
  "type": "individual_membership" | "class_membership" | "object_property_assertion" | "data_property_assertion" | "class_subsumption" | "...",
  "subject":   {"kind":"individual"|"class", "iri":"<完整 IRI>"},
  "predicate": {"kind":"object_property"|"data_property", "iri":"<完整 IRI>"},
  "object":    {"kind":"class"|"individual"|"literal", "iri":"..."|"value":<literal>, "datatype":"..."}
}
```

> 修复点（D-008）：顶层 `ontology_id` 权威，claim 内嵌 `ontologyId` 会被覆盖。

#### `ontology_verify_claim`
- **描述**：验证结构化 claim，返回 verdict + 证据
- **参数**：`ontology_id`, `claim`（必填，对象）, `reasoner`（默认 `auto`）
- **响应**：
  ```json
  {
    "claimId": "c1",
    "ontologyId": "v0.3-claim-verification",
    "claimType": "class_membership",
    "verdict": "supported",
    "truncated": false,
    "totalEvidenceAvailable": 2,
    "reasonerName": "HermiT",
    "evidence": [
      {"evidenceId":"e1","role":"assertion","kind":"inferred","value":"...#Dog rdf:type ...#Animal","source":"reasoning","confidence":1.0}
    ]
  }
  ```

#### `ontology_get_evidence_path`
- **描述**：拼装证据路径（推理事实 + reasoning report）
- **参数**：`ontology_id`, `claim`（必填，对象）
- **响应**：
  ```json
  {
    "claimId": "c1", "verdict": "supported",
    "reasoningReport": { "...": "..." },
    "inferredFacts": [
      {"entity":"...#Dog","property":"rdf:type","value":"...#Animal","derivedFrom":"hierarchy:Dog⊑Mammal⊑Animal"}
    ],
    "explanations": ["Dog is a Mammal which is a Animal (transitive subclass chain)"]
  }
  ```

#### `ontology_find_counterexamples`
- **描述**：找反例（仅 contradicted verdict 可用；其它 verdict 返回 `EVIDENCE_NOT_AVAILABLE`）
- **参数**：`ontology_id`, `claim`（必填，对象）
- **响应**：
  ```json
  {
    "claimId": "c1",
    "verdict": "contradicted",
    "counterexamples": [
      {"individual":"...#a","propertyAssertion":"...","explanation":"a is explicitly typed as NOT X"}
    ]
  }
  // verdict = supported 时
  {"error":"EVIDENCE_NOT_AVAILABLE", "message":"Claim is supported; no counterexamples to find"}
  ```

#### `ontology_explain_unknown`
- **描述**：解释 unknown verdict 的原因 + 建议动作
- **参数**：`ontology_id`, `claim`（必填，对象）
- **响应**：
  ```json
  {
    "claimId":"c1","verdict":"unknown",
    "reason":"INSUFFICIENT_AXIOMS",
    "category":"推理机无法判定",
    "suggestedAction":"Add an axiom linking subject and object, or provide explicit class assertion"
  }
  ```

#### `ontology_detect_missing_entities`
- **描述**：检测 matched / ambiguous / missing / out-of-scope 实体
- **参数**：
  - `ontology_id`（必填）
  - `claim`（对象，必填，**或**）
  - `terms`（对象，备用：JSON 数组，每个元素是 entity IRI）
- **响应**：
  ```json
  {
    "totalChecked": 3,
    "matched": [
      {"term":"Dog","resolvedIri":"...#Dog","status":"matched"}
    ],
    "ambiguous": [],
    "missing": [
      {"term":"Cattus","status":"missing","reason":"No class/individual with this label/IRI in ontology"}
    ],
    "outOfScope": [
      {"term":"Gallifreyan","status":"out_of_scope","reason":"Term is not in this ontology's vocabulary"}
    ]
  }
  ```

---

### 3.7 批量 claim 工作流（3 个工具，v0.5+）

**Claims batch schema**：
```json
{
  "answerId": "<answer id>",
  "claims": [
    {
      "id": "c1",
      "type": "individual_membership",
      "subject": {"kind":"individual","iri":"..."},
      "object": {"kind":"class","iri":"..."},
      "required": true
    }
  ]
}
```

#### `ontology_verify_claims_batch`
- **描述**：批量验证 answer claims，返回每条 verdict + 聚合状态
- **参数**：
  - `ontology_id`（必填）
  - `claims`（对象，claims batch JSON）
  - `options`（可选对象，字段：`reasoner`, `requireReasoning`, `maxEvidencePerClaim`, `maxContextTokens`）
- **响应**：
  ```json
  {
    "answerId": "ans-001",
    "aggregateStatus": "PARTIALLY_VERIFIED",
    "claims": [
      {"id":"c1","verdict":"supported","evidenceCount":2},
      {"id":"c2","verdict":"contradicted","evidenceCount":1},
      {"id":"c3","verdict":"unknown","evidenceCount":0}
    ],
    "stats": {"total":3, "supported":1, "contradicted":1, "unknown":1, "outOfScope":0, "pending":0}
  }
  ```

#### `ontology_build_evidence_context`
- **描述**：为 LLM Agent 拼装紧凑证据上下文
- **参数**：
  - `report`（string，**与 ontology_id+claims 二选一**）：answer verification report JSON 字符串或路径
  - `ontology_id` + `claims`（当不用 report 时必填）
  - `max_context_tokens`（int，默认 0 = 不截断）
  - `format`（`compact`（默认） / `jsonl`（流式 JSONL + 截断元数据））
- **响应**：
  ```json
  // compact
  {
    "contextText": "...compressed evidence text...",
    "tokens": 312,
    "budgetExceeded": false
  }
  // jsonl
  {
    "lines": [
      {"claimId":"c1","verdict":"supported","evidence":"..."},
      ...
    ],
    "budgetCharsUsed": 1240,
    "totalAvailableEvidenceChars": 5600,
    "truncated": true
  }
  ```

#### `ontology_review_answer_claims`
- **描述**：用策略 review 答案 claim
- **参数**：
  - `ontology_id`（必填）
  - `claims`（对象，claims batch JSON）
  - `max_context_tokens`（int，默认 0）
  - `policy`（`strict`（默认） / `conservative` / `report-only`）
- **策略含义**：
  - `strict`：contradicted / unknown 必须有证据；只接受 supported
  - `conservative`：contradicted 拒绝；unknown 警告；supported 接受
  - `report-only`：仅生成报告，不做处置建议
- **响应**：
  ```json
  {
    "answerId": "ans-001",
    "policy": "strict",
    "verdict": "REJECTED",
    "report": {"...same as verify_claims_batch..."},
    "guidance": [
      {"claimId":"c1","action":"ACCEPT","reason":"supported with 2 evidence items"},
      {"claimId":"c2","action":"REJECT","reason":"contradicted, must be removed"},
      {"claimId":"c3","action":"REVIEW","reason":"unknown, needs additional evidence"}
    ]
  }
  ```

---

### 3.8 评估与基准（3 个工具，v0.6+）

#### `ontology_benchmark_run`
- **描述**：跑实验配置 → JSONL 结果
- **参数**：`config_yaml`（string，YAML 内容或文件路径）
- **YAML 实验配置 schema**（必需字段）：
  ```yaml
  name: <实验名>
  description: <描述>
  ontologyIds: [<ontology_id>]
  questionSetPath: <jsonl 文件绝对路径>     # 必填，不能用内联 questions:
  outputPath: <jsonl 输出绝对路径>
  reasoners: [<hermit|openllet|elk|auto>]
  # repeatCount: 会被拒绝
  ```
- **响应**：
  ```json
  {
    "experimentName": "v3-smoke",
    "totalQuestions": 10,
    "completed": 10,
    "outputPath": "d:/owl4agents/data/bench.jsonl",
    "durationMs": 12345
  }
  ```
- **JSONL 每行 schema**（结果）：
  ```json
  {"questionId":"q1","claimId":"c1","verdict":"supported","expectedVerdict":"supported","correct":true,"durationMs":234,"reasoner":"hermit"}
  ```

#### `ontology_eval_qa`
- **描述**：评估 JSONL 结果，计算 accuracy / false support rate / unresolved rate / coverage / 4×4 confusion matrix
- **参数**：`results_path`（JSONL 文件绝对路径）
- **响应**：
  ```json
  {
    "total": 100,
    "accuracy": 0.78,
    "falseSupportRate": 0.05,
    "unresolvedRate": 0.12,
    "verificationCoverage": 0.83,
    "confusionMatrix": {
      "supported":   {"supported":62, "contradicted":1, "unknown":3, "outOfScope":0},
      "contradicted":{"supported":0,  "contradicted":12,"unknown":0, "outOfScope":0},
      "unknown":     {"supported":5,  "contradicted":0, "unknown":10,"outOfScope":0},
      "outOfScope":  {"supported":0,  "contradicted":0, "unknown":0, "outOfScope":7}
    },
    "edgeCasesSkipped": 5,
    "pendingReview": 3
  }
  ```

#### `ontology_context_batch`
- **描述**：为 question set 批量拼装证据（含 `budgetCharsUsed` 元数据）
- **参数**：
  - `question_set_path`（JSONL，每行 `{questionId, question, ontologyId?}`）
  - `ontology_id`（当 question 不自带时使用）
  - `max_context_tokens`（int，默认 0）
- **响应**：
  ```json
  {
    "totalQuestions": 50,
    "outputPath": "d:/owl4agents/data/context-batch.jsonl",
    "totalBudgetChars": 25000,
    "truncatedCount": 8
  }
  ```

---

## 4. CLI 命令详细文档

### 4.0 CLI 公共规范

**入口**：
- `node tools/npm/bin/owl4agents.js <subcommand> [args]`（推荐，跨平台）
- `java -jar build/modules/ontology-cli/libs/owl4agents.jar <subcommand> [args]`（Linux/macOS）
- `tools/bin/owl4agents-mcp.cmd <subcommand> [args]`（Windows classpath 模式，绕开 ACCESS_VIOLATION）
- `.\gradlew.bat run --args="<subcommand> [args]"`（开发期）

**全局选项**（在根 `owl4agents` 后）：
- `--workspace <name>`：workspace 名（默认 `default`）
- `--home <path>`：owl4agents home 目录覆盖

**大部分子命令通用选项**：
- `--workspace <name>`：workspace 名（默认 `default`）
- `--json`：以 JSON 格式输出（机器可读）

**退出码**：
- `0`：成功
- `1`：业务错误（参数错、ontology 不存在等）
- `2`：环境错误（Java 版本错、jar 找不到等）

---

### 4.1 工作区管理（3 个命令）

#### `init`
- **功能**：初始化默认 workspace
- **参数**：无（仅 `--workspace`）
- **使用**：`node owl4agents.js init`

#### `setup`
- **功能**：校验环境（Java/Gradle/workspace/jar）/ 初始化工作区
- **选项**：
  - `--check`：只检查环境，不修改
  - `--dry-run`：报告计划动作，不执行
  - `--init`：初始化 workspace + 导入 onboarding fixtures（幂等）
  - `--json`：JSON 输出
- **使用**：
  - `setup --check` — 校验环境
  - `setup --init` — 初始化
  - `setup --check --dry-run` — 看计划动作

#### `smoke`
- **功能**：跑完整 onboarding smoke（import pizza + v0.3 fixture → list → summary → reasoner list → classify → verify claim）
- **选项**：`--workspace`、可选 `--home`
- **使用**：`node owl4agents.js smoke`（幂等）

---

### 4.2 本体生命周期（5 个命令）

#### `import`
- **功能**：把 OWL/RDF 文件导入 workspace
- **参数**：
  - `<owl-file>`（位置 0）：OWL/RDF 文件绝对路径
  - `<ontology_id>`（位置 1）：注册名（短 ID）
- **选项**：`--workspace`
- **使用**：`node owl4agents.js import test/corpus/smoke/pizza.owl pizza`

#### `list`
- **功能**：列出已导入的所有本体
- **参数**：无
- **选项**：`--workspace`、`--json`

#### `summary`
- **功能**：返回 ontology 元数据
- **参数**：`<ontology_id>`（位置 0）
- **选项**：`--workspace`、`--json`

#### `search`
- **功能**：按 label/IRI/alias 搜索
- **参数**：
  - `<ontology_id>`（位置 0）
  - `<query>`（位置 1）：搜索词

#### `entity`
- **功能**：按 IRI 获取实体上下文
- **参数**：
  - `<ontology_id>`（位置 0）
  - `<iri>`（位置 1）：实体完整 IRI

---

### 4.3 推理（11 个命令）

#### `list-reasoners`
- **功能**：列出已注册推理机 + 能力

#### `consistency`
- **功能**：一致性检查
- **参数**：`<ontology_id>`
- **选项**：`--reasoner <hermit|elk|openllet|auto>`（默认 auto）

#### `classify`
- **功能**：推理类层级（落盘）
- **参数**：`<ontology_id>`
- **选项**：`--reasoner`

#### `realize`
- **功能**：推理个体类型（落盘）
- **参数**：`<ontology_id>`
- **选项**：`--reasoner`

#### `reason`
- **功能**：复合入口：跑 consistency + classify + realize
- **参数**：`<ontology_id>`
- **选项**：`--reasoner`

#### `report`
- **功能**：读 `reasoning-report.json`
- **参数**：`<ontology_id>`

#### `unsat`
- **功能**：列出不可满足类
- **参数**：`<ontology_id>`

#### `explain`
- **功能**：解释本体为何不一致
- **参数**：`<ontology_id>`
- **选项**：`--reasoner`（默认 openllet）

#### `explain-unsat`
- **功能**：解释某类为何不可满足
- **参数**：`<ontology_id> <class_iri>`

#### `entailment`
- **功能**：检查公理是否被蕴含
- **参数**：`<ontology_id>`
- **选项**：`--axiom-type <SubClassOf|...>`、`--axiom-args <json>`

---

### 4.4 SPARQL（1 个命令）

#### `query`
- **功能**：校验或执行 SPARQL 查询
- **参数**：`<ontology_id>`（位置 0）
- **选项**：
  - `--validate`：只校验不执行
  - `--select <query>`：SELECT 查询
  - `--ask <query>`：ASK 查询
  - `--construct <query>`：CONSTRUCT 查询
  - `--describe <query>`：DESCRIBE 查询
- **使用**：
  - `query pizza --ask "ASK { ?s ?p ?o }"`
  - `query pizza --select "SELECT ?s WHERE { ?s rdf:type :Pizza }"`
  - `query pizza --validate --select "..."` （只校验）

---

### 4.5 上下文与检索（1 个命令）

#### `context`
- **功能**：为自然语言问题拼装本体上下文
- **参数**：`<ontology_id> <question>`
- **选项**：
  - `--max-entities <n>`：最多匹配实体数（默认 10）
  - `--max-depth <n>`：上下文深度（默认 3）
  - `--include-inferred`：包含推理事实

---

### 4.6 Claim 验证（5 个命令，v0.3+）

**所有 claim 命令通用**：
- 第一个位置参数：`<ontology_id>`
- `--claim <json_or_path>`：claim JSON 字符串或文件路径
- `--workspace`
- `--json`

#### `verify-claim`
- **功能**：验证结构化 claim
- **参数**：`<ontology_id>`
- **选项**：`--claim`（必填）、`--reasoner`、`--workspace`、`--json`
- **使用**：`verify-claim v0.3 --claim test/fixtures/v0.3/claim-supported.json --json`

#### `evidence`
- **功能**：获取证据路径
- **参数**：`<ontology_id>`
- **选项**：`--claim`、`--workspace`、`--json`

#### `counterexamples`
- **功能**：找反例（仅 contradicted verdict）
- **参数**：`<ontology_id>`
- **选项**：`--claim`、`--workspace`、`--json`

#### `explain-unknown`
- **功能**：解释 unknown verdict
- **参数**：`<ontology_id>`
- **选项**：`--claim`、`--workspace`、`--json`

#### `missing-entities`
- **功能**：检测 claim 中的 missing/ambiguous 实体
- **参数**：`<ontology_id>`
- **选项**：`--claim`、`--workspace`、`--json`

---

### 4.7 批量 claim 工作流（3 个命令，v0.5+）

#### `verify-answer`
- **功能**：批量验证 answer claims
- **参数**：`<ontology_id>`
- **选项**：
  - `--claims <json_or_path>`（必填）：claims batch JSON
  - `--out <file>`：写报告到文件
  - `--workspace`、`--json`

#### `evidence-context`
- **功能**：拼装证据上下文
- **参数**：`<ontology_id>`（可选；当 `--report` 时可省）
- **选项**：
  - `--claims <json_or_path>`：claims batch
  - `--report <json_or_path>`：answer verification report
  - `--max-context-tokens <n>`：token 预算
  - `--format <compact|jsonl>`（默认 compact）
  - `--workspace`、`--json`

#### `review-answer`
- **功能**：用策略 review 答案
- **参数**：`<ontology_id>`
- **选项**：
  - `--claims <json_or_path>`（必填）
  - `--policy <strict|conservative|report-only>`（默认 strict）
  - `--max-context-tokens <n>`
  - `--workspace`、`--json`

---

### 4.8 评估与基准（3 个命令，v0.6+）

#### `benchmark-run`
- **功能**：跑基准实验
- **参数**：`<config.yaml>`（YAML 配置文件绝对路径）
- **选项**：
  - `--out <file>`：输出文件（默认 stdout）
  - `--workspace`、`--json`（输出 JSON 而非 JSONL）

#### `eval-qa`
- **功能**：评估 QA 结果
- **参数**：`<results.jsonl>`（基准结果 JSONL 绝对路径）
- **选项**：`--workspace`、`--json`

#### `context-batch`
- **功能**：批量拼装问题证据
- **参数**：`<question-set.jsonl>`（JSONL 绝对路径）
- **选项**：
  - `--ontology <ontology_id>`（必填）
  - `--max-context-tokens <n>`
  - `--out <file>`：输出文件
  - `--workspace`、`--json`

---

### 4.9 详细检查（12 个命令）

| 命令 | 第一个位置参数 | 主要选项 | 用途 |
|---|---|---|---|
| `imports` | `<ontology_id>` | --workspace | 显示 import 闭包 |
| `restrictions` | `<ontology_id> <class_iri>` | --include-inferred | 类 restrictions |
| `properties` | `<ontology_id> <property_iri>` | --include-inferred | 属性特征 |
| `disjoint` | `<ontology_id> <property_iri>` | --include-inferred | 不相交属性 |
| `equivalent` | `<ontology_id> <property_iri>` | --include-inferred | 等价属性 |
| `membership` | `<ontology_id> <ind_iri> <class_iri>` | --include-inferred, --reasoner | 个体成员关系 |
| `relation-check` | `<ontology_id> <a_iri> <prop_iri> <b_iri>` | --include-inferred, --reasoner | 关系断言检查 |
| `compatibility` | `<ontology_id> <class_a_iri> <class_b_iri>` | | 类相容性 |
| `scope` | `<ontology_id>` | | ontology 域覆盖 |
| `datatype-constraints` | `<ontology_id> <datatype_iri>` | | datatype facets |
| `validate-literal` | `<ontology_id> <datatype_iri> <value>` | --property-uri | 字面量验证 |
| `relations` | `<ontology_id>` | --source (必填), --target (必填), --include-inferred | 两实体间关系 |
| `assertions` | `<ontology_id> <ind_iri>` | --include-inferred | 个体所有断言 |
| `same-individuals` | `<ontology_id> <ind_iri>` | --include-inferred | SameAs 链 |
| `different-individuals` | `<ontology_id> <ind_iri>` | --include-inferred | DifferentFrom 链 |

---

### 4.10 MCP 服务器（1 个命令）

#### `mcp`
- **功能**：启动 readonly MCP 服务器
- **选项**：
  - `--readonly`：readonly 模式（默认 true）
  - `--transport <stdio|http>`：传输方式（默认 stdio）
  - `--host <host>`：HTTP 主机（默认 127.0.0.1）
  - `--port <port>`：HTTP 端口（默认 8080；0 = 临时）
  - `--max-sse-connections <n>`：SSE 流上限（默认 100，>=1）
  - `--session-ttl-minutes <m>`：会话 TTL（默认 30，>=1）
  - `--sse-heartbeat-seconds <s>`：心跳间隔（默认 15，>=1）
  - `--workspace`、`--home`
- **使用**：
  - `mcp --readonly` — stdio 模式
  - `mcp --transport http --port 8080 --max-sse-connections 100` — HTTP 模式
  - `mcp --transport http --port 0` — 临时端口

#### `mcp-config`
- **功能**：生成 MCP 客户端配置
- **选项**：
  - `--client <name>`（必填）：`generic` / `claude` / `cursor` / `http` / `trae`
  - `--workspace-home <path>`：stdio 客户端使用的 home 目录
  - `--out <file>`：写到文件
  - `--workspace`、`--home`
  - `--url <url>`：HTTP 客户端 URL（默认 `http://127.0.0.1:8080/mcp`）
- **使用**：
  - `mcp-config --client claude`
  - `mcp-config --client trae --out trae-mcp-config.json`
  - `mcp-config --client http --url http://192.168.1.10:8080/mcp`

---

## 5. 部署与集成

### 5.1 本地源码部署
```bash
git clone <repo>
cd owl4agents
.\gradlew.bat :modules:ontology-cli:shadowJar
node tools/npm/bin/owl4agents.js init
node tools/npm/bin/owl4agents.js import test/corpus/smoke/pizza.owl pizza
node tools/npm/bin/owl4agents.js mcp --readonly
```

### 5.2 接入 MCP 客户端

**Claude Desktop**（`mcp-config` 一键生成）：
```bash
node tools/npm/bin/owl4agents.js mcp-config --client claude
```

**Trae IDE**（v0.8+ 推荐用 URL 模式）：
```json
{
  "mcpServers": {
    "owl4agents": {
      "url": "http://127.0.0.1:8080/mcp",
      "transport": "http"
    }
  }
}
```

**Windows 兼容配置**（绕开 stdin 转发问题）：
```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "D:/path/to/owl4agents/tools/bin/owl4agents-mcp.cmd",
      "args": ["--readonly"],
      "env": {"OWL4AGENTS_HOME": "D:/owl4agents-workspace"}
    }
  }
}
```

### 5.3 Workspace 路径

默认：`~/.owl4agents/workspaces/default/`
自定义：`OWL4AGENTS_HOME=<path>`

---

## 6. 推理机集成

| 推理机 | Profile | 能力 | 适用场景 |
|---|---|---|---|
| **HermiT** | OWL 2 DL | 一致性 / 分类 / 实现 | 默认 DL 推理 |
| **ELK** | OWL 2 EL | 一致性 / 分类（快） | 大型 EL 本体（生物医学） |
| **Openllet** | OWL 2 DL | 全部 + **解释**（矛盾 / 不可满足类） | 调试、教学、可解释推理 |
| **auto** | 动态 | 按 profile 选：EL→ELK，DL→HermiT，explanation→Openllet | 不确定时让系统选 |

> 修复点（D-007）：推理机名称大小写不敏感（`openllet` 和 `Openllet` 都接受）。

---

## 7. Claim 验证能力详解

**Claim JSON schema**（v0.5+）：
```json
{
  "claimId": "<唯一 id，必填>",
  "type": "individual_membership" | "class_membership" | "object_property_assertion" | "data_property_assertion" | "class_subsumption" | "...",
  "subject":   {"kind":"individual"|"class", "iri":"<完整 IRI>"},
  "predicate": {"kind":"object_property"|"data_property", "iri":"<完整 IRI>"},
  "object":    {"kind":"class"|"individual"|"literal", "iri":"..."| "value":<literal>, "datatype":"..."}
}
```

**返回 verdict**：
- `supported` — 本体蕴含该 claim（含推理事实）
- `contradicted` — 本体否认该 claim
- `unknown` — 本体既不支持也不否认（reasoning 没结果）
- `out_of_scope` — claim 中的实体不在本体内

**Evidence Path** 结构：
```json
{
  "verdict": "supported",
  "reasoningReport": {...},        // 引用 reasoning-report.json
  "inferredFacts": [
    {"entity":"<iri>","property":"<axiom>","derivedFrom":"<rule>"}
  ],
  "explanations": [...]
}
```

**典型工作流**（v0.5 批量）：
```
LLM 生成 answer
  → 拆成结构化 claims（v0.5 不抽 free text，靠应用层拆）
  → ontology_verify_claims_batch
  → 对 supported/contradicted 走 ontology_build_evidence_context
  → LLM 用 context 修订 answer
  → ontology_review_answer_claims --policy conservative
```

---

## 8. 安全模型

| 维度 | 限制 |
|---|---|
| **MCP 默认只读** | `--readonly` 是默认；写操作必须经 CLI |
| **SPARQL 过滤** | 拒 `INSERT/DELETE/LOAD/CLEAR/DROP/COPY/MOVE/ADD` |
| **Mcp-Session-Id** | UUID v4 校验，非 v4 → 400 |
| **SSE 连接数** | `--max-sse-connections` 全局上限；超额 → 503 + `Retry-After: 30` |
| **Session TTL** | `--session-ttl-minutes`；闲置过期被 sweep |
| **审计日志** | `McpToolCallLogger` 记录所有工具调用（tool name、session id、时间、参数、结果） |
| **数据本地** | 默认 `~/.owl4agents/`；支持 `OWL4AGENTS_HOME` 重定向 |

---

## 9. 测试覆盖与质量数据

| 维度 | 数字 |
|---|---|
| 单元测试 | **767/767 PASS**（11 个模块） |
| 端到端 HTTP 用例 | 23/23 PASS |
| 56 工具集成 | 51 PASS + 5 业务预期 ISERR |
| 跨版本验收 | V01..V07 acceptance suite 全过 |
| 累计修复缺陷 | D-001..D-010（10 个，6 阻断 + 4 非阻断） |
| 当前版本 | v0.8.0 |

**5 个 ISERR（非 bug）**：
| 工具 | 错误码 | 业务含义 |
|---|---|---|
| `explain_inconsistency` | `ONTOLOGY_CONSISTENT` | 本体一致，无矛盾可解释 |
| `explain_unsat_class` | `ONTOLOGY_CONSISTENT` | 类可满足 |
| `get_datatype_constraints` | `DATATYPE_NO_FACETS` | datatype 无 facet |
| `find_relations_between_entities` | `ENTITY_NOT_FOUND` | 源 IRI 不存在 |
| `find_counterexamples` / `explain_unknown` | `EVIDENCE_NOT_AVAILABLE` | verdict 是 supported，不需要反例/解释 |

---

## 10. 典型使用场景

### 10.1 LLM Agent 接入
```
用户：把 Pizza 本体加载好，让 LLM 能查任何关于 pizza 的事实。
1. node tools/npm/bin/owl4agents.js import test/corpus/smoke/pizza.owl pizza
2. node tools/npm/bin/owl4agents.js reason pizza --reasoner hermit
3. node tools/npm/bin/owl4agents.js mcp --readonly
4. 在 Trae IDE 配置 mcp-server 指向 http://127.0.0.1:8080/mcp
5. LLM 调用 ontology_get_class_context 拿 Margherita 的父类 → Pizza → Food
```

### 10.2 Claim 验证工作流（v0.5）
```
LLM 给出答案："Margherita 是一种 Pizza"
1. 把答案拆成 claim JSON: {type: class_membership, subject: Margherita, object: Pizza}
2. ontology_verify_claim → verdict=supported
3. ontology_get_evidence_path → 拿到 reasoning-report + inferred facts
4. ontology_review_answer_claims --policy strict → 通过
5. 把 evidence 注入 LLM prompt 修订答案
```

### 10.3 推理调试
```
本体报不一致
1. ontology_check_consistency → false
2. ontology_explain_inconsistency --reasoner openllet → 找到矛盾子集（axiom set）
3. ontology_get_unsat_classes → 列出所有不可满足类
4. ontology_explain_unsat_class <class_iri> --reasoner openllet → 解释该类为何不可满足
```

### 10.4 大规模 QA 评估（v0.6）
```
1. 准备 question set (JSONL) + claim fixture
2. 写 experiment.yaml: ontologyIds=[bfo], questionSetPath=..., outputPath=...
3. node tools/npm/bin/owl4agents.js benchmark-run exp.yaml
4. node tools/npm/bin/owl4agents.js eval-qa results.jsonl
5. 拿到 accuracy / false support rate / 4×4 confusion matrix
```

---

## 11. 已知限制

| 限制 | 原因 |
|---|---|
| **Windows `java -jar` 可能 ACCESS_VIOLATION** | JVM/OWL API 交互问题，用 npm launcher 或 `.cmd` wrapper 绕开 |
| **Windows MCP stdin 转发问题** | Node.js `execSync` 行为差异，用 `tools/bin/owl4agents-mcp.cmd` 绕开 |
| **v0.5+ 不抽 free-text claim** | 拆分质量难控制，靠应用层拆结构化 claim |
| **SPARQL 写操作** | 完全禁止 |
| **MCP 服务器无写工具** | 写走 CLI，留 audit trail |
| **codex / codex-cli MCP config 模板未发布** | 客户端命名与配置格式未稳定 |
| **离线 build 需缓存** | 第一次 build 需联网拉依赖 |
| **Java 22 必需** | Gradle toolchain 锁定 |

---

**维护信息**
- 验收报告：[reports/acceptance/v0.8.0_acceptance_report.md](reports/acceptance/v0.8.0_acceptance_report.md)
- 测试脚本：[test_all_tools_v3.ps1](test_all_tools_v3.ps1)
- 变更日志：[CHANGELOG.md](CHANGELOG.md)
- 入门文档：[README.md](README.md)
- OpenSpec 设计：`openspec/changes/add-v0-8-mcp-streamable-http-transport/`
