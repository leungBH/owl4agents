@echo off
REM MCP server launcher for Windows
REM This wrapper avoids ACCESS_VIOLATION when running java from Node.js

setlocal

REM Find the script's directory
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

REM Check for shadow jar
set "JAR_PATH=%PROJECT_ROOT%\modules\ontology-cli\build\libs\owl4agents.jar"
if exist "%JAR_PATH%" (
    if defined JAVA_HOME (
        "%JAVA_HOME%\bin\java.exe" -jar "%JAR_PATH%" mcp %*
    ) else (
        java -jar "%JAR_PATH%" mcp %*
    )
    exit /b %errorlevel%
)

REM Check for fat jar
set "FAT_JAR=%PROJECT_ROOT%\modules\ontology-cli\build\libs\ontology-cli-all.jar"
if exist "%FAT_JAR%" (
    if defined JAVA_HOME (
        "%JAVA_HOME%\bin\java.exe" -jar "%FAT_JAR%" mcp %*
    ) else (
        java -jar "%FAT_JAR%" mcp %*
    )
    exit /b %errorlevel%
)

REM Fallback to Gradle wrapper
set "GRADLEW=%PROJECT_ROOT%\gradlew.bat"
if exist "%GRADLEW%" (
    "%GRADLEW%" :modules:ontology-cli:run --args="mcp %*" --console=plain
    exit /b %errorlevel%
)

echo Error: No runtime found. Run 'gradle build' first.
exit /b 1
