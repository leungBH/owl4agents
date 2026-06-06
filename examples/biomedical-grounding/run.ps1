@echo off
REM Biomedical grounding example runner for Windows
REM Uses npm launcher as entry point
setlocal

set WS_HOME=bio-demo

echo === Step 1: Import biomedical ontology ===
node npm\bin\owl4agents.js import test\corpus\golden\v0.4-biomedical-grounding.owl v0.4-biomedical-grounding --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Import step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 2: Search for Hypertension ===
node npm\bin\owl4agents.js search v0.4-biomedical-grounding Hypertension --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Search step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 3: Entity context for Hypertension ===
node npm\bin\owl4agents.js entity v0.4-biomedical-grounding http://example.org/v0.4-biomedical#Hypertension --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Entity context step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 4: Run reasoner ===
node npm\bin\owl4agents.js reason v0.4-biomedical-grounding --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Reasoner step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 4: Verify supported claim ===
node npm\bin\owl4agents.js verify-claim v0.4-biomedical-grounding --claim test\fixtures\v0.4\claim-bio-supported.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Supported claim step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 5: Verify unknown claim ===
node npm\bin\owl4agents.js verify-claim v0.4-biomedical-grounding --claim test\fixtures\v0.4\claim-bio-unknown.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Unknown claim step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 6: Verify out_of_scope claim ===
node npm\bin\owl4agents.js verify-claim v0.4-biomedical-grounding --claim test\fixtures\v0.4\claim-bio-out-of-scope.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Out of scope claim step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === All steps passed ===
exit /b 0