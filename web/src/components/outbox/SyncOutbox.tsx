import { useState } from 'react';
import { Database, HardDrive, RefreshCw, Trash2, UploadCloud } from 'lucide-react';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';

export function SyncOutbox({ core }: { core: ReturnType<typeof useAthletiqCore> }) {
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);

  const quota = core.workspace.storageQuota;
  const outbox = core.workspace.outbox;

  const usedMb = ((quota?.videoTotalBytes ?? 0) / (1024 * 1024)).toFixed(2);
  const usagePct = quota?.quotaBytes
    ? Math.min(100, ((quota.usageBytes ?? 0) / quota.quotaBytes) * 100)
    : quota?.videoTotalBytes
      ? Math.min(100, (quota.videoTotalBytes / (500 * 1024 * 1024)) * 100)
      : 0;

  const handleSync = async () => {
    setSyncing(true);
    setError(null);
    setNotice(null);
    try {
      const res = await core.syncPendingOutbox();
      await core.refreshWorkspace();
      if (!res) {
        setNotice('Sync skipped in local guest mode.');
      } else if (res.deferredReason) {
        setNotice(res.deferredReason);
      } else {
        setNotice(
          `Synced ${res.syncedCount} new, ${res.replayedCount} replayed, ${res.failedCount} failed.`,
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sync failed.');
    } finally {
      setSyncing(false);
    }
  };

  const handlePurge = async () => {
    setError(null);
    try {
      const n = await core.deleteAllVideos();
      await core.refreshWorkspace();
      setNotice(`Deleted ${n} OPFS video clip${n !== 1 ? 's' : ''}.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Purge failed.');
    }
  };

  const handlePersist = async () => {
    setError(null);
    try {
      const granted = await core.requestDurablePersistence();
      setNotice(
        granted ? 'Durable storage persistence granted by browser.' : 'Browser declined persistent storage.',
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Persistence request failed.');
    }
  };

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-bold text-on-surface sm:text-base">
              Durable Offline Storage (IndexedDB + OPFS)
            </h2>
            <p className="text-[11px] text-on-surface-variant">
              Idempotent attempt outbox and browser Origin Private File System video clips
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={syncing || core.auth.isGuest}
              onClick={() => void handleSync()}
              className="inline-flex min-h-[42px] items-center gap-1.5 rounded-xl bg-primary-container px-3.5 text-xs font-bold text-white shadow-xs disabled:opacity-40 active:scale-[0.98]"
            >
              <UploadCloud className={`h-4 w-4 ${syncing ? 'animate-spin' : ''}`} />
              Sync Queue
            </button>
            <button
              type="button"
              onClick={() => void handlePersist()}
              className="inline-flex min-h-[42px] items-center gap-1.5 rounded-xl border border-outline-variant bg-surface-container-low px-3.5 text-xs font-bold text-on-surface active:scale-[0.98]"
            >
              <RefreshCw className="h-4 w-4" />
              Request Persist
            </button>
            <button
              type="button"
              onClick={() => void handlePurge()}
              className="inline-flex min-h-[42px] items-center gap-1.5 rounded-xl border border-error/30 bg-error-container/50 px-3.5 text-xs font-bold text-error active:scale-[0.98]"
            >
              <Trash2 className="h-4 w-4" />
              Purge Videos
            </button>
          </div>
        </div>
      </div>

      {notice && (
        <div className="rounded-xl border border-tertiary/30 bg-emerald-500/15 p-3 text-xs text-tertiary">
          {notice}
        </div>
      )}
      {error && (
        <div className="rounded-xl border border-error/30 bg-error-container/50 p-3 text-xs text-error">
          {error}
        </div>
      )}
      {core.auth.isGuest && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/15 p-3 text-xs text-amber-800 dark:text-amber-300">
          Guest Mode: attempts save locally in IndexedDB/OPFS without remote server sync.
        </div>
      )}
      {core.workspace.lastSyncSummary?.deferredReason && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/15 p-3 text-xs text-amber-800 dark:text-amber-300">
          {core.workspace.lastSyncSummary.deferredReason}
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-3">
        {[
          {
            Icon: Database,
            label: 'Outbox Attempts',
            value: outbox.length.toString(),
            sub: 'queued locally',
          },
          {
            Icon: HardDrive,
            label: 'OPFS Clips',
            value: (quota?.videoFileCount ?? 0).toString(),
            sub: `${usedMb} MB video bytes`,
          },
          {
            Icon: RefreshCw,
            label: 'Persistence',
            value:
              quota?.isPersistent === null || quota?.isPersistent === undefined
                ? 'Unknown'
                : quota.isPersistent
                  ? 'Granted'
                  : 'Best-effort',
            sub: quota?.isPersistent ? 'OS protects local storage' : 'Subject to eviction under pressure',
          },
        ].map(({ Icon, label, value, sub }) => (
          <div
            key={label}
            className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs"
          >
            <div className="mb-2 flex items-center gap-2 text-on-surface-variant">
              <Icon className="h-4 w-4 text-primary" />
              <span className="text-[10px] font-bold uppercase tracking-wider">{label}</span>
            </div>
            <p className="text-2xl font-bold text-on-surface tnum">{value}</p>
            <p className="mt-0.5 text-xs text-on-surface-variant">{sub}</p>
          </div>
        ))}
      </div>

      {quota && (
        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-2">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-on-surface-variant">Origin Storage Footprint</span>
            <span className="tnum font-semibold text-on-surface">
              {((quota.usageBytes ?? 0) / (1024 * 1024)).toFixed(2)} MB
              {quota.quotaBytes ? ` / ${(quota.quotaBytes / (1024 * 1024 * 1024)).toFixed(1)} GB` : ''}
            </span>
          </div>
          <div className="h-2 w-full overflow-hidden rounded-full bg-surface-container">
            <div
              className="h-full rounded-full bg-primary-container transition-all"
              style={{ width: `${usagePct}%` }}
            />
          </div>
        </div>
      )}

      <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
        <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
          Outbox Ledger ({outbox.length})
        </h3>
        {outbox.length === 0 ? (
          <div className="py-8 text-center">
            <UploadCloud className="mx-auto mb-2 h-8 w-8 text-outline" />
            <p className="text-xs text-on-surface-variant">
              Outbox is empty. Captured attempts queued locally will appear here.
            </p>
          </div>
        ) : (
          <div className="divide-y divide-outline-variant/50">
            {outbox.map((entry) => (
              <div
                key={entry.client_uuid}
                className="flex flex-wrap items-center justify-between gap-2 py-3 text-xs"
              >
                <div>
                  <p className="font-bold text-on-surface tnum">
                    {entry.test_type}: {entry.value.toFixed(2)} {entry.unit}
                  </p>
                  <p className="mt-0.5 text-on-surface-variant tnum">
                    {entry.validity_status} • {new Date(entry.recorded_at).toLocaleString()}
                  </p>
                  {entry.opfs_video_filename && (
                    <p className="mt-0.5 text-tertiary">OPFS: {entry.opfs_video_filename}</p>
                  )}
                </div>
                <span
                  className={[
                    'rounded-full px-2.5 py-0.5 text-[10px] font-bold',
                    entry.status === 'FAILED'
                      ? 'bg-error-container text-error'
                      : 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
                  ].join(' ')}
                >
                  {core.auth.isGuest ? 'LOCAL_GUEST' : entry.status}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
