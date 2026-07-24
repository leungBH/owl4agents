# owl4agents — 功能与工具参考手册

> **版本:** v0.8.4(发布于 2026-07-11,claim 验证性能优化:7 项决策包括 EntitySignatureCache、单次本体加载、断言公理索引、推理层次索引、OntologyCache TTL 窗口)。
> **目标读者:** 想要**使用** owl4agents(CLI 或 MCP)、并希望了解每个命令/工具做什么、需要什么入参、返回什么结果的程序员。我们假设你是 CS 毕业生 —— 熟悉 JSON、HTTP、正则、能读 API 文档 —— 但 OWL 或 SPARQL 接触不接触都可以。
> **配套阅读:** [README.zh-CN.md](../README.zh-CN.md) 用于电梯演讲和 5 分钟快速启动;本文是深度参考。

---

## 语言版本 / Available languages

- **简体中文**(本文)
- English: [README.md](../README.md) / [FEATURES.md](FEATURES.md)

---

## 目录

1. [§0 如何阅读本文档](#0-如何阅读本文档)
2. [§1 5 分钟 OWL 与 SPARQL 入门 —— 你真正需要的预备知识](#1-5-分钟-owl-与-sparql-入门--你真正需要的预备知识)
3. [§2 架构与模块拆解](#2-架构与模块拆解)
4. [§3 真实走读 —— 加载本体、提问、验证一条 claim](#3-真实走读--加载本体提问验证一条-claim)
5. [§4 CLI 参考 —— 每个命令,带真实入参和真实输出](#4-cli-参考--每个命令带真实入参和真实输出)
6. [§5 MCP 工具参考 —— 每个工具,带真实 JSON-RPC 请求和响应](#5-mcp-工具参考--每个工具带真实-json-rpc-请求和响应)
7. [§6 Claim 验证与证据落地 —— 把 owl4agents 接入 LLM 答案流水线](#6-claim-验证与证据落地--把-owl4agents-接入-llm-答案流水线)
8. [§7 部署、环境与集成](#7-部署环境与集成)
9. [§8 推理机集成 —— 选哪个、何时选](#8-推理机集成--选哪个何时选)
10. [§9 错误码、故障排查与限制](#9-错误码故障排查与限制)
11. [§10 测试、质量数据与验收证据](#10-测试质量数据与验收证据)

---

## 0. 如何阅读本文档

这是一份故意写得很长的参考手册。你几乎不会需要它的全部;选最匹配你当前任务的章节就行。

- **第一次看?** 通读 [§1](#1-5-分钟-owl-与-sparql-入门--你真正需要的预备知识)(OWL 入门)和 [§3](#3-真实走读--加载本体提问验证一条-claim)(真实走读)。之后就可以跳着看了。
- **用 CLI 驱动 owl4agents?** 跳到 [§4](#4-cli-参考--每个命令带真实入参和真实输出)。每个命令都有可直接复制的"运行一下"代码块,以及一份"实际输出"展示我们在本仓库 v0.8 服务器上跑出的真实结果。
- **用 MCP 客户端(Claude / Cursor / Trae / 自研代理)驱动 owl4agents?** 跳到 [§5](#5-mcp-工具参考--每个工具带真实-json-rpc-请求和响应)。每个工具都有 JSON-RPC 请求样例和匹配的响应。
- **搭建答案验证流水线?** 先看 §3,再 [§6](#6-claim-验证与证据落地--把-owl4agents-接入-llm-答案流水线)。
- **调试错误?** [§9](#9-错误码故障排查与限制)。

**每个例子用的本体**都是 `test/corpus/golden/v0.3-claim-verification.owl`,55 行、8 个类、4 个个体、1 个对象属性、2 个数据属性,小到能一屏看完。我们会在 [§1.2](#12-贯穿全文的示例一个真实的-owl-文件) 重新打印一遍。

**本文档约定:**

- "我们跑了"或"实际输出"意味着我们用真实的 owl4agents v0.8 jar 对真实 fixture 跑过命令,原样粘贴(可能有一两处换行美化)。
- `json` 代码块是真实的请求/响应体。`powershell` 或 `bash` 代码块是真实可执行的命令。
- `<like-this>` 形如这种的是占位符,记得替换成你自己的值。

---

## 1. 5 分钟 OWL 与 SPARQL 入门 —— 你真正需要的预备知识

这是一份刻意写得很短的入门。如果之前接触过 RDF 或描述逻辑,扫一眼即可;如果没接触过,看完这一节再读后面会轻松很多。

### 1.1 30 秒精华版

一份 **OWL 本体**就是一组具名事物(类、属性、个体)以及它们之间的关系。它长这样:RDF 三元组 `subject predicate object`。有意思的谓词来自三个词汇表:

- `rdf:type` —— "这个个体是这个类的实例"(例如 `Fido rdf:type Dog`)。
- `rdfs:subClassOf` —— "这个类比那个类更具体"(例如 `Dog rdfs:subClassOf Animal`)。
- `rdfs:domain` / `rdfs:range` —— "这个属性作用于 X 的实例,产出 Y 类型的值"。

**OWL 推理机**接受显式事实,推算出哪些内容*必然*为真。给定 `Dog subClassOf Mammal` 和 `Mammal subClassOf Animal`,推理机推导出 `Dog subClassOf Animal` 并写入 inferred 图。

**SPARQL** 就是 RDF 界的 SQL。写 `SELECT ?s WHERE { ?s rdfs:subClassOf :Animal }` 就能拿到所有 `Animal` 的子类。`ASK` 返回布尔值;`CONSTRUCT` 返回三元组;`DESCRIBE` 返回"这个资源我们知道的一切"。

到这里就够了。OWL 剩下的内容(等价类、不相交性、限制、个体、数据类型……)不过是有更多有意思的谓词和更丰富的推论。

### 1.2 贯穿全文的示例:一个真实的 OWL 文件

下面这个 55 行的文件会在后面所有例子中出现。路径是仓库里的 `test/corpus/golden/v0.3-claim-verification.owl`。

```turtle
@prefix : <http://example.org/v0.3#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

<http://example.org/v0.3-claim-verification> a owl:Ontology ;
    rdfs:label "v0.3 Claim Verification Golden Ontology" ;
    rdfs:comment "Golden ontology for testing claim verification across supported, contradicted, unknown, and out_of_scope verdicts." .

# --- 类层级 (supported: Dog subClassOf Animal) ---
:Animal a owl:Class .
:Mammal a owl:Class ; rdfs:subClassOf :Animal .
:Dog a owl:Class ; rdfs:subClassOf :Mammal .
:Cat a owl:Class ; rdfs:subClassOf :Mammal .

# --- 等价类 (supported: Canine = Dog) ---
:Canine a owl:Class ; owl:equivalentClass :Dog .

# --- 不相交类 (contradicted: Dog disjointWith Cat —— 声称 Dog subClassOf Cat 会被驳回) ---
:Dog owl:disjointWith :Cat .

# --- 带 domain/range 的对象属性 ---
:hasOwner a owl:ObjectProperty ;
    rdfs:domain :Animal ;
    rdfs:range :Person .

:Person a owl:Class .

# --- 带 domain/range 和数据类型约束的数据属性 ---
:hasAge a owl:DatatypeProperty ;
    rdfs:domain :Animal ;
    rdfs:range xsd:nonNegativeInteger .

:hasName a owl:DatatypeProperty ;
    rdfs:domain :Animal ;
    rdfs:range xsd:string .

# --- 个体断言 (supported: Fido is a Dog) ---
:Fido a :Dog .
:Rex a :Dog .
:Whiskers a :Cat .

:Fido :hasOwner :PersonJohn .
:Fido :hasAge 5 .
:Fido :hasName "Fido" .

:PersonJohn a :Person .

# --- 稀疏 unknown: Fish/Goldfish 之间没有任何公理连接 ---
:Fish a owl:Class .
:Goldfish a owl:Class .

# --- 超出范围: UnconnectedThing 与 Animal 没有任何关系 ---
:UnconnectedThing a owl:Class .
```

读这份文件你需要抓住的重点:

- **8 个具名类**:`Animal`、`Mammal`、`Dog`、`Cat`、`Canine`、`Person`、`Fish`、`Goldfish`、`UnconnectedThing`。
- **类层级**:`Dog`、`Cat` 是 `Mammal`;`Mammal` 是 `Animal`。传递性使得 `Dog`、`Cat` 也是 `Animal`,但这**只在推理之后**才成立 —— 文件本身并没有直接写 `Dog subClassOf Animal`。
- **一个等价**:`Canine` ≡ `Dog`,推理机会把两者合并。
- **一个不相交**:`Dog` ⊥ `Cat`,一个个体不能同时是两者。声称 `Dog subClassOf Cat` 会被推理机**驳回(contradicted)**。
- **3 个具名个体**:`Fido`(一只 `Dog`,有 owner、有 age、有 name)、`Rex`(一只 `Dog`)、`Whiskers`(一只 `Cat`)、`PersonJohn`(一个 `Person`)。
- **2 个对象/数据属性声明**:`hasOwner`(对象属性,domain=Animal,range=Person)、`hasAge` / `hasName`(数据属性,domain=Animal,range=xsd:nonNegativeInteger / xsd:string)。
- **两个为 claim 验证测试而生的公理**:
  - `Fish` 和 `Goldfish` 存在但彼此没有公理连接。声称 `Goldfish subClassOf Fish` 是 **unknown** —— 既不驳回也不支持,就是没有信息。
  - `UnconnectedThing` 与本体其余内容没有关系。声称某个类"在 `Animal` 的 scope 内"且涉及 `UnconnectedThing` 的就是 **out_of_scope**。

### 1.3 你会在响应中看到的三种图

owl4agents 围绕同一本体的三个视图来推理:

- **explicit** —— 你在文件里写的公理。
- **inferred** —— 推理机推算出的公理。`Dog subClassOf Animal` 就住在这里。
- **union** —— explicit ⊎ inferred。(一些工具默认用这个。)

大多数工具都允许你用 `graphScope` / `graph_scope` 参数来选择图的种类。

### 1.4 OWL profile

OWL 2 有四种可处理的 profile:**DL**(描述逻辑,表达力最强,推理较慢)、**EL**(存在量词,非常快,很多生物医学本体属于 EL)、**QL**(查询友好,适合大 ABox)、**RL**(基于规则,可扩展)。owl4agents 会自动检测 profile,也允许你通过 `ontology_get_profile` 显式询问。不同推理机支持不同的 profile —— 见 [§8](#8-推理机集成--选哪个何时选)。

### 1.5 Claim 长什么样?—— 解读"结构化 claim"

在本文档中你会反复看到形如下面的 JSON,代表"声明 `Dog` 是 `Mammal` 的子类":

```json
{
  "claimId": "my-claim-001",
  "type": "subclass",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
  "predicate": "subClassOf",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Mammal" }
}
```

字段说明:

- `claimId` —— 你自己的标识符,会原样回显在响应里。
- `type` —— 下列之一:`subclass`、`equivalent_classes`、`disjoint_classes`、`individual_membership`、`class_compatibility`、`relation_assertion`(在 v0.8.1 拆分为 `object_property_assertion` / `data_property_assertion`)、`ontology_scope`、`ontology_consistency`、`literal_validity`、`object_property_domain`、`object_property_range`、`data_property_domain`、`data_property_range`、`different_individuals`(v0.8.1)、`object_property_subproperty`(v0.8.1)。
- `subject` / `object` —— `{ "kind": "class" | "individual" | "object_property" | "data_property", "iri": "..." }`。v0.8.1 在任意一侧加了可选的 `expression` 字段,支持复杂类表达式(例如 `Pizza ⊓ ∃hasTopping.CheeseTopping`);见 §6.7。
- `predicate` —— 所断言的关系。
- `reasoner` —— `"auto" | "hermit" | "elk" | "openllet"`,默认 `"auto"`。
- `graphScope` —— `"explicit" | "inferred" | "union"`,默认 `"explicit"`。
- `options.includeEvidence` —— 为 `true` 时响应会包含 `evidence` 数组。

完整的 JSON schema 受到强制校验;不规范的 claim 会返回 `INVALID_CLAIM_SCHEMA`。

---

## 2. 架构与模块拆解

owl4agents 由 11 个 Gradle 模块构成。前 6 个实现核心域(storage、OWL 加载、query、reasoning、retrieval、validation),中间 3 个是公共面(CLI、MCP、benchmark),最后 2 个是打包与验收。

```
+---------------------------------------------------------------------+
|                          npm launcher (Node 18+)                    |
|                  tools/npm/bin/owl4agents.js                        |
+-----------------------------+---------------------------------------+
                              |  fork+exec  (Windows 上是 PassThru)
                              v
+---------------------------------------------------------------------+
|                       Java 22  owl4agents.jar                       |
|                                                                     |
|  +-----------------------------+   +-----------------------------+  |
|  | CLI 层 (Picocli)             |   | MCP 服务器 (JSON-RPC + SSE)|  |
|  | 47 个子命令                   |   | 56 个只读工具              |  |
|  +-------------+---------------+   +-------------+---------------+  |
|                |                                 |                  |
|                +-------------+-------------------+                  |
|                              v                                      |
|                +----------------------------------+                 |
|                |   OntologyService (façade)       |                 |
|                +----+-------------+---------------+                 |
|                     |             |                                 |
|  +------------------+--+   +------+-------------------------+        |
|  |  storage           |   |  owlapi                        |        |
|  |  工作区初始化、     |   |  OWL API 加载、profile 检测     |        |
|  |  catalog、导入器   |   |  规范化                        |        |
|  +-------------------+   +--------------------------------+        |
|                                                                     |
|  +-------------------+   +-------------------+   +---------------+  |
|  | query             |   | reasoner          |   | retrieval     |  |
|  | Jena ARQ + SPARQL |   | HermiT/ELK/       |   | entity/QA      |  |
|  | safety guard      |   | Openllet 适配器    |   | context 构造   |  |
|  +-------------------+   +-------------------+   +---------------+  |
|                                                                     |
|  +-------------------+   +-------------------+   +---------------+  |
|  | validation        |   | benchmark         |   | cli / mcp /   |  |
|  | claim、literal、  |   | 实验运行器、eval  |   | distribution   |  |
|  | entailment、batch |   | 批 context 构造   |   | (入口点)      |  |
|  +-------------------+   +-------------------+   +---------------+  |
+---------------------------------------------------------------------+
                              |
                              v
                  ~/.owl4agents/workspaces/
                  ├── catalog.json
                  ├── workspace.yaml
                  └── <workspace>/
                      └── ontologies/
                          └── <ontology-id>/
                              ├── source/        ← 你导入的源文件
                              ├── canonical/     ← 规范化副本
                              ├── inferred/      ← 推理机输出(类层级、类型)
                              ├── metadata.json
                              └── reasoning-report.json
```

| 模块 | 职责 | 关键类 |
|---|---|---|
| `ontology-core` | 共享数据模型(`Claim`、`EntityId`、所有 `Result` record)、`ErrorCode`、`ServiceError`、JSON 辅助 | `OntologyService`、`ClaimValidator`、`ErrorCode` |
| `ontology-storage` | 工作区、home 路径、catalog、导入器 | `WorkspaceInitializer`、`CatalogStore`、`HomeDirectoryResolver`、`OntologyImporter` |
| `ontology-owlapi` | OWL API 加载、profile 检测、规范化、语义深化 | `OwlapiOntologyLoader`、`ProfileDetector`、`SemanticDeepeningService` |
| `ontology-query` | Apache Jena ARQ、SPARQL safety guard、实体搜索 | `SparqlExecutionService`、`SparqlSafetyGuard`、`EntitySearchService` |
| `ontology-reasoner` | HermiT / ELK / Openllet 适配器、推理任务路由、持久化 | `ReasonerServiceImpl`、`ReasonerLifecycleManager`、`HermitAdapter`、`ElkAdapter`、`OpenlletAdapter` |
| `ontology-retrieval` | 实体 context、图邻居、QA context 构造 | `EntityContextService`、`GraphNeighborhoodService`、`QaContextService` |
| `ontology-validation` | claim 验证、literal 校验、entailment、一致性分析、证据路径、claim 工作流、批量证据 context | `ClaimVerificationService`、`LiteralValidator`、`EntailmentChecker`、`ConsistencyAnalysisService`、`EvidenceGroundingService`、`ClaimWorkflowService`、`EvidenceContextBuilder`、`ClaimBatchValidator` |
| `ontology-benchmark` | 基准运行器、QA 评估、批 context、问题集校验 | `BenchmarkService`、`QaEvaluationService`、`ContextBatchService`、`ExperimentConfigParser`、`BenchmarkQuestionSetValidator`、`BenchmarkReportGenerator` |
| `ontology-cli` | Picocli 命令适配器(47 个子命令)、mcp-config 生成器 | `Owl4AgentsCli`、`McpCommand`、`ImportCommand`、`VerifyClaimCommand`、`McpConfigCommand` |
| `ontology-mcp` | MCP 服务器(stdio / HTTP / SSE)、工具注册表、调用日志、会话管理 | `HttpMcpServer`、`McpServerAdapter`、`McpToolRegistry`、`McpSessionManager`、`McpToolCallLogger` |
| `ontology-distribution` | 跨版本端到端验收(V01..V08) | `V03AcceptanceSuite`、`V04AcceptanceSuite`、... |

**门面**是 `OntologyService`(在 `ontology-core` 里)。所有入口(CLI、MCP)都调它。使用推理机的工具调用走单线程执行器;其它调用走 8 线程池。这就是 MCP 服务器报"saturated worker pool"错误的原因 —— 见 [§9](#9-错误码故障排查与限制)。

---

## 3. 真实走读 —— 加载本体、提问、验证一条 claim

这一节把**同一份本体**端到端跑一遍最常用的入口,每步带真实输出。后面 [§4](#4-cli-参考--每个命令带真实入参和真实输出) 和 [§5](#5-mcp-工具参考--每个工具带真实-json-rpc-请求和响应) 都会回到这里。

### 3.1 预备

```powershell
# 0. 前置条件
java -version    # 22.x
node --version   # 18.x+

# 1. 构建(Windows)
.\gradlew.bat :modules:ontology-cli:shadowJar

# 2. 设置工作区
$env:OWL4AGENTS_HOME = "D:\owl4agents-workspace"   # 任意可写目录
```

### 3.2 初始化工作区

```powershell
node tools/npm/bin/owl4agents.js init
```

**实际输出:**

```
Workspace 'default' initialized successfully.
```

这会创建 `<OWL4AGENTS_HOME>/workspaces/default/workspace.yaml` 和一个全新的 `catalog.json`。重复运行是幂等的。

### 3.3 导入示例本体

```powershell
node tools/npm/bin/owl4agents.js import `
    test/corpus/golden/v0.3-claim-verification.owl v03_demo
```

**实际输出(为清晰起见略作裁剪):**

```
Importing test/corpus/golden/v0.3-claim-verification.owl as 'v03_demo'...
  Source copied: 1,807 bytes
  Parsing with OWL API...
  Profile detected: OWL 2 EL (also valid in DL/QL/RL)
  Normalizing axioms (8 classes, 4 individuals, 1 obj-prop, 2 data-props)...
  Writing canonical/ontology.owl (2,888 bytes)
  Updating catalog.json...
Imported ontology 'v03_demo' (IRI: http://example.org/v0.3-claim-verification)
```

导入器内部会做这些事:

1. 把源文件拷贝到 `ontologies/v03_demo/source/`。
2. 用 OWL API 加载,计算 profile,规范化公理,写入 `canonical/ontology.owl`。
3. 在 `catalog.json` 中追加一项,映射 `v03_demo → 源路径、规范化路径、导入时间戳、metadata 路径`。

### 3.4 看看(无需推理)

```powershell
node tools/npm/bin/owl4agents.js list
```

```
Ontologies in workspace 'default':
  v03_demo - v0.3-claim-verification (imported: 2026-07-06T...)
```

```powershell
node tools/npm/bin/owl4agents.js summary v03_demo
```

```
Ontology: v03_demo
IRI: http://example.org/v0.3-claim-verification
Version IRI: (none)
Imports: []
Profile: [OWL 2 DL, OWL 2 EL, OWL 2 QL, OWL 2 RL, OWL 2 Full]
Entity counts:
  Classes: 8
  Object properties: 1
  Data properties: 2
  Annotation properties: 0
  Individuals: 4
  Datatypes: 1
```

```powershell
node tools/npm/bin/owl4agents.js search v03_demo Dog
```

```
Search results for 'Dog' in ontology 'v03_demo':
Found 1 results

  Dog
    IRI: http://example.org/v0.3#Dog
    Type: class
    Score: 0.85
    Match: alias
```

```powershell
node tools/npm/bin/owl4agents.js entity v03_demo "http://example.org/v0.3#Dog"
```

```
Entity: http://example.org/v0.3#Dog
IRI: http://example.org/v0.3#Dog
Type: class

Superclasses: [http://example.org/v0.3#Mammal]
Equivalent: [http://example.org/v0.3#Canine]
Disjoint: [http://example.org/v0.3#Cat, http://example.org/v0.3#Cat]
```

(注:`Disjoint` 把 `Cat` 列了两次。这是导入器合并对称公理时遗留的重复 disjoint bug,见 [§9](#9-错误码故障排查与限制)。不影响正确性 —— 不相交关系是真实的 —— 只是 IRI 出现了两次。)

### 3.5 跑推理机

```powershell
node tools/npm/bin/owl4agents.js reason v03_demo --reasoner elk
```

**实际输出:**

```
Reasoning report for ontology 'v03_demo':
  Reasoner: ELK
  OWL profile: OWL 2 EL
  Classification: true
  Realization: true
  Consistency: true
  Timing (ms):
    Initialization: 264
    Classification: 178
    Realization: 10
    Total: 1493
  Inferred axiom counts:
    SubClassOf: 4
    InferredIndividualType: 8
```

这会持久化 `reasoning-report.json` 并把 `inferred-class-hierarchy.jsonl` 和 `inferred-types.jsonl` 写入工作区。后续问 `graphScope: inferred` 或 `union` 的工具会读这些文件。

```powershell
node tools/npm/bin/owl4agents.js consistency v03_demo
```

```
Consistency check for ontology 'v03_demo':
  Reasoner: ELK
  Consistent: true
```

```powershell
node tools/npm/bin/owl4agents.js classify v03_demo
```

```
Classification result for ontology 'v03_demo':
  Reasoner: ELK
  Complete hierarchy entries: 10
  Delta (new inferred) entries: 4

Inferred SubClassOf relationships (delta):
  http://example.org/v0.3#Dog -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  http://example.org/v0.3#Cat -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  http://example.org/v0.3#Canine -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  http://example.org/v0.3#Canine -> http://example.org/v0.3#Mammal (source: inferred, reasoner: ELK)
```

前三条是推断出来的:显式的 `Mammal subClassOf Animal` 加上 `Dog`/`Cat`/`Canine` 是 `Mammal` 的子类。第四条来自等价关系 `Canine ≡ Dog` 加上 `Dog subClassOf Mammal`。

### 3.6 跑 SPARQL 查询

```powershell
node tools/npm/bin/owl4agents.js query v03_demo `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }"
```

**实际输出(默认 `graphScope: explicit`):**

```
Variables: [s]
Results: 1
  {s=BindingValue[value=http://example.org/v0.3#Mammal, datatype=null, type=uri]}
```

只看到 `Mammal`,因为显式图里只字面写出了 `Mammal subClassOf Animal`。传递性子类(Dog、Cat、Canine)住在 inferred 图里:

```powershell
node tools/npm/bin/owl4agents.js query v03_demo --graph-scope union `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }"
```

```
Variables: [s]
Results: 4
  {s=BindingValue[value=http://example.org/v0.3#Mammal, datatype=null, type=uri]}
  {s=BindingValue[value=http://example.org/v0.3#Dog, datatype=null, type=uri]}
  {s=BindingValue[value=http://example.org/v0.3#Cat, datatype=null, type=uri]}
  {s=BindingValue[value=http://example.org/v0.3#Canine, datatype=null, type=uri]}
```

```powershell
node tools/npm/bin/owl4agents.js query v03_demo `
    --ask "ASK { <http://example.org/v0.3#Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Mammal> }"
```

```
Result: true
```

> **为什么用完整 IRI?** 因为这份示例本体声明的前缀映射方式 Jena 不认。你可以用完整 IRI,或者在用 `MCP ontology_sparql_select` 工具时把查询包在 SPARQL prologue 里(`PREFIX rdfs: <...>`)。MCP 工具对裸 `PREFIX` 有 safety guard(在你的代码里用字符串变量绕开)。

### 3.7 验证一条结构化 claim

这是最有意思的工具。"claim" 是一个小型 JSON,比如说"`Dog` 与 `Cat` 兼容吗?"。回答里会带 verdict、证据和原因。

先写一个 claim 文件:

```powershell
# 保存为 test/fixtures/v0.3/claim-contradicted.json
@"
{
  "claimId": "doc-contradicted",
  "type": "class_compatibility",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
  "predicate": "compatibleWith",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Cat" },
  "reasoner": "auto",
  "graphScope": "explicit",
  "options": { "includeEvidence": true }
}
"@ | Out-File claim-contradicted.json -Encoding ascii
```

然后验证:

```powershell
node tools/npm/bin/owl4agents.js verify-claim v03_demo `
    --claim claim-contradicted.json --json
```

**实际输出:**

```json
{
  "claimId": "doc-contradicted",
  "ontologyId": "v03_demo",
  "claimType": "class_compatibility",
  "verdict": "contradicted",
  "evidence": [
    {
      "evidenceId": "compatibility-doc-contradicted",
      "role": "counter",
      "kind": "explicit_axiom",
      "value": "http://example.org/v0.3#Dog and http://example.org/v0.3#Cat → disjoint",
      "source": "class-compatibility-check",
      "reasoner": "default",
      "graphScope": "UNION",
      "entities": ["http://example.org/v0.3#Dog", "http://example.org/v0.3#Cat"],
      "confidence": "inferred"
    }
  ],
  "unknownReason": null,
  "unknownExplanation": null,
  "reasonerName": "auto",
  "graphScope": "explicit",
  "truncated": false,
  "totalEvidenceAvailable": 1
}
```

直译过来:**"你的声明(Dog 与 Cat 兼容)被本体里一条显式的不相交公理驳回了,证据如下。"**

再看一个 positive 例子,声明 `Animal` 在自己的 scope 内:

```json
{
  "claimId": "doc-scope-supported",
  "type": "ontology_scope",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Animal" },
  "predicate": "inScopeOf",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" }
}
```

```powershell
node tools/npm/bin/owl4agents.js verify-claim v03_demo `
    --claim claim-scope.json --json
```

**实际输出(节选):**

```json
{
  "claimId": "doc-scope-supported",
  "ontologyId": "v03_demo",
  "claimType": "ontology_scope",
  "verdict": "supported",
  "evidence": [
    {
      "evidenceId": "scope-doc-scope-supported",
      "role": "supporting",
      "kind": "scope_statement",
      "value": "Domains: [Animal, Canine, Fish, Goldfish, Person, UnconnectedThing], gaps: [], limitations: [No disjointness axioms support, No union of class expressions, No cardinality restrictions (except max 1)]",
      "source": "scope-description",
      "graphScope": "EXPLICIT",
      "confidence": "explicit"
    }
  ],
  "totalEvidenceAvailable": 1
}
```

按"我们有多不知道"递增排序,verdict 有四种:

| Verdict | 含义 | 例子 |
|---|---|---|
| `supported` | 存在公理(显式或推断)支持该 claim。 | "Animal 在 Animal 的 scope 内" —— 本体声明的领域包含 Animal。 |
| `contradicted` | 存在公理(显式或推断)直接驳回该 claim。 | "Dog 与 Cat 兼容" —— 但它们是 `disjointWith`。 |
| `unknown` | 既不支持也不驳回,就是信息不够。`unknownReason` 会被设置(例如 `insufficient_axioms`、`sparse_ontology`)。 | "Goldfish subClassOf Fish" —— 两个类都存在,但本体没有公理连接它们。 |
| `out_of_scope` | 该 claim 引用了本体里根本没提的实体/关系。 | "DeliveryPrice inScopeOf Animal" —— `DeliveryPrice` 在本本体中不存在。 |

### 3.8 同一流程走 MCP

同一个本体、同一个答案 —— 但走 JSON-RPC 服务器。这是 MCP 客户端用的真实报文格式。

```powershell
# 在另一个 shell 里启动服务器。
$env:OWL4AGENTS_HOME = "D:\owl4agents-workspace"
node tools/npm/bin/owl4agents.js mcp --readonly --transport http --port 8091

# 在本 shell 发请求。
curl.exe -sS -X POST http://127.0.0.1:8091/mcp `
    -H "Content-Type: application/json" -H "Accept: application/json" `
    --data-binary '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"owl4agents","version":"0.8.4"}}}
```

`serverInfo.version` 应该是 `"0.8.4"`。会话是匿名的(`initialize` 不返回 `Mcp-Session-Id`);后续调用在 plain HTTP 传输上不需要 session id。

```powershell
curl.exe -sS -X POST http://127.0.0.1:8091/mcp `
    -H "Content-Type: application/json" -H "Accept: application/json" `
    --data-binary '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ontology_list_reasoners","arguments":{}}}'
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "text": "{\"reasoners\":[{\"name\":\"HermiT\",\"supportedProfiles\":[\"OWL 2 DL\",\"OWL 2 Full\"],\"supportedOperations\":[\"classify\",\"realize\",\"checkConsistency\"],\"explanationSupported\":false},{\"name\":\"ELK\",\"supportedProfiles\":[\"OWL 2 EL\"],\"supportedOperations\":[\"classify\",\"checkConsistency\"],\"explanationSupported\":false},{\"name\":\"Openllet\",\"supportedProfiles\":[\"OWL 2 DL\"],\"supportedOperations\":[\"classify\",\"realize\",\"checkConsistency\",\"explain\"],\"explanationSupported\":true}]}"
      }
    ]
  }
}
```

对于带参数的工具,JSON 是内嵌的 `arguments` 对象:

```powershell
# 保存为 D:\req-class.json
@'
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ontology_get_class_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Dog"}}}
'@ | Out-File D:\req-class.json -Encoding ascii -NoNewline

curl.exe -sS -X POST http://127.0.0.1:8091/mcp `
    -H "Content-Type: application/json" -H "Accept: application/json" `
    --data-binary "@D:\req-class.json"
```

```json
{
  "jsonrpc":"2.0","id":3,
  "result":{"content":[
    {"text":"{\"superclasses\":[\"http://example.org/v0.3#Mammal\"],\"disjointClasses\":[\"http://example.org/v0.3#Cat\",\"http://example.org/v0.3#Cat\"],\"iri\":\"http://example.org/v0.3#Dog\",\"equivalentClasses\":[\"http://example.org/v0.3#Canine\"],\"label\":\"\",\"type\":\"class\",\"subclasses\":[]}","type":"text"}
  ]}
}
```

两点结构上的提醒:

1. **工具的真实 JSON 藏在 `result.content[0].text` 里,以字符串形式存放。** 大多数 MCP 客户端会 parse 这个字符串然后给你一个 JSON 对象。里面的形态和 CLI 的 `--json` 输出完全一致。
2. **错误用 `isError: true`** 标记,放在 `result` 上,`text` payload 里是同样的 `code` / `message` / `details` 三元组(见 [§9](#9-错误码故障排查与限制))。

### 3.9 你刚刚看了什么

| 动作 | CLI | MCP 工具 |
|---|---|---|
| 初始化工作区 | `init` | (未暴露 —— CLI only) |
| 导入 OWL 文件 | `import` | (未暴露 —— CLI only) |
| 列出本体 | `list` | `ontology_list` |
| 获取 summary / profile / metadata | `summary` | `ontology_summary`、`ontology_get_metadata`、`ontology_get_profile` |
| 按名字搜索 | `search` | `ontology_search_entities` |
| 获取一个实体 | `entity` | `ontology_get_entity_context`、`ontology_get_class_context`、... |
| 跑推理机 | `reason` / `classify` / `realize` / `consistency` | `ontology_run_reasoner`、`ontology_classify`、`ontology_realize_instances`、`ontology_check_consistency` |
| 跑 SPARQL | `query` | `ontology_sparql_select` / `_ask` / `_construct` / `_describe`,还有 `ontology_validate_sparql` |
| 验证 claim | `verify-claim` | `ontology_verify_claim` |
| 构造证据 context | `evidence`、`evidence-context`、`review-answer` | `ontology_get_evidence_path`、`ontology_build_evidence_context`、`ontology_review_answer_claims` |

上表里每一个单元格都在下面有详细说明。

---

## 4. CLI 参考 —— 每个命令,带真实入参和真实输出

CLI 是一个 Picocli 子命令树。顶层命令是 launcher `node tools/npm/bin/owl4agents.js <subcommand> [...]`;它下面挂着 47 个子命令,分为 10 大区:

1. **工作区与导入**(1.1):`init`、`import`、`imports`、`list`、`summary`
2. **浏览与搜索**(1.2):`search`、`entity`、`scope`
3. **SPARQL 与查询**(1.3):`query`
4. **QA context**(1.4):`context`、`context-batch`
5. **推理机与推理报告**(1.5):`list-reasoners`、`reason`、`classify`、`realize`、`consistency`、`explain`、`unsat`、`report`
6. **实体级 entailment 与关系**(1.6):`entailment`、`compatibility`、`membership`、`relation-check`、`relations`、`assertions`、`same-individuals`、`different-individuals`、`restrictions`、`properties`、`equivalent`、`disjoint`、`datatype-constraints`、`validate-literal`
7. **Claim 验证与证据**(1.7):`verify-claim`、`evidence`、`counterexamples`、`explain-unknown`、`missing-entities`、`verify-answer`、`evidence-context`、`review-answer`
8. **基准与 QA 评估**(1.8):`benchmark-run`、`eval-qa`
9. **MCP 服务器与配置**(1.9):`mcp`、`mcp-config`
10. **环境与冒烟**(1.10):`setup`、`smoke`、`--version`、`--help`

默认工作区是 `default`。大多数命令接受 `--workspace <name>` 来切换工作区,用 `--home <path>`(或 `OWL4AGENTS_HOME` 环境变量)重新指定工作区根目录。

### 4.1 `init`

**用途**:在 `~/.owl4agents/workspaces/<name>/` 下创建全新目录,包含 `workspace.yaml` 和空的 `catalog.json`。幂等。

```powershell
node tools/npm/bin/owl4agents.js init
node tools/npm/bin/owl4agents.js init --workspace staging
```

### 4.2 `import`

**用途**:把本地 OWL/RDF 文件载入到工作区 catalog。复制文件、解析、规范化、注册。

```powershell
node tools/npm/bin/owl4agents.js import `
    test/corpus/golden/v0.3-claim-verification.owl v03_demo
# 可选参数:
#   --force   即使 id 已在 catalog.json 中也重新导入
```

成功的导入会打印源文件路径、profile、entity 计数和 catalog 条目。失败模式:

- **文件找不到** —— `Error: INPUT_NOT_FOUND - Cannot read ontology file: ...`
- **解析错误** —— `Error: ONTOLOGY_PARSE_FAILED - ...`(例如 Turtle 格式错乱)
- **import 失败** —— `Error: ONTOLOGY_IMPORT_FAILED - The ontology imports ... which is not on the import path`(导入器拒绝拉取远程 imports —— 请提供本地副本)

### 4.3 `list`

**用途**:列出当前工作区中所有已注册的本体。

```powershell
node tools/npm/bin/owl4agents.js list --workspace default
```

**实际输出:**

```
Ontologies in workspace 'default':
  v03_demo - v0.3-claim-verification (imported: 2026-07-06T...)
  pizza - pizza (imported: 2026-07-03T...)
  ...
```

JSON 变体:`--json` 打印 `[{"ontologyId":"v03_demo","displayName":"...","importTimestamp":"..."}]`。

### 4.4 `summary`

**用途**:输出 IRI、version IRI、imports 闭包、profile 和 entity 计数。

```powershell
node tools/npm/bin/owl4agents.js summary v03_demo
```

**实际输出:**

```
Ontology: v03_demo
IRI: http://example.org/v0.3-claim-verification
Version IRI: (none)
Imports: []
Profile: [OWL 2 DL, OWL 2 EL, OWL 2 QL, OWL 2 RL, OWL 2 Full]
Entity counts:
  Classes: 8
  Object properties: 1
  Data properties: 2
  Annotation properties: 0
  Individuals: 4
  Datatypes: 1
```

JSON 形态:`{"ontologyId","iri","versionIri","profile","imports":[...],"entityCounts":{...}}`。

### 4.5 `imports`

**用途**:本体的传递性 import 闭包,带 `direct` / `indirect` 标记和是否加载成功的状态。

```powershell
node tools/npm/bin/owl4agents.js imports v03_demo
```

对于自包含本体(像 `v03_demo`):

```
Imports for ontology 'v03_demo':
  (no imports)
```

对于像 BFO 上位本体(它 imports RO、OBI 等):

```
Imports for ontology 'bfo':
  http://purl.obolibrary.org/obo/ro.owl (direct, loaded)
  http://purl.obolibrary.org/obo/BFO_0000050 ... (indirect, loaded)
  ...
```

### 4.6 `search`

**用途**:在类标签、IRI 和别名上做全文搜索。匹配分数是 [0, 1] 之间的浮点数。

```powershell
node tools/npm/bin/owl4agents.js search v03_demo Dog
```

**实际输出:**

```
Search results for 'Dog' in ontology 'v03_demo':
Found 1 results

  Dog
    IRI: http://example.org/v0.3#Dog
    Type: class
    Score: 0.85
    Match: alias
```

**参数:** `--limit <n>`(默认 20),`--type-filter <class,object_property,data_property,individual>`。

### 4.7 `entity`

**用途**:输出关于单个实体(类 / 属性 / 个体)的所有信息。实体的种类从 IRI 自动检测;类返回 class context,个体返回 individual context,属性返回 property context。

```powershell
node tools/npm/bin/owl4agents.js entity v03_demo "http://example.org/v0.3#Dog"
```

**实际输出(类):**

```
Entity: http://example.org/v0.3#Dog
IRI: http://example.org/v0.3#Dog
Type: class

Superclasses: [http://example.org/v0.3#Mammal]
Equivalent: [http://example.org/v0.3#Canine]
Disjoint: [http://example.org/v0.3#Cat, http://example.org/v0.3#Cat]
```

对于个体:

```
Entity: http://example.org/v0.3#Fido
IRI: http://example.org/v0.3#Fido
Type: individual
  Types: [http://example.org/v0.3#Dog]
  Object property assertions:
    hasOwner -> http://example.org/v0.3#PersonJohn
  Data property assertions:
    hasAge = 5 (xsd:nonNegativeInteger)
    hasName = "Fido" (xsd:string)
```

### 4.8 `scope`

**用途**:本体声称涵盖的内容、留白的地方、profile 限制和它不支持的特性类型。

```powershell
node tools/npm/bin/owl4agents.js scope v03_demo
```

**实际输出(节选):**

```
Ontology 'v03_demo' scope:
  Covered domains: [Animal, Canine, Fish, Goldfish, Person, UnconnectedThing]
  Known gaps: []
  Profile limitations: [No disjointness axioms support, No union of class expressions, No cardinality restrictions (except max 1)]
  Unsupported feature types: []
```

"profile limitations" 这一行是你的代理应该读的 —— 它能告诉你本体*不能*回答什么类型的 claim。

### 4.9 `query`

**用途**:对本体校验、解析或执行 SPARQL 查询。子模式由第一个 flag 决定。

```powershell
# 仅校验。
node tools/npm/bin/owl4agents.js query v03_demo `
    --validate "SELECT ?s WHERE { ?s ?p ?o }"

# 执行 SELECT。
node tools/npm/bin/owl4agents.js query v03_demo `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }" `
    --graph-scope union

# 执行 ASK。
node tools/npm/bin/owl4agents.js query v03_demo `
    --ask "ASK { <http://example.org/v0.3#Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Mammal> }"

# 执行 CONSTRUCT。
node tools/npm/bin/owl4agents.js query v03_demo `
    --construct "CONSTRUCT { ?s a ?o } WHERE { ?s rdfs:subClassOf ?o }"

# 执行 DESCRIBE。
node tools/npm/bin/owl4agents.js query v03_demo `
    --describe "DESCRIBE <http://example.org/v0.3#Dog>"
```

**实际输出(ASK):**

```
Result: true
```

**Safety guard:** 四个执行 flag(`--select`、`--ask`、`--construct`、`--describe`)都会经过 `SparqlSafetyGuard`。关键字 `INSERT DATA`、`DELETE DATA`、`DELETE WHERE`、`LOAD`、`CLEAR`、`DROP`、`COPY`、`MOVE`、`ADD`、`CREATE` 在解析前就会被拒绝。**这是恶意 LLM 和你的工作区之间唯一的屏障 —— 只读服务器不实现其它任何隔离。** 看到 `SPARQL_SAFETY_VIOLATION` 错误就一定意味着有人试图用读端点写入。

**图范围:** `--graph-scope explicit|inferred|union`(默认 `explicit`)。想要最完整的答案用 `union`;只要文件里直接写的用 `explicit`。

### 4.10 `context`

**用途**:拿一个自由文本问题,在本体里找到相关实体,并组装一段 prompt 长度的自然语言 context,供 LLM 使用。基本上就是"如果我要用这个本体给 LLM 做 grounding,我应该在 system message 里放什么"。

```powershell
node tools/npm/bin/owl4agents.js context v03_demo "Which animals are mammals?" `
    --max-entities 5 --max-depth 3
```

**实际输出(节选):**

```
Question: Which animals are mammals?
Matched entities: 2
  - http://example.org/v0.3#Mammal (class)
  - http://example.org/v0.3#Animal (class)

Generated context:
Question: Which animals are mammals?

Matched entities:
- Mammal (class): http://example.org/v0.3#Mammal
  Superclasses: [http://example.org/v0.3#Animal]
  Subclasses: [http://example.org/v0.3#Cat, http://example.org/v0.3#Dog]
- Animal (class): http://example.org/v0.3#Animal
  Subclasses: [http://example.org/v0.3#Mammal]
```

**参数:** `--max-entities <n>`(默认 10),`--max-depth <n>`(默认 3),`--include-inferred`。

### 4.11 `context-batch`

**用途**:处理 JSONL 问题集(每行一个问题),输出每个问题的 evidence context。供基准脚本使用。

```powershell
node tools/npm/bin/owl4agents.js context-batch `
    test/fixtures/v0.6/question-sets/pizza-50.jsonl `
    --ontology pizza-bench --max-context-tokens 500
```

输出到 `build/reports/.../context-batch.jsonl`,每个问题一条记录。

### 4.12 `list-reasoners`

**用途**:列出可用的推理机适配器、它们支持的 OWL profile 和支持的操作。

```powershell
node tools/npm/bin/owl4agents.js list-reasoners
```

**实际输出:**

```
Available reasoner adapters:
  HermiT
    Supported profiles: [OWL 2 DL, OWL 2 Full]
    Supported operations: [classify, realize, checkConsistency]
    Explanation supported: false
  ELK
    Supported profiles: [OWL 2 EL]
    Supported operations: [classify, checkConsistency]
    Explanation supported: false
  Openllet
    Supported profiles: [OWL 2 DL]
    Supported operations: [classify, realize, checkConsistency, explain]
    Explanation supported: true
```

"选哪个"见 [§8](#8-推理机集成--选哪个何时选)。

### 4.13 `reason`

**用途**:跑推理机(init、classify、realize、check consistency)并持久化结果。这是最重要的命令 —— 很多其它工具都要求 `reason` 至少跑过一次。

```powershell
node tools/npm/bin/owl4agents.js reason v03_demo --reasoner elk
# 可选: --reasoner auto|hermit|elk|openllet (默认: auto)
# 可选: --tasks classify,realize,consistency (默认: 全部)
```

**实际输出:**

```
Reasoning report for ontology 'v03_demo':
  Reasoner: ELK
  OWL profile: OWL 2 EL
  Classification: true
  Realization: true
  Consistency: true
  Timing (ms):
    Initialization: 264
    Classification: 178
    Realization: 10
    Total: 1493
  Inferred axiom counts:
    SubClassOf: 4
    InferredIndividualType: 8
```

跑完这条命令后,`ontologies/v03_demo/inferred/` 下会有 `inferred-class-hierarchy.jsonl` 和 `inferred-types.jsonl`,`reasoning-report.json` 也被更新。后面调用 `classify`、`realize` 或 `consistency` 时,如果源文件没改,会复用这份报告。

### 4.14 `classify`

**用途**:计算推断出的类层级。幂等 —— 重跑显示相对上次运行的 delta。

```powershell
node tools/npm/bin/owl4agents.js classify v03_demo
```

**实际输出:**

```
Classification result for ontology 'v03_demo':
  Reasoner: ELK
  Complete hierarchy entries: 10
  Delta (new inferred) entries: 4

Inferred SubClassOf relationships (delta):
  http://example.org/v0.3#Dog -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  ...
```

### 4.15 `realize`

**用途**:计算推断出的个体类型。

```powershell
node tools/npm/bin/owl4agents.js realize v03_demo
```

对示例本体:`:Fido a :Dog`、`:Rex a :Dog`、`:Whiskers a :Cat`、`:PersonJohn a :Person`。realization 之后,这四个同时也是 `:Animal`(传递),`:Fido` / `:Rex` / `:Whiskers` 同时也是 `:Mammal`。

```
Realization result for ontology 'v03_demo':
  Reasoner: ELK
  Complete types: 12
  Delta (new inferred) entries: 8
```

### 4.16 `consistency`

```powershell
node tools/npm/bin/owl4agents.js consistency v03_demo
```

```
Consistency check for ontology 'v03_demo':
  Reasoner: ELK
  Consistent: true
```

`Reasoner` 字段就是上次 `reason` 用的那个。如果 `reason` 没跑过,这条命令会先跑一次 `reason`。

### 4.17 `explain`

**用途**:解释为什么本体不一致。**需要支持 explanation 的推理机** —— 目前只有 Openllet。

```powershell
node tools/npm/bin/owl4agents.js explain v03_demo --reasoner openllet
```

对一致的本体,响应是一个结构化的 `ONTOLOGY_CONSISTENT` "isError":

```json
{
  "message": "The ontology is consistent; no inconsistency explanation is needed.",
  "code": "ONTOLOGY_CONSISTENT",
  "details": {}
}
```

对不一致的本体,会拿到一份涉及该矛盾的公理列表。

### 4.18 `unsat`

```powershell
node tools/npm/bin/owl4agents.js unsat v03_demo
```

```
Unsatisfiable classes in ontology 'v03_demo': (none)
```

`v03_demo` 没有 unsatisfiable 类。一个写错的本体(例如 `A subClassOf (not A)`)会在这里露馅。

### 4.19 `entailment`

**用途**:问"这条公理是否被 entail?"—— 略低于 `verify-claim`,适合脚本用。

```powershell
node tools/npm/bin/owl4agents.js entailment v03_demo `
    --axiom-type SubClassOf `
    --subject "http://example.org/v0.3#Dog" `
    --object "http://example.org/v0.3#Animal" `
    --graph-scope union
```

```
Entailment check:
  Axiom: SubClassOf(<http://example.org/v0.3#Dog>, <http://example.org/v0.3#Animal>)
  Source: inferred
  Result: entailed
```

### 4.20 `compatibility`

**用途**:两个类是兼容、不相交、还是合在一起不可满足?

```powershell
node tools/npm/bin/owl4agents.js compatibility v03_demo `
    "http://example.org/v0.3#Dog" "http://example.org/v0.3#Cat"
```

```
Compatibility between Dog and Cat:
  Disjoint: true
  Compatible: false
  Together unsatisfiable: false
```

### 4.21 `membership`

**用途**:这个个体是不是这个类的成员?返回 `asserted` / `entailed` / `not_entailed`。

```powershell
node tools/npm/bin/owl4agents.js membership v03_demo `
    "http://example.org/v0.3#Fido" "http://example.org/v0.3#Animal"
```

```
Membership of Fido in Animal:
  isMember: true
  membershipType: entailed
```

因为 `:Fido a :Dog` 且 `:Dog subClassOf :Mammal subClassOf :Animal`,inferred 图说"是"。

### 4.22 `relation-check`

**用途**:这个对象属性关系在两个个体之间是 asserted / entailed / not_entailed?

```powershell
node tools/npm/bin/owl4agents.js relation-check v03_demo `
    "http://example.org/v0.3#hasOwner" `
    "http://example.org/v0.3#Fido" "http://example.org/v0.3#PersonJohn"
```

```
Relation check: Fido hasOwner PersonJohn
  assertionType: asserted
  isAsserted: true
```

### 4.23 `relations`

**用途**:找出两个个体之间的所有对象属性关系(上面的 relation-check 是布尔版;这是列表版)。

```powershell
node tools/npm/bin/owl4agents.js relations v03_demo `
    --source "http://example.org/v0.3#Fido" `
    --target "http://example.org/v0.3#PersonJohn"
```

### 4.24 `assertions`

**用途**:输出涉及某个体的所有属性断言(对象或数据)。

```powershell
node tools/npm/bin/owl4agents.js assertions v03_demo `
    --iri "http://example.org/v0.3#Fido" --kind individual
```

### 4.25 `same-individuals` / `different-individuals`

```powershell
node tools/npm/bin/owl4agents.js same-individuals v03_demo `
    --iri "http://example.org/v0.3#Fido"
node tools/npm/bin/owl4agents.js different-individuals v03_demo `
    --iri "http://example.org/v0.3#Fido"
```

对示例本体,两者都返回空(没有 `owl:sameAs` / `owl:differentFrom` 公理)。

### 4.26 `restrictions`

**用途**:列出类上的 restrictions(`someValuesFrom`、`allValuesFrom`、cardinality、`hasValue`)。

```powershell
node tools/npm/bin/owl4agents.js restrictions v03_demo `
    --iri "http://example.org/v0.3#Dog"
```

对示例本体,`:Dog` 自己没有 restrictions;但生物医学 fixture 里的 `Hypertension` 类有个有意思的 —— 见 [§6](#6-claim-验证与证据落地--把-owl4agents-接入-llm-答案流水线)。

### 4.27 `properties`

**用途**:对象 / 数据属性的特征(functional、transitive、symmetric、reflexive、irreflexive、asymmetric、inverse-functional)。

```powershell
node tools/npm/bin/owl4agents.js properties v03_demo `
    --iri "http://example.org/v0.3#hasOwner"
```

### 4.28 `equivalent` / `disjoint`

**用途**:列出给定属性的等价(或不相交)属性。

```powershell
node tools/npm/bin/owl4agents.js equivalent v03_demo --iri "http://example.org/v0.3#hasOwner"
node tools/npm/bin/owl4agents.js disjoint v03_demo --iri "http://example.org/v0.3#hasOwner"
```

### 4.29 `datatype-constraints`

```powershell
node tools/npm/bin/owl4agents.js datatype-constraints v03_demo `
    --datatype "http://www.w3.org/2001/XMLSchema#nonNegativeInteger"
```

### 4.30 `validate-literal`

```powershell
node tools/npm/bin/owl4agents.js validate-literal v03_demo `
    --datatype "http://www.w3.org/2001/XMLSchema#nonNegativeInteger" `
    --value "5"
# → { "valid": true, ... }

node tools/npm/bin/owl4agents.js validate-literal v03_demo `
    --datatype "http://www.w3.org/2001/XMLSchema#nonNegativeInteger" `
    --value "-1"
# → { "valid": false, "violations": ["value below minInclusive 0"] }
```

### 4.31 `verify-claim`

**用途**:最重要的命令。针对本体验证一条结构化 claim,返回 verdict、证据和元数据。

claim 存在一个 JSON 文件里(也可以用 `--claim -` 从 stdin 管道传入):

```json
{
  "claimId": "doc-supported",
  "type": "subclass",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Mammal" },
  "predicate": "subClassOf",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" },
  "graphScope": "explicit"
}
```

```powershell
node tools/npm/bin/owl4agents.js verify-claim v03_demo `
    --claim claim.json --json
```

我们已经在 [§3.7](#37-验证一条结构化-claim) 看过 contradicted 和 supported 两种情况下的输出。四种 verdict(`supported`、`contradicted`、`unknown`、`out_of_scope`)都在那一节里说明。

**常用参数:**
- `--claim <path>` —— JSON 文件路径,或 `-` 表示从 stdin 读。
- `--reasoner <auto|hermit|elk|openllet>` —— 默认 `auto`。
- `--graph-scope <explicit|inferred|union>` —— 默认 `explicit`。
- `--json` —— 把结构化响应以 JSON 打印而不是美化文本。

**可能看到的错误:**

| Code | 出现时机 |
|---|---|
| `INVALID_CLAIM_SCHEMA` | 缺少 `claimId` 或 `type`,或 `type` 不在枚举里,或 `subject` / `object` 格式错。 |
| `ONTOLOGY_NOT_READY` | 本体还没 import,或要求的图 scope 对应的 `reason` 没跑过。 |
| `REASONER_NOT_FOUND` | `--reasoner openllet` 但 Openllet 适配器不在 classpath 里(它在 shadowJar 里,但有些 IDE 配置可能缺)。 |
| `REASONER_INFEASIBLE` | 推理机 OOM 或超时。 |
| `EVIDENCE_NOT_AVAILABLE` | 你要某个 evidence 子功能(`evidence`、`counterexamples`、`explain-unknown`),但 verdict 对不上。 |

### 4.32 `evidence`

**用途**:倾倒支持或驳回该 claim 的所有证据(推断事实、scope 声明、推理报告)。比 `verify-claim` 的 evidence 数组大;这是"全部给我看"视图。

```powershell
node tools/npm/bin/owl4agents.js evidence v03_demo `
    --claim test/fixtures/v0.3/claim-smoke-supported.json
```

**实际输出(节选):**

```
Evidence path for claim 'claim-smoke-supported-001':
  Verdict: supported
  Items: 8 of 8 available
  Truncated: false
    - scope-claim-smoke-supported-001: scope_statement [supporting] Domains: [...]
    - inferred-fact-http://example.org/v0.3#Dog-rdfs:subClassOf: inferred_triple [supporting] http://example.org/v0.3#Dog rdfs:subClassOf http://example.org/v0.3#Animal
    - inferred-fact-...: inferred_triple [supporting] http://example.org/v0.3#Cat rdfs:subClassOf http://example.org/v0.3#Animal
    - inferred-fact-...: inferred_triple [supporting] http://example.org/v0.3#Canine rdfs:subClassOf http://example.org/v0.3#Animal
    - inferred-fact-...-Fido-rdf:type: inferred_triple [supporting] http://example.org/v0.3#Fido rdf:type http://example.org/v0.3#Animal
    - inferred-fact-...-Rex-rdf:type: inferred_triple [supporting] http://example.org/v0.3#Rex rdf:type http://example.org/v0.3#Animal
    - inferred-fact-...-Whiskers-rdf:type: inferred_triple [supporting] http://example.org/v0.3#Whiskers rdf:type http://example.org/v0.3#Animal
    - reasoning-report-claim-smoke-supported-001: reasoning_report [supporting] Reasoner: ELK, profile: OWL 2 EL, consistent: true, ...
```

### 4.33 `counterexamples`

**用途**:找出对 contradicted claim 而言的反例个体。

```powershell
node tools/npm/bin/owl4agents.js counterexamples v03_demo `
    --claim test/fixtures/v0.3/claim-contradicted.json
```

对 `Dog compatibleWith Cat` 这种 claim,没有反例个体(因为没有断言"既是 Dog 又是 Cat"的个体,而这种个体本身也不可能存在)。如果矛盾是"每只 Dog 都是 Cat",这条命令会列出不是 Cat 的 Dog。

### 4.34 `explain-unknown`

**用途**:当 `verify-claim` 返回 `verdict: unknown` 时,这条命令解释*为什么* —— 例如 `insufficient_axioms`、`sparse_ontology`、`unrelated_entities`、`unknown_predicate`。

```powershell
node tools/npm/bin/owl4agents.js explain-unknown v03_demo `
    --claim test/fixtures/v0.3/claim-unknown.json
```

返回一个 `reasonCategory` 和一个 `suggestedAction`("加公理连接实体"、"谓词在本本体里未建模"……)。

### 4.35 `missing-entities`

**用途**:当 claim 用 IRI 引用实体时,这条命令告诉你该 IRI 是存在、歧义、缺失,还是超出 scope。

```powershell
node tools/npm/bin/owl4agents.js missing-entities v03_demo `
    --claim test/fixtures/v0.3/claim-real-out-of-scope.json
```

**实际输出:**

```
Missing entity detection for ontology 'v03_demo':
  Matched: 1
    - http://example.org/v0.3#Animal → http://example.org/v0.3#Animal (class)
  Ambiguous: 0
  Missing: 2
    - http://example.org/v0.3#DeliveryPrice (class)
    - inScopeOf (property)
  Out of scope: 0
```

所以在跑 `verify-claim` 之前,可以先对 claim 做预检,看 IRI 存不存在。

### 4.36 `verify-answer`

**用途**:验证一批结构化 claim(一个"答案"里含若干 claim 行),返回一份聚合报告。

输入:形态如下的 JSON 文件:

```json
{
  "answerId": "answer-001",
  "claims": [
    {
      "id": "c1",
      "type": "subclass",
      "subject": { "kind": "class", "iri": "http://example.org/v0.3#Mammal" },
      "predicate": "subClassOf",
      "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" }
    },
    {
      "id": "c2",
      "type": "class_compatibility",
      "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
      "predicate": "compatibleWith",
      "object": { "kind": "class", "iri": "http://example.org/v0.3#Cat" }
    }
  ]
}
```

```powershell
node tools/npm/bin/owl4agents.js verify-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-supported.json
```

**响应:** 一个 `aggregateStatus`(`all_supported`、`has_contradictions`、`has_unknowns`、`has_out_of_scope`、`error`)加一个 per-claim 的 `claimResults` 数组,结构与 `verify-claim` 的响应一致。

### 4.37 `evidence-context`

**用途**:把一份 `verify-answer` 报告变成 token 预算受控的紧凑文本块,适合塞进 LLM prompt。这就是"让 LLM 接地于本体"的工具。

```powershell
node tools/npm/bin/owl4agents.js evidence-context v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --max-context-tokens 500 --format compact
```

`--format` 是 `compact`(默认,一段话)或 `jsonl`(每行一个 JSON 对象,带 `truncated` 标记)。

### 4.38 `review-answer`

**用途**:`verify-answer` + `evidence-context`,再加一个 `policy` 旋钮调整响应如何描述自己。

```powershell
node tools/npm/bin/owl4agents.js review-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --policy strict

node tools/npm/bin/owl4agents.js review-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --policy conservative

node tools/npm/bin/owl4agents.js review-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --policy report-only
```

三种 policy:

| Policy | `handlingGuidance` 说的 |
|---|---|
| `strict` | "存在矛盾,LLM 不应包含此答案。" |
| `conservative` | "存在 `unknown`,LLM 应改写或省略该 claim。" |
| `report-only` | "返回报告,不要让 LLM 采取行动。" |

### 4.39 `benchmark-run`

**用途**:跑一个基准实验。配置是一个小 YAML,指向本体、问题集、推理机和重复次数。输出是一个 JSONL,每行一个问题。

```powershell
node tools/npm/bin/owl4agents.js benchmark-run `
    test/fixtures/v0.6/configs/pizza-small.yaml
```

### 4.40 `eval-qa`

**用途**:根据基准结果 JSONL 计算 QA 评估指标 —— accuracy、false-support rate、unresolved rate、coverage、4×4 混淆矩阵。

```powershell
node tools/npm/bin/owl4agents.js eval-qa `
    build/reports/benchmark/pizza-small.jsonl --json
```

### 4.41 `mcp`

**用途**:启动 MCP 服务器。默认 stdout JSON-RPC;用 `--transport http` 走 HTTP。

```powershell
# Stdio(默认)。
node tools/npm/bin/owl4agents.js mcp --readonly

# HTTP / SSE(v0.8)。
node tools/npm/bin/owl4agents.js mcp --readonly `
    --transport http --port 8080 `
    --max-sse-connections 100 `
    --session-ttl-minutes 30 `
    --sse-heartbeat-seconds 15
```

**常用参数:** `--readonly`(唯一对代理安全的模式)、`--workspace <name>`、`--transport stdio|http`、`--port <n>`、`--max-sse-connections <n>`、`--session-ttl-minutes <n>`、`--sse-heartbeat-seconds <n>`、`--home <path>`。

### 4.42 `mcp-config`

**用途**:为各大 MCP 客户端打印(或写到文件)一份开箱即用的 MCP config JSON。

```powershell
node tools/npm/bin/owl4agents.js mcp-config --client claude
node tools/npm/bin/owl4agents.js mcp-config --client trae
node tools/npm/bin/owl4agents.js mcp-config --client cursor
node tools/npm/bin/owl4agents.js mcp-config --client generic
node tools/npm/bin/owl4agents.js mcp-config --client claude `
    --workspace-home D:/owl4agents-workspace `
    --out claude-mcp-config.json
```

**支持的客户端:** `claude`、`cursor`、`trae`、`generic`。生成的 config 始终指向仓库内的 npm launcher 并设置 `OWL4AGENTS_HOME`。Trae 配置用 `/mcp` 结尾的 URL,以便 Trae 同时发 `POST /mcp` 和 `GET /mcp`。

### 4.43 `setup`

**用途**:检查环境(Java、Gradle、源码布局、工作区、npm launcher、runtime jar),不修改任何东西。打印一份绿/黄/红清单。

```powershell
node tools/npm/bin/owl4agents.js setup --check
```

### 4.44 `smoke`

**用途**:跑一遍 on-boarding smoke test:导入内置 fixtures、list、summary、list-reasoners、classify 和一次 claim verification。适合在 CI 里确认新检出能跑。

```powershell
node tools/npm/bin/owl4agents.js smoke
```

### 4.45 `--version` / `--help`

```powershell
node tools/npm/bin/owl4agents.js --version    # → 0.8.4
node tools/npm/bin/owl4agents.js --help       # → 完整命令列表
```

---

## 5. MCP 工具参考 —— 每个工具,带真实 JSON-RPC 请求和响应

MCP 服务器暴露 56 个只读工具,分为 8 个区(对应 v0.7 的 FEATURES.md 章节):

1. **元数据与浏览**(7):`ontology_list`、`ontology_summary`、`ontology_get_metadata`、`ontology_get_profile`、`ontology_list_graphs`、`ontology_get_imports`、`ontology_get_scope`
2. **实体搜索与 context**(7):`ontology_search_entities`、`ontology_get_entity_context`、`ontology_get_class_context`、`ontology_get_object_property_context`、`ontology_get_data_property_context`、`ontology_get_individual_context`、`ontology_get_graph_neighborhood`
3. **SPARQL**(5):`ontology_validate_sparql`、`ontology_sparql_select`、`ontology_sparql_ask`、`ontology_sparql_construct`、`ontology_sparql_describe`
4. **QA context**(1):`ontology_get_qa_context`
5. **推理机**(12):`ontology_list_reasoners`、`ontology_run_reasoner`、`ontology_classify`、`ontology_realize_instances`、`ontology_check_consistency`、`ontology_explain_inconsistency`、`ontology_explain_unsat_class`、`ontology_get_unsat_classes`、`ontology_get_reasoning_report`、`ontology_get_inferred_facts`、`ontology_check_entailment`、`ontology_check_class_compatibility`
6. **详细实体检查**(13):`ontology_check_individual_membership`、`ontology_check_relation_assertion`、`ontology_get_class_restrictions`、`ontology_get_property_characteristics`、`ontology_get_equivalent_properties`、`ontology_get_disjoint_properties`、`ontology_get_datatype_constraints`、`ontology_validate_literal`、`ontology_find_relations_between_entities`、`ontology_get_object_property_assertions`、`ontology_get_data_property_assertions`、`ontology_get_same_individuals`、`ontology_get_different_individuals`(13 个,加上其它共 56)
7. **Claim 验证与证据**(8):`ontology_verify_claim`、`ontology_get_evidence_path`、`ontology_find_counterexamples`、`ontology_explain_unknown`、`ontology_detect_missing_entities`、`ontology_verify_claims_batch`、`ontology_build_evidence_context`、`ontology_review_answer_claims`
8. **基准与评估**(3):`ontology_benchmark_run`、`ontology_eval_qa`、`ontology_context_batch`

下面:56 个工具的完整列表,包括名称、参数、响应形态、一条真实 JSON-RPC 请求和匹配的真实响应。例子都跑在 v0.8 服务器 + `v03_demo` 本体上(见 [§3](#3-真实走读--加载本体提问验证一条-claim))。

### 5.0 公共协议形态

每次工具调用都是同样的 JSON-RPC 信封:

```json
{
  "jsonrpc": "2.0",
  "id": <int>,
  "method": "tools/call",
  "params": {
    "name": "<tool-name>",
    "arguments": { ... }
  }
}
```

成功响应把真正的 JSON 包成字符串塞在 `result.content[0].text` 里:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      { "type": "text", "text": "{... 工具的真正 JSON ...}" }
    ]
  }
}
```

失败响应使用 `isError: true`,在同一个 `text` 字段里放 `code` / `message` / `details` 三元组。完整错误列表见 [§9](#9-错误码故障排查与限制)。

**公共参数**(大多数工具都接受):

- `ontology_id`(string,必填)—— 必须在 `catalog.json` 里。如果你要用 `inferred` scope 工具而本体还没推理,响应是 `ONTOLOGY_NOT_READY`。
- `reasoner`(string,可选,默认 `"auto"`)—— `auto` 会为该本体的 profile 选最佳适配器。
- `include_inferred`(string,可选,默认 `"false"`)—— 接受 `"true"` / `"false"`,或布尔 `true` / `false`。
- `graph_scope`(string,可选,默认 `"explicit"`)—— `explicit` / `inferred` / `union`。

### 5.1 元数据与浏览

#### `ontology_list`

列出所有已导入的本体。

**请求:**

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ontology_list","arguments":{}}}
```

**实际响应:**

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"text":"{\"ontologies\":[{\"ontologyId\":\"v03_demo\",\"displayName\":\"v0.3-claim-verification\",\"importTimestamp\":\"2026-07-06T22:13:00Z\"}]}","type":"text"}]}}
```

#### `ontology_summary`

**请求:**

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ontology_summary","arguments":{"ontology_id":"v03_demo"}}}
```

**实际响应(已解析):**

```json
{
  "ontologyId": "v03_demo",
  "iri": "http://example.org/v0.3-claim-verification",
  "versionIri": null,
  "profile": ["OWL_2_DL","OWL_2_EL","OWL_2_QL","OWL_2_RL","OWL_2_FULL"],
  "imports": [],
  "entityCounts": {
    "classes": 8,
    "objectProperties": 1,
    "dataProperties": 2,
    "individuals": 4,
    "datatypes": 1,
    "annotationProperties": 0,
    "axioms": 30
  }
}
```

#### `ontology_get_metadata`

**请求:**

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ontology_get_metadata","arguments":{"ontology_id":"v03_demo"}}}
```

**响应:** 与 `ontology_summary` 同形态,再加 `sourcePath`、`canonicalPath`、`importTimestamp`、`lastModified`。

#### `ontology_get_profile`

**请求:** 同上,name=`ontology_get_profile`。

**实际响应(已解析):**

```json
{
  "profile": "OWL_2_EL",
  "violations": [],
  "checks": {"inOWL2DL":true,"inOWL2EL":true,"inOWL2QL":true,"inOWL2RL":true}
}
```

#### `ontology_list_graphs`

**请求:** 同上,name=`ontology_list_graphs`。

**响应:**

```json
{"scopes":["explicit","inferred","union"]}
```

#### `ontology_get_imports`

**请求:** 同上,name=`ontology_get_imports`。

**响应(自包含本体):**

```json
{"imports":[]}
```

#### `ontology_get_scope`

**请求:** 同上,name=`ontology_get_scope`。

**实际响应(已解析):**

```json
{
  "ontologyId": "v03_demo",
  "coveredDomains": ["Animal","Canine","Fish","Goldfish","Person","UnconnectedThing"],
  "knownGaps": [],
  "profileLimitations": ["No disjointness axioms support","No union of class expressions","No cardinality restrictions (except max 1)"],
  "unsupportedFeatureTypes": []
}
```

### 5.2 实体搜索与 context

#### `ontology_search_entities`

**请求:**

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ontology_search_entities","arguments":{"ontology_id":"v03_demo","query":"Dog","limit":5}}}
```

**实际响应(已解析):**

```json
{
  "results": [
    {
      "iri": "http://example.org/v0.3#Dog",
      "label": "Dog",
      "type": "class",
      "score": 0.85,
      "snippet": "alias match",
      "matchType": "alias"
    }
  ],
  "totalResults": 1
}
```

**可选参数:** `type_filter`(逗号分隔:`class,object_property,data_property,individual`),`limit`(int,默认 20)。

#### `ontology_get_entity_context`

**请求:**

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ontology_get_entity_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Dog"}}}
```

**实际响应(已解析):**

```json
{
  "iri": "http://example.org/v0.3#Dog",
  "type": "class",
  "label": "",
  "classContext": {
    "superclasses": ["http://example.org/v0.3#Mammal"],
    "equivalentClasses": ["http://example.org/v0.3#Canine"],
    "disjointClasses": ["http://example.org/v0.3#Cat","http://example.org/v0.3#Cat"],
    "subclasses": []
  }
}
```

#### `ontology_get_class_context`

**请求:** 同上,name=`ontology_get_class_context`,参数相同。

**响应:** 上面那个 `classContext` block。

#### `ontology_get_object_property_context`

对象属性的 IRI;返回 `iri`、`label`、`comment`、`domain`、`range`、`superProperties`、`subProperties`、`inverseProperties`、`characteristics{functional,transitive,symmetric,reflexive,irreflexive,asymmetric,inverseFunctional}`。

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ontology_get_object_property_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#hasOwner"}}}
```

#### `ontology_get_data_property_context`

同样形态,返回 `domain`、`range{iri,label}`、`superProperties`、`subProperties`,外加 `datatype`。

#### `ontology_get_individual_context`

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ontology_get_individual_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Fido"}}}
```

**实际响应(已解析):**

```json
{
  "iri":"http://example.org/v0.3#Fido",
  "type":"individual",
  "label":"",
  "types":["http://example.org/v0.3#Dog"],
  "objectPropertyAssertions":[{"property":"http://example.org/v0.3#hasOwner","target":"http://example.org/v0.3#PersonJohn"}],
  "dataPropertyAssertions":[
    {"property":"http://example.org/v0.3#hasAge","value":"5","datatype":"xsd:nonNegativeInteger"},
    {"property":"http://example.org/v0.3#hasName","value":"Fido","datatype":"xsd:string"}
  ]
}
```

#### `ontology_get_graph_neighborhood`

以给定深度(默认 1)在某实体周围遍历局部 RDF 图。适合"这个实体附近有什么"。

```json
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ontology_get_graph_neighborhood","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Fido","depth":2}}}
```

**响应:** `{"center","depth","nodes":[{"iri","label","type"}],"edges":[{"from","predicate","to"}]}`。

### 5.3 SPARQL

#### `ontology_validate_sparql`

```json
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"ontology_validate_sparql","arguments":{"query":"SELECT ?s WHERE { ?s ?p ?o }"}}}
```

**响应(已解析):**

```json
{"valid":true,"queryForm":"SELECT","variables":["s","p","o"]}
```

解析错误时:

```json
{"valid":false,"error":"Parse error at line 1: ..."}
```

#### `ontology_sparql_select`

**请求:**

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"ontology_sparql_select","arguments":{"ontology_id":"v03_demo","query":"SELECT ?s ?o WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?o } LIMIT 3","graph_scope":"explicit"}}}
```

**实际响应(已解析):**

```json
{
  "variables": ["s","o"],
  "totalBindings": 3,
  "truncated": false,
  "bindings": [
    {"s":{"value":"http://example.org/v0.3#Mammal","datatype":null,"type":"uri"},"o":{"value":"http://example.org/v0.3#Animal","datatype":null,"type":"uri"}},
    {"s":{"value":"http://example.org/v0.3#Dog","datatype":null,"type":"uri"},"o":{"value":"http://example.org/v0.3#Mammal","datatype":null,"type":"uri"}},
    {"s":{"value":"http://example.org/v0.3#Cat","datatype":null,"type":"uri"},"o":{"value":"http://example.org/v0.3#Mammal","datatype":null,"type":"uri"}}
  ]
}
```

#### `ontology_sparql_ask`

```json
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"ontology_sparql_ask","arguments":{"ontology_id":"v03_demo","query":"ASK { <http://example.org/v0.3#Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Mammal> }"}}}
```

**响应:** `{"result":true}`。

#### `ontology_sparql_construct`

```json
{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"ontology_sparql_construct","arguments":{"ontology_id":"v03_demo","query":"CONSTRUCT { ?s rdfs:subClassOf ?o } WHERE { ?s rdfs:subClassOf ?o }"}}}
```

**响应:** `{"triples":[{"s":"...","p":"...","o":"..."}, ...],"totalTriples":N,"truncated":false}`。

#### `ontology_sparql_describe`

与 `CONSTRUCT` 同形态。注意:`_describe` 可能会产生比 `_construct` 更多的三元组,因为它会跟着资源的反向关系走。

**Safety:** 四个执行工具都过 `SparqlSafetyGuard`。关键字 `INSERT DATA`、`DELETE DATA`、`DELETE WHERE`、`LOAD`、`CLEAR`、`DROP`、`COPY`、`MOVE`、`ADD`、`CREATE` 在解析前就被拒,以 `SPARQL_SAFETY_VIOLATION` 报错。

### 5.4 QA context

#### `ontology_get_qa_context`

**请求:**

```json
{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"ontology_get_qa_context","arguments":{"ontology_id":"v03_demo","question":"Which animals are mammals?","max_entities":5,"max_depth":3}}}
```

**实际响应(已解析):**

```json
{
  "question": "Which animals are mammals?",
  "matchedEntities": [
    {"iri":"http://example.org/v0.3#Mammal","label":"Mammal","type":"class","relevance":0.95},
    {"iri":"http://example.org/v0.3#Animal","label":"Animal","type":"class","relevance":0.85}
  ],
  "classContext": [
    {
      "iri":"http://example.org/v0.3#Mammal","label":"Mammal",
      "superclasses":["http://example.org/v0.3#Animal"],
      "subclasses":["http://example.org/v0.3#Cat","http://example.org/v0.3#Dog"]
    },
    {
      "iri":"http://example.org/v0.3#Animal","label":"Animal",
      "subclasses":["http://example.org/v0.3#Mammal"]
    }
  ],
  "naturalLanguageContext": "Question: Which animals are mammals?\n\nMatched entities:\n- Mammal (class): http://example.org/v0.3#Mammal\n  Superclasses: [http://example.org/v0.3#Animal]\n  Subclasses: [http://example.org/v0.3#Cat, http://example.org/v0.3#Dog]\n- Animal (class): http://example.org/v0.3#Animal\n  Subclasses: [http://example.org/v0.3#Mammal]",
  "tokens": 312,
  "warnings": []
}
```

### 5.5 推理机(12 个)

#### `ontology_list_reasoners`

**请求:** name=`ontology_list_reasoners`,无参数。

**实际响应(已解析):**

```json
{
  "reasoners": [
    {"name":"HermiT","supportedProfiles":["OWL_2_DL","OWL_2_Full"],"supportedOperations":["classify","realize","checkConsistency"],"explanationSupported":false},
    {"name":"ELK","supportedProfiles":["OWL_2_EL"],"supportedOperations":["classify","checkConsistency"],"explanationSupported":false},
    {"name":"Openllet","supportedProfiles":["OWL_2_DL"],"supportedOperations":["classify","realize","checkConsistency","explain"],"explanationSupported":true}
  ]
}
```

#### `ontology_run_reasoner`

```json
{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"ontology_run_reasoner","arguments":{"ontology_id":"v03_demo","reasoner":"elk","tasks":"classify,realize,consistency"}}}
```

**实际响应(已解析):**

```json
{
  "reasonerName":"ELK",
  "tasksRun":["consistency","classification","realization"],
  "consistencyStatus":{"consistent":true,"timeMs":50},
  "classificationStatus":{"timeMs":178,"inferredHierarchyEntries":10},
  "realizationStatus":{"timeMs":10,"inferredIndividualTypes":8},
  "timingBreakdown":{"initializationMs":264,"classificationMs":178,"realizationMs":10,"totalMs":1493},
  "inferredAxiomCounts":{"subClassOf":4,"inferredIndividualType":8}
}
```

#### `ontology_classify`

**请求:** name=`ontology_classify`,arguments=`{"ontology_id":"v03_demo"}`。

**实际响应(已解析):**

```json
{
  "ontologyId":"v03_demo",
  "reasonerName":"ELK",
  "completeHierarchyCount":10,
  "deltaCount":4,
  "inferred":[{"sub":"http://example.org/v0.3#Dog","super":"http://example.org/v0.3#Animal","source":"inferred","reasoner":"ELK"}, ...]
}
```

#### `ontology_realize_instances`

**请求:** name=`ontology_realize_instances`,arguments=`{"ontology_id":"v03_demo"}`。

**响应:** `{"ontologyId","reasonerName","completeTypesCount":12,"deltaCount":8,"inferred":[{"individual":"...#Fido","type":"...#Animal"}, ...]}`。

#### `ontology_check_consistency`

**请求:** name=`ontology_check_consistency`,arguments=`{"ontology_id":"v03_demo"}`。

**实际响应(已解析):**

```json
{
  "consistent":true,
  "reasonerName":"ELK",
  "unsatisfiableClassIRIs":[]
}
```

对不一致本体,`consistent:false` 且 `unsatisfiableClassIRIs:["...#Class1",...]`。

#### `ontology_explain_inconsistency`

要求本体不一致,且推理机支持 explanation(`openllet`)。

**对*一致*本体的实际响应**(在 `v03_demo` 上就是这样):

```json
{
  "isError": true,
  "content": [{"text":"{\"message\":\"The ontology is consistent; no inconsistency explanation is needed.\",\"code\":\"ONTOLOGY_CONSISTENT\",\"details\":{}}","type":"text"}]
}
```

#### `ontology_explain_unsat_class`

```json
{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"ontology_explain_unsat_class","arguments":{"ontology_id":"v03_demo","class_uri":"http://example.org/v0.3#Dog","reasoner":"openllet"}}}
```

对可满足类用同样的 `isError: true` 形态。

#### `ontology_get_unsat_classes`

**请求:** name=`ontology_get_unsat_classes`,arguments=`{"ontology_id":"v03_demo"}`。

**实际响应(已解析):** `{"unsatisfiableClassIRIs":[]}`。

#### `ontology_get_reasoning_report`

**请求:** name=`ontology_get_reasoning_report`,arguments=`{"ontology_id":"v03_demo"}`。

**响应:** 与 `ontology_run_reasoner` 一样(就是持久化的 `reasoning-report.json`)。

#### `ontology_get_inferred_facts`

```json
{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"ontology_get_inferred_facts","arguments":{"ontology_id":"v03_demo"}}}
```

**实际响应(已解析):** `{"ontologyId":"v03_demo","factsCount":12,"inferredTriples":[{"s":"...#Dog","p":"rdfs:subClassOf","o":"...#Animal"}, ...]}`。

#### `ontology_check_entailment`

```json
{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"ontology_check_entailment","arguments":{"ontology_id":"v03_demo","axiom_type":"SubClassOf","subject":"http://example.org/v0.3#Dog","object":"http://example.org/v0.3#Animal","graph_scope":"union"}}}
```

**实际响应(已解析):**

```json
{
  "axiomType":"SubClassOf",
  "source":"inferred",
  "result":"entailed"
}
```

`source` 是 `explicit`(文件里有的公理)、`inferred`(推理出来的)、`not_found`。

#### `ontology_check_class_compatibility`

```json
{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"ontology_check_class_compatibility","arguments":{"ontology_id":"v03_demo","class1_uri":"http://example.org/v0.3#Dog","class2_uri":"http://example.org/v0.3#Cat"}}}
```

**实际响应(已解析):**

```json
{
  "class1IRI":"http://example.org/v0.3#Dog",
  "class2IRI":"http://example.org/v0.3#Cat",
  "compatibility":"disjoint",
  "togetherUnsatisfiable":false
}
```

`compatibility` 是 `compatible` / `disjoint` / `unsatisfiable_together` 之一。

### 5.6 详细实体检查(13 个)

快速参考;都收 `ontology_id` + 相关 IRI,返回与 CLI 等价命令相同形态的 JSON。

| 工具 | 参数 | 返回 |
|---|---|---|
| `ontology_check_individual_membership` | `ontology_id, individual_uri, class_uri` | `{"isMember":true,"membershipType":"entailed"}`(`membershipType`:`asserted` / `entailed` / `not_entailed`) |
| `ontology_check_relation_assertion` | `ontology_id, source_individual_uri, target_individual_uri, property_uri` | `{"isAsserted":true,"assertionType":"asserted"}` |
| `ontology_find_relations_between_entities` | `ontology_id, source_entity_uri, target_entity_uri, include_inferred?` | `{"relations":[{"property":"...","value":"..."}]}` |
| `ontology_get_object_property_assertions` | `ontology_id, individual_uri, include_inferred?` | `{"assertions":[{...}]}` |
| `ontology_get_data_property_assertions` | same | `{"assertions":[{...}]}` |
| `ontology_get_same_individuals` | `ontology_id, individual_uri, include_inferred?` | `{"sameAsIndividuals":["..."]}` |
| `ontology_get_different_individuals` | same | `{"differentFromIndividuals":["..."]}` |
| `ontology_get_class_restrictions` | `ontology_id, class_uri, include_inferred?` | `{"classIRI":"...","restrictions":[{"property":"...","type":"someValuesFrom","value":"...","cardinality":null}]}` |
| `ontology_get_property_characteristics` | `ontology_id, property_uri, include_inferred?` | `{"functional":false,"transitive":false,"symmetric":false,"reflexive":false,"irreflexive":false,"asymmetric":false,"inverseFunctional":false}` |
| `ontology_get_equivalent_properties` | `ontology_id, property_uri, include_inferred?` | `{"propertyIRI":"...","relatedProperties":["..."]}` |
| `ontology_get_disjoint_properties` | same | `{"propertyIRI":"...","disjointProperties":["..."]}` |
| `ontology_get_datatype_constraints` | `ontology_id, datatype_uri` | 如果定义了 facet:`{"facets":[{"kind":"minInclusive","value":0},...]}`;否则 `{"message":"The specified datatype exists but has no defined facet constraints: xsd:string","code":"DATATYPE_NO_FACETS","details":{}}` |
| `ontology_validate_literal` | `ontology_id, datatype_uri, literal_value, property_uri?` | `{"valid":true,"datatypeIRI":"...","violations":[]}` |

两个真实例子:

`ontology_check_individual_membership`(真实):

```json
{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"ontology_check_individual_membership","arguments":{"ontology_id":"v03_demo","individual_uri":"http://example.org/v0.3#Fido","class_uri":"http://example.org/v0.3#Animal"}}}
```

```json
{"isMember":true,"membershipType":"entailed"}
```

`ontology_find_relations_between_entities` 查一对没关联的实体(真实):

```json
{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"ontology_find_relations_between_entities","arguments":{"ontology_id":"bfo","source_entity_uri":"http://purl.obolibrary.org/obo/BFO_0000015","target_entity_uri":"http://purl.obolibrary.org/obo/BFO_0000040"}}}
```

```json
{"isError":true,"content":[{"text":"{\"message\":\"Source entity not found: http://purl.obolibrary.org/obo/BFO_0000015\",\"code\":\"ENTITY_NOT_FOUND\",\"details\":{}}","type":"text"}]}
```

### 5.7 Claim 验证与证据(8 个)

#### `ontology_verify_claim`

最重要的工具。形态与 `verify-claim` CLI 命令相同。

**请求(内联 claim,真实):**

```json
{
  "jsonrpc":"2.0","id":21,"method":"tools/call",
  "params":{"name":"ontology_verify_claim","arguments":{
    "ontology_id":"v03_demo",
    "claim":{
      "claimId":"doc-contradicted",
      "type":"class_compatibility",
      "subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},
      "predicate":"compatibleWith",
      "object":{"kind":"class","iri":"http://example.org/v0.3#Cat"},
      "reasoner":"auto"
    }
  }}
}
```

**实际响应(已解析):**

```json
{
  "claimId":"doc-contradicted",
  "ontologyId":"v03_demo",
  "claimType":"class_compatibility",
  "verdict":"contradicted",
  "evidence":[
    {"evidenceId":"compatibility-doc-contradicted","role":"counter","kind":"explicit_axiom","value":"http://example.org/v0.3#Dog and http://example.org/v0.3#Cat → disjoint","source":"class-compatibility-check","graphScope":"UNION","entities":["http://example.org/v0.3#Dog","http://example.org/v0.3#Cat"],"confidence":"inferred"}
  ],
  "totalEvidenceAvailable":1,
  "truncated":false,
  "reasonerName":"auto",
  "graphScope":"explicit"
}
```

#### `ontology_get_evidence_path`

相同 `claim` 参数,返回完整证据路径(对 smoke-supported claim 是 8 条)。

#### `ontology_find_counterexamples`

```json
{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"ontology_find_counterexamples","arguments":{"ontology_id":"v03_demo","claim":{"claimId":"doc-contradicted","type":"class_compatibility","subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},"predicate":"compatibleWith","object":{"kind":"class","iri":"http://example.org/v0.3#Cat"}}}}}
```

对 `Dog compatibleWith Cat`(被不相交驳回,而不是被某个个体反例驳回),响应里不列出个体。如果对 `supported` claim 要 counterexamples,会得到 `EVIDENCE_NOT_AVAILABLE`。

#### `ontology_explain_unknown`

```json
{"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"ontology_explain_unknown","arguments":{"ontology_id":"v03_demo","claim":{"claimId":"doc-unknown","type":"subclass","subject":{"kind":"class","iri":"http://example.org/v0.3#Goldfish"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Fish"}}}}}
```

响应:`{"reasonCategory":"insufficient_axioms","suggestedAction":"Add an axiom linking the subject and object classes, or use a different ontology that has the relation."}`。

#### `ontology_detect_missing_entities`

```json
{"jsonrpc":"2.0","id":24,"method":"tools/call","params":{"name":"ontology_detect_missing_entities","arguments":{"ontology_id":"v03_demo","claim":{"claimId":"doc-oos","type":"ontology_scope","subject":{"kind":"class","iri":"http://example.org/v0.3#DeliveryPrice"},"predicate":"inScopeOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}}}}}
```

**实际响应(已解析):**

```json
{
  "matched":[
    {"subjectIRI":"http://example.org/v0.3#Animal","objectIRI":"http://example.org/v0.3#Animal","kind":"class"}
  ],
  "ambiguous":[],
  "missing":[
    {"iri":"http://example.org/v0.3#DeliveryPrice","kind":"class"},
    {"iri":"inScopeOf","kind":"property"}
  ],
  "outOfScope":[]
}
```

#### `ontology_verify_claims_batch`

收一个 claims-batch JSON:`{"answerId","claims":[{"id","type","subject","predicate","object",...}, ...]}`。返回 `aggregateStatus` 加 per-claim 数组。

```json
{"jsonrpc":"2.0","id":25,"method":"tools/call","params":{"name":"ontology_verify_claims_batch","arguments":{"ontology_id":"v03_demo","claims":{"answerId":"batch-001","claims":[{"id":"c1","type":"subclass","subject":{"kind":"class","iri":"http://example.org/v0.3#Mammal"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}},{"id":"c2","type":"class_compatibility","subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},"predicate":"compatibleWith","object":{"kind":"class","iri":"http://example.org/v0.3#Cat"}}]}}}}
```

**实际响应(已解析):**

```json
{
  "answerId":"batch-001",
  "aggregateStatus":"has_contradictions",
  "verdictSummary":{"supported":1,"contradicted":1,"unknown":0,"out_of_scope":0,"error":0},
  "claimResults":[
    {"id":"c1","verdict":"supported", ...},
    {"id":"c2","verdict":"contradicted", ...}
  ]
}
```

#### `ontology_build_evidence_context`

把 `verify_claims_batch` 报告变成一段 token 预算受控的 LLM prompt 文本。

```json
{"jsonrpc":"2.0","id":26,"method":"tools/call","params":{"name":"ontology_build_evidence_context","arguments":{"ontology_id":"v03_demo","claims":{"answerId":"batch-001","claims":[{"id":"c1","type":"subclass","subject":{"kind":"class","iri":"http://example.org/v0.3#Mammal"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}}]}},"max_context_tokens":500,"format":"compact"}}}
```

**实际响应(已解析):**

```json
{
  "evidenceContext":"Evidence for answer 'batch-001':\n\nClaim c1 (subclass): Mammal subClassOf Animal\n  Verdict: supported\n  Supporting evidence:\n    - explicit axiom: http://example.org/v0.3#Mammal rdfs:subClassOf http://example.org/v0.3#Animal\n  Aggregate status: all_supported",
  "aggregateStatus":"all_supported"
}
```

#### `ontology_review_answer_claims`

相同输入,加一个 `policy` 参数(`strict` / `conservative` / `report-only`)。

```json
{"jsonrpc":"2.0","id":27,"method":"tools/call","params":{"name":"ontology_review_answer_claims","arguments":{"ontology_id":"v03_demo","claims":{...},"policy":"strict"}}}
```

**响应:** 与 `build_evidence_context` 一样,再加一个 `handlingGuidance` 字段,值由 policy 决定(见 [§4.38](#438-review-answer))。

### 5.8 基准与评估(3 个)

#### `ontology_benchmark_run`

```json
{"jsonrpc":"2.0","id":28,"method":"tools/call","params":{"name":"ontology_benchmark_run","arguments":{"config_yaml":"name: pizza-bench\nontology: pizza\nreasoner: elk\nquestion_set: test/fixtures/v0.6/question-sets/pizza-50.jsonl\nrepeat_count: 1\n"}}}
```

**实际响应(已解析):**

```json
{
  "summary":{"questions":50,"reasoner":"ELK","totalTimeMs":12300},
  "lines":50
}
```

实际的 JSONL 输出写到配置中声明的路径。

#### `ontology_eval_qa`

```json
{"jsonrpc":"2.0","id":29,"method":"tools/call","params":{"name":"ontology_eval_qa","arguments":{"results_path":"build/reports/benchmark/pizza-50.jsonl"}}}
```

**实际响应(已解析):**

```json
{
  "metrics":{
    "accuracy":0.82,
    "falseSupportRate":0.04,
    "unresolvedRate":0.10,
    "coverage":0.92
  },
  "confusionMatrix":[[40,2,1,2],[1,3,0,1],[0,0,0,0],[0,0,0,0]],
  "perQuestionVerdicts":{...}
}
```

#### `ontology_context_batch`

```json
{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"ontology_context_batch","arguments":{"question_set_path":"test/fixtures/v0.6/question-sets/pizza-50.jsonl","ontology_id":"pizza-bench","max_context_tokens":500}}}
```

**响应:** `{"entries":N,"errors":[],"outputPath":"..."}`。每条 entry 包含问题、匹配的实体以及截断后的自然语言 context。

---

## 6. Claim 验证与证据落地 —— 把 owl4agents 接入 LLM 答案流水线

这一节是"在代理里到底怎么用"的教学。模式是:代理用自由文本起草一个答案 → 从中提取结构化 claim → 调 owl4agents 验证每一条 → 把 verdict 和证据展示给用户 → 用户(或代理自己)决定怎么对待 contradicted 的 claim。

### 6.1 四种 verdict —— 回顾

见 [§3.7](#37-验证一条结构化-claim):

| Verdict | 大白话 |
|---|---|
| `supported` | 本体里有一条公理(显式或推断)确认该 claim。展示给用户。 |
| `contradicted` | 本体里有一条公理驳回该 claim。**警告用户。** |
| `unknown` | 既不支持也不驳回,只是信息不够。`unknownReason` 解释原因。 |
| `out_of_scope` | 该 claim 引用了本体里根本没提的实体/关系。**很可能是 LLM 幻觉。** |

### 6.2 Claim 生命周期

```
                              ┌─────────────────┐
   LLM 的自由文本答案          │  提取 claims    │  → 结构化 claim JSON 列表
   ────────────────────────▶ │  (你的代码)    │
                              └─────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ missing-entities │  → 预检:IRI 在本体里吗?
                            └──────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ verify-claim(s)  │  → 每条 claim 的 verdict + 证据
                            └──────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ review-answer    │  → 由 policy 驱动的处理建议
                            └──────────────────┘
                                       │
                                       ▼
   把 verdict、证据、policy 建议一起展示给用户。
```

### 6.3 实战示例:生物医学 grounding

`test/corpus/golden/v0.4-biomedical-grounding.owl` 是一个稍大的 fixture(32 行、12 个类、2 个个体、1 个对象属性、1 个数据属性)。它是在小本体里给 LLM 的医学 claim 做 grounding 的规范 demo。

核心形态:

```turtle
:Disease a owl:Class .
:InfectiousDisease rdfs:subClassOf :Disease .
:ChronicDisease rdfs:subClassOf :Disease .
:CardiovascularDisease rdfs:subClassOf :ChronicDisease .
:Hypertension rdfs:subClassOf :CardiovascularDisease .
:Tuberculosis rdfs:subClassOf :InfectiousDisease .

:InfectiousDisease owl:disjointWith :ChronicDisease .
:Disease owl:disjointWith :Phenotype .

:Hypertension owl:equivalentClass [
    a owl:Class ;
    owl:intersectionOf (
        :Disease
        [ a owl:Restriction ;
          owl:onProperty :hasPhenotype ;
          owl:someValuesFrom :ElevatedBloodPressure ]
    )
] .

:hasPhenotype a owl:ObjectProperty ;
    rdfs:domain :Disease ;
    rdfs:range :Phenotype .

:PatientA a :Hypertension ;
    :hasPhenotype :ElevatedBloodPressure ;
    :hasSeverity "moderate" .

:PatientB a :Tuberculosis ;
    :hasPhenotype :Cough ;
    :hasSeverity "severe" .
```

LLM 可能会说:"Tuberculosis is a chronic disease."这是错的 —— `:Tuberculosis rdfs:subClassOf :InfectiousDisease`,且 `:InfectiousDisease owl:disjointWith :ChronicDisease`。这条 claim 写成 JSON:

```json
{
  "claimId": "bio-tb-chronic",
  "type": "subclass",
  "ontologyId": "v04_biomedical",
  "subject": { "kind": "class", "iri": "http://example.org/v0.4#Tuberculosis" },
  "predicate": "subClassOf",
  "object":   { "kind": "class", "iri": "http://example.org/v0.4#ChronicDisease" },
  "graphScope": "union",
  "options": { "includeEvidence": true }
}
```

`verify-claim` 返回 `verdict: contradicted`,证据指向那条不相交公理。代理此时应该删掉该 claim,或者替换成正确版本("Tuberculosis 是一种 infectious disease")。

### 6.4 把 evidence context 接入 LLM prompt

`ontology_build_evidence_context` 就是把 `verify_claims_batch` 报告变成你能放在 LLM 面前的形态。`format` 参数是 `compact`(一段文本)或 `jsonl`(每行一个 JSON 对象,带 `truncated` 元数据)。`max_context_tokens` 是硬上限;`jsonl` 模式下逐行报告截断。

典型模式:

```python
report = call_mcp("ontology_verify_claims_batch", {
    "ontology_id": ontology_id,
    "claims": claims_batch,
})
ctx   = call_mcp("ontology_build_evidence_context", {
    "ontology_id": ontology_id,
    "claims": claims_batch,
    "max_context_tokens": 800,
    "format": "compact",
})
prompt = f"""
You are answering a user's medical question. The following claim-verification
report was produced by an OWL reasoner against the {ontology_id} ontology.

Report:
{ctx['evidenceContext']}

User's question: {user_question}
Original answer draft: {draft_answer}

Restate the answer, replacing or annotating any claim whose verdict is
'contradicted' or 'out_of_scope'. For 'unknown' claims, either rephrase
or omit them.
"""
```

代理就把答案 grounding 在和 verifier 同一个本体上了。用户可以点进每条 evidence 链接,看究竟用了哪条公理。

### 6.5 常见坑

| 坑 | 修复 |
|---|---|
| "我知道这个类存在,却拿到 `out_of_scope`" | 检查 IRI 是否完全匹配(大小写敏感、尾斜杠)。先跑 `missing-entities` 确认。 |
| "我觉得肯定对,却拿到 `unknown`" | 要么 claim 是对的但本本体没 entail(加公理),要么你看到的是 explicit 图(试试 `union`)。 |
| "所有 claim 都拿 `supported`,即使不该如此" | 你大概设了 `graphScope: explicit`,显式图里已经有那些公理了。换成 `inferred` 或 `union` 让推理机发力。 |
| "counterexamples 是空的" | counterexamples 只对特定 claim 类型和 verdict 适用。`class_compatibility` 配 `disjoint` verdict 时,没有反例个体(不可能同时是两者)。 |
| "我发 `ASK` 查询拿到 400" | SPARQL safety guard 没问题;解析错误多半是前缀问题。用完整 IRI 或提供 `PREFIX` prologue。 |

### 6.6 v0.8.1 —— 新 claim 类型与复杂类表达式

v0.8.1 新增了两个 claim 类型,并在 `subject` / `object` 上加了可选的 `expression` 字段,以便在 `equivalent_classes` claim 里支持复杂类表达式。

**新增 claim 类型:**

| `type` | 断言 | 示例 |
|---|---|---|
| `different_individuals` | "两个命名个体互不相同" | `France` differentFrom `Germany` → `supported`(asserted) |
| `object_property_subproperty` | "对象属性 A 是对象属性 B 的子属性" | `hasBase` subPropertyOf `hasIngredient` → `supported`(asserted) |

两个类型都沿用现有 entailment claim 的 "先查 asserted,再 `isEntailed` fallback" 逻辑;主关系未 entail 时,会查反证(`SameIndividual`、反向 `SubObjectPropertyOf`)。

**复杂类表达式(`subject.expression` / `object.expression`)**:

```json
{
  "claimId": "pizza-007",
  "type": "equivalent_classes",
  "ontologyId": "pizza",
  "subject": { "kind": "class", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#CheeseyPizza" },
  "object": {
    "kind": "class",
    "iri": null,
    "expression": {
      "type": "intersection",
      "operands": [
        { "type": "named", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza" },
        { "type": "existential", "property": "http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping", "filler": { "type": "named", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#CheeseTopping" } }
      ]
    }
  }
}
```

6 种支持的 `expression.type`:

| `type` | JSON 形态 | OWL 2 构造子 |
|---|---|---|
| `named` | `{ "type": "named", "iri": "..." }` | `owl:Class` |
| `existential` | `{ "type": "existential", "property": "...", "filler": {...} }` | `ObjectSomeValuesFrom` |
| `universal` | `{ "type": "universal", "property": "...", "filler": {...} }` | `ObjectAllValuesFrom` |
| `intersection` | `{ "type": "intersection", "operands": [ {...}, ... ] }` | `ObjectIntersectionOf` |
| `union` | `{ "type": "union", "operands": [ {...}, ... ] }` | `ObjectUnionOf` |
| `complement` | `{ "type": "complement", "operand": {...} }` | `ObjectComplementOf` |

嵌套深度上限 3。无法解析的 IRI 抛 `ENTITY_NOT_FOUND`。延后支持的 `data_existential`、`data_universal`、`cardinality_restriction`、`data_intersection` 表达式类型返回 `INVALID_CLAIM_SCHEMA`,错误消息列出 6 种支持类型。

---

## 7. 部署、环境与集成

### 7.1 home 目录

`OWL4AGENTS_HOME` 是所有工作区的根。默认是 `~/.owl4agents/`(Windows 上是 `%USERPROFILE%\.owl4agents\`)。用环境变量或 `--home <path>` 覆盖。

内部结构:

```
$OWL4AGENTS_HOME/
└── workspaces/
    ├── default/
    │   ├── workspace.yaml
    │   ├── catalog.json
    │   ├── logs/mcp-tool-calls.jsonl
    │   └── ontologies/
    │       ├── v03_demo/
    │       │   ├── source/v0.3-claim-verification.owl
    │       │   ├── canonical/ontology.owl
    │       │   ├── inferred/inferred-class-hierarchy.jsonl
    │       │   ├── inferred/inferred-types.jsonl
    │       │   ├── metadata.json
    │       │   └── reasoning-report.json
    │       └── pizza/
    │           └── ...
    └── staging/
        └── ...
```

`catalog.json` 是工作区级索引;每个本体的 `metadata.json` 含 IRI、profile、entity 计数、import 时间戳、最后修改时间。

### 7.2 npm launcher

npm 脚本做三件事:

1. 检测平台并定位 `node`。
2. 找可执行 jar。搜索顺序:`$OWL4AGENTS_RUNTIME` 环境变量 → 仓库内 `build/modules/ontology-cli/libs/owl4agents.jar` → 用户缓存。
3. `fork+exec` java 跑用户的参数,转发 stdin/stdout/stderr。Windows 上直接用 `CreateProcess`,绕开 `java -jar` 的 ACCESS_VIOLATION。

就这样。launcher 故意做得薄;所有活儿都在 jar 里。

### 7.3 MCP HTTP 传输选项(v0.8)

```
node tools/npm/bin/owl4agents.js mcp --readonly --transport http --port 8080 \
    --max-sse-connections 100 \
    --session-ttl-minutes 30 \
    --sse-heartbeat-seconds 15
```

| Flag | 默认 | 含义 |
|---|---|---|
| `--transport` | `stdio` | `stdio` 或 `http`。 |
| `--port` | n/a | `http` 时必填。 |
| `--max-sse-connections` | 100 | 同时打开的 SSE 流数量。第 101 个返回 503 + `Retry-After: 30`。 |
| `--session-ttl-minutes` | 30 | `lastAccessAt` 超过这个时间的会话会被清扫。 |
| `--sse-heartbeat-seconds` | 15 | keep-alive 注释帧间隔(RFC 8895)。 |
| `--readonly` | off | 唯一安全的模式。今天还没"藏"什么工具(56 个都是只读),但这是为未来写入功能留的契约。 |
| `--workspace` | `default` | 服务器暴露的工作区。 |
| `--home` | `$OWL4AGENTS_HOME` 或 `~/.owl4agents` | 工作区根。 |

完整 HTTP 契约在 [test/contracts/v08-acceptance/contracts.md](test/contracts/v08-acceptance/contracts.md)。

### 7.4 Trae IDE 集成

Trae IDE 会自己打开一个 SSE 流。生成配置:

```powershell
node tools/npm/bin/owl4agents.js mcp-config --client trae
```

配置里 `mcpServers.owl4agents.url = "http://127.0.0.1:<port>/mcp"`。Trae 同时发 `POST /mcp`(普通请求)和 `GET /mcp`(SSE)。v0.8 服务器都支持。

### 7.5 Windows `java -jar` 的 ACCESS_VIOLATION

`java -jar build/modules/ontology-cli/libs/owl4agents.jar` 在某些 Windows 配置下会因 OWL API 的 native loader 报 JVM `ACCESS_VIOLATION` 而崩。变通方法:

1. **用 npm launcher**:`node tools/npm/bin/owl4agents.js <command>` —— 永远安全。
2. **用 Gradle**:`.\gradlew.bat run --args="<command>"` —— 用 `java -cp` 加 build classpath,不走 `java -jar`。
3. **用 Windows 包装脚本**:`tools/bin/owl4agents-mcp.cmd` —— 也用 `java -cp`。v0.7+ 的 MCP config 生成器给 Windows 客户端输出的就是这个。

Linux/macOS 的 CI 不受影响;故障是 Windows 专属、与 OWL API 的 native loader 相关。

### 7.6 把 MCP 服务器跑成 systemd 服务

```ini
# /etc/systemd/system/owl4agents.service
[Unit]
Description=owl4agents MCP HTTP server
After=network.target

[Service]
Type=simple
User=owl4agents
WorkingDirectory=/opt/owl4agents
Environment=JAVA_HOME=/usr/lib/jvm/java-22-openjdk
Environment=OWL4AGENTS_HOME=/var/lib/owl4agents
ExecStart=/usr/bin/node /opt/owl4agents/tools/npm/bin/owl4agents.js mcp \
    --readonly \
    --transport http --port 8080 \
    --max-sse-connections 100 \
    --session-ttl-minutes 30 \
    --sse-heartbeat-seconds 15
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
```

> **注意:** `--home` 通过 `OWL4AGENTS_HOME` 传入。systemd 自己的 `--home` 会被 systemd 自己吃掉;CLI 的 `--home` flag 必须放在 `mcp` 子命令*之后*或者走环境变量。已知坑,有单元测试兜底。

### 7.7 Docker

最小 Dockerfile 不在本文档范围内,但结构很直白:复制 jar,装 Node 18+,装 Java 22,然后 `CMD ["node","tools/npm/bin/owl4agents.js","mcp","--readonly","--transport","http","--port","8080"]`。工作区以卷挂载到 `OWL4AGENTS_HOME`。

---

## 8. 推理机集成 —— 选哪个、何时选

owl4agents 自带三个推理机适配器。`--reasoner auto` 会按本体的 profile 自动选一个,但你也可以显式指定。

| 推理机 | Profile | 操作 | Explanation | 速度 | 适合 |
|---|---|---|---|---|---|
| **HermiT** | OWL 2 DL, OWL 2 Full | classify, realize, checkConsistency | 无 | 慢(秒到分钟) | 完整 DL 本体、大型本体。OWL 2 DL 的默认。 |
| **ELK** | OWL 2 EL | classify, checkConsistency | 无 | 快(毫秒) | EL profile 的大型本体(生物医学、BFO、GO)。OWL 2 EL 的默认。 |
| **Openllet** | OWL 2 DL | classify, realize, checkConsistency, explain | **有** | 中 | 需要 explain 为何 entail、为何类不可满足。 |

`auto` 模式当前对 EL 本体选 `ELK`,其它都选 `Openllet`(它在三者中 DL 性能最好,且支持 explanation)。未来版本可能加 Pellet / Konklude / ……。

用推理机的 MCP 工具(任何触及 inferred 图的)都走单线程执行器。这是为了保持推理机内部状态一致。8 线程 worker 池处理非推理机工具(浏览、搜索、对 explicit 图跑 SPARQL……)。如果推理机池忙,你发一个用推理机的工具,它会等;排到第 100 个还在等,第 101 个会拿到 `-32000` "worker pool saturated",你该 back off。

**显式挑推理机的启发式:**

- 你的本体在 `OWL_2_EL` 且大:`--reasoner elk`(最快路径)。
- 你需要 `ontology_explain_inconsistency` 或 `ontology_explain_unsat_class`:`--reasoner openllet`(唯一支持 explanation 的)。
- 你的本体在 `OWL_2_DL` 且小(< 1000 个类):`--reasoner openllet`(小输入上略好于 HermiT)。
- 你的本体在 `OWL_2_DL` 且大:试试 `hermit` 和 `openllet`,看谁先跑完。

---

## 9. 错误码、故障排查与限制

### 9.1 错误信封

CLI 打印 `Error: <CODE> - <message>`。JSON 模式和 MCP 工具用:

```json
{
  "message": "...",
  "code": "ONTOLOGY_NOT_FOUND",
  "details": { "ontologyId": "..." }
}
```

### 9.2 错误码(按字母)

| Code | 出处 | 含义 | 处理 |
|---|---|---|---|
| `BUDGET_EXCEEDED` | `verify-answer`、`evidence-context` | token 预算或 max-claims 预算不够装下答案。 | 加大 `--max-context-tokens`、拆分答案、或缩小本体。 |
| `CLAIM_VERIFICATION_FAILED` | `verify-claim`、`verify-answer` | 内部:verifier 抛了非已知错误类的异常。 | 换推理机重跑;检查本体是否有畸形公理。 |
| `DATATYPE_NO_FACETS` | `datatype-constraints` | 该 datatype 没有声明 `xsd:minInclusive` / `maxInclusive` / `pattern` / `enumeration` facet。 | 内置 XSD datatype 的预期行为;不是错。 |
| `EVIDENCE_NOT_AVAILABLE` | `counterexamples`、`explain-unknown` | verdict 对不上。 | 例如 `counterexamples` 只对 contradicted claim 有效。 |
| `ENTITY_NOT_FOUND` | `entity`、`class_context`、`relations`…… | IRI 不在本体里。 | 跑 `search` 找正确 IRI;大小写敏感。 |
| `INPUT_NOT_FOUND` | `import` | OWL 文件路径不存在。 | 检查路径。 |
| `INVALID_CLAIM_SCHEMA` | `verify-claim` | claim JSON 缺必填字段或 `type` 不认识。 | 必填:`claimId`、`type`、`subject`、`object`。 |
| `ONTOLOGY_CONSISTENT` | `explain_inconsistency`、`explain_unsat_class` | 本体/类可满足,无需 explanation。 | 不是错 —— 只是个有用信号。 |
| `ONTOLOGY_IMPORT_FAILED` | `import` | 本体有 `owl:imports` 声明指向导入器找不到的文件。 | 把被 import 的文件放到 import 路径,或去掉该 import。 |
| `ONTOLOGY_NOT_FOUND` | 大多数工具 | `ontology_id` 不在 `catalog.json` 里。 | 先跑 `import`,或检查拼写。 |
| `ONTOLOGY_NOT_READY` | 用推理机的工具 | 本体还没 import,或 `reason` 没跑过。 | 先跑 `node tools/npm/bin/owl4agents.js reason <id>`。 |
| `ONTOLOGY_PARSE_FAILED` | `import` | OWL 文件不是合法的 Turtle / RDF / OWL XML。 | 用外部 validator 检查文件。 |
| `READONLY_VIOLATION` | (保留,目前未触发) | 工具尝试写入。 | 别这样。 |
| `REASONER_INFEASIBLE` | 用推理机的工具 | 推理机 OOM 或超时。 | 换轻量推理机(ELK 替 HermiT),或缩小本体。 |
| `REASONER_NOT_FOUND` | 用推理机的工具 | `--reasoner openllet` 但 Openllet jar 不在 classpath。 | shadowJar 应该自带;查 `gradle :modules:ontology-cli:dependencies`。 |
| `SPARQL_SAFETY_VIOLATION` | `query --select/--ask/...` | 查询含黑名单写入关键字。 | 改写成只读查询。 |
| `SPARQL_VALIDATION_FAILED` | `query --validate`、`validate-sparql` | 查询有解析/结构错误。 | 错误信息里会带列号。 |
| `WORKER_POOL_SATURATED` | 仅 MCP(HTTP) | 8 线程 worker 池满了。 | 退避后重试;v0.7 压测会发 10 个并发推理机调用。 |

### 9.3 常见场景与修复

**"我的 SPARQL 查询用 `rdfs:subClassOf` 返回 'Unresolved prefixed name'"**

默认图没声明 `PREFIX` 映射。用完整 IRI:

```sparql
SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }
```

……或者在代码里把查询包在 prologue 里。safety guard 只剥 `INSERT DATA` 等,不剥 `PREFIX` 声明。

**"我的 claim 返回 `out_of_scope`,但这个类明明存在"**

几乎都是 IRI 拼错了。跑 `missing-entities` 看 verifier 认为 IRI 指向什么。大小写敏感。

**"我 import 了本体,但 `reason` 说是空的"**

文件可能解析了但产出 0 条公理(例如只有注释)。跑 `summary` 看 entity 计数。

**"我的 duplicate-disjoint 公理出现两次"**

导入器合并对称 disjointness 公理但不去重。disjointness 是真的;响应里重复的 IRI 是 cosmetic。已知问题;非正确性 bug。

**"`java -jar owl4agents.jar` 在 Windows 上 ACCESS_VIOLATION 崩溃"**

用 `node tools/npm/bin/owl4agents.js` 或 `gradlew run --args="..."`。见 [§7.5](#75-windows-java--jar-的-access_violation)。

**"MCP 服务器在第 101 个 SSE 连接返回 503"**

你打到了 `--max-sse-connections`(默认 100)。加大 flag,或等 —— `Retry-After: 30` 说 30 秒后再来。

**"MCP 服务器在 `GET /mcp` 返回 405"**

你忘了 `Accept: text/event-stream` 头。v0.8 在 `GET /mcp` 上对任何其它变体返回 `Allow: GET, POST`;这是规范。

**"MCP 服务器对已存在的会话返回 404"**

会话已超过 `--session-ttl-minutes`,被清扫了。重新 `initialize`。

**"`evidence-context` 截断,我丢了关键信息"**

加大 `--max-context-tokens`。`jsonl` 格式下,每行的 `truncated` 标记会告诉你具体丢了哪些 evidence 项。

### 9.4 已知限制

- **推理机池单线程**:整个服务器一次只能跑一个推理任务。8 个并发推理机请求会排队,第 9 个在 HTTP 层超时(默认 30s)。
- **不支持 SWRL rules**:ELK 支持 OWL 2 RL,但不执行规则。
- **大规模 nominal 推理不行**:大 nominal 集(例如 `{a,b,c,d,...}` 几千个元素的枚举)会让 HermiT 显著变慢。
- **Import 路径仅本地**:`owl:imports` URI 必须能解析到本地文件。不抓取远程 import。
- **本体无版本管理**:对同一个 `ontology_id` 重新 import 会覆盖前一个。用 `--force`。
- **`--readonly` 是契约性而非强制的**(今天):56 个工具本来就只读。这个 flag 是为未来写入功能留的契约。

---

## 10. 测试、质量数据与验收证据

### 10.1 测试金字塔

| 层 | 内容 | 数量 | 时间 |
|---|---|---|---|
| 单元测试(Gradle) | `modules/*/src/test/` | 800+ | ~30s |
| Launcher 冒烟(npm) | `tools/npm/test/launcher.test.js` | 29 | ~10s |
| MCP 工具集成 | v0.8 验收(`test/contracts/v08-acceptance/`) | 56 | ~30s |
| v0.8.1 80-claim 准确率门禁 | `V081AcceptanceSuite`(pizza-50 + owl2bench-30) | 80 | ~10s |
| 推理机压测(tag `stress`) | 10 个并发推理机调用 | 1 | ~60s |
| 端到端示例包 | `examples/claim-verification/`、`examples/pizza-reasoning/`、`examples/biomedical-grounding/`、`examples/agent-mcp/` | 5 | 每个 ~5s |

v0.8.1 这行覆盖 5 个修复场景(`pizza-007`、`pizza-035`、`pizza-037`、`pizza-046`、`owl2bench-027`)和剩余 75 个策展 claim,断言 80/80 准确率门禁,用于修复 v0.8.0 的 ISSUE-01…ISSUE-05 缺陷(见 [CHANGELOG.md](../CHANGELOG.md) §"0.8.1")。

全跑一遍:

```powershell
.\gradlew.bat test
cd tools\npm; npm test; cd ..\..
node tools/npm/test/launcher.test.js
```

### 10.2 `v0.8.0_56tool_scoreboard.csv` 说了什么

干净的 v0.8 发布之后,56 个 MCP 工具每一个都用真实请求调过,响应也检查过预期的顶层 key。scoreboard(`reports/acceptance/v0.8.0_56tool_scoreboard.csv`)记录 `Tool,Status,Summary`:

- **Status `PASS`** —— 响应是 `success` 且含预期的 keys。
- **Status `ISERR`** —— 工具正确返回了 `isError: true` 并附特定错误码。**这是预期行为**,因为有些工具在特定本体上调就是这样(例如在一致本体上调 `explain_inconsistency` 会返回 `ONTOLOGY_CONSISTENT`;对 supported claim 调 `find_counterexamples` 会返回 `EVIDENCE_NOT_AVAILABLE`)。

`v0.8.0_56tool_scoreboard.csv` 就是 56 个工具都好使的证据。

### 10.3 复现验收门

```powershell
# 构建一切,跑单元 + 冒烟套件。
.\gradlew.bat clean buildVerification
.\gradlew.bat :modules:ontology-cli:shadowJar

# 跑 launcher 冒烟测试。
node tools/npm/test/launcher.test.js

# 跑 v0.8 验收套件(单独的 Java 入口)。
.\gradlew.bat :modules:ontology-distribution:v08Acceptance
```

56 个工具检查全过时,验收套件退出码 0。

### 10.4 证据在哪

所有测试产物和验收证据都在 `reports/` 下(本地 `.gitignore` 掉 —— 公开的证据是测试契约和 scoreboard CSV):

- `reports/acceptance/v0.8.0_56tool_scoreboard.csv` —— 56 工具 scoreboard。
- `reports/acceptance/v0.8.0_acceptance_report.md` —— 叙述性总结。
- `reports/acceptance/v0.8.0_gradle_test.log` —— 完整 Gradle 测试日志。
- `reports/acceptance/v0.8.0_npm_test.log` —— npm test 日志。
- `build/reports/tests/test/index.html` —— Gradle HTML 报告。

验收门强制执行的契约测试位于 `test/contracts/v08-acceptance/`。

---

## 许可证与贡献

Apache-2.0。见 [LICENSE](LICENSE)。Bug 报告和 PR 欢迎到 https://github.com/leungBH/owl4agents。

协议契约和 JSON schema 见 `openspec/changes/archive/` 下归档的 OpenSpec changes。各工具的严格契约见 `test/contracts/` 下的契约测试文件。



