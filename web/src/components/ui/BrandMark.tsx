import { Moon, Sun } from 'lucide-react';
import type { ThemeMode } from '../../core/domain/useTheme';

export function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <div className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[#003594] to-[#004ac6] text-white shadow-sm ring-1 ring-white/15">
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" aria-hidden="true">
          <circle cx="12" cy="5.5" r="2.2" fill="#00f2fe" />
          <path
            d="M6.5 9.5L12 11.2L17.5 6.8M12 11.2V16.2M12 16.2L8.2 21M12 16.2L15.8 21"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle cx="17.5" cy="6.8" r="1.4" fill="#34d399" />
        </svg>
      </div>
      {!compact && (
        <div className="flex flex-col leading-tight">
          <span className="text-base font-bold tracking-tight text-primary">AthletiQ</span>
          <span className="text-[10px] font-semibold uppercase tracking-wider text-outline">
            Clinical Kinematics
          </span>
        </div>
      )}
    </div>
  );
}

export function ThemeToggle({
  theme,
  onToggle,
  compact = false,
}: {
  theme: ThemeMode;
  onToggle: () => void;
  compact?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={theme === 'dark' ? 'Switch to Light theme' : 'Switch to Dark theme'}
      title={theme === 'dark' ? 'Switch to Daylight Clinical theme' : 'Switch to Obsidian Dark theme'}
      className="inline-flex min-h-[40px] items-center gap-1.5 rounded-lg border border-outline-variant bg-surface-container-low px-2.5 py-1.5 text-xs font-semibold text-on-surface transition-all hover:bg-surface-container active:scale-95"
    >
      {theme === 'dark' ? (
        <>
          <Sun className="h-4 w-4 text-amber-400" />
          {!compact && <span className="hidden sm:inline">Light</span>}
        </>
      ) : (
        <>
          <Moon className="h-4 w-4 text-primary" />
          {!compact && <span className="hidden sm:inline">Dark</span>}
        </>
      )}
    </button>
  );
}
