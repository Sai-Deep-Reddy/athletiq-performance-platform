import { type FormEvent, useEffect, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Building2,
  Eye,
  EyeOff,
  KeyRound,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  UserCheck,
} from 'lucide-react';
import type { AuthSessionController } from '../../core/domain/useAuthSession';
import type { ThemeMode } from '../../core/domain/useTheme';
import { BrandMark, ThemeToggle } from '../ui/BrandMark';

interface SubsystemAudit {
  indexedDb: boolean;
  webCodecs: boolean;
  wasm: boolean;
  opfs: boolean;
}

function inspectSubsystems(): SubsystemAudit {
  if (typeof window === 'undefined') {
    return { indexedDb: false, webCodecs: false, wasm: false, opfs: false };
  }
  return {
    indexedDb: typeof indexedDB !== 'undefined',
    webCodecs: typeof (window as unknown as { VideoDecoder?: unknown }).VideoDecoder === 'function',
    wasm: typeof WebAssembly === 'object' && typeof WebAssembly.instantiate === 'function',
    opfs: Boolean(navigator?.storage && typeof navigator.storage.getDirectory === 'function'),
  };
}

export function AuthGate({
  auth,
  theme,
  onToggleTheme,
}: {
  auth: AuthSessionController;
  theme: ThemeMode;
  onToggleTheme: () => void;
}) {
  const [mode, setMode] = useState<'login' | 'bootstrap'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [instName, setInstName] = useState('');
  const [instCode, setInstCode] = useState('');
  const [adminName, setAdminName] = useState('');
  const [audit, setAudit] = useState<SubsystemAudit>(() => inspectSubsystems());

  useEffect(() => {
    setAudit(inspectSubsystems());
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (mode === 'login') {
      await auth.login(email, password);
    } else {
      await auth.bootstrapInstitution({
        institution_name: instName,
        institution_code: instCode,
        admin_email: email,
        admin_full_name: adminName,
        admin_password: password,
      });
    }
  };

  return (
    <div className="relative flex min-h-[100dvh] flex-col items-center justify-center overflow-x-hidden bg-surface px-4 py-6 sm:px-6">
      {/* Subtle clinical ambient glow matching Stitch telemetry backdrop */}
      <div
        aria-hidden="true"
        className="pointer-events-none fixed inset-0 overflow-hidden opacity-45 dark:opacity-30"
      >
        <div className="absolute -left-24 -top-24 h-72 w-72 rounded-full bg-primary/15 blur-3xl" />
        <div className="absolute -bottom-24 -right-24 h-72 w-72 rounded-full bg-tertiary/15 blur-3xl" />
      </div>

      <div className="relative z-10 w-full max-w-md space-y-4 animate-panel-enter">
        {/* Header Bar */}
        <div className="flex items-center justify-between gap-2">
          <BrandMark />
          <div className="flex items-center gap-2">
            <span className="hidden rounded bg-surface-container px-2 py-1 text-[10px] font-semibold uppercase tracking-wider text-primary sm:inline-flex">
              Field Station
            </span>
            <ThemeToggle theme={theme} onToggle={onToggleTheme} compact />
          </div>
        </div>

        {/* Backend Health Telemetry Strip */}
        <div className="flex items-center justify-between gap-2 rounded-xl border border-outline-variant bg-surface-container-lowest px-3.5 py-2.5 shadow-xs">
          <div className="flex items-center gap-2 text-xs">
            <span
              className={`h-2 w-2 rounded-full ${
                auth.backendHealthy ? 'bg-tertiary animate-pulse' : 'bg-error'
              }`}
            />
            <span className="font-semibold text-on-surface">
              {auth.backendHealthy ? 'API Online' : 'API Unavailable'}
            </span>
            {auth.backendHealthy && auth.healthLatencyMs !== null && (
              <span className="rounded bg-emerald-500/15 px-1.5 py-0.5 text-[10px] font-bold text-tertiary tnum">
                {auth.healthLatencyMs}ms
              </span>
            )}
          </div>
          <button
            type="button"
            onClick={() => void auth.refreshSession()}
            className="inline-flex min-h-[32px] items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-semibold uppercase tracking-wide text-primary transition hover:bg-surface-container-low"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${auth.isBusy ? 'animate-spin' : ''}`} />
            Ping Health
          </button>
        </div>

        {/* Direct Guest Access (Zero Login Friction) */}
        <div className="rounded-2xl border border-primary/30 bg-gradient-to-br from-surface-container-low to-surface-container-lowest p-4 shadow-sm">
          <div className="flex items-start justify-between gap-3">
            <div className="space-y-1">
              <div className="inline-flex items-center gap-1.5 rounded bg-primary/10 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-primary">
                <Sparkles className="h-3 w-3" /> Instant Field Mode
              </div>
              <h2 className="text-sm font-bold text-on-surface">Direct Guest Entry</h2>
              <p className="text-xs leading-relaxed text-on-surface-variant">
                Launch live camera pose tracking, MediaPipe CV overlays, WebCodecs analysis, and local reference calibration immediately without an institutional account.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => auth.enterGuestMode()}
            className="mt-3 flex min-h-[46px] w-full items-center justify-center gap-2 rounded-xl bg-tertiary-container px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:brightness-110 active:scale-[0.99]"
          >
            <UserCheck className="h-4 w-4" />
            Enter as Guest (No Login)
            <ArrowRight className="h-4 w-4" />
          </button>
        </div>

        {/* Main Institutional Auth & Setup Card */}
        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-5 shadow-sm sm:p-6">
          <div className="flex items-center justify-between gap-2">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-on-surface-variant">
                Institutional Sync Gate
              </p>
              <h1 className="text-lg font-bold text-on-surface">
                {mode === 'login' ? 'Institutional Sign In' : 'First-Time Institution Setup'}
              </h1>
            </div>
            <ShieldCheck className="h-5 w-5 text-primary" />
          </div>

          <p className="mt-1.5 text-xs text-on-surface-variant">
            HttpOnly cookie session + X-CSRF-Token protection for institutional cohort records &amp; OpenCV server calibration.
          </p>

          {!auth.backendHealthy && (
            <div className="mt-3 flex items-start gap-2 rounded-xl border border-error/30 bg-error-container/50 p-3 text-xs text-error">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              <div className="flex-1">
                <p className="font-semibold">Backend unavailable</p>
                <p>{auth.error ?? 'Start FastAPI on port 8000 for institutional login, or use Guest Mode above.'}</p>
              </div>
            </div>
          )}

          {auth.error && auth.backendHealthy && (
            <div className="mt-3 rounded-xl border border-error/30 bg-error-container/50 p-3 text-xs text-error">
              {auth.error}
            </div>
          )}

          <div className="mt-4 grid grid-cols-2 gap-1.5 rounded-xl bg-surface-container-low p-1">
            <button
              type="button"
              onClick={() => {
                auth.clearError();
                setMode('login');
              }}
              className={`flex min-h-[42px] items-center justify-center gap-1.5 rounded-lg text-xs font-semibold transition-all sm:text-sm ${
                mode === 'login'
                  ? 'bg-surface-container-lowest text-primary shadow-xs'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <KeyRound className="h-3.5 w-3.5" /> Sign In
            </button>
            <button
              type="button"
              onClick={() => {
                auth.clearError();
                setMode('bootstrap');
              }}
              className={`flex min-h-[42px] items-center justify-center gap-1.5 rounded-lg text-xs font-semibold transition-all sm:text-sm ${
                mode === 'bootstrap'
                  ? 'bg-surface-container-lowest text-primary shadow-xs'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Building2 className="h-3.5 w-3.5" /> Bootstrap
            </button>
          </div>

          <form onSubmit={(e) => void handleSubmit(e)} className="mt-4 space-y-3">
            {mode === 'bootstrap' && (
              <>
                <label className="block">
                  <span className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant">
                    Institution Name
                  </span>
                  <input
                    required
                    value={instName}
                    onChange={(e) => setInstName(e.target.value)}
                    className="mt-1 min-h-[44px] w-full rounded-lg border border-outline-variant bg-surface-container-low px-3 py-2 text-sm text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                    placeholder="Enter institution name"
                  />
                </label>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <label className="block">
                    <span className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant">
                      Institution Code
                    </span>
                    <input
                      required
                      value={instCode}
                      onChange={(e) => setInstCode(e.target.value)}
                      className="mt-1 min-h-[44px] w-full rounded-lg border border-outline-variant bg-surface-container-low px-3 py-2 text-sm text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                      placeholder="INST_CODE"
                    />
                  </label>
                  <label className="block">
                    <span className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant">
                      Admin Full Name
                    </span>
                    <input
                      required
                      value={adminName}
                      onChange={(e) => setAdminName(e.target.value)}
                      className="mt-1 min-h-[44px] w-full rounded-lg border border-outline-variant bg-surface-container-low px-3 py-2 text-sm text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                      placeholder="Administrator name"
                    />
                  </label>
                </div>
              </>
            )}

            <label className="block">
              <span className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant">
                Examiner Email
              </span>
              <input
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="mt-1 min-h-[44px] w-full rounded-lg border border-outline-variant bg-surface-container-low px-3 py-2 text-sm text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                placeholder="examiner@institution.org"
              />
            </label>

            <label className="block">
              <span className="text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant">
                Password {mode === 'bootstrap' ? '(min 12 chars)' : ''}
              </span>
              <div className="relative mt-1">
                <input
                  type={showPassword ? 'text' : 'password'}
                  required
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="min-h-[44px] w-full rounded-lg border border-outline-variant bg-surface-container-low py-2 pl-3 pr-11 text-sm text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((prev) => !prev)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  className="absolute right-1.5 top-1/2 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded-md text-on-surface-variant hover:bg-surface-container"
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </label>

            <button
              type="submit"
              disabled={auth.isBusy || !auth.backendHealthy}
              className="mt-2 min-h-[48px] w-full rounded-xl bg-primary-container px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:brightness-110 active:scale-[0.99] disabled:opacity-50"
            >
              {auth.isBusy
                ? 'Authenticating...'
                : mode === 'login'
                  ? 'Sign In to Station'
                  : 'Initialize Institution'}
            </button>
          </form>
        </div>

        {/* Real Client Subsystem Capabilities */}
        <div className="rounded-xl border border-outline-variant bg-surface-container-lowest p-3 text-[11px]">
          <div className="mb-2 flex items-center justify-between font-semibold uppercase tracking-wider text-on-surface-variant">
            <span>Client Execution Subsystem Audit</span>
            <span className={audit.indexedDb && audit.wasm ? 'text-tertiary' : 'text-amber-600'}>
              {audit.indexedDb && audit.wasm ? 'Runtime Ready' : 'Limited Runtime'}
            </span>
          </div>
          <div className="grid grid-cols-2 gap-1.5 tnum">
            <div className="flex items-center justify-between rounded bg-surface-container-low px-2 py-1">
              <span className="text-on-surface-variant">IndexedDB</span>
              <span className={`font-bold ${audit.indexedDb ? 'text-tertiary' : 'text-error'}`}>
                {audit.indexedDb ? 'READY' : 'UNAVAIL'}
              </span>
            </div>
            <div className="flex items-center justify-between rounded bg-surface-container-low px-2 py-1">
              <span className="text-on-surface-variant">WebCodecs</span>
              <span className={`font-bold ${audit.webCodecs ? 'text-tertiary' : 'text-amber-600'}`}>
                {audit.webCodecs ? 'ACTIVE' : 'FALLBACK'}
              </span>
            </div>
            <div className="flex items-center justify-between rounded bg-surface-container-low px-2 py-1">
              <span className="text-on-surface-variant">MediaPipe Wasm</span>
              <span className={`font-bold ${audit.wasm ? 'text-tertiary' : 'text-error'}`}>
                {audit.wasm ? 'READY' : 'UNAVAIL'}
              </span>
            </div>
            <div className="flex items-center justify-between rounded bg-surface-container-low px-2 py-1">
              <span className="text-on-surface-variant">OPFS Video</span>
              <span className={`font-bold ${audit.opfs ? 'text-tertiary' : 'text-amber-600'}`}>
                {audit.opfs ? 'SUPPORTED' : 'UNAVAIL'}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
