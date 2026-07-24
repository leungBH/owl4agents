<#
.SYNOPSIS
    owl4agents v0.8.8 verify_claim 完整测试脚本

.DESCRIPTION
    遍历 6 个验证集（pizza-112, hpo-60, mondo-60, sosa-84, hpo-extra-20, mondo-extra-20），
    对每个 claim 调用 ontology_verify_claims_batch 工具，
    将 "verified" verdict 映射为 "supported"，
    与 expected verdict 对比，统计准确率和错误分类，
    输出详细结果到 results/ 目录。

    已知 bug（详见 BUG_REPORT.md）：
    - v0.8.8 的 isEntityDeclared 检查存在系统性 bug
    - pizza 全部 112 个 claim 会被误判为 out_of_scope
    - hpo/mondo 部分实体受影响

.PARAMETER McpUrl
    MCP 服务地址，默认 http://10.67.82.218:8083/mcp

.PARAMETER OutputDir
    结果输出目录，默认为脚本同级的 ../results

.PARAMETER RetryCount
    失败重试次数，默认 1 次

.PARAMETER TimeoutSec
    单次请求超时秒数，默认 120 秒

.PARAMETER QuestionSets
    可选，只运行指定的验证集名称（逗号分隔），
    例如 -QuestionSets "pizza-112,hpo-60"

.EXAMPLE
    .\run_full_test.ps1
    .\run_full_test.ps1 -McpUrl "http://10.67.82.218:8083/mcp"
    .\run_full_test.ps1 -QuestionSets "pizza-112" -RetryCount 2
#>

param(
    [string]$McpUrl = "http://10.67.82.218:8083/mcp",
    [string]$OutputDir = "",
    [int]$RetryCount = 1,
    [int]$TimeoutSec = 120,
    [string]$QuestionSets = ""
)

$ErrorActionPreference = "Stop"

# 解析脚本所在目录与测试套件根目录
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SuiteRoot = Split-Path -Parent $ScriptDir

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $SuiteRoot "results"
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$QuestionSetDir = Join-Path $SuiteRoot "question-sets"
$BaselineDir = Join-Path $SuiteRoot "v086-baseline-results"

# 验证集定义：ontology_id, reasoner, timeout, claim 数量说明
$SuiteDefs = @(
    @{ Name = "pizza-112";      OntologyId = "pizza"; Reasoner = "hermit"; Timeout = 30;  File = "pizza-112.jsonl" },
    @{ Name = "hpo-60";         OntologyId = "hpo";    Reasoner = "elk";    Timeout = 180; File = "hpo-60.jsonl" },
    @{ Name = "mondo-60";       OntologyId = "mondo";  Reasoner = "elk";    Timeout = 300; File = "mondo-60.jsonl" },
    @{ Name = "sosa-84";        OntologyId = "sosa";   Reasoner = "hermit"; Timeout = 30;  File = "sosa-84.jsonl" },
    @{ Name = "hpo-extra-20";   OntologyId = "hpo";    Reasoner = "elk";    Timeout = 180; File = "hpo-extra-20.jsonl" },
    @{ Name = "mondo-extra-20"; OntologyId = "mondo";  Reasoner = "elk";    Timeout = 300; File = "mondo-extra-20.jsonl" }
)

# 过滤要运行的验证集
if (-not [string]::IsNullOrWhiteSpace($QuestionSets)) {
    $selected = $QuestionSets -split "," | ForEach-Object { $_.Trim() }
    $SuiteDefs = $SuiteDefs | Where-Object { $selected -contains $_.Name }
    if ($SuiteDefs.Count -eq 0) {
        Write-Host "ERROR: 没有匹配到任何验证集，请检查 -QuestionSets 参数" -ForegroundColor Red
        exit 1
    }
}

$headers = @{ "Content-Type" = "application/json" }
$globalStep = 0

# 时间戳，用于输出文件命名
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

<#
    调用 MCP 工具，返回解析后的 JSON 对象
#>
function Invoke-McpTool {
    param(
        [string]$ToolName,
        [object]$Arguments,
        [int]$RequestTimeout
    )

    $global:globalStep++
    $bodyObj = @{
        jsonrpc = "2.0"
        method  = "tools/call"
        params  = @{ name = $ToolName; arguments = $Arguments }
        id      = $global:globalStep
    }
    $body = $bodyObj | ConvertTo-Json -Depth 20 -Compress

    $response = Invoke-RestMethod -Uri $McpUrl -Method Post -Headers $headers -Body $body -TimeoutSec $RequestTimeout
    $text = $response.result.content[0].text
    return ($text | ConvertFrom-Json)
}

<#
    将实际 verdict 映射到统一词汇
    v0.8.8 的 verify_claims_batch 使用 "verified"，需要映射为 "supported"
#>
function Map-Verdict {
    param([string]$Verdict)
    if ([string]::IsNullOrWhiteSpace($Verdict)) { return "unknown" }
    switch ($Verdict.ToLower()) {
        "verified"     { return "supported" }
        "supported"    { return "supported" }
        "contradicted" { return "contradicted" }
        "refuted"      { return "contradicted" }
        "unknown"      { return "unknown" }
        "out_of_scope" { return "out_of_scope" }
        default        { return $Verdict }
    }
}

<#
    运行单个验证集
#>
function Invoke-Suite {
    param(
        [object]$SuiteDef
    )

    $name = $SuiteDef.Name
    $ontologyId = $SuiteDef.OntologyId
    $reasoner = $SuiteDef.Reasoner
    $perQuestionTimeout = $SuiteDef.Timeout
    $filePath = Join-Path $QuestionSetDir $SuiteDef.File

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  Running suite: $name (ontology=$ontologyId, reasoner=$reasoner)" -ForegroundColor Cyan
    Write-Host "  Question set: $filePath" -ForegroundColor Cyan
    Write-Host "  MCP URL: $McpUrl" -ForegroundColor Cyan
    Write-Host "  Retry count: $RetryCount" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    if (-not (Test-Path $filePath)) {
        Write-Host "ERROR: 文件不存在: $filePath" -ForegroundColor Red
        return $null
    }

    $lines = Get-Content $filePath
    $total = 0
    $correct = 0
    $mismatches = @()
    $errors = @()
    $allResults = @()
    $verdictConfusion = @{}

    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $q = $line | ConvertFrom-Json
        $qid = $q.questionId
        $expected = $q.expectedVerdict
        $total++

        $actualVerdict = $null
        $actualRaw = $null
        $attempt = 0
        $success = $false
        $lastError = $null
        $elapsedMs = 0

        # 构造 batch
        $batch = @{
            answerId = $qid
            claims   = $q.claims
        }

        $arguments = @{
            ontology_id = $ontologyId
            claims      = $batch
        }

        # 重试循环
        while (-not $success -and $attempt -le $RetryCount) {
            $attempt++
            try {
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $result = Invoke-McpTool -ToolName "ontology_verify_claims_batch" -Arguments $arguments -RequestTimeout ([math]::Max($TimeoutSec, $perQuestionTimeout))
                $sw.Stop()
                $elapsedMs = $sw.ElapsedMilliseconds

                $actualRaw = $result.aggregateStatus
                $actualVerdict = Map-Verdict -Verdict $actualRaw
                $success = $true
            } catch {
                $sw.Stop()
                $elapsedMs = $sw.ElapsedMilliseconds
                $lastError = $_.Exception.Message
                if ($attempt -le $RetryCount) {
                    Write-Host "  RETRY $attempt/$RetryCount for $qid : $lastError" -ForegroundColor Yellow
                    Start-Sleep -Seconds 2
                }
            }
        }

        if (-not $success) {
            # 全部重试失败
            $actualVerdict = "error"
            $actualRaw = "error"
            $errors += [PSCustomObject]@{
                questionId    = $qid
                expected      = $expected
                actual        = "error"
                error         = $lastError
                attempts      = $attempt
                elapsedMs     = $elapsedMs
            }
            $match = "ERROR"
        } else {
            if ($actualVerdict -eq $expected) {
                $correct++
                $match = "OK"
            } else {
                $match = "MISMATCH"
                $mismatches += [PSCustomObject]@{
                    questionId      = $qid
                    expected        = $expected
                    actual          = $actualVerdict
                    actualRaw       = $actualRaw
                    elapsedMs       = $elapsedMs
                    claimSummary    = ($q.claims | ForEach-Object { "$($_.type):$($_.subject.iri) -> $($_.object.iri)" }) -join "; "
                }
            }
        }

        # 混淆矩阵统计
        $confusionKey = "${expected} -> ${actualVerdict}"
        if ($verdictConfusion.ContainsKey($confusionKey)) {
            $verdictConfusion[$confusionKey]++
        } else {
            $verdictConfusion[$confusionKey] = 1
        }

        $statusIcon = switch ($match) {
            "OK"       { "[OK]" }
            "MISMATCH" { "[MISMATCH]" }
            "ERROR"    { "[ERROR]" }
        }
        $color = switch ($match) {
            "OK"       { "Green" }
            "MISMATCH" { "Yellow" }
            "ERROR"    { "Red" }
        }
        Write-Host ("  {0,-5} {1,-16} expected={2,-14} actual={3,-14} ({4}ms)" -f $statusIcon, $qid, $expected, $actualVerdict, $elapsedMs) -ForegroundColor $color

        $allResults += [PSCustomObject]@{
            questionId    = $qid
            ontologyId    = $ontologyId
            expected      = $expected
            actual        = $actualVerdict
            actualRaw     = $actualRaw
            match         = $match
            elapsedMs     = $elapsedMs
            attempts      = $attempt
        }
    }

    # 准确率
    $accuracy = if ($total -gt 0) { [math]::Round($correct / $total * 100, 2) } else { 0 }

    Write-Host ""
    Write-Host "  --- $name Summary ---" -ForegroundColor Cyan
    Write-Host "  Total:      $total"
    Write-Host "  Correct:    $correct"
    Write-Host "  Accuracy:   $accuracy%"
    Write-Host "  Mismatches: $($mismatches.Count)"
    Write-Host "  Errors:     $($errors.Count)"

    if ($mismatches.Count -gt 0) {
        Write-Host ""
        Write-Host "  Mismatch Details (expected -> actual):" -ForegroundColor Yellow
        $mismatches | ForEach-Object {
            Write-Host ("    {0,-16} {1,-14} -> {2,-14}" -f $_.questionId, $_.expected, $_.actual) -ForegroundColor Yellow
        }
    }

    if ($errors.Count -gt 0) {
        Write-Host ""
        Write-Host "  Error Details:" -ForegroundColor Red
        $errors | ForEach-Object {
            Write-Host ("    {0,-16} {1}" -f $_.questionId, $_.error) -ForegroundColor Red
        }
    }

    Write-Host ""
    Write-Host "  Verdict Confusion Matrix:" -ForegroundColor Cyan
    $verdictConfusion.GetEnumerator() | Sort-Object Name | ForEach-Object {
        Write-Host ("    {0,-40} : {1}" -f $_.Key, $_.Value)
    }

    # 保存单集结果
    $setResult = [PSCustomObject]@{
        suiteName    = $name
        ontologyId   = $ontologyId
        reasoner     = $reasoner
        mcpUrl       = $McpUrl
        timestamp    = $timestamp
        total        = $total
        correct      = $correct
        accuracy     = $accuracy
        mismatchCount = $mismatches.Count
        errorCount   = $errors.Count
        results      = $allResults
        mismatches   = $mismatches
        errors       = $errors
        confusionMatrix = ($verdictConfusion.GetEnumerator() | ForEach-Object { [PSCustomObject]@{ transition = $_.Key; count = $_.Value } })
    }

    $resultFile = Join-Path $OutputDir "${name}-results-${timestamp}.json"
    $setResult | ConvertTo-Json -Depth 10 | Out-File -FilePath $resultFile -Encoding UTF8
    Write-Host "  Results saved to: $resultFile" -ForegroundColor Green

    # 同时保存一个不带时间戳的 latest 版本
    $latestFile = Join-Path $OutputDir "${name}-results-latest.json"
    $setResult | ConvertTo-Json -Depth 10 | Out-File -FilePath $latestFile -Encoding UTF8

    # 保存 jsonl 格式的逐条结果（与 v086 baseline 格式对齐）
    $jsonlFile = Join-Path $OutputDir "${name}-output.jsonl"
    $jsonlLines = @()
    foreach ($r in $allResults) {
        $jsonlObj = [ordered]@{
            questionId       = $r.questionId
            ontologyId       = $r.ontologyId
            actualVerdict    = $r.actual
            actualRaw        = $r.actualRaw
            expectedVerdict  = $r.expected
            verdictMatch     = ($r.match -eq "OK")
            elapsedMs        = $r.elapsedMs
            executionStatus  = $(if ($r.match -eq "ERROR") { "error" } else { "completed" })
        }
        $jsonlLines += $jsonlObj | ConvertTo-Json -Compress -Depth 5
    }
    $jsonlLines | Out-File -FilePath $jsonlFile -Encoding UTF8
    Write-Host "  JSONL output saved to: $jsonlFile" -ForegroundColor Green

    return $setResult
}

<#
    主流程
#>

Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "  owl4agents v0.8.8 verify_claim Full Test Suite" -ForegroundColor Magenta
Write-Host "  Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host "  MCP URL: $McpUrl" -ForegroundColor Magenta
Write-Host "  Output:  $OutputDir" -ForegroundColor Magenta
Write-Host "  Suites:  $($SuiteDefs.Name -join ', ')" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta

# 健康检查
Write-Host ""
Write-Host "Performing MCP health check..." -ForegroundColor Cyan
try {
    $healthBody = @{
        jsonrpc = "2.0"
        method  = "tools/list"
        params  = @{}
        id      = 0
    } | ConvertTo-Json -Compress
    $healthResp = Invoke-RestMethod -Uri $McpUrl -Method Post -Headers $headers -Body $healthBody -TimeoutSec 30
    $toolCount = $healthResp.result.tools.Count
    Write-Host "  MCP server is alive. Available tools: $toolCount" -ForegroundColor Green
} catch {
    Write-Host "  WARN: MCP health check failed: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "  Continuing anyway..." -ForegroundColor Yellow
}

# 运行所有验证集
$allSuiteResults = @()
foreach ($def in $SuiteDefs) {
    $res = Invoke-Suite -SuiteDef $def
    if ($null -ne $res) {
        $allSuiteResults += $res
    }
}

# 汇总报告
Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "  OVERALL SUMMARY" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta

$totalAll = 0
$correctAll = 0
$mismatchAll = 0
$errorAll = 0

foreach ($r in $allSuiteResults) {
    $totalAll += $r.total
    $correctAll += $r.correct
    $mismatchAll += $r.mismatchCount
    $errorAll += $r.errorCount
    $acc = if ($r.total -gt 0) { [math]::Round($r.correct / $r.total * 100, 2) } else { 0 }
    Write-Host ("  {0,-18} total={1,-5} correct={2,-5} accuracy={3,6}%  mismatch={4,-4} error={5}" -f $r.suiteName, $r.total, $r.correct, $acc, $r.mismatchCount, $r.errorCount)
}

$overallAcc = if ($totalAll -gt 0) { [math]::Round($correctAll / $totalAll * 100, 2) } else { 0 }
Write-Host ""
Write-Host "  TOTAL: total=$totalAll  correct=$correctAll  accuracy=${overallAcc}%  mismatch=$mismatchAll  error=$errorAll" -ForegroundColor Magenta

# 保存汇总报告
$summary = [PSCustomObject]@{
    testSuite    = "owl4agents-v0.8.8-verify_claim-full-test"
    mcpUrl       = $McpUrl
    timestamp    = $timestamp
    completedAt  = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    totalClaims  = $totalAll
    correctClaims = $correctAll
    overallAccuracy = $overallAcc
    totalMismatches = $mismatchAll
    totalErrors   = $errorAll
    suites       = $allSuiteResults | ForEach-Object {
        [PSCustomObject]@{
            suiteName    = $_.suiteName
            ontologyId   = $_.ontologyId
            reasoner     = $_.reasoner
            total        = $_.total
            correct      = $_.correct
            accuracy     = $_.accuracy
            mismatchCount = $_.mismatchCount
            errorCount   = $_.errorCount
        }
    }
}

$summaryFile = Join-Path $OutputDir "summary-${timestamp}.json"
$summary | ConvertTo-Json -Depth 10 | Out-File -FilePath $summaryFile -Encoding UTF8
$latestSummaryFile = Join-Path $OutputDir "summary-latest.json"
$summary | ConvertTo-Json -Depth 10 | Out-File -FilePath $latestSummaryFile -Encoding UTF8

Write-Host ""
Write-Host "  Summary saved to: $summaryFile" -ForegroundColor Green
Write-Host "  Latest summary:   $latestSummaryFile" -ForegroundColor Green
Write-Host ""
Write-Host "  Done. $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
