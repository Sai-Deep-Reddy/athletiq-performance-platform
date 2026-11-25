---
name: testing-verification
description: >-
  Designs and executes honest, rigorous unit, integration, and boundary tests
  across Kotlin/Android and FastAPI/PostgreSQL. Activate when adding features,
  fixing bugs, validating calculations or API/DB behavior, or verifying changes
  before completion to ensure real execution evidence and zero fabricated passes.
---

# Athltiq — Testing and Verification

Ensure every change is backed by meaningful automated or clearly scoped verification with truthful reporting in alignment with [AGENTS.md](../../AGENTS.md).

## When to Activate

- Implementing new features, bug fixes, biomechanical calculators, API endpoints, or database queries.
- Verifying changes before claiming completion or preparing a release/stage report.
- Checking mathematical/measurement accuracy, error handling, edge cases, or regression risk.

## Procedure

1. **Inspect Existing Test & Build Tooling First**
   - Locate existing test runners, build scripts, linters, type checkers, and test directories (e.g., Gradle `:test` / `:connectedAndroidTest`, `pytest`, `ruff`/`mypy`, `ktlint`/`detekt`) before writing or running checks.
   - Verify exact executable/venv/wrapper paths (e.g., `backend/.venv/Scripts/python -m pytest` when `app` is not editable-installed, Gradle wrapper presence, Node scripts) and shell semantics (on Windows PowerShell 5.1 use `;` or `cmd /c "..."` instead of `&&`, and check `$LASTEXITCODE` rather than letting `2>&1` convert stderr tool warnings into false failures). Check `manage_task` status before killing background tasks.

2. **Design Meaningful Tests Exercising Real Code**
   - Test actual production implementations—never test trivial mocks that bypass the real logic under test.
   - Isolate synthetic fixtures, sample frames, or mocked network/DB doubles strictly inside test sources (`src/test`, `src/androidTest`, `tests/`), labelled explicitly as test fixtures.
   - Never skip, disable, weaken assertions, or delete failing tests just to obtain a green run.

3. **Cover Required Test Dimensions**
   - **Unit & Mathematical/Measurement Boundaries:** Independently derive expected outputs for calculations, scoring, rankings, and unit conversions; test zero, negative, boundary, rounding, NaN/Inf, jitter/dropped-frame, coordinate-orientation variants (`+Y` up and OpenCV top-left `+Y` down), filter responsiveness on real normalized `[0..1]` trajectories, and invalid-input cases.
   - **Packaging & Cold-Start Verification:** Confirm built artifacts (`dist/`, APK assets) physically contain required `.task`/`.wasm`/native binaries and verify cold startup succeeds without eager native-init crashes.
   - **API Integrations:** Test request serialization, response validation, auth/RBAC enforcement (`401/403`), `422` validation errors, timeouts, rate limits (`429`), and server errors (`5xx`).
   - **Database Operations:** Test migrations, schema constraints, unique/FK rejections, transaction rollbacks, idempotent sync/upserts, and tenant/ownership isolation.
   - **Error Handling & Regressions:** Add regression tests reproducing fixed bugs and verify explicit unavailable/low-confidence/error states instead of silent fallbacks.

4. **Execute & Report Truthfully**
   - Run the relevant unit tests, integration tests, static checks, and builds **after** the final edit.
   - Report exact commands run, exact scope (including any filters, mocks, or skipped suites), and verbatim observed pass/fail counts.
   - Never fabricate test logs, coverage, or benchmark numbers, and explicitly mark anything that could not be run (e.g., no physical Android device or Docker daemon) as **unverified** with the reason.

## Verification Requirements

- Confirm all new or updated tests ran after the final code change and failed prior to a bug fix (where applicable) or verified the intended behavior without relaxed assertions.
- Confirm any unrun suites (such as instrumented device tests or hardware-camera checks) are explicitly reported as unverified.

## Expected Deliverables

- Isolated, maintainable unit and/or integration tests covering normal, boundary, and error paths.
- Exact verification summary listing commands executed, actual observed results, mocked/fixture boundaries, and any unverified scope.
