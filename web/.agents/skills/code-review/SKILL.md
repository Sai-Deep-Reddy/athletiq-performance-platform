---
name: code-review
description: >-
  Performs disciplined, minimal-diff code and architecture reviews grounded in
  Athltiq engineering rules. Activate when reviewing changes, auditing existing
  modules, planning refactors, or checking code before commits to catch fake data,
  placeholders, security flaws, duplication, performance issues, and regressions.
---

# Athltiq — Code Review

Audit existing code and proposed changes to ensure correctness, security, minimal scope, and architectural consistency in alignment with [AGENTS.md](../../AGENTS.md).

## When to Activate

- Reviewing new or existing files, pull requests, diffs, or module designs.
- Preparing to modify an unfamiliar subsystem (inspect-before-change audit).
- Checking completed work for placeholders, hardcoded outputs, duplication, or unintended deletions.

## Procedure

1. **Inspect Context & End-to-End Flow**
   - Read the target files, related callers/callees, schemas, configuration, and tests before evaluating or recommending edits.
   - Trace the full data path (UI -> domain/use-case -> vision/CV or DB/API -> back) to confirm behavior is real end-to-end.

2. **Audit Against Athltiq Integrity & Completeness Rules**
   - **Hardcoded outputs & fake data:** Flag hardcoded scores, measurements, user records, static chart series, or fake API responses masquerading as live behavior.
   - **Unfinished implementations & placeholders:** Flag `TODO`s in critical paths, stubbed handlers, non-functional UI controls (buttons/forms/filters/navigation wired to no-ops), or silent fallbacks hiding errors.
   - **Unnecessary files & duplication:** Identify duplicate helpers, parallel implementations of existing utilities, or extra files/docs/datasets created solely for appearance.

3. **Audit Security, Privacy & Reliability**
   - Check for hardcoded secrets/keys, auth/ownership/validation bypasses, SQL injection or unsafe output handling, PII in logs/error responses, missing boundary checks, and weakened security controls.
   - Check resource management and performance: blocking I/O or heavy ML on the main/UI thread, unclosed `ImageProxy`/`ImageBitmap`/`VideoFrame`/camera/DB streams (always close in `finally`), retaining uncompressed decoded frame bitmaps across a video clip in RAM (stream frame-by-frame instead), concurrent live-preview inference contention during offline clip evaluation, eager initialization of native/WASM ML engines before library load checks, N+1 queries, or unbounded memory allocations in frame loops.

4. **Check Architectural Consistency & Regression Risk**
   - Verify adherence to project module boundaries, naming, error/state modeling, and existing conventions.
   - Ensure no existing verified functionality, comments/docstrings, or tests were silently deleted, skipped, or weakened.

5. **Recommend the Smallest Correct Modification**
   - Prioritize findings by severity: (1) correctness/safety/data integrity, (2) security/privacy, (3) reliability/performance, (4) maintainability.
   - Propose the smallest, localized change that fixes root causes—reuse existing helpers rather than rewriting unrelated code.
   - Cite exact file paths, line numbers, and observed behavior only (never guess uninspected code).

## Verification Requirements

- Confirm every cited symbol, file path, and defect was directly observed in files read during the review.
- Verify any suggested fix preserves existing contracts and includes targeted test coverage.

## Expected Deliverables

- Severity-ordered review findings referencing exact file paths and line ranges.
- Specific, minimal remediation steps for each finding (or explicit confirmation when no defects were found in the inspected scope).
- Note of any uninspected areas or unexecuted checks that limit review coverage.
