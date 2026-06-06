@echo off
REM Pizza reasoning example runner for Windows
REM Uses npm launcher as entry point
setlocal

set WS_HOME=pizza-demo

echo === Step 1: Import Pizza ontology ===
node npm\bin\owl4agents.js import test\corpus\smoke\pizza.owl pizza --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Import step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 2: Summary ===
node npm\bin\owl4agents.js summary pizza --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Summary step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 3: Entity context ===
node npm\bin\owl4agents.js entity pizza http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Entity context step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 4: Classify ===
node npm\bin\owl4agents.js classify pizza --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Classify step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 5: Property characteristics ===
node npm\bin\owl4agents.js properties pizza --property http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Property characteristics step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === All steps passed ===
exit /b 0