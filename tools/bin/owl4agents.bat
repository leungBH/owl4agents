@echo off
REM owl4agents launcher (Windows batch)
REM
REM Starts the owl4agents MCP HTTP server with OOM-protected JVM flags:
REM   -XX:+ExitOnOutOfMemoryError     -> JVM exits with code 3 on OOM (lets watchdog restart)
REM   -XX:+HeapDumpOnOutOfMemoryError -> JVM writes heap dump before exiting
REM   -XX:HeapDumpPath=./owl4agents-heapdump.hprof -> heap dump location
REM
REM The heap dump file is deleted BEFORE each launch so repeated OOM events
REM do not exhaust disk (only the most recent heap dump is preserved).
REM
REM Usage:
REM   tools\bin\owl4agents.bat [mcp args...]
REM
REM Default args (if none supplied): mcp --transport=http --host=0.0.0.0 --port=8080
REM
REM Requires: build/modules/ontology-cli/libs/owl4agents.jar (run
REM   `gradlew.bat :modules:ontology-cli:shadowJar` first).

setlocal enableextensions

REM Script lives in tools\bin\, project root is two levels up.
set "PROJECT_ROOT=%~dp0..\..\"
pushd "%PROJECT_ROOT%"

REM Locate shadow jar (built by :modules:ontology-cli:shadowJar).
set "JAR_PATH=%PROJECT_ROOT%build\modules\ontology-cli\libs\owl4agents.jar"
if not exist "%JAR_PATH%" (
    echo Error: shadow jar not found: %JAR_PATH%
    echo Run '.\gradlew.bat :modules:ontology-cli:shadowJar' first.
    popd
    exit /b 1
)

REM v0.8.6 P0-4: delete stale heap dump before launch to prevent disk exhaustion.
if exist .\owl4agents-heapdump.hprof del .\owl4agents-heapdump.hprof

REM Default args: start MCP HTTP server on 0.0.0.0:8080.
if "%~1"=="" (
    set "MCP_ARGS=mcp --transport=http --host=0.0.0.0 --port=8080"
) else (
    set "MCP_ARGS=%*"
)

REM v0.8.6 P0-4: hardcode OOM JVM flags (D5). The JVM-Args manifest attribute is
REM documentation only; java -jar does NOT read it. Launch scripts must hardcode.
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -Xmx4g ^
        -XX:+ExitOnOutOfMemoryError ^
        -XX:+HeapDumpOnOutOfMemoryError ^
        -XX:HeapDumpPath=./owl4agents-heapdump.hprof ^
        -jar "%JAR_PATH%" %MCP_ARGS%
) else (
    java -Xmx4g ^
        -XX:+ExitOnOutOfMemoryError ^
        -XX:+HeapDumpOnOutOfMemoryError ^
        -XX:HeapDumpPath=./owl4agents-heapdump.hprof ^
        -jar "%JAR_PATH%" %MCP_ARGS%
)

set "EXIT_CODE=%errorlevel%"
popd
exit /b %EXIT_CODE%
