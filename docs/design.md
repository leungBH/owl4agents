# owl4agents 详细设计文档

版本：v0.1  
状态：设计整理稿  
目标：为后续 OpenSpec 提案、工程初始化和 README 拆分提供稳定基线。

## 1. 项目定位

### 1.1 项目名称

`owl4agents`，含义是 OWL for Agents。

它面向大模型智能体提供 OWL 本体管理、推理和语义上下文增强能力。

### 1.2 一句话定位

`owl4agents` 是一个本地运行的 OWL/RDF 本体管理、推理与 MCP 工具服务，面向科研人员和 Agent 开发者，用于构建本体增强的大模型智能体实验环境。

### 1.3 项目目标

项目目标不是重新实现 OWL reasoner，而是基于成熟 OWL 生态构建一个面向 Agent 的本体增强运行时，使研究者可以方便地探索“本体 + 推理 + 大模型智能体”的能力边界。

核心能力包括：

1. 导入、管理和导出本地 OWL/RDF 本体。
2. 调用成熟 reasoner 执行一致性检查、分类推理和实例推理。
3. 将显式事实和推理事实转换为 Agent 可使用的结构化上下文。
4. 通过 CLI 和 MCP Server 暴露本体能力。
5. 支持本地优先、可复现、可审计的科研实验流程。

### 1.4 目标用户

主要用户：

1. 研究本体增强 LLM / Agent 的科研人员。
2. 构建领域知识增强智能体的开发者。
3. 管理本地 OWL/RDF 本体知识库的知识工程师。
4. 希望让本地 Agent 调用本体推理能力的高级用户。

典型使用场景：

1. 导入医学、法律、工业、科研等领域本体。
2. 对本体执行一致性检查、分类推理、实例推理。
3. 将推理结果作为 Agent 的结构化上下文。
4. 比较不同 reasoner 对 Agent 问答效果的影响。
5. 构建本体增强问答 benchmark。
6. 通过 MCP 接入 Claude Desktop、Cursor、本地 Agent 框架等客户端。

## 2. 设计原则

### 2.1 优先整合开源能力，不重复造轮子

`owl4agents` 的核心价值不是重写语义网基础设施，而是把成熟开源能力整合成面向 Agent 的本地运行时。项目代码应重点放在 adapter、统一服务契约、本地 workspace、权限边界、索引、审计日志、科研复现和 Agent 上下文构建上。

优先复用的开源基础：

| 能力 | 优先开源基础 |
| --- | --- |
| OWL 加载、编辑、保存、profile 检查 | OWL API |
| OWL 2 DL 推理 | HermiT |
| OWL 2 EL 快速分类 | ELK |
| 不一致解释、Pellet 风格工作流 | Openllet |
| RDF 图和 SPARQL 查询 | Apache Jena ARQ |
| 本体工作流思想和可复用能力 | ROBOT |
| CLI 框架 | Picocli |
| MCP 协议集成 | MCP Java SDK |
| Java runtime 打包 | jlink / jpackage |

只有当开源组件不能满足本地 Agent 场景、科研复现、安全边界或统一接口需求时，才在 `owl4agents` 内实现补充逻辑。

### 2.2 不重新发明 Reasoner

推理是项目核心能力，但不自行实现完整推理器。项目应基于成熟 reasoner 做二次集成：

| Reasoner | 主要场景 | 说明 |
| --- | --- | --- |
| HermiT | OWL 2 DL / Direct Semantics | 默认 DL reasoner，适合严格语义推理实验 |
| ELK | OWL 2 EL | 适合大规模类层级快速分类 |
| Openllet | OWL 2 DL 补充、解释推理 | 适合解释、不一致分析和 Pellet 生态兼容场景 |

### 2.3 Java 作为语义核心

Java 作为核心运行时，原因是：

1. OWL API 是 Java 生态中成熟的 OWL 2 底座。
2. ROBOT、HermiT、ELK、Openllet 都在 JVM 生态中较成熟。
3. CLI、MCP、推理、索引可以共享同一个 `OntologyService`。
4. 避免 Node.js / Python / Java 多语言核心割裂。
5. 更利于科研复现和跨平台分发。

### 2.4 npm 作为安装入口，而不是核心运行时

目标用户体验：

```bash
npx -y owl4agents mcp
```

npm 包只负责：

1. 检测平台和架构。
2. 下载或调用对应平台的 `owl4agents` runtime。
3. 转发命令行参数。
4. 提供一行安装体验。

真正执行本体管理和推理的是 Java runtime。

### 2.5 CLI 与 MCP 共用核心服务

不要让 CLI 和 MCP 各自实现一套业务逻辑。应采用统一服务层：

```text
OntologyService
  -> CLI Adapter
  -> MCP Adapter
  -> Optional HTTP Adapter
```

这样可以保证行为、测试、错误处理、推理结果和实验复现路径一致。

## 3. 总体架构

### 3.1 架构总览

```text
+------------------------------------------------------+
|                    npm package                       |
|                 owl4agents launcher                  |
+--------------------------+---------------------------+
                           |
                           v
+------------------------------------------------------+
|              owl4agents Java Runtime                 |
|                                                      |
|  +------------------+    +------------------------+  |
|  | CLI Adapter      |    | MCP Server Adapter      |  |
|  | picocli          |    | MCP Java SDK            |  |
|  +---------+--------+    +------------+-----------+  |
|            |                          |              |
|            +-----------+--------------+              |
|                        v                             |
|              Ontology Application Service            |
|                        |                             |
|  +---------------------+--------------------------+  |
|  |                                                |  |
|  v                                                v  |
| OWL API Layer                                Retrieval Layer
|  |                                                |
|  v                                                v  |
| Reasoner Layer                              QA Context Builder
|  |                                                |
|  v                                                v  |
| Workspace / Storage / Index / Snapshot / Logs        |
+------------------------------------------------------+
```

### 3.2 模块划分

```text
owl4agents/
  modules/
    ontology-core/
    ontology-owlapi/
    ontology-reasoner/
    ontology-retrieval/
    ontology-storage/
    ontology-cli/
    ontology-mcp/
    ontology-distribution/
  npm/
    launcher/
```

模块职责：

| 模块 | 职责 |
| --- | --- |
| ontology-core | 领域模型、应用服务接口、统一结果和错误模型 |
| ontology-owlapi | 封装 OWL API 的加载、保存、实体解析、profile 检查和 axiom 编辑 |
| ontology-reasoner | 封装 HermiT、ELK、Openllet 等 reasoner |
| ontology-retrieval | 面向 Agent 的实体搜索、语义上下文和 QA context 构建 |
| ontology-storage | 本地 workspace、catalog、metadata、snapshot、index、audit log |
| ontology-cli | Picocli 命令行适配器 |
| ontology-mcp | MCP Java SDK 工具服务适配器 |
| ontology-distribution | jlink / jpackage 分发产物 |
| npm launcher | npm 安装入口和平台 runtime 调用 |

## 4. 技术选型

| 层级 | 技术 | 说明 |
| --- | --- | --- |
| 主语言 | Java 21 | 语义核心、CLI、MCP、推理统一运行 |
| 构建工具 | Gradle Kotlin DSL | 多模块工程管理 |
| OWL 核心 | OWL API | OWL 2 加载、编辑、保存、profile 检查 |
| Ontology Workflow | ROBOT core | 借鉴或复用 ontology workflow 能力 |
| 默认 DL Reasoner | HermiT | OWL 2 DL 一致性检查、分类、实例推理 |
| EL Reasoner | ELK | 大规模 OWL 2 EL 本体快速分类 |
| 补充 Reasoner | Openllet | 解释推理、Pellet 风格能力、SPARQL-DL 相关能力 |
| RDF / SPARQL | Apache Jena ARQ | RDF 图、SPARQL 查询、可选 TDB2 |
| CLI | Picocli | Java CLI 框架 |
| MCP | MCP Java SDK | 在 Java runtime 中实现 MCP Server |
| 本地存储 | 文件系统 + JSONL 索引 | 单机、可复制、可调试 |
| 分发 | npm launcher + jlink / jpackage | npm 一行安装，不要求用户手动安装 Java |
| 测试 | JUnit 5 | 单元测试、集成测试、reasoner 对比测试 |

## 5. 项目目录设计

```text
owl4agents/
  README.md
  LICENSE
  build.gradle.kts
  settings.gradle.kts
  gradle.properties

  docs/
    design.md
    quickstart.md
    cli.md
    mcp.md
    reasoning.md
    workspace.md
    research-evaluation.md
    security.md

  modules/
    ontology-core/
    ontology-owlapi/
    ontology-reasoner/
    ontology-retrieval/
    ontology-storage/
    ontology-cli/
    ontology-mcp/
    ontology-distribution/

  npm/
    package.json
    bin/
      owl4agents.js
    platform-packages/
      darwin-arm64/
      darwin-x64/
      linux-x64/
      linux-arm64/
      win32-x64/

  examples/
    basic/
    biomedical/
    mcp/
    benchmark/

  test/
    owl_files/
```

## 6. 核心模块设计

### 6.1 ontology-core

`ontology-core` 是领域模型和应用服务层，不直接依赖 CLI 或 MCP。

核心职责：

1. 工作空间管理。
2. 本体项目管理。
3. 本体元数据管理。
4. 统一服务接口。
5. 错误模型。
6. 操作结果模型。
7. 权限与写操作控制。

核心接口：

```java
public interface OntologyService {
    OntologyImportResult importOntology(ImportRequest request);
    OntologySummary getSummary(String ontologyId);
    EntitySearchResult searchEntities(EntitySearchRequest request);
    EntityContext getEntityContext(EntityContextRequest request);
    GraphNeighborhood getNeighborhood(NeighborhoodRequest request);
    ReasoningResult runReasoner(ReasoningRequest request);
    ConsistencyResult checkConsistency(ConsistencyRequest request);
    QaContext buildQaContext(QaContextRequest request);
    ExportResult exportOntology(ExportRequest request);
}
```

核心领域对象：

```text
OntologyWorkspace
OntologyProject
OntologyDocument
OntologyId
OntologyMetadata
OntologySnapshot
OntologyOperation
OntologyError
OntologyResult<T>
```

### 6.2 ontology-owlapi

`ontology-owlapi` 封装 OWL API，不让上层直接依赖复杂 OWL API 细节。

核心职责：

1. 加载 OWL/RDF 文件。
2. 保存不同格式。
3. 处理 import closure。
4. 查询 classes / properties / individuals。
5. 添加和删除 axiom。
6. 解析 Manchester Syntax。
7. 执行 OWL profile 检查。
8. 管理 IRI 和 prefix。

核心类：

```text
OwlApiOntologyLoader
OwlApiOntologyWriter
OwlApiEntityResolver
OwlApiAxiomEditor
OwlApiProfileChecker
OwlApiImportClosureManager
OwlApiPrefixManager
ManchesterSyntaxService
```

第一版建议支持格式：

```text
RDF/XML
Turtle
OWL/XML
Functional Syntax
Manchester Syntax
N-Triples
N-Quads
```

### 6.3 ontology-reasoner

`ontology-reasoner` 统一封装不同 reasoner。

核心接口：

```java
public interface OntologyReasoner {
    String name();

    ReasonerCapabilities capabilities();

    boolean supportsProfile(OntologyProfile profile);

    ConsistencyResult checkConsistency(ReasoningInput input);

    ClassificationResult classify(ReasoningInput input);

    InstanceRealizationResult realize(ReasoningInput input);

    EntailmentResult checkEntailment(EntailmentRequest request);

    ExplanationResult explain(ExplanationRequest request);
}
```

支持的 adapter：

```text
HermitReasonerAdapter
ElkReasonerAdapter
OpenlletReasonerAdapter
```

`auto` reasoner 选择策略：

1. 先执行 OWL profile 检查。
2. 如果属于 OWL 2 EL，并且请求任务可由 ELK 支持，优先使用 ELK。
3. 如果需要完整 DL 推理，使用 HermiT。
4. 如果请求 explanation，优先使用 Openllet 或 HermiT explanation 支持。
5. 如果 reasoner 失败，返回结构化错误，不静默降级。

### 6.4 ontology-retrieval

`ontology-retrieval` 是面向 Agent 的语义上下文构建层，也是 `owl4agents` 区别于普通本体工具的关键。

核心功能：

1. 实体搜索。
2. label / comment / IRI / alias 匹配。
3. 类层级上下文提取。
4. 属性约束上下文提取。
5. 个体关系上下文提取。
6. 推理事实上下文提取。
7. 本体上下文压缩。
8. QA Prompt Context 渲染。

核心类：

```text
EntitySearchService
EntityRankingService
GraphNeighborhoodService
HierarchyContextService
PropertyConstraintContextService
InferredFactService
QaContextBuilder
PromptContextRenderer
```

`QaContextBuilder` 输入示例：

```json
{
  "ontologyId": "medical",
  "question": "What symptoms are associated with myocardial infarction?",
  "maxEntities": 8,
  "maxDepth": 2,
  "includeInferred": true,
  "includeAxioms": true,
  "format": "markdown"
}
```

`QaContextBuilder` 输出示例：

```json
{
  "matchedEntities": [
    {
      "iri": "http://example.org/MyocardialInfarction",
      "label": "Myocardial infarction",
      "type": "Class",
      "score": 0.96
    }
  ],
  "classHierarchy": [
    "MyocardialInfarction subClassOf IschemicHeartDisease",
    "IschemicHeartDisease subClassOf CardiovascularDisease"
  ],
  "propertyConstraints": [
    "hasSymptom domain Disease",
    "hasSymptom range Symptom"
  ],
  "entailedFacts": [
    "MyocardialInfarction subClassOf Disease"
  ],
  "naturalLanguageContext": "Myocardial infarction is a subclass of ischemic heart disease..."
}
```

### 6.5 ontology-storage

`ontology-storage` 提供单机本地存储，不依赖数据库服务。

默认数据目录：

```text
~/.owl4agents/
  config.yaml
  workspaces/
    default/
      workspace.yaml
      catalog.json
      ontologies/
        medical/
          source/
            original.owl
          canonical/
            ontology.owl
          inferred/
            hermit.owl
            elk.owl
            openllet.owl
            reasoning-report.json
          index/
            entities.jsonl
            labels.jsonl
            hierarchy.jsonl
            properties.jsonl
            inferred-facts.jsonl
          snapshots/
            2026-05-29T10-00-00Z.owl
            2026-05-29T10-20-00Z.owl
          metadata.json
      logs/
        cli-operations.jsonl
        mcp-tool-calls.jsonl
```

存储原则：

1. 原始文件不覆盖。
2. 规范化文件可重建。
3. 推理结果可重建。
4. 索引可重建。
5. 每次写操作生成 snapshot。
6. 所有 Agent 写操作记录 audit log。

## 7. CLI 设计

### 7.1 命令示例

```bash
owl4agents init
owl4agents import ./medical.owl --name medical
owl4agents list
owl4agents summary medical
owl4agents search medical "myocardial infarction"
owl4agents entity medical "ex:MyocardialInfarction"
owl4agents neighborhood medical "ex:MyocardialInfarction" --depth 2
owl4agents reason medical --reasoner auto
owl4agents consistency medical --reasoner hermit
owl4agents explain medical --entity ex:MyocardialInfarction
owl4agents context medical "What symptoms are associated with myocardial infarction?"
owl4agents export medical --format ttl --out ./medical.ttl
owl4agents mcp --workspace default --readonly
```

### 7.2 子命令分组

```text
workspace:
  init
  workspace list
  workspace use
  workspace remove

ontology:
  import
  list
  summary
  export
  diff
  snapshot
  rollback

entity:
  search
  entity
  classes
  properties
  individuals
  neighborhood

reasoning:
  reason
  consistency
  classify
  realize
  entailment
  explain

agent:
  context
  mcp
  inspect-tools

debug:
  doctor
  version
  env
```

## 8. MCP 设计

### 8.1 启动方式

```bash
owl4agents mcp
owl4agents mcp --readonly
owl4agents mcp --allow-write
owl4agents mcp --workspace default
```

### 8.2 v0.1 MCP 工具

MCP 工具面需要覆盖真实 Agent 工作流，但要分阶段开放。v0.1 优先提供只读能力；写操作必须等到权限、snapshot、diff、audit log 都稳定后，通过 `--allow-write` 显式开启。

v0.1 只读核心工具：

| 工具 | 说明 |
| --- | --- |
| ontology_list | 列出 workspace 内 ontology |
| ontology_summary | 查看 ontology 摘要 |
| ontology_search_entities | 搜索实体 |
| ontology_get_entity_context | 获取实体上下文 |
| ontology_get_graph_neighborhood | 获取图邻域 |
| ontology_get_qa_context | 面向问答构建上下文 |

### 8.3 v0.1 SPARQL / RDF 工具

SPARQL 应该从 MCP 设计早期就纳入，因为很多本体和 RDF 使用者需要直接查询图数据，而不是只能走预设的上下文构建工具。

| 工具 | 说明 |
| --- | --- |
| ontology_sparql_select | 在 explicit / inferred / union graph 上执行只读 `SELECT` 查询 |
| ontology_sparql_ask | 执行 SPARQL `ASK` 查询 |
| ontology_sparql_construct | 执行 SPARQL `CONSTRUCT` 查询并返回 RDF triples |
| ontology_sparql_describe | 对一个或多个资源执行 SPARQL `DESCRIBE` 查询 |
| ontology_validate_sparql | 解析并校验 SPARQL 查询，不执行 |
| ontology_list_graphs | 列出可查询图范围，例如 explicit、inferred、union |

SPARQL 安全规则：

1. v0.1 只允许 `SELECT`、`ASK`、`CONSTRUCT`、`DESCRIBE`。
2. 禁止 `INSERT`、`DELETE`、`LOAD`、`CLEAR`、`CREATE`、`DROP`、`MOVE` 等更新或图管理操作。
3. 所有查询必须支持 timeout。
4. 所有查询必须支持 result limit。
5. 禁止通过 SPARQL 读取任意本地文件。
6. 查询结果应返回结构化数据，并保留 prefix / IRI 信息。

示例：

```json
{
  "ontologyId": "medical",
  "graph": "union",
  "query": "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 20",
  "timeoutSeconds": 10,
  "maxRows": 100
}
```

### 8.4 后续 MCP 工具

以下工具放在 v0.2 或 v0.3：

| 工具 | 建议版本 | 说明 |
| --- | --- | --- |
| ontology_run_reasoner | v0.2 | 执行推理 |
| ontology_check_consistency | v0.2 | 一致性检查 |
| ontology_explain_inconsistency | v0.2 | 解释不一致 |
| ontology_list_reasoners | v0.2 | 列出可用 reasoner 和能力 |
| ontology_get_reasoning_report | v0.2 | 获取最近一次推理报告 |
| ontology_import | v0.3 | 导入 ontology |
| ontology_edit_axiom | v0.3 | 编辑 axiom |
| ontology_export | v0.3 | 导出 ontology |
| ontology_diff | v0.3 | 比较 ontology 或 snapshot 差异 |
| ontology_snapshot | v0.3 | 创建 snapshot |
| ontology_rollback | v0.3 | 回滚到指定 snapshot |
| ontology_get_audit_log | v0.3 | 查看写操作和 MCP tool 调用审计日志 |

### 8.5 MCP 客户端配置示例

Claude Desktop / 兼容 MCP 客户端：

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "npx",
      "args": ["-y", "owl4agents", "mcp", "--readonly"]
    }
  }
}
```

如果用户已经全局安装：

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "owl4agents",
      "args": ["mcp", "--readonly"]
    }
  }
}
```

## 9. Agent 问答增强流程

### 9.1 核心流程

```text
用户问题
  -> Agent 调用 ontology_get_qa_context
  -> owl4agents 执行实体识别
  -> 检索本体实体、类层级、属性约束、个体关系
  -> 合并显式事实和推理事实
  -> 生成结构化上下文 + 自然语言上下文
  -> Agent 基于上下文回答用户问题
```

### 9.2 为什么不直接让 owl4agents 回答问题

`owl4agents` 的职责是：

1. 提供可信语义上下文。
2. 提供推理结果。
3. 提供本体证据。
4. 提供引用路径。

最终自然语言回答应由 Agent 完成。这样更适合科研对比：

1. 无本体上下文的 Agent。
2. 仅显式本体上下文的 Agent。
3. 显式 + 推理上下文的 Agent。
4. 不同 reasoner 生成上下文的 Agent。
5. 不同上下文压缩策略下的 Agent。

## 10. 推理设计

### 10.1 支持的推理任务

1. Consistency Checking。
2. Classification。
3. Realization。
4. Entailment Checking。
5. Inferred Axiom Generation。
6. Inconsistency Explanation。
7. Unsatisfiable Class Detection。
8. Class Hierarchy Extraction。
9. Instance Type Inference。
10. Property Hierarchy Inference。

### 10.2 Reasoner 能力矩阵

| Reasoner | 主要用途 | 优点 | 限制 |
| --- | --- | --- | --- |
| HermiT | OWL 2 DL 推理 | 语义能力强，适合严格实验 | 大本体可能慢 |
| ELK | OWL 2 EL 分类 | 大型类层级推理快 | 只覆盖 EL 片段 |
| Openllet | DL 推理与解释 | 解释能力、Pellet 生态兼容 | 需关注维护状态和许可证 |

### 10.3 推理结果报告

```json
{
  "ontologyId": "medical",
  "reasoner": "hermit",
  "profile": "OWL2_DL",
  "consistent": true,
  "startedAt": "2026-05-29T10:00:00Z",
  "finishedAt": "2026-05-29T10:00:08Z",
  "durationMs": 8231,
  "explicitAxiomCount": 12030,
  "inferredAxiomCount": 1832,
  "unsatisfiableClasses": [],
  "warnings": []
}
```

## 11. 本体编辑设计

### 11.1 支持的编辑操作

第一版写操作应保持高层、结构化，不建议直接让 Agent 拼 Manchester Syntax。

```text
addClass
removeClass
addObjectProperty
addDataProperty
addIndividual
addSubClassOf
removeSubClassOf
addEquivalentClass
addDisjointClasses
addDomain
addRange
addClassAssertion
addObjectPropertyAssertion
addDataPropertyAssertion
updateLabel
updateComment
```

### 11.2 写操作安全策略

MCP Server 默认只读：

```bash
owl4agents mcp --readonly
```

允许写操作需要显式开启：

```bash
owl4agents mcp --allow-write
```

所有写操作必须：

1. 生成 snapshot。
2. 写入 audit log。
3. 返回 diff。
4. 支持 rollback。

### 11.3 EditResult 示例

```json
{
  "success": true,
  "operation": "addSubClassOf",
  "ontologyId": "medical",
  "snapshotId": "2026-05-29T10-20-00Z",
  "addedAxioms": [
    "SubClassOf(ex:MyocardialInfarction ex:CardiovascularDisease)"
  ],
  "removedAxioms": [],
  "warnings": []
}
```

## 12. SPARQL 与 RDF 查询设计

### 12.1 SPARQL 支持

使用 Apache Jena ARQ 提供 SPARQL 查询能力。

CLI：

```bash
owl4agents query medical -q "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 20"
```

MCP：

```json
{
  "ontologyId": "medical",
  "query": "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 20",
  "includeInferred": true
}
```

### 12.2 查询数据范围

```text
explicit    只查询显式本体
inferred    只查询推理结果
union       显式 + 推理结果
```

示例：

```bash
owl4agents query medical --graph union -q query.rq
```

## 13. 本地工作空间设计

### 13.1 Workspace

```yaml
name: default
createdAt: "2026-05-29T10:00:00Z"
defaultReasoner: auto
defaultOutputFormat: turtle
readonlyByDefault: true
```

### 13.2 Ontology Metadata

```json
{
  "id": "medical",
  "name": "medical",
  "ontologyIri": "http://example.org/medical",
  "versionIri": "http://example.org/medical/1.0.0",
  "sourcePath": "/Users/user/medical.owl",
  "canonicalPath": "canonical/ontology.owl",
  "importedAt": "2026-05-29T10:00:00Z",
  "lastModifiedAt": "2026-05-29T10:20:00Z",
  "profile": "OWL2_DL",
  "entityCounts": {
    "classes": 1201,
    "objectProperties": 182,
    "dataProperties": 74,
    "individuals": 2350
  }
}
```

## 14. 索引设计

### 14.1 Entity Index

`entities.jsonl`

```json
{"iri":"http://example.org/MyocardialInfarction","prefixedName":"ex:MyocardialInfarction","type":"Class","label":"Myocardial infarction","comment":"..."}
```

### 14.2 Label Index

`labels.jsonl`

```json
{"text":"myocardial infarction","iri":"http://example.org/MyocardialInfarction","field":"rdfs:label","language":"en"}
```

### 14.3 Hierarchy Index

`hierarchy.jsonl`

```json
{"child":"ex:MyocardialInfarction","parent":"ex:IschemicHeartDisease","source":"explicit"}
{"child":"ex:MyocardialInfarction","parent":"ex:Disease","source":"inferred"}
```

### 14.4 Inferred Facts Index

`inferred-facts.jsonl`

```json
{"subject":"ex:MyocardialInfarction","predicate":"rdfs:subClassOf","object":"ex:Disease","reasoner":"hermit"}
```

## 15. 分发与安装设计

### 15.1 用户安装体验

```bash
npx -y owl4agents init
npx -y owl4agents import ./domain.owl --name domain
npx -y owl4agents mcp
```

全局安装：

```bash
npm install -g owl4agents
owl4agents mcp
```

### 15.2 npm launcher 设计

`owl4agents` npm 主包负责：

1. 检测 `process.platform`。
2. 检测 `process.arch`。
3. 找到对应 platform package。
4. 调用其中的 `owl4agents` runtime。
5. 透传 CLI 参数。

平台包：

```text
@owl4agents/runtime-darwin-arm64
@owl4agents/runtime-darwin-x64
@owl4agents/runtime-linux-x64
@owl4agents/runtime-linux-arm64
@owl4agents/runtime-win32-x64
```

### 15.3 Java Runtime 打包

使用 jlink / jpackage 打包自包含 Java runtime，避免用户单独安装 JDK/JRE。

## 16. 安全设计

### 16.1 默认只读

MCP Server 默认不允许修改本体。

```text
readonly = true
```

显式开启写操作：

```bash
owl4agents mcp --allow-write
```

### 16.2 文件访问限制

1. 默认只能访问 workspace 内文件。
2. import 外部文件需要显式路径。
3. export 外部路径需要用户显式配置。
4. 禁止读取任意系统敏感路径。
5. MCP 工具不得暴露 unrestricted file read。

### 16.3 Agent 写操作审计

`mcp-tool-calls.jsonl`

```json
{
  "timestamp": "2026-05-29T10:20:00Z",
  "tool": "ontology_edit_axiom",
  "arguments": {
    "ontologyId": "medical",
    "operation": "addSubClassOf"
  },
  "result": "success",
  "snapshotId": "2026-05-29T10-20-00Z"
}
```

## 17. 实验复现设计

科研用户需要独立评估本体上下文对 Agent 表现的影响，因此第一版就应保留实验复现路径。

### 17.1 Experiment Config

```yaml
name: ontology-agent-qa-exp-001
ontology: medical
reasoner: hermit
includeInferred: true
contextBuilder:
  maxEntities: 8
  maxDepth: 2
  includeAxioms: true
  includeLabels: true
  includeComments: true
llm:
  provider: external
  model: unspecified
dataset:
  path: ./questions.jsonl
```

### 17.2 导出上下文

```bash
owl4agents context-batch medical ./questions.jsonl \
  --reasoner hermit \
  --out ./contexts.jsonl
```

输出：

```json
{
  "questionId": "q001",
  "question": "What symptoms are associated with myocardial infarction?",
  "matchedEntities": [],
  "context": "...",
  "reasoner": "hermit",
  "includeInferred": true
}
```

可评估指标：

1. 上下文召回质量。
2. 推理事实贡献。
3. LLM 答案质量。
4. 不同 reasoner 差异。
5. 不同上下文压缩策略差异。

## 18. 错误处理设计

### 18.1 统一错误结构

```json
{
  "error": {
    "code": "ONTOLOGY_INCONSISTENT",
    "message": "The ontology is inconsistent.",
    "details": {
      "ontologyId": "medical",
      "reasoner": "hermit"
    },
    "suggestions": [
      "Run owl4agents explain medical --reasoner openllet",
      "Check recently added disjointness axioms"
    ]
  }
}
```

### 18.2 常见错误码

```text
ONTOLOGY_NOT_FOUND
ENTITY_NOT_FOUND
IMPORT_FAILED
UNSUPPORTED_FORMAT
PROFILE_NOT_SUPPORTED
REASONER_TIMEOUT
REASONER_FAILED
ONTOLOGY_INCONSISTENT
WRITE_DISABLED
INVALID_AXIOM
EXPORT_FAILED
MCP_TOOL_ERROR
```

## 19. 测试设计

### 19.1 单元测试

```text
ontology-core:
  workspace lifecycle
  metadata management
  service result model

ontology-owlapi:
  load/save
  entity resolving
  axiom editing
  profile checking

ontology-reasoner:
  hermit consistency
  elk classification
  openllet explanation

ontology-retrieval:
  entity search
  hierarchy extraction
  qa context generation

ontology-mcp:
  tool schema
  readonly policy
  write policy
```

### 19.2 集成测试

1. 导入测试本体。
2. 执行 profile check。
3. 执行 reasoner。
4. 生成 inferred graph。
5. 生成 QA context。
6. 调用 MCP tool。
7. 验证 CLI 与 MCP 结果一致性。

### 19.3 Benchmark 本体

建议内置：

1. small ontology。
2. medium ontology。
3. inconsistent ontology。
4. OWL 2 EL ontology。
5. OWL 2 DL ontology。
6. ontology with individuals。
7. ontology with property restrictions。

## 20. 版本路线图

### 20.1 v0.0 技术验证

用于从零启动工程，验证依赖和关键链路。

1. Gradle 多模块骨架。
2. 验证关键开源库可以被干净集成。
3. OWL API 加载一个本地 OWL 文件。
4. 输出 classes / properties / individuals 摘要。
5. HermiT 执行 consistency check。
6. Apache Jena ARQ 对已加载 ontology 执行一个 SPARQL `SELECT` 查询。
7. CLI-only spike。

### 20.2 v0.1 核心闭环

1. Java 多模块工程。
2. OWL API 导入 / 导出。
3. 本地 workspace。
4. CLI `init/import/list/summary/search/entity`。
5. HermiT consistency / classification。
6. MCP readonly server。
7. `ontology_get_qa_context`。
8. SPARQL MCP 只读工具：`SELECT`、`ASK`、`CONSTRUCT`、`DESCRIBE`。
9. SPARQL validation、timeout、result limit。
10. npm launcher 初版。

### 20.3 v0.2 Reasoner 增强

1. ELK adapter。
2. Openllet adapter。
3. `auto` reasoner 策略。
4. inferred graph 存储。
5. explain inconsistency。
6. reasoner report。
7. 评估 ROBOT 集成，用于复用成熟 ontology workflow 能力。

### 20.4 v0.3 编辑与快照

1. add / remove axiom。
2. update label / comment。
3. 受控 import / export 工作流。
4. snapshot / rollback。
5. MCP `--allow-write`。
6. diff。
7. audit log。

### 20.5 v0.4 科研实验工具

1. context-batch。
2. benchmark runner。
3. context export。
4. reasoner comparison。
5. ontology-agent QA evaluation helper。

### 20.6 v1.0 稳定版

1. 完整文档。
2. 平台 npm runtime 包。
3. Windows / macOS / Linux 支持。
4. 稳定 MCP tools。
5. 稳定 CLI。
6. 示例数据集。
7. 论文 / 实验复现模板。

## 21. README 首屏草案

````markdown
# owl4agents

Local OWL ontology reasoning and MCP server for LLM agents.

## Features

- Import and manage local OWL/RDF ontologies
- Run OWL reasoners such as HermiT, ELK and Openllet
- Build ontology-grounded QA context for LLM agents
- Expose ontology tools through CLI and MCP
- Local-first, research-friendly, reproducible
- Installable through npm

## Quick Start

```bash
npx -y owl4agents init
npx -y owl4agents import ./domain.owl --name domain
npx -y owl4agents reason domain --reasoner auto
npx -y owl4agents mcp --readonly
```
````

## 22. 最终架构结论

`owl4agents` 应采用：

```text
Java semantic runtime
+ OWL API
+ ROBOT-inspired ontology workflow
+ HermiT / ELK / Openllet reasoner adapters
+ Jena SPARQL support
+ Picocli CLI
+ MCP Java SDK
+ local workspace storage
+ npm launcher distribution
```

这个架构的优势是：

1. 推理可靠。
2. 语义能力强。
3. 科研可复现。
4. CLI 和 MCP 一致。
5. 本地运行。
6. 用户安装仍然简单。
7. 长期扩展空间充足。

核心设计判断：

1. Node.js 负责安装体验。
2. Java 负责语义和推理。
3. MCP 和 CLI 共享同一个 `OntologyService`。
4. Reasoner 采用成熟框架，不自行实现。
