---
name: api-integration
description: >-
  Guides verified, secure, and resilient external or internal API integrations.
  Activate when designing, implementing, or reviewing HTTP clients, FastAPI
  endpoints, webhooks, third-party SDKs, authentication flows, or sync workers
  to ensure verified contracts, real error handling, and execution-backed claims.
---

# Athltiq — API Integration

Implement and verify genuine API integrations with real schemas, secure authentication, and explicit failure handling in alignment with [AGENTS.md](../../AGENTS.md).

## When to Activate

- Adding or modifying REST/HTTP clients, FastAPI routes, synchronization workers, or third-party service integrations.
- Implementing authentication, token refresh, request signing, or payload serialization.
- Debugging network failures, timeouts, rate limits, or contract mismatches.

## Procedure

1. **Verify Official Documentation & Actual Contracts**
   - Inspect existing project API routes, OpenAPI/Pydantic schemas, client interfaces, and environment variable conventions before adding new ones.
   - Verify exact endpoint URLs, HTTP methods, headers, query/path parameters, request payloads, response schemas, and status codes against official documentation or the live service specification.

2. **Prevent Fabricated API Behavior**
   - Never return hardcoded JSON payloads, stubbed success responses, or fake client adapters in production code to make an integration appear complete.
   - Keep HTTP mock servers/stubs strictly inside automated test suites (`tests/`, `src/test/`) and clearly labelled as test doubles.

3. **Implement Secure Authentication & Configuration**
   - Load base URLs, API keys, client secrets, and signing keys strictly from environment variables, secret managers, or Android Keystore-backed storage—never hardcode credentials in source files.
   - Enforce server-side authentication, role-based access control, and resource ownership checks on every protected endpoint; never bypass auth checks during integration work.
   - Exclude secrets, bearer tokens, and unnecessary PII from request/response logs and error messages.
   - Handle local development loopbacks (`localhost`, `127.0.0.1`, `[::1]`, and emulator-specific `10.0.2.2` where applicable) consistently while enforcing HTTPS for non-local hosts; never bake emulator-only URLs as universal defaults for physical/web targets, and never let URL/session validation throw uncaught crashes after sign-in—catch and surface an inline error banner.

4. **Validate Payloads & Handle Failure Modes Explicitly**
   - Validate outbound and inbound payloads with strict schemas (e.g., Pydantic v2 / Kotlinx Serialization) and fail explicitly on unexpected schema drift.
   - Handle timeouts, connection loss, `401/403` auth errors, `404/422` validation errors, `429` rate limits (respecting `Retry-After` / bounded exponential backoff), and `5xx` upstream failures explicitly.
   - For offline-first sync (e.g., `WorkManager`), use idempotency keys/client UUIDs and preserve explicit pending/failed sync states instead of dropping data or faking success.

5. **Prove Integration with Execution Evidence**
   - Never declare an integration complete solely because code compiles or types match.
   - Execute real requests against the running local/staging service or clearly scoped contract/integration tests, and record the exact scope tested.
   - When bridging a physical device or local proxy (e.g., `adb reverse` or Vite `/api` proxy), verify reachability from inside the client runtime itself and keep persistent tunnels alive when testing across commands.

## Verification Requirements

- Run integration or contract tests covering success paths, malformed responses, auth rejections (`401/403`), timeouts, and rate-limit/5xx handling.
- Confirm zero hardcoded secrets or stubbed production responses exist in changed files.
- Report verbatim command outputs and clearly separate live-verified endpoints from locally mocked/untested ones.

## Expected Deliverables

- Verified API client/endpoint implementation matching documented schemas and project env conventions.
- Explicit error, timeout, rate-limit, and auth handling with zero fabricated responses.
- Concise verification report listing exact requests/tests executed and any unverified external dependencies.
