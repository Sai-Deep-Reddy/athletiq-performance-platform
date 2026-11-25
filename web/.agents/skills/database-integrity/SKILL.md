---
name: database-integrity
description: >-
  Protects database schemas, migrations, transactions, referential integrity, and
  sensitive user data across Room/SQLCipher and PostgreSQL/SQLAlchemy. Activate
  when designing tables, writing queries or DAOs, creating migrations, syncing
  records, or handling student, examination, and biometric records.
---

# Athltiq — Database Integrity

Maintain schema correctness, transactional safety, referential integrity, and strict user-data protection in alignment with [AGENTS.md](../../AGENTS.md).

## When to Activate

- Creating or modifying database entities, Room DAOs, SQLAlchemy models, Alembic/Room migrations, or repository sync logic.
- Writing queries involving student profiles, examinations, attempts, calibration records, scoring standards, or audit logs.
- Investigating duplicate records, constraint violations, or data-isolation concerns.

## Procedure

1. **Inspect Existing Database Architecture First**
   - Inspect existing entities, foreign keys, indexes, constraints, migration history, and authorization boundaries before altering any schema or query.
   - Respect established ORM/persistence conventions (e.g., Room + SQLCipher on Android; SQLAlchemy 2.0 + Alembic + PostgreSQL on backend).

2. **Enforce Schema & Referential Integrity**
   - Define explicit primary keys, foreign key constraints (`ON DELETE` behavior chosen deliberately), `NOT NULL` constraints, check constraints, and unique indexes (e.g., institution-scoped student IDs, idempotency keys).
   - Prevent duplicate or inconsistent records through database-level uniqueness constraints and idempotent upsert logic rather than best-effort application checks alone.
   - Store units, timestamps (UTC/monotonic provenance), and calibration/standard references explicitly alongside measurements.

3. **Validate Migrations Safely**
   - Make all schema changes exclusively through versioned migrations (Alembic revisions / Room `Migration` objects with exported schemas).
   - Never use destructive shortcuts (`DROP TABLE`, `fallbackToDestructiveMigration` in production, database resets, or wiping user data) unless explicitly requested by the user.
   - Verify both upgrade and downgrade/compatibility paths against existing data shapes.

4. **Use Proper Transactions & Parameterized Queries**
   - Wrap multi-step mutations (e.g., recording an attempt, updating best-attempt flags, appending an audit log, enqueueing sync outbox rows) in atomic database transactions.
   - Always use parameterized queries/ORM bindings—never interpolate untrusted strings into raw SQL.

5. **Protect Sensitive Data & Prevent Fake Production Records**
   - Enforce institution/tenant isolation and server-side ownership/RBAC checks on all reads and writes.
   - Protect sensitive records at rest (e.g., SQLCipher with Android Keystore-wrapped keys; encrypted/app-private media paths) and never leak PII in logs, error payloads, or unauthenticated endpoints.
   - Never seed production databases with fictional students, test scores, or placeholder accounts; keep test fixtures isolated to test databases.

6. **Verify Actual Database Operations**
   - Execute real database tests (migrations, constraints, rollbacks, duplicate-prevention, and tenant isolation) against an actual DB engine before reporting completion.

## Verification Requirements

- Run migration checks and DB integration tests verifying constraint enforcement, transaction rollback on failure, idempotency, and authorization isolation.
- Confirm no raw SQL string interpolation, unmigrated schema edits, or production seed fixtures were introduced.

## Expected Deliverables

- Versioned schema and migration files preserving referential integrity and backwards safety.
- Atomic, parameterized data-access methods with tenant/authorization enforcement.
- Actual DB test execution results and explicit disclosure of any checks that could not run (e.g., if a live PostgreSQL/device target was unavailable).
