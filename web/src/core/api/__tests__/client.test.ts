import { describe, expect, it, vi } from 'vitest';
import { ApiClientError, AthletiqApiClient } from '../client';

describe('AthletiqApiClient', () => {
  it('sends credentials=include and X-CSRF-Token on mutating cookie requests', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            user: {
              id: '00000000-0000-0000-0000-000000000001',
              institution_id: '00000000-0000-0000-0000-000000000002',
              email: 'admin@test.local',
              full_name: 'Admin User',
              role: 'ADMIN',
              is_active: true,
              created_at: '2026-11-25T00:00:00Z',
            },
            institution: {
              id: '00000000-0000-0000-0000-000000000002',
              name: 'Test Academy',
              code: 'TST',
              is_active: true,
              created_at: '2026-11-25T00:00:00Z',
            },
            csrf_token: 'csrf-secret-xyz',
            expires_at: '2026-11-25T01:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: '00000000-0000-0000-0000-000000000003',
            institution_id: '00000000-0000-0000-0000-000000000002',
            title: 'Exam 1',
            test_types_json: ['HEIGHT'],
            status: 'ACTIVE',
            created_by_user_id: '00000000-0000-0000-0000-000000000001',
            created_at: '2026-11-25T00:00:00Z',
          }),
          { status: 201, headers: { 'Content-Type': 'application/json' } },
        ),
      );

    const client = new AthletiqApiClient({ baseUrl: '', fetchImpl: fetchMock });
    const session = await client.webLogin('admin@test.local', 'Strong-Pass-123!');
    expect(session.institution.name).toBe('Test Academy');

    await client.createExamination({ title: 'Exam 1', test_types: ['HEIGHT'] });
    const secondCallInit = fetchMock.mock.calls[1][1]!;
    expect(secondCallInit.credentials).toBe('include');
    expect(new Headers(secondCallInit.headers).get('X-CSRF-Token')).toBe('csrf-secret-xyz');
  });

  it('surfaces explicit RATE_LIMITED error with Retry-After and SCHEMA_DRIFT on malformed payloads', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: 'Too many attempts' }), {
          status: 429,
          headers: { 'Retry-After': '12' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ wrong: 'shape' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

    const client = new AthletiqApiClient({ baseUrl: '', fetchImpl: fetchMock });
    await expect(client.checkHealth()).rejects.toMatchObject({
      kind: 'RATE_LIMITED',
      statusCode: 429,
      retryAfterSeconds: 12,
    } satisfies Partial<ApiClientError>);

    await expect(client.checkHealth()).rejects.toMatchObject({
      kind: 'SCHEMA_DRIFT',
    } satisfies Partial<ApiClientError>);
  });
});
