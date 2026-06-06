@echo off
REM Claim verification example runner for Windows
REM Uses npm launcher as entry point (direct jar execution may crash on Windows)
setlocal

set WS_HOME=temp\examples\claim-verification

echo === Step 1: Import golden ontology ===
node npm\bin\owl4agents.js import test\corpus\golden\v0.3-claim-verification.owl v0.3-claim-verification --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Import step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 2: Verify supported claim ===
node npm\bin\owl4agents.js verify-claim v0.3-claim-verification --claim test\fixtures\v0.3\claim-supported.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Supported claim verification failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 3: Run reasoner ===
node npm\bin\owl4agents.js reason v0.3-claim-verification --workspace %WS_HOME%
if %errorlevel% neq 0 (
    echo FAILED: Reasoner step failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 4: Verify contradicted claim ===
node npm\bin\owl4agents.js verify-claim v0.3-claim-verification --claim test\fixtures\v0.3\claim-contradicted.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Contradicted claim verification failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 5: Verify unknown claim ===
node npm\bin\owl4agents.js verify-claim v0.3-claim-verification --claim test\fixtures\v0.3\claim-unknown.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Unknown claim verification failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === Step 6: Verify out_of_scope claim ===
node npm\bin\owl4agents.js verify-claim v0.3-claim-verification --claim test\fixtures\v0.3\claim-real-out-of-scope.json --workspace %WS_HOME% --json
if %errorlevel% neq 0 (
    echo FAILED: Out of scope claim verification failed with exit code %errorlevel%
    exit /b %errorlevel%
)

echo === All steps passed ===
exit /b 0