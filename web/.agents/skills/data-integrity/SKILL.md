---
name: data-integrity
description: >-
  Enforces strict data authenticity, provenance, and fixture isolation across the
  Athltiq project. Activate when creating, editing, or auditing models, schemas,
  measurements, dashboards, analytics, seed scripts, fixtures, or API/UI data
  flows to prevent fabricated records, hardcoded runtime metrics, and silent fallbacks.
---

# Athltiq — Data Integrity

Enforce truthful data handling across all backend, Android, ML/vision, and frontend layers in alignment with [AGENTS.md](../../AGENTS.md).

## When to Activate

- Building or modifying data models, calculators, dashboards, charts, rankings, or reports.
- Creating seed scripts, sample payloads, test fixtures, or demo views.
- Handling missing, uncalibrated, cached, stale, or failed measurements and external data.

## Procedure

1. **Trace Data Origin & Provenance**
   - Trace every displayed or persisted metric from its origin (camera frame/sensor, database row, API response, or calculation) to its consumer.
   - Preserve required provenance metadata where relevant: data source, retrieval or capture timestamp, measurement units, calibration/algorithm version, and confidence/validity indicators.
   - Explicitly distinguish live data from cached or stale data and surface cache freshness where applicable.
   - Document and verify coordinate-frame conventions end-to-end (`+Y` upward vs image/OpenCV top-to-bottom `+Y` downward, travel direction axis, normalized `[0..1]` vs pixel vs `cm`), and ensure filter hyperparameters (e.g., 1-Euro `beta`) match the coordinate unit scale actually passed into the filter.

2. **Eliminate Fabricated & Hardcoded Runtime Data**
   - Reject and flag any hardcoded user profiles, accounts, activity logs, physical measurements, scores, rankings, chart series, or predetermined success outputs in production paths.
   - Reject any silent fallback values (e.g., substituting `0`, average values, or synthetic defaults when calibration, pose landmarks, or backend data are missing).
   - Require explicit `loading`, `empty`, `unavailable`, `calibration_required`, `low_confidence`, or `error` states whenever real data cannot be produced.
   - Verify that qualification thresholds or scoring standards come from verified institution/configuration data rather than invented cutoffs.

3. **Enforce Incoming Data Validation**
   - Validate all untrusted inputs (API requests, sensor/landmark payloads, CSV/JSON imports, user inputs) at system boundaries for types, physical plausibility bounds, required fields, and units.
   - Reject malformed, incomplete, or physically impossible records cleanly with structured error messages.

4. **Isolate Mocks, Fixtures, and Demo Datasets**
   - Permit synthetic/mock datasets **only** inside clearly named test directories (`test/`, `androidTest/`, `tests/fixtures/`) or explicitly labelled demo/Storybook scopes.
   - Verify production builds, release flavors, and production API/DB ingestion paths cannot import, seed, or consume test fixtures or placeholder datasets.
   - Document the exact file paths and purpose whenever any test fixture or demo dataset is added.

5. **Prevent Unrequested Filler Artifacts**
   - Do not generate extra sample datasets, placeholder images, or dummy records solely to make screens or repositories look populated.

## Verification Requirements

- Search modified production paths for hardcoded sample arrays, dummy user IDs, placeholder metrics, and silent `?: defaultValue` fallbacks on failed measurements.
- Confirm tests use isolated fixtures and verify both valid inputs and missing/invalid/unavailable data paths.
- Inspect serialization and UI state mappings to confirm provenance and units are preserved intact.

## Expected Deliverables

- Production code paths free of fabricated records, placeholder datasets, and silent fallbacks.
- Explicit boundary validation and honest unavailable/error states.
- Clear inventory of any isolated test/demo fixtures created or modified and confirmation they are unreachable from production paths.
