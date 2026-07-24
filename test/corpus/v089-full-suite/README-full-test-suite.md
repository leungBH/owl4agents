# owl4agents v0.8.8 verify_claim 完整测试套件

## 1. 用途

本测试套件用于验证 owl4agents v0.8.8 的 verify_claim 功能正确性。

测试套件覆盖 6 个验证集，共 388 个 claim，涉及 4 个本体（pizza、hpo、mondo、sosa），通过 MCP 协议调用 ontology_verify_claims_batch 工具，将实际 verdict 与 expected verdict 对比，统计准确率和错误分类。

主要目标：

- 检测 v0.8.8 中 isEntityDeclared 检查的系统性 bug（详见 BUG_REPORT.md）
- 对比 v0.8.6 基线结果，量化回归幅度
- 为修复后的版本提供回归测试基线

## 2. 目录结构

```
full_test_suite/
├── README.md                      本文件
├── BUG_REPORT.md                 verify_claim bug 详细报告
├── ontologies/                   本体源文件
│   ├── pizza.owl                 Pizza 本体（co-ode.org namespace）
│   ├── hp.owl                    Human Phenotype Ontology
│   ├── mondo.owl                 Mondo Disease Ontology
│   └── sosa.rdf                  SOSA（Sensor Observation Sample Actuator）
├── question-sets/                 验证集（JSONL 格式，每行一个 claim）
│   ├── pizza-112.jsonl
│   ├── hpo-60.jsonl
│   ├── mondo-60.jsonl
│   ├── sosa-84.jsonl
│   ├── hpo-extra-20.jsonl
│   └── mondo-extra-20.jsonl
├── configs/                       YAML 实验配置
│   ├── pizza-112.yaml
│   ├── hpo-60.yaml
│   ├── mondo-60.yaml
│   ├── sosa-84.yaml
│   ├── hpo-extra-20.yaml
│   └── mondo-extra-20.yaml
├── expected-results/             预期结果
│   └── expected_results.json     所有验证集的 questionId 与 expectedVerdict 汇总
├── v086-baseline-results/         v0.8.6 基线输出（用于回归对比）
│   ├── pizza-112-output.jsonl
│   ├── hpo-60-output.jsonl
│   ├── mondo-60-output.jsonl
│   ├── sosa-84-output.jsonl
│   ├── hpo-extra-20-output.jsonl
│   └── mondo-extra-20-output.jsonl
├── scripts/                       测试脚本
│   ├── run_full_test.ps1         完整测试运行脚本
│   └── reproduce_bug.ps1         bug 复现脚本（来自 verify_claim_bug_report）
└── results/                       运行结果输出目录（运行后自动创建）
```

## 3. 使用方法

### 前提条件

- owl4agents v0.8.8 MCP 服务已启动并可访问
- 本体已加载到 MCP 服务（ontologyId 分别为 pizza、hpo、mondo、sosa）
- PowerShell 5.1 或更高版本

### 运行完整测试

```powershell
# 默认连接 http://10.67.82.218:8083/mcp
.\scripts\run_full_test.ps1

# 指定 MCP 地址
.\scripts\run_full_test.ps1 -McpUrl "http://your-server:8083/mcp"

# 只运行部分验证集
.\scripts\run_full_test.ps1 -QuestionSets "pizza-112,hpo-60"

# 增加重试次数
.\scripts\run_full_test.ps1 -RetryCount 2 -TimeoutSec 180
```

### 运行 bug 复现脚本

```powershell
.\scripts\reproduce_bug.ps1
```

该脚本会逐步验证：

1. ontology_search_entities 能找到实体（正常）
2. ontology_sparql_ask 能确认实体存在（正常）
3. ontology_get_class_context 能返回实体上下文（正常）
4. ontology_verify_claim 报告 out_of_scope（bug）
5. ontology_detect_missing_entities 报告实体缺失（bug）

### 参数说明

run_full_test.ps1 参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| McpUrl | http://10.67.82.218:8083/mcp | MCP 服务地址 |
| OutputDir | ../results | 结果输出目录 |
| RetryCount | 1 | 失败重试次数 |
| TimeoutSec | 120 | 单次请求超时秒数 |
| QuestionSets | （空，运行全部） | 指定运行的验证集名称 |

## 4. 验证集统计

| 验证集 | 本体 | 推理器 | claim 数量 | 说明 |
|--------|------|--------|-----------|------|
| pizza-112 | pizza | hermit | 100 | 10 种 claim 类型 |
| hpo-60 | hpo | elk | 60 | 子类推理 |
| mondo-60 | mondo | elk | 60 | 子类推理 |
| sosa-84 | sosa | hermit | 126 | 14 种 claim 类型 |
| hpo-extra-20 | hpo | elk | 20 | 非子类类型 |
| mondo-extra-20 | mondo | elk | 22 | 非子类类型 |
| 合计 | | | 388 | |

注意：文件名中的数字（如 pizza-112、sosa-84）不等于实际 claim 数量。pizza-112 对应 100 个 claim，sosa-84 对应 126 个 claim。

## 5. verdict 映射规则

v0.8.8 的 ontology_verify_claims_batch 返回 aggregateStatus 字段，使用以下 verdict 词汇：

| v0.8.8 原始 verdict | 映射后 verdict | 说明 |
|---------------------|---------------|------|
| verified | supported | 验证通过 |
| contradicted | contradicted | 验证反驳 |
| unknown | unknown | 无法判定 |
| out_of_scope | out_of_scope | 超出范围 |

测试脚本自动将 verified 映射为 supported，与 expected verdict 词汇统一。

## 6. v0.8.6 基线结果

v086-baseline-results/ 目录包含 v0.8.6 版本的输出结果，字段包括：

- questionId：问题 ID
- ontologyId：本体 ID
- reasoner：使用的推理器
- actualVerdict：实际 verdict
- expectedVerdict：预期 verdict
- verdictMatch：是否匹配
- elapsedMs：耗时（毫秒）
- executionStatus：执行状态

可用于对比 v0.8.8 与 v0.8.6 的回归情况。

## 7. 已知 bug

详见 BUG_REPORT.md。核心问题：

- 影响版本：owl4agents v0.8.8
- 严重程度：高
- 现象：ontology_verify_claim 的 isEntityDeclared 检查存在系统性 bug，即使实体存在于本体中，仍返回 out_of_scope
- 影响范围：pizza 全部 100 个 claim 受影响，hpo 部分实体受影响
- 根因：v0.8.3 引入的严格声明检查与 v0.8.4 的 EntitySignatureCache 构建不完整

bug 表现：

1. ontology_search_entities 能找到实体（正常）
2. ontology_sparql_ask 能确认实体存在（正常）
3. ontology_get_class_context 能返回实体上下文（正常）
4. ontology_verify_claim 报告 out_of_scope（bug）
5. ontology_detect_missing_entities 报告实体缺失（bug）

## 8. 输出结果说明

运行 run_full_test.ps1 后，results/ 目录包含以下文件：

| 文件 | 说明 |
|------|------|
| {suite}-results-{timestamp}.json | 带时间戳的完整结果（含逐条记录、混淆矩阵） |
| {suite}-results-latest.json | 最新结果（每次运行覆盖） |
| {suite}-output.jsonl | 逐条结果（JSONL 格式，与 v086 baseline 对齐） |
| summary-{timestamp}.json | 汇总报告（带时间戳） |
| summary-latest.json | 最新汇总报告 |

每条结果记录包含：

- questionId：问题 ID
- ontologyId：本体 ID
- expected：预期 verdict
- actual：实际 verdict（已映射）
- actualRaw：原始 verdict（映射前）
- match：OK 或 MISMATCH 或 ERROR
- elapsedMs：耗时（毫秒）
- attempts：尝试次数

## 9. 文件来源

| 文件类型 | 来源路径 |
|---------|---------|
| pizza.owl | owl4agents-v086/owl4agents/test/corpus/smoke/pizza.owl |
| hp.owl | owl4agents-v086/owl4agents/.owl4agents-home/workspaces/default/ontologies/hpo/source/hp.owl |
| mondo.owl | owl4agents-v086/owl4agents/.owl4agents-home/workspaces/default/ontologies/mondo/source/mondo.owl |
| sosa.rdf | owl4agents/test/corpus/public/sosa.rdf（v086 无此文件，从主仓库回退） |
| 验证集 | owl4agents-v086/owl4agents/test/fixtures/v0.6/question-sets/ |
| 配置 | owl4agents-v086/owl4agents/repro-package/configs/ |
| 基线结果 | owl4agents-v086/owl4agents/results/v086/ |
| BUG_REPORT.md | owl4agents-v086/owl4agents/verify_claim_bug_report/BUG_REPORT.md |
| reproduce_bug.ps1 | owl4agents-v086/owl4agents/verify_claim_bug_report/repro_files/reproduce_commands.ps1 |
