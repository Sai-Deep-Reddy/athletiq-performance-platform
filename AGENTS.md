# Athltiq — Project Engineering Rules

These instructions apply to all work performed in this repository. Follow them alongside the user's current task and the repository's existing architecture.

## 1. Core principles

Prioritize, in this order:
1. Correctness and user safety
2. Data integrity and truthful reporting
3. Security and privacy
4. Reliability and maintainability
5. Functionality
6. Visual polish

Do not make the application merely appear functional. Implement and verify the actual behavior requested.

## 2. No fabricated production data or functionality

- Never invent real users, accounts, profiles, activity records, measurements, scores, rankings, performance results, transactions, or other production records.
- Never seed a production database with fictional records.
- Never present sample, simulated, generated, cached, or stale values as live or genuine data.
- Never return predetermined values to make a feature appear operational.
- Never create fake API responses and describe the integration as complete.
- Never fabricate test results, execution logs, screenshots, user feedback, analytics, or benchmark results.
- If required data is missing or a service is unavailable, show an explicit empty, loading, unavailable, or error state. Explain the limitation; do not silently invent a fallback.
- Do not create extra files, datasets, images, components, or documentation simply to make the project look more complete. Create files only when they are needed for the requested implementation.

## 3. Mocks, fixtures, and demo data

Mocks and synthetic data are permitted only when they are necessary for isolated unit tests, integration tests, Storybook, or a clearly labelled demo environment.

When using them:
- Keep them separate from production code and production data paths.
- Put them in clearly named test or demo locations.
- Label them explicitly as mock, fixture, synthetic, or demo data.
- Ensure production builds and production API/database paths cannot accidentally consume them.
- Never use mock data to claim that a real backend, API, device integration, or feature works.
- State exactly where mocks or fixtures were introduced.

If the project already contains mock data, identify where it is used before changing it. Do not delete useful test fixtures without a reason.

## 4. Inspect before changing

Before editing a feature:
- Inspect the relevant files, dependencies, routes, components, services, schemas, and tests.
- Verify exact file paths, symbol imports, and line counts before reading or editing (never guess paths, API constants, or line ranges).
- Trace the existing data flow from the UI to the backend or external service and back.
- Check whether the requested functionality already exists.
- Prefer extending or correcting the existing implementation over creating duplicate modules.
- Respect established frameworks, conventions, architecture, and naming.
- Do not rewrite unrelated parts of the application.

For substantial, multi-file, architectural, database, authentication, or data-flow changes, first provide a concise plan identifying the files and behavior to change. For small, localized fixes, proceed directly and explain the change afterward. Do not repeatedly ask for approval when the task is already clear and authorized.

## 5. Real integrations and data provenance

- Use the actual configured backend, database, API, or device interface required by the feature.
- Inspect existing environment-variable names and configuration before adding new ones.
- Never hardcode secrets, API keys, passwords, access tokens, or private credentials. Use environment variables or the project's established secret-management mechanism.
- Verify endpoint URLs, request methods, payloads, response schemas, authentication, permissions, and error handling against available documentation or the actual service.
- Do not claim an integration is operational merely because code was written. Verify it with an actual request or a clearly stated substitute test.
- Where the origin of data matters, preserve useful provenance such as source, retrieval time, units, and relevant identifiers.
- Distinguish live data from cached data and explain cache freshness where relevant.
- Handle unavailable services, rate limits, invalid responses, timeouts, and permission failures explicitly.

## 6. Dynamic values and hardcoding

- Do not hardcode values that should come from users, the database, an API, device measurements, or runtime configuration.
- Hardcoded constants are acceptable when they are genuinely static application configuration, design tokens, protocol constants, or documented domain constants.
- Keep such constants centralized where appropriate, name them clearly, and document their meaning and source when material.
- Never hardcode a success response, user result, score, measurement, chart series, or API result to imitate real runtime behavior.

## 7. Database and user data

- Follow the existing schema, migrations, validation, and authorization model.
- Do not bypass authentication, authorization, ownership checks, or server-side validation to make a feature work.
- Do not expose private user data in logs, client bundles, error messages, or public endpoints.
- Use least-privilege access and validate all untrusted inputs at the appropriate boundary.
- Make database changes through the project's established migration or schema process.
- Do not run destructive operations, reset databases, delete user data, or overwrite production data unless the user explicitly requested that exact action.
- Do not create fictitious accounts or records in a real environment for testing.

## 8. Security and privacy

- Treat credentials, tokens, personal information, and user-generated content as sensitive.
- Do not commit `.env` files, secrets, private keys, or credentials.
- Avoid logging secrets or unnecessary personal information.
- Validate and sanitize untrusted input; use parameterized database queries and safe output handling.
- Preserve appropriate access controls and session protections.
- Do not weaken security controls or disable validation simply to pass a test.
- Flag security-sensitive changes and explain their implications.

## 9. Testing and verification

- Inspect the existing test and build commands before choosing how to verify changes.
- Add or update tests for changed behavior where practical.
- Run the relevant tests, type checks, linting, and build commands when available and proportionate to the change.
- Verify packaged build artifacts actually include required runtime assets (e.g., `.task` models, `.wasm` binaries, native `.so` libs) and lazy-initialize native/ML engines only after explicit loader verification.
- Tests must exercise the actual implementation. Keep test fixtures isolated as described above.
- Do not skip, disable, weaken, or delete tests merely to obtain a passing result.
- Do not claim a test, build, API call, or device operation succeeded unless it was actually executed and succeeded.
- If a check cannot be run, state why and identify it as unverified.
- For calculations, scoring, ranking, or measurement features, test important boundaries, invalid inputs, units, rounding, and expected behavior using independently derived test cases where possible.

## 10. Frontend behavior

- Connect UI elements to real application state and real service operations where those are required.
- Do not make buttons, forms, filters, charts, navigation, or settings appear functional when they have no working behavior.
- Do not populate dashboards with fabricated values to avoid empty states.
- Provide honest loading, empty, unavailable, validation, success, and error states.
- Preserve accessibility, responsive behavior, and the existing design system.
- Clearly label demo-only screens and data.

## 11. File and dependency discipline

- Do not create duplicate files or parallel implementations without a demonstrated need. Never leave empty scaffolded modules or zero-implementation placeholder targets to make architecture look complete.
- Do not add dependencies when existing project capabilities are sufficient.
- Before adding a dependency, check whether it is maintained, compatible, necessary, and already available in the project.
- Do not make unrelated formatting or generated-file changes.
- Do not delete or rename files without checking their references and explaining why the change is necessary.
- Keep changes focused on the user's requested task.

## 12. Required completion report

After each implementation task, report:

1. **What changed:** concise description of the implemented behavior.
2. **Files changed:** every created, modified, renamed, or deleted file and why.
3. **Data and integrations:** actual sources/services used; clearly identify mocks, fixtures, cached data, and demo data.
4. **Verification:** exact commands/checks executed and their actual results.
5. **Limitations:** unavailable services, unverified behavior, assumptions, failures, and remaining work.
6. **Hardcoding:** any new hardcoded values and why they are appropriate.

Separate verified facts from assumptions. Do not describe unfinished or untested functionality as complete.

## 13. When blocked or uncertain

If a required source, credential, device, service, specification, or permission is unavailable:
- Do not guess, fabricate, or silently substitute.
- Complete independent work that can be implemented correctly.
- Mark the blocked part clearly and explain what is needed to verify it.
- Prefer a safe and explicit unavailable state over misleading behavior.

These rules are persistent project constraints. A task-specific user instruction may refine them, but do not interpret a request for speed, polish, or completeness as permission to fabricate data, bypass security, or misrepresent verification.
