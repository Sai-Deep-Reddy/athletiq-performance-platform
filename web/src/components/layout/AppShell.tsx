import type { ReactNode } from 'react';
import {
  Activity,
  Camera,
  CheckCircle2,
  ClipboardCheck,
  Database,
  LayoutGrid,
  LogIn,
  LogOut,
  RefreshCw,
  Shield,
  Sliders,
  UploadCloud,
  UserCheck,
} from 'lucide-react';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';
import type { ThemeMode } from '../../core/domain/useTheme';
import { BrandMark, ThemeToggle } from '../ui/BrandMark';

export type AppSection =
  | 'overview'
  | 'calibrate'
  | 'capture'
  | 'review'
  | 'outbox'
  | 'governance';

interface NavEntry {
  id: AppSection;
  label: string;
  mobileLabel: string;
  icon: typeof Activity;
  badge?: string | number;
  liveDot?: boolean;
}

export function AppShell({
  core,
  activeTab,
  onSelectTab,
  theme,
  onToggleTheme,
  children,
}: {
  core: ReturnType<typeof useAthletiqCore>;
  activeTab: AppSection;
  onSelectTab: (tab: AppSection) => void;
  theme: ThemeMode;
  onToggleTheme: () => void;
  children: ReactNode;
}) {
  const { auth, workspace, capture } = core;
  const outboxCount = workspace.outbox.length;

  const navItems: NavEntry[] = [
    { id: 'overview', label: 'Operational Overview', mobileLabel: 'Overview', icon: LayoutGrid },
    { id: 'calibrate', label: 'Camera Calibration', mobileLabel: 'Calibrate', icon: Sliders, badge: '3-STAGE' },
    {
      id: 'capture',
      label: 'AI Capture Workbench',
      mobileLabel: 'Capture',
      icon: Camera,
      liveDot: capture.state.phase === 'CAMERA_LIVE' || capture.state.phase === 'RECORDING',
    },
    { id: 'review', label: 'Scoring & Review', mobileLabel: 'Review', icon: ClipboardCheck },
    {
      id: 'outbox',
      label: 'Sync Outbox',
      mobileLabel: 'Outbox',
      icon: UploadCloud,
      badge: outboxCount > 0 ? outboxCount : undefined,
    },
    { id: 'governance', label: 'Governance & Standards', mobileLabel: 'Standards', icon: Shield },
  ];

  const mobileBottomItems = navItems.slice(0, 5);
  const currentTitle = navItems.find((n) => n.id === activeTab)?.label ?? 'Telemetry Diagnostics';
  const facilityTitle = auth.isGuest ? 'Guest Field Session' : (auth.institution?.name ?? 'Institution');
  const facilitySub = auth.isGuest
    ? 'Local Browser Engine • No Server Sync'
    : `Code: ${auth.institution?.code ?? '—'}`;
  const actorName = auth.isGuest ? 'Guest Operator' : (auth.user?.full_name ?? 'Examiner');
  const actorRole = auth.isGuest ? 'LOCAL GUEST' : (auth.user?.role ?? 'USER');

  return (
    <div className="min-h-[100dvh] bg-surface text-on-surface lg:grid lg:grid-cols-[272px_1fr]">
      {/* Persistent Desktop Sidebar */}
      <aside className="hidden border-r border-outline-variant bg-surface-container-lowest lg:flex lg:flex-col lg:justify-between lg:sticky lg:top-0 lg:h-[100dvh]">
        <div className="flex flex-col overflow-y-auto aq-scroll">
          <div className="flex h-16 items-center justify-between border-b border-outline-variant/60 px-4">
            <BrandMark />
            <span
              title={capture.state.capabilities?.hasWebCodecs ? 'WebCodecs Hardware Active' : 'Server Fallback Ready'}
              className={`h-2 w-2 rounded-full ${
                capture.state.phase === 'CAMERA_LIVE' || capture.state.phase === 'RECORDING'
                  ? 'bg-emerald-400 animate-ping'
                  : 'bg-tertiary'
              }`}
            />
          </div>

          <div className="mx-4 mt-4 rounded-xl border border-outline-variant bg-surface-container-low p-3">
            <div className="flex items-center justify-between">
              <span className="text-[10px] font-bold uppercase tracking-wider text-on-surface-variant">
                {auth.isGuest ? 'Active Mode' : 'Active Facility'}
              </span>
              {auth.isGuest ? (
                <UserCheck className="h-3.5 w-3.5 text-tertiary" />
              ) : (
                <CheckCircle2 className="h-3.5 w-3.5 text-tertiary" />
              )}
            </div>
            <p className="mt-1 truncate text-xs font-bold text-on-surface">{facilityTitle}</p>
            <p className="truncate text-[10px] text-outline">{facilitySub}</p>
          </div>

          <nav aria-label="Primary navigation" className="mt-4 flex flex-col gap-1 px-3">
            {navItems.map((item) => {
              const Icon = item.icon;
              const active = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => onSelectTab(item.id)}
                  aria-current={active ? 'page' : undefined}
                  className={`flex min-h-[44px] w-full items-center justify-between rounded-xl px-3 py-2 text-left text-[13px] font-semibold transition-all active:scale-[0.99] ${
                    active
                      ? 'bg-primary-container text-white shadow-sm'
                      : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'
                  }`}
                >
                  <span className="flex items-center gap-2.5 truncate">
                    <Icon className="h-4 w-4 shrink-0" />
                    <span className="truncate">{item.label}</span>
                  </span>
                  {item.liveDot && (
                    <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-300 animate-pulse" />
                  )}
                  {item.badge !== undefined && (
                    <span
                      className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-bold tnum ${
                        active
                          ? 'bg-white/20 text-white'
                          : typeof item.badge === 'number'
                            ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300'
                            : 'bg-surface-container text-primary'
                      }`}
                    >
                      {item.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>
        </div>

        {/* Sidebar Footer Profile */}
        <div className="border-t border-outline-variant/60 p-3 space-y-2">
          <div className="flex items-center gap-2.5 rounded-xl border border-outline-variant bg-surface-container-low p-2.5">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary-container text-xs font-bold text-white">
              {actorName.slice(0, 2).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-semibold text-on-surface">{actorName}</p>
              <p className="truncate text-[10px] font-bold uppercase tracking-wide text-primary">
                {actorRole}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => void auth.logout()}
            className="flex min-h-[42px] w-full items-center justify-center gap-2 rounded-xl border border-outline-variant bg-surface-container-lowest px-3 py-2 text-xs font-semibold text-on-surface transition hover:bg-surface-container-low"
          >
            {auth.isGuest ? (
              <>
                <LogIn className="h-4 w-4 text-primary" /> Switch to Institutional Login
              </>
            ) : (
              <>
                <LogOut className="h-4 w-4" /> Sign Out
              </>
            )}
          </button>
        </div>
      </aside>

      {/* Main Column */}
      <div className="flex min-w-0 flex-col pb-24 lg:pb-8">
        {/* Sticky Top Telemetry Header */}
        <header className="sticky top-0 z-30 flex h-14 items-center justify-between gap-2 border-b border-outline-variant bg-surface-container-lowest/90 px-3 backdrop-blur-xl sm:px-5 lg:h-16">
          <div className="flex min-w-0 items-center gap-2.5">
            <div className="lg:hidden">
              <BrandMark compact />
            </div>
            <div className="min-w-0">
              <div className="hidden items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-outline lg:flex">
                <span>Platform</span>
                <span>/</span>
                <span className="text-on-surface">{currentTitle}</span>
              </div>
              <p className="truncate text-xs font-bold text-on-surface lg:hidden">
                {currentTitle}
              </p>
              <p className="truncate text-[10px] font-medium text-on-surface-variant lg:hidden">
                {facilityTitle} • {actorRole}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-1.5 sm:gap-2">
            {/* API Status Pill */}
            <div
              className={`hidden items-center gap-1.5 rounded-lg border px-2.5 py-1 text-[10px] font-bold sm:inline-flex tnum ${
                auth.backendHealthy
                  ? 'border-tertiary/25 bg-emerald-500/10 text-tertiary'
                  : 'border-error/25 bg-error-container/50 text-error'
              }`}
            >
              <span
                className={`h-1.5 w-1.5 rounded-full ${
                  auth.backendHealthy ? 'bg-tertiary' : 'bg-error'
                }`}
              />
              <span>{auth.backendHealthy ? 'API ONLINE' : 'OFFLINE'}</span>
              {auth.backendHealthy && auth.healthLatencyMs !== null && (
                <span className="rounded bg-tertiary px-1 py-0.5 text-[9px] text-white">
                  {auth.healthLatencyMs}ms
                </span>
              )}
            </div>

            {workspace.usingCachedData && (
              <span className="rounded-lg bg-amber-500/15 px-2 py-1 text-[10px] font-bold text-amber-700 dark:text-amber-300">
                Cached Data
              </span>
            )}

            {/* IndexedDB Outbox Pill */}
            <button
              type="button"
              onClick={() => onSelectTab('outbox')}
              className="hidden items-center gap-1.5 rounded-lg border border-outline-variant bg-surface-container-low px-2.5 py-1 text-[11px] font-semibold text-on-surface md:inline-flex tnum hover:bg-surface-container"
            >
              <Database className="h-3.5 w-3.5 text-primary" />
              <span>Outbox: {outboxCount}</span>
            </button>

            {!auth.isGuest && outboxCount > 0 && (
              <button
                type="button"
                onClick={() => void core.syncPendingOutbox().then(() => core.refreshWorkspace())}
                className="inline-flex min-h-[38px] items-center gap-1 rounded-lg bg-primary-container px-2.5 py-1 text-xs font-semibold text-white shadow-xs transition hover:brightness-110"
              >
                <UploadCloud className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">Sync</span>
              </button>
            )}

            <ThemeToggle theme={theme} onToggle={onToggleTheme} compact />

            {/* Mobile Governance quick tab */}
            <button
              type="button"
              onClick={() => onSelectTab('governance')}
              aria-label="Governance & Standards"
              className={`flex min-h-[40px] min-w-[40px] items-center justify-center rounded-lg border transition lg:hidden ${
                activeTab === 'governance'
                  ? 'border-primary bg-primary-container text-white'
                  : 'border-outline-variant bg-surface-container-low text-on-surface-variant'
              }`}
            >
              <Shield className="h-4 w-4" />
            </button>

            <button
              type="button"
              onClick={() => void core.refreshWorkspace()}
              aria-label="Refresh workspace"
              className="flex min-h-[40px] min-w-[40px] items-center justify-center rounded-lg border border-outline-variant bg-surface-container-low text-on-surface transition hover:bg-surface-container active:scale-95"
            >
              <RefreshCw className={`h-4 w-4 ${workspace.busy ? 'animate-spin text-primary' : ''}`} />
            </button>

            <button
              type="button"
              onClick={() => void auth.logout()}
              aria-label={auth.isGuest ? 'Sign in' : 'Sign out'}
              className="flex min-h-[40px] min-w-[40px] items-center justify-center rounded-lg border border-outline-variant bg-surface-container-low text-on-surface lg:hidden"
            >
              {auth.isGuest ? <LogIn className="h-4 w-4 text-primary" /> : <LogOut className="h-4 w-4" />}
            </button>
          </div>
        </header>

        {/* Main Viewport */}
        <main className="mx-auto w-full max-w-[1400px] flex-1 p-3 sm:p-5 lg:p-6">
          <div key={activeTab} className="animate-panel-enter">
            {children}
          </div>
        </main>
      </div>

      {/* Ergonomic Mobile Phone Bottom Navigation Bar */}
      <nav
        aria-label="Mobile navigation"
        className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-outline-variant bg-surface-container-lowest/95 pb-safe backdrop-blur-xl lg:hidden"
      >
        {mobileBottomItems.map((item) => {
          const Icon = item.icon;
          const active = activeTab === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onSelectTab(item.id)}
              className={`relative flex min-h-[58px] flex-col items-center justify-center gap-0.5 px-1 text-[10px] font-semibold transition-colors ${
                active ? 'text-primary' : 'text-on-surface-variant'
              }`}
            >
              <span
                className={`flex h-7 w-11 items-center justify-center rounded-full transition-all ${
                  active ? 'bg-primary-container/15 text-primary' : ''
                }`}
              >
                <Icon className="h-4 w-4" />
              </span>
              <span className="truncate">{item.mobileLabel}</span>
              {item.badge !== undefined && typeof item.badge === 'number' && (
                <span className="absolute right-2.5 top-1.5 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-amber-500 px-1 text-[9px] font-bold text-white tnum">
                  {item.badge}
                </span>
              )}
              {item.liveDot && (
                <span className="absolute right-3 top-2 h-2 w-2 rounded-full bg-emerald-400 animate-ping" />
              )}
            </button>
          );
        })}
      </nav>
    </div>
  );
}
