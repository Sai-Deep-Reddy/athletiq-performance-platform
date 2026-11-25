import { useState } from 'react';
import { AuthGate } from './components/auth/AuthGate';
import { CalibrationCenter } from './components/calibration/CalibrationCenter';
import { CaptureWorkbench } from './components/capture/CaptureWorkbench';
import { GovernancePanel } from './components/governance/GovernancePanel';
import { AppShell, type AppSection } from './components/layout/AppShell';
import { SyncOutbox } from './components/outbox/SyncOutbox';
import { OverviewDashboard } from './components/overview/OverviewDashboard';
import { ScoringReview } from './components/review/ScoringReview';
import { BrandMark } from './components/ui/BrandMark';
import { useAthletiqCore } from './core/domain/useAthletiqCore';
import { useTheme } from './core/domain/useTheme';
import type { TestType } from './core/model/types';

export function App() {
  const { theme, toggleTheme } = useTheme();
  const core = useAthletiqCore();
  const [tab, setTab] = useState<AppSection>('overview');
  const [preselectedTestType, setPreselectedTestType] = useState<TestType>('VERTICAL_JUMP');

  if (core.auth.status === 'BOOTING') {
    return (
      <div className="flex min-h-[100dvh] flex-col items-center justify-center gap-3 bg-surface px-4 text-center">
        <BrandMark />
        <p className="text-xs font-medium text-on-surface-variant animate-pulse">
          Verifying backend connectivity and runtime capabilities...
        </p>
      </div>
    );
  }

  if (core.auth.status !== 'AUTHENTICATED' && core.auth.status !== 'GUEST') {
    return <AuthGate auth={core.auth} theme={theme} onToggleTheme={toggleTheme} />;
  }

  const handleLaunchTest = (testType: TestType) => {
    setPreselectedTestType(testType);
    setTab('capture');
  };

  return (
    <AppShell
      core={core}
      activeTab={tab}
      onSelectTab={setTab}
      theme={theme}
      onToggleTheme={toggleTheme}
    >
      {tab === 'overview' && (
        <OverviewDashboard core={core} onNavigate={setTab} onLaunchTest={handleLaunchTest} />
      )}
      {tab === 'calibrate' && <CalibrationCenter core={core} />}
      {tab === 'capture' && (
        <CaptureWorkbench core={core} initialTestType={preselectedTestType} />
      )}
      {tab === 'review' && <ScoringReview core={core} />}
      {tab === 'outbox' && <SyncOutbox core={core} />}
      {tab === 'governance' && <GovernancePanel core={core} />}
    </AppShell>
  );
}
