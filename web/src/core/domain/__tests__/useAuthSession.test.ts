import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AthletiqApiClient } from '../../api/client';
import { useAuthSession } from '../useAuthSession';

describe('useAuthSession', () => {
  it('transitions from BOOTING to UNAUTHENTICATED when /me returns 401 and authenticates via cookie login', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      // Initial checkHealth
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ status: 'ok', database: 'connected' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      // Initial getMe -> 401
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: 'Authentication required' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      // Initial getInstitution -> 401
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: 'Authentication required' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      // webLogin
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            user: {
              id: '00000000-0000-0000-0000-000000000010',
              institution_id: '00000000-0000-0000-0000-000000000020',
              email: 'inst@test.local',
              full_name: 'Instructor One',
              role: 'INSTRUCTOR',
              is_active: true,
              created_at: '2026-11-25T00:00:00Z',
            },
            institution: {
              id: '00000000-0000-0000-0000-000000000020',
              name: 'Demo Free Academy',
              code: 'DFA',
              is_active: true,
              created_at: '2026-11-25T00:00:00Z',
            },
            csrf_token: 'csrf-abc',
            expires_at: '2026-11-25T01:00:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      );

    const api = new AthletiqApiClient({ baseUrl: '', fetchImpl: fetchMock });
    const { result } = renderHook(() => useAuthSession(api));

    await waitFor(() => expect(result.current.status).toBe('UNAUTHENTICATED'));
    expect(result.current.backendHealthy).toBe(true);

    let ok = false;
    await act(async () => {
      ok = await result.current.login('inst@test.local', 'Strong-Pass-123!');
    });

    expect(ok).toBe(true);
    expect(result.current.status).toBe('AUTHENTICATED');
    expect(result.current.canEditAssessments).toBe(true);
    expect(result.current.isAdmin).toBe(false);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('allows zero-friction Guest Mode entry even when backend is unavailable and exits cleanly', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockRejectedValueOnce(new Error('Network offline'));

    const api = new AthletiqApiClient({ baseUrl: '', fetchImpl: fetchMock });
    const { result } = renderHook(() => useAuthSession(api));

    await waitFor(() => expect(result.current.status).toBe('UNAVAILABLE'));

    act(() => {
      result.current.enterGuestMode();
    });

    expect(result.current.status).toBe('GUEST');
    expect(result.current.isGuest).toBe(true);
    expect(result.current.canRunLocalAssessments).toBe(true);
    expect(result.current.canEditAssessments).toBe(false);

    await act(async () => {
      await result.current.logout();
    });

    expect(result.current.status).toBe('UNAVAILABLE');
    expect(result.current.isGuest).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
