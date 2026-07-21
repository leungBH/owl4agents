# owl4agents launcher (Windows PowerShell)
#
# Starts the owl4agents MCP HTTP server with OOM-protected JVM flags:
#   -XX:+ExitOnOutOfMemoryError     -> JVM exits with code 3 on OOM (lets watchdog restart)
#   -XX:+HeapDumpOnOutOfMemoryError -> JVM writes heap dump before exiting
#   -XX:HeapDumpPath=./owl4agents-heapdump.hprof -> heap dump location
#
# The heap dump file is deleted BEFORE each launch so repeated OOM events
# do not exhaust disk (only the most recent heap dump is preserved).
#
# Usage:
#   .\owl4agents.ps1 [mcp args...]
#
# Default args (if none supplied): mcp --transport=http --host=0.0.0.0 --port=8080
#
# Requires: build/modules/ontology-cli/libs/owl4agents.jar (run
#   `.\gradlew.bat :modules:ontology-cli:shadowJar` first).

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$McpArgs
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
Set-Location -Path $ProjectRoot

# Locate shadow jar (built by :modules:ontology-cli:shadowJar).
$JarPath = Join-Path $ProjectRoot "build\modules\ontology-cli\libs\owl4agents.jar"
if (-not (Test-Path $JarPath)) {
    Write-Error "shadow jar not found: $JarPath. Run '.\gradlew.bat :modules:ontology-cli:shadowJar' first."
    exit 1
}

# v0.8.6 P0-4: delete stale heap dump before launch to prevent disk exhaustion.
if (Test-Path .\owl4agents-heapdump.hprof) {
    Remove-Item .\owl4agents-heapdump.hprof
}

# Default args: start MCP HTTP server on 0.0.0.0:8080.
if ($null -eq $McpArgs -or $McpArgs.Count -eq 0) {
    $McpArgs = @("mcp", "--transport=http", "--host=0.0.0.0", "--port=8080")
}

# Resolve java executable (prefer JAVA_HOME).
if ($env:JAVA_HOME) {
    $JavaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    $JavaExe = "java"
}

# v0.8.6 P0-4: hardcode OOM JVM flags (D5). The JVM-Args manifest attribute is
# documentation only; java -jar does NOT read it. Launch scripts must hardcode.
$JvmArgs = @(
    "-Xmx4g",
    "-XX:+ExitOnOutOfMemoryError",
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=./owl4agents-heapdump.hprof",
    "-jar",
    $JarPath
) + $McpArgs

& $JavaExe @JvmArgs
exit $LASTEXITCODE
