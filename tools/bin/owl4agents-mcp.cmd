@echo off
REM MCP server launcher for Windows
REM This wrapper provides an alternative entry point for MCP clients that
REM have trouble with Node.js stdin/stdout forwarding.
REM Uses java -cp mode to avoid the ACCESS_VIOLATION crash that java -jar
REM produces on some Windows environments.

setlocal

REM Find the script's directory
set "PROJECT_ROOT=%~dp0..\.."

REM Primary: use java -cp with the shadow jar (avoids ACCESS_VIOLATION)
REM java -cp works with the shadow jar because it contains all merged classes without relocation.
set "JAR_PATH=%PROJECT_ROOT%\modules\ontology-cli\build\libs\owl4agents.jar"
if exist "%JAR_PATH%" (
    if defined JAVA_HOME (
        "%JAVA_HOME%\bin\java.exe" -cp "%JAR_PATH%" org.owl4agents.cli.Owl4AgentsCli mcp %*
    ) else (
        java -cp "%JAR_PATH%" org.owl4agents.cli.Owl4AgentsCli mcp %*
    )
    exit /b %errorlevel%
)

REM Fallback: Gradle wrapper (MCP stdin/stdout works through Gradle run)
set "GRADLEW=%PROJECT_ROOT%\gradlew.bat"
if exist "%GRADLEW%" (
    "%GRADLEW%" :modules:ontology-cli:run --args="mcp %*" --console=plain --no-daemon
    exit /b %errorlevel%
)

echo Error: No runtime found. Run 'gradlew.bat :modules:ontology-cli:shadowJar' first.
exit /b 1