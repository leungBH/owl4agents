# owl4agents

**本地 OWL 本体运行时、推理机集成、SPARQL 查询层,以及面向 LLM 代理的只读 MCP 服务器。**

`owl4agents` 把一个装满 OWL/RDF 文件的目录变成可查询的本地知识库。它负责加载本体、运行 OWL 推理机(HermiT / ELK / Openllet)、执行 SPARQL,并把结果通过 56 个工具的只读 MCP 服务器暴露给任何 LLM 代理(Claude Desktop、Trae IDE、Cursor……),全程不离开本机。

> **v0.8** 新增 MCP Streamable HTTP 传输(基于 `GET /mcp` 的 SSE、`Mcp-Session-Id` 往返、内容协商)。v0.7 的 plain-JSON HTTP 传输保留为无回归基线;stdio 和报文格式保持不变。
>
> **v0.8.4**(推荐)优化 claim 验证性能,包含 7 项决策:推理机分类状态跟踪(跳过冗余 `precomputeInferences`)、profile 缓存、单次本体加载、`EntitySignatureCache` O(1) 实体签名查询、断言公理索引(SubClassOf + DisjointClasses)、内存推理层次索引、`OntologyCache` 5 秒 TTL 窗口。Pizza 热路径 ~85ms → ~30-40ms。见 [CHANGELOG.md](CHANGELOG.md) §"0.8.4"。
>
> **v0.8.3** 修复 claim 验证中的 7 项语义准确度问题(R1, R2, R4-R7:OOS 预检查、矛盾代理、EquivalentClasses 复杂表达式、个体级不相交、属性层次、ObjectPropertyDomain 复杂 domain;D7:ClaimType 反序列化加固)——解决了升级包中的 9 个错误 claim。见 [CHANGELOG.md](CHANGELOG.md) §"0.8.3"。
>
> **v0.8.1**修复 v0.8.0 的 5 个 claim verification 准确率缺陷(ISSUE-01…ISSUE-05)—— 见 [CHANGELOG.md](CHANGELOG.md) §"0.8.1"。80 个策展 claim 的准确率门禁从 75/80 提升到 80/80。新增两个 claim 类型(`different_individuals`、`object_property_subproperty`),并支持在 `subject` / `object` 上使用可选的 `expression` 字段表达复杂类表达式。

---

## 语言版本 / Available languages

- [English](README.md) | **简体中文**(本文)
- 详细功能参考:[English FEATURES](docs/FEATURES.md) | [中文版 FEATURES](docs/FEATURES.zh-CN.md)

---

## README 与 FEATURES 的关系 —— 读哪一份?

本仓库有两份文档,它们的职责不同:

| 文件 | 受众 | 篇幅 | 内容 |
|---|---|---|---|
| [README.md](README.md)(英文)/ [README.zh-CN.md](README.zh-CN.md)(本文) | 所有人,尤其是新用户 | ~10 分钟 | 项目是什么、5 分钟快速启动、部署、MCP 客户端配置、故障排查指引 |
| [FEATURES.md](docs/FEATURES.md)(英文)/ [FEATURES.zh-CN.md](docs/FEATURES.zh-CN.md) | 想要**使用** owl4agents(CLI 或 MCP)的程序员 | ~60 分钟 | 完整参考。覆盖每个 CLI 命令和每个 MCP 工具,带真实 OWL 文件、真实入参/出参 JSON、以及"何时使用"的指引。准备写代码对接 owl4agents 时,先读这份 |

**经验法则:** 想安装并跑通 owl4agents,读本 README。想了解某个工具*做什么*以及响应长什么样,读 [FEATURES.zh-CN.md](docs/FEATURES.zh-CN.md)。

> 寻找严格的协议契约(错误码、HTTP 语义、JSON schema)?见 [CHANGELOG.md](CHANGELOG.md) 和 `openspec/changes/archive/` 下的各特性 spec。

---

## owl4agents 做什么

```
OWL / RDF / Turtle 文件
        │
        ▼
  owl4agents (Java 22 + OWL API + HermiT/ELK/Openllet + Jena ARQ)
        │
        ├── CLI  (47 个命令: import、query、reason、verify-claim、……)
        └── MCP  (56 个只读工具,支持 stdio、HTTP 或 SSE —— 禁止写入)
```

- **本地优先**: 所有数据在 `~/.owl4agents/workspaces/<name>/`;不上云,不打网络。
- **可复现**: 推理机输出写入磁盘(`reasoning-report.json`、`inferred-class-hierarchy.jsonl` 等),同一本体的两次运行产出完全一致。
- **可审计**: 每次 MCP 工具调用追加到 `mcp-tool-calls.jsonl`(原子 JSON 行)。
- **默认只读安全**: MCP 服务器使用 `--readonly` 启动,只暴露 56 个读/验证工具。变更操作(import、delete)只通过 CLI 进行。
- **标准协议**: MCP `2025-03-26` Streamable HTTP、JSON-RPC 2.0、SPARQL 1.1、OWL 2 (DL/EL/QL/RL)。

---

## 5 分钟快速启动

本节面向 CS 本科毕业生,带你克隆仓库、构建、导入小型本体、问个问题 —— 在 Windows PowerShell 下演示。macOS / Linux 步骤类似,把 `.\gradlew.bat` 换成 `./gradlew` 即可。

```powershell
# 0. 前置条件: Java 22(已设置 JAVA_HOME)和 Node.js 18+。
java -version    # → 22.x
node --version   # → 18.x 或更高

# 1. 构建可执行 jar。
git clone https://github.com/leungBH/owl4agents.git
cd owl4agents
.\gradlew.bat :modules:ontology-cli:shadowJar

# 2. 初始化本地工作区(对应 ~/.owl4agents/workspaces/default/ 下的一个目录)。
node tools/npm/bin/owl4agents.js init

# 3. 导入一个小型示例本体。该文件随仓库提供。
node tools/npm/bin/owl4agents.js import `
    test/corpus/golden/v0.3-claim-verification.owl v03_demo

# 4. 用 ELK 推理(最轻量的推理机,对 EL profile 够用)。
node tools/npm/bin/owl4agents.js reason v03_demo --reasoner elk

# 5. 看看: 列出已导入的本体、搜索、dump 一个类、跑 SPARQL。
node tools/npm/bin/owl4agents.js list
node tools/npm/bin/owl4agents.js search v03_demo Dog
node tools/npm/bin/owl4agents.js entity v03_demo "http://example.org/v0.3#Dog"
node tools/npm/bin/owl4agents.js query v03_demo `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }"
```

如果 `?s` 拿到绑定结果,恭喜 —— 端到端打通。接下来:

- **想用 LLM 代理驱动它?** 跳到 [MCP 客户端配置](#mcp-客户端配置)。
- **想对本体验证一条结构化 claim?** 见 [FEATURES.zh-CN.md §6 "Claim 验证与证据"](docs/FEATURES.zh-CN.md)。
- **想了解每个 CLI 命令做什么?** [FEATURES.zh-CN.md §4](docs/FEATURES.zh-CN.md)。
- **想了解每个 MCP 工具返回什么?** [FEATURES.zh-CN.md §5](docs/FEATURES.zh-CN.md)。

---

## 架构速览

```
+--------------------+        fork + exec         +----------------------------+
|  npm launcher      |  ──────────────────────▶  |  owl4agents.jar (Java 22)   |
|  (Node.js 18+)     |    转发 argv/IO             |                            |
+--------------------+                            |  ┌────────┐  ┌────────┐   |
        │                                         |  │ CLI    |  │ MCP    |   |
        │  $env:OWL4AGENTS_HOME = ...             |  │(Picocli|  │(JSON-  |   |
        ▼                                         |│  │ 47    │  │ RPC +  │   │|
~/.owl4agents/workspaces/                          |  │ cmds)  |  │ SSE)   |   |
└── default/                                       |  └───┬────┘  └───┬────┘   |
    ├── catalog.json                                |      │           │       |
    └── ontologies/                                 |      ▼           ▼       |
        └── v03_demo/                              |  ┌──────────────────┐   |
            ├── source/  (原始 .owl)                |  │ ontology-service │   |
            ├── canonical/ (规范化)                |  └────┬─────────────┘   |
            ├── inferred/ (推理机输出)              |       │                 |
            └── reasoning-report.json              |       ▼                 |
                                                   |  OWL API · HermiT ·    |
                                                   |  ELK · Openllet · Jena |
                                                   +────────────────────────+
```

11 个 Gradle 模块,分为三层(核心的 storage / OWL-API / query / reasoner / retrieval / validation / benchmark,入口的 ontology-cli 与 ontology-mcp,以及验收测试用的 ontology-distribution)。模块级拆解见 [FEATURES.zh-CN.md §2](docs/FEATURES.zh-CN.md)。

---

## MCP 客户端配置

`mcp-config` 命令会输出可直接粘贴的 JSON 配置,覆盖主流 MCP 客户端。生成的配置始终指向仓库内的 npm launcher,并自动设置 `OWL4AGENTS_HOME`。

```powershell
# Claude Desktop —— 把配置写到用户的默认位置。
node tools/npm/bin/owl4agents.js mcp-config --client claude

# 通用 stdio MCP 客户端(例如自研 IDE 插件)—— 打印到 stdout。
node tools/npm/bin/owl4agents.js mcp-config --client generic

# Cursor。
node tools/npm/bin/owl4agents.js mcp-config --client cursor

# Trae IDE —— 生成的配置 URL 以 /mcp 结尾,Trae 会向 v0.8 服务器
# 同时发出 POST /mcp 和 GET /mcp(SSE)。
node tools/npm/bin/owl4agents.js mcp-config --client trae

# 覆盖工作区根目录,例如指向 D:\owl4agents-workspace。
node tools/npm/bin/owl4agents.js mcp-config --client claude `
    --workspace-home D:/owl4agents-workspace
```

> MCP 服务器**默认只读**。请用 CLI 来 import / delete 本体,用 MCP 服务器来查询 / 验证。

想看完整的端到端走读(启动服务器、调用 `tools/list`、调用工具、读取响应),见 [FEATURES.zh-CN.md §3 "5 分钟体验 MCP 服务器"](docs/FEATURES.zh-CN.md)。

---

## 本地代理部署

1. 把本仓库克隆到运行代理的机器上。
2. 安装 Java 22(已设 `JAVA_HOME`)和 Node.js 18+。
3. 构建可执行 jar:`.\gradlew.bat :modules:ontology-cli:shadowJar`。
4. 用 CLI 初始化工作区并导入本体。
5. 把 MCP 客户端指向 `node tools/npm/bin/owl4agents.js mcp --readonly`。

完整的部署方案(包括 Windows 上 `java -jar` 出现 ACCESS_VIOLATION 的变通方法、环境变量、systemd 服务模板)见 [FEATURES.zh-CN.md §7](docs/FEATURES.zh-CN.md)。

---

## 环境要求

- **Java 22**(`JAVA_HOME` 指向 JDK;Windows 上避免使用 Oracle 的 `javapath` 垫片)。
- **Node.js 18+**(用于 npm launcher;嵌入式 jar 本身不需要)。
- **Windows / macOS / Linux** —— npm launcher 抹平了平台差异。

Windows 上,优先使用 npm launcher 或 `gradlew run --args="..."`,避免直接 `java -jar owl4agents.jar`;后者在某些 Windows 环境下会与 OWL API 的 native loader 发生 ACCESS_VIOLATION。

---

## 验证一份新检出的代码

```powershell
.\gradlew.bat clean buildVerification
.\gradlew.bat :modules:ontology-cli:shadowJar
node tools/npm/test/launcher.test.js
node tools/npm/bin/owl4agents.js --version    # → 0.8.4
node tools/npm/bin/owl4agents.js --help
```

绿色运行表示 `BUILD SUCCESSFUL`、npm launcher 输出 `Results: 29 passed, 0 failed`、`--version` 打印 `0.8.4`。完整的 Gradle 套件有 900+ 个单元测试(0 失败),覆盖 80 个策展 claim 的准确率门禁(80/80)—— 运行后可在 `build/reports/tests/test/index.html` 查看报告。

---

## 下一步读什么

- **OWL / RDF 新手?** [FEATURES.zh-CN.md §1 "5 分钟 OWL 与 SPARQL 入门"](docs/FEATURES.zh-CN.md) 是面向 CS 毕业生的速成课。
- **想看真实的工具调用?** [FEATURES.zh-CN.md §3 "完整走读"](docs/FEATURES.zh-CN.md) 演示加载一个本体、跑推理、提 SPARQL 查询、验证 claim —— 每一步都带真实输出。
- **正在搭建代理?** [FEATURES.zh-CN.md §6 "Claim 验证与证据落地"](docs/FEATURES.zh-CN.md) 展示如何把 `verify-claim` / `evidence-context` 接入答案流水线。
- **遇到错误?** [FEATURES.zh-CN.md §9 "故障排查"](docs/FEATURES.zh-CN.md) 列出常见的 `READONLY_VIOLATION`、`SPARQL_SAFETY_VIOLATION`、`ONTOLOGY_NOT_READY` 等错误及修复方法。

## 许可证

Apache-2.0。见 [LICENSE](LICENSE)。
