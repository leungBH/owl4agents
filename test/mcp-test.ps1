# MCP Server Test Script for PowerShell
# Tests the MCP server by sending JSON-RPC requests

$requests = @'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ontology_list","arguments":{}}}
'@

Write-Host "Testing MCP server..."
Write-Host "=" * 60

# Run MCP server with piped input
$result = $requests | & .\gradlew.bat :modules:ontology-cli:run --args="mcp" --console=plain 2>&1

Write-Host "`nOutput:"
Write-Host $result

# Try to parse JSON responses
$jsonLines = $result | Where-Object { $_ -match '^\{' }
if ($jsonLines) {
    Write-Host "`nParsed JSON responses:"
    foreach ($line in $jsonLines) {
        try {
            $obj = $line | ConvertFrom-Json
            Write-Host ($obj | ConvertTo-Json -Depth 5)
        } catch {
            # Not valid JSON
        }
    }
}
