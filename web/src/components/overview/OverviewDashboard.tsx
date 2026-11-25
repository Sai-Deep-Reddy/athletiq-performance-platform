import {
  Activity,
  ArrowRight,
  Camera,
  CheckCircle2,
  ClipboardList,
  Cpu,
  Database,
  Footprints,
  HardDrive,
  Ruler,
  Sliders,
  Timer,
  UploadCloud,
  Users,
  Wifi,
} from 'lucide-react';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';
import type { TestType } from '../../core/model/types';
import type { AppSection } from '../layout/AppShell';

function formatBytesMb(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return '0.0';
  return (bytes / (1024 * 1024)).toFixed(1);
}

export function OverviewDashboard({
  core,
  onNavigate,
  onLaunchTest,
}: {
  core: ReturnType<typeof useAthletiqCore>;
  onNavigate: (tab: AppSection) => void;
  onLaunchTest: (testType: TestType) => void;
}) {
  const { auth, workspace, capture } = core;
  const activeVertical = workspace.calibrations.find(
    (c) => c.plane_type === 'VERTICAL_PLANE' && c.is_active,
  );
  const activeGround = workspace.calibrations.find(
    (c) => c.plane_type === 'GROUND_PLANE' && c.is_active,
  );
  const activeIntrinsics = workspace.calibrations.find(
    (c) => c.plane_type === 'INTRINSICS' && c.is_active,
  );

  const quota = workspace.storageQuota;
  const caps = capture.state.capabilities;

  const launchpadCards: Array<{
    testType: TestType;
    title: string;
    subtitle: string;
    description: string;
    planeLabel: string;
    calibrated: boolean;
    rmsPx?: number;
    metricLabel: string;
    metricValue: string;
    cta: string;
    icon: typeof Ruler;
  }> = [
    {
      testType: 'HEIGHT',
      title: 'Stature Analysis',
      subtitle: 'VERTICAL PLANE',
      description:
        'Standing crown-to-heel orthometric stature using MediaPipe pose landmarks and segmentation crown refinement.',
      planeLabel: activeVertical ? `V-PLANE • ${activeVertical.method}` : 'V-PLANE REQUIRED',
      calibrated: Boolean(activeVertical),
      rmsPx: activeVertical?.reprojection_error_px,
      metricLabel: 'Calibration Gate',
      metricValue: activeVertical ? `${activeVertical.reprojection_error_px.toFixed(2)} px RMS` : 'Uncalibrated',
      cta: 'Launch Height',
      icon: Ruler,
    },
    {
      testType: 'VERTICAL_JUMP',
      title: 'Vertical Jump',
      subtitle: 'REACH DELTA & FLIGHT TIME',
      description:
        'Overhead standing reach delta cross-checked with countermovement flight-time kinematics (g · t² / 8).',
      planeLabel: activeVertical ? `V-PLANE • ${activeVertical.method}` : 'V-PLANE REQUIRED',
      calibrated: Boolean(activeVertical),
      rmsPx: activeVertical?.reprojection_error_px,
      metricLabel: 'Decoder Mode',
      metricValue: caps?.hasWebCodecs ? 'WebCodecs Sub-Frame' : 'Server Fallback',
      cta: 'Launch Vertical Jump',
      icon: Activity,
    },
    {
      testType: 'STANDING_BROAD_JUMP',
      title: 'Standing Broad Jump',
      subtitle: 'SAI KHELO INDIA PROTOCOL',
      description:
        'Takeoff foul line verification and official rear-heel landing contact measurement with backward fall detection.',
      planeLabel: activeGround ? `G-PLANE • ${activeGround.method}` : 'G-PLANE REQUIRED',
      calibrated: Boolean(activeGround),
      rmsPx: activeGround?.reprojection_error_px,
      metricLabel: 'Ground Lock',
      metricValue: activeGround ? `${activeGround.reprojection_error_px.toFixed(2)} px RMS` : 'Uncalibrated',
      cta: 'Launch Broad Jump',
      icon: Footprints,
    },
    {
      testType: 'SPRINT',
      title: 'Sprint Acceleration',
      subtitle: 'OPTICAL TIMING GATE',
      description:
        'Calibrated start/finish plane crossing interpolation from WebCodecs microsecond frame timestamps.',
      planeLabel: activeGround ? `G-PLANE • ${activeGround.method}` : 'G-PLANE REQUIRED',
      calibrated: Boolean(activeGround),
      rmsPx: activeGround?.reprojection_error_px,
      metricLabel: 'Clock Precision',
      metricValue: 'Microsecond Frame',
      cta: 'Launch Sprint Timing',
      icon: Timer,
    },
  ];

  return (
    <div className="space-y-5">
      {auth.isGuest && (
        <div className="flex flex-col justify-between gap-3 rounded-2xl border border-primary/30 bg-surface-container-low p-4 sm:flex-row sm:items-center">
          <div className="space-y-0.5">
            <p className="text-xs font-bold uppercase tracking-wider text-primary">
              Guest Field Workspace Active
            </p>
            <p className="text-xs text-on-surface-variant">
              Camera tracking, MediaPipe CV vectors, WebCodecs decoding, OPFS storage, and local reference calibration run directly in your browser. Sign in only when syncing official examination rosters.
            </p>
          </div>
          <button
            type="button"
            onClick={() => void auth.logout()}
            className="inline-flex min-h-[40px] shrink-0 items-center justify-center gap-1.5 rounded-xl bg-primary-container px-3.5 py-2 text-xs font-semibold text-white shadow-xs transition hover:brightness-110"
          >
            Institutional Sign In <ArrowRight className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* Top 4 Real KPI Cards */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs transition hover:border-primary/40">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-bold uppercase tracking-wider">Registered Athletes</span>
            <Users className="h-4 w-4 text-primary" />
          </div>
          <p className="mt-2 text-2xl font-bold text-on-surface sm:text-3xl tnum">
            {auth.isGuest ? 'Local' : workspace.students.length}
          </p>
          <p className="mt-1 text-[11px] text-on-surface-variant">
            {auth.isGuest ? 'Sign in for cohort roster' : 'Scoped to active institution'}
          </p>
        </div>

        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs transition hover:border-primary/40">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-bold uppercase tracking-wider">Examinations</span>
            <ClipboardList className="h-4 w-4 text-tertiary" />
          </div>
          <p className="mt-2 text-2xl font-bold text-on-surface sm:text-3xl tnum">
            {auth.isGuest ? '—' : workspace.examinations.length}
          </p>
          <p className="mt-1 text-[11px] text-on-surface-variant">
            {auth.isGuest
              ? 'Ad-hoc guest tests enabled'
              : `${workspace.examinations.filter((e) => e.status === 'ACTIVE').length} active cohorts`}
          </p>
        </div>

        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs transition hover:border-primary/40">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-bold uppercase tracking-wider">Calibrations</span>
            <Sliders className="h-4 w-4 text-primary" />
          </div>
          <p className="mt-2 text-2xl font-bold text-on-surface sm:text-3xl tnum">
            {workspace.calibrations.filter((c) => c.is_active).length}
          </p>
          <div className="mt-1 flex flex-wrap gap-1 text-[10px] font-bold">
            <span
              className={`rounded px-1.5 py-0.5 ${
                activeVertical
                  ? 'bg-emerald-500/15 text-tertiary'
                  : 'bg-surface-container text-on-surface-variant'
              }`}
            >
              V-PLANE {activeVertical ? 'OK' : 'REQ'}
            </span>
            <span
              className={`rounded px-1.5 py-0.5 ${
                activeGround
                  ? 'bg-emerald-500/15 text-tertiary'
                  : 'bg-surface-container text-on-surface-variant'
              }`}
            >
              G-PLANE {activeGround ? 'OK' : 'REQ'}
            </span>
          </div>
        </div>

        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs transition hover:border-primary/40">
          <div className="flex items-center justify-between text-on-surface-variant">
            <span className="text-[10px] font-bold uppercase tracking-wider">OPFS &amp; Outbox</span>
            <HardDrive className="h-4 w-4 text-primary" />
          </div>
          <p className="mt-2 text-2xl font-bold text-on-surface sm:text-3xl tnum">
            {formatBytesMb(quota?.videoTotalBytes)} <span className="text-sm font-semibold">MB</span>
          </p>
          <div className="mt-1 flex items-center justify-between text-[11px] text-on-surface-variant tnum">
            <span>{quota?.videoFileCount ?? 0} video clips</span>
            <span>{workspace.outbox.length} queued</span>
          </div>
        </div>
      </div>

      {/* Real Hardware & Browser Runtime Telemetry Strip */}
      <div className="grid grid-cols-2 gap-2.5 rounded-2xl border border-outline-variant bg-surface-container-lowest p-3.5 sm:grid-cols-4">
        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-container-low text-primary">
            <Cpu className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-outline">
              Video Decoder
            </p>
            <p className="truncate text-xs font-bold text-on-surface">
              {caps?.hasWebCodecs ? 'WebCodecs Hardware' : 'FFmpeg Server Fallback'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-container-low text-tertiary">
            <Camera className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-outline">
              Camera Engine
            </p>
            <p className="truncate text-xs font-bold text-on-surface">
              {caps?.status ?? 'PROBING'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-container-low text-primary">
            <Database className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-outline">
              Browser Quota
            </p>
            <p className="truncate text-xs font-bold text-on-surface tnum">
              {quota?.quotaBytes
                ? `${formatBytesMb(quota.usageBytes)} / ${(quota.quotaBytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
                : 'Inspecting...'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-container-low text-tertiary">
            <Wifi className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-outline">
              Intrinsics Lens
            </p>
            <p className="truncate text-xs font-bold text-on-surface">
              {activeIntrinsics
                ? `Active (${activeIntrinsics.reprojection_error_px.toFixed(2)} px)`
                : 'Optional (Unconfigured)'}
            </p>
          </div>
        </div>
      </div>

      {/* Kinematic Capture Launchpad */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-wider text-primary">
              Standardized Diagnostic Batteries
            </p>
            <h2 className="text-lg font-bold text-on-surface">Kinematic Capture Launchpad</h2>
          </div>
          <button
            type="button"
            onClick={() => onNavigate('calibrate')}
            className="text-xs font-semibold text-primary hover:underline"
          >
            Manage Camera Calibrations →
          </button>
        </div>

        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {launchpadCards.map((card) => {
            const Icon = card.icon;
            return (
              <div
                key={card.testType}
                className="flex flex-col justify-between rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
              >
                <div>
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-surface-container text-primary">
                      <Icon className="h-4 w-4" />
                    </div>
                    <span
                      className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-[10px] font-bold ${
                        card.calibrated
                          ? 'bg-emerald-500/15 text-tertiary'
                          : 'bg-amber-500/15 text-amber-700 dark:text-amber-300'
                      }`}
                    >
                      <span
                        className={`h-1.5 w-1.5 rounded-full ${
                          card.calibrated ? 'bg-tertiary' : 'bg-amber-500'
                        }`}
                      />
                      {card.calibrated ? 'CALIBRATED' : 'NEEDS CALIB'}
                    </span>
                  </div>

                  <h3 className="mt-3 text-base font-bold text-on-surface">{card.title}</h3>
                  <p className="text-[10px] font-bold uppercase tracking-wider text-outline">
                    {card.subtitle}
                  </p>
                  <p className="mt-2 text-xs leading-relaxed text-on-surface-variant">
                    {card.description}
                  </p>
                </div>

                <div className="mt-4 space-y-2.5 border-t border-outline-variant/50 pt-3">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-on-surface-variant">{card.metricLabel}</span>
                    <span className="font-semibold text-on-surface tnum">{card.metricValue}</span>
                  </div>
                  <button
                    type="button"
                    onClick={() => onLaunchTest(card.testType)}
                    className="flex min-h-[42px] w-full items-center justify-center gap-1.5 rounded-xl bg-primary-container px-3 py-2 text-xs font-semibold text-white transition hover:brightness-110 active:scale-[0.99]"
                  >
                    {card.cta} <ArrowRight className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {/* Real Outbox / Local Attempt Activity Feed */}
      <section className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h3 className="text-sm font-bold text-on-surface">
              Local Capture &amp; Outbox Ledger ({workspace.outbox.length})
            </h3>
            <p className="text-xs text-on-surface-variant">
              Real evaluated measurements stored in IndexedDB &amp; OPFS from this browser workspace.
            </p>
          </div>
          <div className="flex items-center gap-2">
            {!auth.isGuest && workspace.outbox.length > 0 && (
              <button
                type="button"
                onClick={() => void core.syncPendingOutbox().then(() => core.refreshWorkspace())}
                className="inline-flex min-h-[36px] items-center gap-1.5 rounded-lg bg-primary-container px-3 py-1.5 text-xs font-semibold text-white"
              >
                <UploadCloud className="h-3.5 w-3.5" /> Sync All
              </button>
            )}
            <button
              type="button"
              onClick={() => onNavigate('capture')}
              className="inline-flex min-h-[36px] items-center gap-1 rounded-lg border border-outline-variant bg-surface-container-low px-3 py-1.5 text-xs font-semibold text-on-surface hover:bg-surface-container"
            >
              Open Capture →
            </button>
          </div>
        </div>

        {workspace.outbox.length === 0 ? (
          <div className="mt-4 flex flex-col items-center justify-center rounded-xl border border-dashed border-outline-variant bg-surface-container-low/50 px-4 py-8 text-center">
            <CheckCircle2 className="h-7 w-7 text-outline" />
            <p className="mt-2 text-xs font-semibold text-on-surface">
              No queued or local captured attempts yet
            </p>
            <p className="mt-1 max-w-md text-xs text-on-surface-variant">
              Calibrate a vertical or ground plane, then capture live camera footage or analyze a test video in the AI Capture Workbench.
            </p>
          </div>
        ) : (
          <div className="mt-3 divide-y divide-outline-variant/50">
            {workspace.outbox.slice(0, 8).map((row) => (
              <div
                key={row.client_uuid}
                className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-xs"
              >
                <div className="min-w-0">
                  <p className="font-semibold text-on-surface tnum">
                    {row.test_type} • {row.value.toFixed(2)} {row.unit}
                  </p>
                  <p className="text-[11px] text-on-surface-variant tnum">
                    {new Date(row.recorded_at).toLocaleString()} • {row.fps_actual.toFixed(1)} FPS •{' '}
                    {row.landmark_provenance}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <span
                    className={`rounded px-2 py-0.5 text-[10px] font-bold ${
                      row.validity_status === 'VALID'
                        ? 'bg-emerald-500/15 text-tertiary'
                        : row.validity_status === 'FLAGGED'
                          ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300'
                          : 'bg-error-container text-error'
                    }`}
                  >
                    {row.validity_status}
                  </span>
                  <span className="rounded bg-surface-container px-2 py-0.5 text-[10px] font-bold text-primary">
                    {auth.isGuest ? 'LOCAL_GUEST' : row.status}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
