import { useCallback, useEffect, useState } from 'react';
import { ApiClientError, AthletiqApiClient } from '../api/client';
import type { InstitutionResponse, UserResponse } from '../api/schemas';

export type AuthSessionStatus =
  | 'BOOTING'
  | 'AUTHENTICATED'
  | 'GUEST'
  | 'UNAUTHENTICATED'
  | 'UNAVAILABLE';

export interface AuthSessionState {
  status: AuthSessionStatus;
  user: UserResponse | null;
  institution: InstitutionResponse | null;
  backendHealthy: boolean;
  healthLatencyMs: number | null;
  isBusy: boolean;
  error: string | null;
}

export interface AuthSessionController extends AuthSessionState {
  canEditAssessments: boolean;
  canRunLocalAssessments: boolean;
  isAdmin: boolean;
  isGuest: boolean;
  refreshSession: () => Promise<void>;
  login: (email: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  enterGuestMode: () => void;
  bootstrapInstitution: (payload: {
    institution_name: string;
    institution_code: string;
    admin_email: string;
    admin_full_name: string;
    admin_password: string;
  }) => Promise<boolean>;
  clearError: () => void;
}

export function useAuthSession(api: AthletiqApiClient): AuthSessionController {
  const [state, setState] = useState<AuthSessionState>({
    status: 'BOOTING',
    user: null,
    institution: null,
    backendHealthy: false,
    healthLatencyMs: null,
    isBusy: false,
    error: null,
  });

  const refreshSession = useCallback(async () => {
    setState((prev) => ({ ...prev, isBusy: true, error: null }));
    const t0 = typeof performance !== 'undefined' ? performance.now() : Date.now();
    try {
      await api.checkHealth();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Backend service unavailable';
      setState({
        status: 'UNAVAILABLE',
        user: null,
        institution: null,
        backendHealthy: false,
        healthLatencyMs: null,
        isBusy: false,
        error: msg,
      });
      return;
    }
    const latencyMs = Math.max(
      1,
      Math.round((typeof performance !== 'undefined' ? performance.now() : Date.now()) - t0),
    );

    try {
      const [user, institution] = await Promise.all([api.getMe(), api.getInstitution()]);
      setState({
        status: 'AUTHENTICATED',
        user,
        institution,
        backendHealthy: true,
        healthLatencyMs: latencyMs,
        isBusy: false,
        error: null,
      });
    } catch (err) {
      if (err instanceof ApiClientError && err.kind === 'AUTH_REJECTED') {
        setState({
          status: 'UNAUTHENTICATED',
          user: null,
          institution: null,
          backendHealthy: true,
          healthLatencyMs: latencyMs,
          isBusy: false,
          error: null,
        });
      } else {
        setState({
          status: 'UNAVAILABLE',
          user: null,
          institution: null,
          backendHealthy: false,
          healthLatencyMs: null,
          isBusy: false,
          error: err instanceof Error ? err.message : 'Unable to verify session',
        });
      }
    }
  }, [api]);

  useEffect(() => {
    void refreshSession();
  }, [refreshSession]);

  const login = useCallback(
    async (email: string, password: string): Promise<boolean> => {
      setState((prev) => ({ ...prev, isBusy: true, error: null }));
      try {
        const session = await api.webLogin(email, password);
        setState((prev) => ({
          status: 'AUTHENTICATED',
          user: session.user,
          institution: session.institution,
          backendHealthy: true,
          healthLatencyMs: prev.healthLatencyMs,
          isBusy: false,
          error: null,
        }));
        return true;
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Authentication failed';
        setState((prev) => ({
          ...prev,
          status: prev.user ? prev.status : 'UNAUTHENTICATED',
          isBusy: false,
          error: msg,
        }));
        return false;
      }
    },
    [api],
  );

  const logout = useCallback(async (): Promise<void> => {
    const wasGuest = state.status === 'GUEST';
    setState((prev) => ({ ...prev, isBusy: true, error: null }));
    if (!wasGuest) {
      try {
        await api.webLogout();
      } catch {
        // Clear local session state even if network dropped during logout.
      }
    }
    setState((prev) => ({
      status: prev.backendHealthy ? 'UNAUTHENTICATED' : 'UNAVAILABLE',
      user: null,
      institution: null,
      backendHealthy: prev.backendHealthy,
      healthLatencyMs: prev.healthLatencyMs,
      isBusy: false,
      error: null,
    }));
  }, [api, state.status]);

  const enterGuestMode = useCallback(() => {
    setState((prev) => ({
      ...prev,
      status: 'GUEST',
      user: null,
      institution: null,
      isBusy: false,
      error: null,
    }));
  }, []);

  const bootstrapInstitution = useCallback(
    async (payload: {
      institution_name: string;
      institution_code: string;
      admin_email: string;
      admin_full_name: string;
      admin_password: string;
    }): Promise<boolean> => {
      setState((prev) => ({ ...prev, isBusy: true, error: null }));
      try {
        await api.bootstrapInstitution(payload);
        const session = await api.webLogin(payload.admin_email, payload.admin_password);
        setState((prev) => ({
          status: 'AUTHENTICATED',
          user: session.user,
          institution: session.institution,
          backendHealthy: true,
          healthLatencyMs: prev.healthLatencyMs,
          isBusy: false,
          error: null,
        }));
        return true;
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Institution bootstrap failed';
        setState((prev) => ({ ...prev, isBusy: false, error: msg }));
        return false;
      }
    },
    [api],
  );

  const clearError = useCallback(() => {
    setState((prev) => ({ ...prev, error: null }));
  }, []);

  const role = state.user?.role;
  const isGuest = state.status === 'GUEST';
  return {
    ...state,
    canEditAssessments: role === 'ADMIN' || role === 'INSTRUCTOR',
    canRunLocalAssessments: state.status === 'AUTHENTICATED' || isGuest,
    isAdmin: role === 'ADMIN',
    isGuest,
    refreshSession,
    login,
    logout,
    enterGuestMode,
    bootstrapInstitution,
    clearError,
  };
}
