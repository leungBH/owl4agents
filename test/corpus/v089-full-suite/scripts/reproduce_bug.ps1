# verify_claim bug 复现脚本
# 用法：powershell -ExecutionPolicy Bypass -File reproduce_commands.ps1
# 前提：owl4agents v0.8.8 MCP 服务运行在 http://10.67.82.218:8083/mcp

$McpUrl = "http://10.67.82.218:8083/mcp"
$headers = @{"Content-Type" = "application/json"}
$step = 0

function Invoke-Mcp($toolName, $arguments) {
    global:step++
    $body = @{
        jsonrpc = "2.0"
        method  = "tools/call"
        params  = @{ name = $toolName; arguments = $arguments }
        id      = $step
    } | ConvertTo-Json -Depth 10 -Compress

    Write-Host ""
    Write-Host "=== Step $($step): $toolName ==="
    $r = Invoke-RestMethod -Uri $McpUrl -Method Post -Headers $headers -Body $body -TimeoutSec 120
    $text = $r.result.content[0].text
    Write-Host $text
    return ($text | ConvertFrom-Json)
}

Write-Host "=== verify_claim bug reproduction ==="
Write-Host "MCP URL: $McpUrl"
Write-Host ""

# Step 1: 确认 Margherita 存在（search_entities）
$r1 = Invoke-Mcp "ontology_search_entities" @{
    ontology_id = "pizza"
    query       = "Margherita"
    limit       = 5
}
Write-Host "Expected: totalResults >= 1, Margherita found"
Write-Host "Actual: totalResults=$($r1.totalResults)"

# Step 2: 用 SPARQL ASK 确认 Margherita 是 owl:Class
$r2 = Invoke-Mcp "ontology_sparql_ask" @{
    ontology_id = "pizza"
    query       = "ASK WHERE { <http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita> a <http://www.w3.org/2002/07/owl#Class> }"
}
Write-Host "Expected: result=true"
Write-Host "Actual: result=$($r2.result)"

# Step 3: 用 get_class_context 确认 Margherita 有上下文
$r3 = Invoke-Mcp "ontology_get_class_context" @{
    ontology_id = "pizza"
    entity_iri  = "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita"
}
Write-Host "Expected: superclasses contains NamedPizza"
Write-Host "Actual: superclasses count=$($r3.superclasses.Count)"

# Step 4: 用 verify_claim 验证（BUG 复现）
$r4 = Invoke-Mcp "ontology_verify_claim" @{
    ontology_id = "pizza"
    reasoner    = "auto"
    claim       = @{
        id        = "pizza-sc-001-c1"
        type      = "subclass"
        subject   = @{ kind = "class"; iri = "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita" }
        predicate = "subClassOf"
        object    = @{ kind = "class"; iri = "http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza" }
    }
}
Write-Host "Expected: semanticVerdict=supported"
Write-Host "Actual: semanticVerdict=$($r4.semanticVerdict)"
Write-Host "BUG: verdict is out_of_scope instead of supported"

# Step 5: 用 detect_missing_entities 确认（BUG 复现）
$r5 = Invoke-Mcp "ontology_detect_missing_entities" @{
    ontology_id = "pizza"
    claim       = @{
        id        = "test1"
        type      = "subclass"
        subject   = @{ kind = "class"; iri = "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita" }
        predicate = "subClassOf"
        object    = @{ kind = "class"; iri = "http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza" }
    }
}
Write-Host "Expected: matched contains Margherita and NamedPizza"
Write-Host "Actual: matched count=$($r5.matched.Count), missing count=$($r5.missing.Count)"
Write-Host "BUG: entities not matched despite existing in ontology"

# Step 6: hpo 对比测试（部分正常）
Write-Host ""
Write-Host "=== hpo comparison ==="

# HP_0000002（正常）
$r6a = Invoke-Mcp "ontology_verify_claim" @{
    ontology_id = "hpo"
    reasoner    = "auto"
    claim       = @{
        id        = "hpo-001-c1"
        type      = "subclass"
        subject   = @{ kind = "class"; iri = "http://purl.obolibrary.org/obo/HP_0000002" }
        predicate = "subClassOf"
        object    = @{ kind = "class"; iri = "http://purl.obolibrary.org/obo/HP_0001507" }
    }
}
Write-Host "HP_0000002: verdict=$($r6a.semanticVerdict) (expected: supported)"

# HP_0000003（bug）
$r6b = Invoke-Mcp "ontology_verify_claim" @{
    ontology_id = "hpo"
    reasoner    = "auto"
    claim       = @{
        id        = "hpo-002-c1"
        type      = "subclass"
        subject   = @{ kind = "class"; iri = "http://purl.obolibrary.org/obo/HP_0000003" }
        predicate = "subClassOf"
        object    = @{ kind = "class"; iri = "http://purl.obolibrary.org/obo/HP_0000107" }
    }
}
Write-Host "HP_0000003: verdict=$($r6b.semanticVerdict) (expected: supported, got out_of_scope=BUG)"

# HP_0000003 search（确认存在）
$r6c = Invoke-Mcp "ontology_search_entities" @{
    ontology_id = "hpo"
    query       = "HP_0000003"
    limit       = 3
}
Write-Host "HP_0000003 search: totalResults=$($r6c.totalResults) (should be >= 1)"

Write-Host ""
Write-Host "=== Reproduction complete ==="
Write-Host "If Steps 4-5 show out_of_scope/missing while Steps 1-3 show the entity exists, the bug is confirmed."
