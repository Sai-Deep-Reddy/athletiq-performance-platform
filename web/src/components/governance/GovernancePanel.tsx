import { type FormEvent, useState } from 'react';
import { BookOpen, List, Shield, UserPlus } from 'lucide-react';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';
import type { TestType } from '../../core/model/types';

type Tab = 'standards' | 'users' | 'audit';

export function GovernancePanel({ core }: { core: ReturnType<typeof useAthletiqCore> }) {
  const [activeTab, setActiveTab] = useState<Tab>('standards');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [staffEmail, setStaffEmail] = useState('');
  const [staffName, setStaffName] = useState('');
  const [staffRole, setStaffRole] = useState<'ADMIN' | 'INSTRUCTOR' | 'COACH'>('INSTRUCTOR');
  const [staffPassword, setStaffPassword] = useState('');

  const [stdTestType, setStdTestType] = useState<TestType>('VERTICAL_JUMP');
  const [stdName, setStdName] = useState('');
  const [stdCitation, setStdCitation] = useState('');
  const [stdGender, setStdGender] = useState('');
  const [stdMinAge, setStdMinAge] = useState('12');
  const [stdMaxAge, setStdMaxAge] = useState('18');
  const [stdUnit, setStdUnit] = useState('cm');
  const [stdDirection, setStdDirection] = useState<'higher_is_better' | 'lower_is_better'>(
    'higher_is_better',
  );
  const [stdBands, setStdBands] = useState('');

  const handleCreateUser = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await core.createStaffUser({
        email: staffEmail,
        full_name: staffName,
        role: staffRole,
        password: staffPassword,
      });
      setStaffEmail('');
      setStaffName('');
      setStaffPassword('');
      setNotice(`Staff user ${staffName} created.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create staff user.');
    }
  };

  const handleCreateStandard = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setNotice(null);
    let bands: Array<{
      label: string;
      min_value?: number | null;
      max_value?: number | null;
      points?: number | null;
      qualified?: boolean;
    }> = [];
    try {
      const parsed = JSON.parse(stdBands);
      if (!Array.isArray(parsed)) {
        throw new Error('Bands JSON must be an array.');
      }
      bands = parsed;
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Bands must be a valid JSON array.');
      return;
    }
    try {
      await core.createStandard({
        name: stdName,
        test_type: stdTestType,
        age_min: Number(stdMinAge) || 0,
        age_max: Number(stdMaxAge) || 99,
        gender_category: stdGender.trim() || null,
        metric_unit: stdUnit,
        direction: stdDirection,
        bands,
        source_citation: stdCitation,
      });
      setStdName('');
      setStdCitation('');
      setStdBands('');
      setNotice('Normative scoring standard created.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create standard.');
    }
  };

  const inputCls =
    'mt-1 min-h-[44px] w-full rounded-xl border border-outline-variant bg-surface-container-low px-3 py-2 text-xs text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20';
  const btnPrimary =
    'inline-flex min-h-[46px] items-center justify-center gap-2 rounded-xl bg-primary-container px-4 text-xs font-bold text-white shadow-xs transition hover:brightness-110 active:scale-[0.98] disabled:opacity-40';

  const tabs: { key: Tab; label: string; Icon: typeof Shield }[] = [
    { key: 'standards', label: 'Normative Standards', Icon: BookOpen },
    { key: 'users', label: 'Staff Users', Icon: UserPlus },
    { key: 'audit', label: 'Local Audit Log', Icon: List },
  ];

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
        <div className="flex items-center gap-2">
          <Shield className="h-5 w-5 text-primary" />
          <div>
            <h2 className="text-sm font-bold text-on-surface sm:text-base">
              Governance, Standards &amp; Audit
            </h2>
            <p className="text-[11px] text-on-surface-variant">
              Institutional normative benchmarks, staff provisioning, and tamper-evident local audit events
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {tabs.map(({ key, label, Icon }) => (
            <button
              key={key}
              type="button"
              onClick={() => setActiveTab(key)}
              className={[
                'inline-flex min-h-[38px] items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-bold transition-all',
                activeTab === key
                  ? 'bg-primary-container text-white shadow-xs'
                  : 'border border-outline-variant bg-surface-container-low text-on-surface-variant hover:text-on-surface',
              ].join(' ')}
            >
              <Icon className="h-3.5 w-3.5" />
              {label}
            </button>
          ))}
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

      {!core.auth.canEditAssessments && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/15 p-3.5 text-xs text-amber-800 dark:text-amber-300">
          <Shield className="mr-1.5 inline h-4 w-4" />
          Signed-in Admin or Instructor permissions are required to publish institutional standards or provision staff accounts.
        </div>
      )}

      {activeTab === 'standards' && (
        <div className="space-y-4">
          <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
              Configured Normative Benchmarks ({core.workspace.standards.length})
            </h3>
            {core.workspace.standards.length === 0 ? (
              <p className="py-6 text-center text-xs text-on-surface-variant">
                No scoring standards configured yet.
              </p>
            ) : (
              <div className="divide-y divide-outline-variant/50">
                {core.workspace.standards.map((std) => (
                  <div key={std.id} className="space-y-1 py-3">
                    <div className="flex items-center justify-between gap-2">
                      <p className="text-sm font-bold text-on-surface">{std.name}</p>
                      <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-[10px] font-bold text-primary">
                        {std.test_type}
                      </span>
                    </div>
                    <p className="text-xs text-on-surface-variant tnum">
                      {std.gender_category ?? 'ALL'} • Age {std.age_min}–{std.age_max} • Unit:{' '}
                      {std.metric_unit}
                    </p>
                    {std.source_citation && (
                      <p className="text-xs italic text-on-surface-variant">{std.source_citation}</p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {core.auth.canEditAssessments && (
            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
              <h3 className="text-sm font-bold text-on-surface">Create Normative Standard</h3>
              <form onSubmit={(e) => void handleCreateStandard(e)} className="space-y-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="text-xs text-on-surface-variant">
                    Test Type
                    <select
                      value={stdTestType}
                      onChange={(e) => setStdTestType(e.target.value as TestType)}
                      className={inputCls}
                    >
                      <option value="HEIGHT">HEIGHT</option>
                      <option value="VERTICAL_JUMP">VERTICAL_JUMP</option>
                      <option value="STANDING_BROAD_JUMP">STANDING_BROAD_JUMP</option>
                      <option value="SPRINT">SPRINT</option>
                    </select>
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Standard Name
                    <input
                      required
                      value={stdName}
                      onChange={(e) => setStdName(e.target.value)}
                      placeholder="e.g. SAI Khelo India U-18"
                      className={inputCls}
                    />
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Gender Category (optional)
                    <input
                      value={stdGender}
                      onChange={(e) => setStdGender(e.target.value)}
                      placeholder="MALE, FEMALE, or leave blank"
                      className={inputCls}
                    />
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Source Citation
                    <input
                      required
                      value={stdCitation}
                      onChange={(e) => setStdCitation(e.target.value)}
                      placeholder="DOI or official handbook citation"
                      className={inputCls}
                    />
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Min Age
                    <input
                      required
                      value={stdMinAge}
                      onChange={(e) => setStdMinAge(e.target.value)}
                      type="number"
                      className={inputCls + ' tnum'}
                    />
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Max Age
                    <input
                      required
                      value={stdMaxAge}
                      onChange={(e) => setStdMaxAge(e.target.value)}
                      type="number"
                      className={inputCls + ' tnum'}
                    />
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Metric Unit
                    <input
                      required
                      value={stdUnit}
                      onChange={(e) => setStdUnit(e.target.value)}
                      placeholder="cm or s"
                      className={inputCls}
                    />
                  </label>
                  <label className="text-xs text-on-surface-variant">
                    Direction
                    <select
                      value={stdDirection}
                      onChange={(e) =>
                        setStdDirection(e.target.value as 'higher_is_better' | 'lower_is_better')
                      }
                      className={inputCls}
                    >
                      <option value="higher_is_better">Higher is better</option>
                      <option value="lower_is_better">Lower is better</option>
                    </select>
                  </label>
                </div>
                <label className="block text-xs text-on-surface-variant">
                  Bands JSON
                  <textarea
                    required
                    value={stdBands}
                    onChange={(e) => setStdBands(e.target.value)}
                    placeholder={`[{"label": "Excellent", "min_value": 50, "max_value": null, "qualified": true}]`}
                    rows={3}
                    className="mt-1 w-full rounded-xl border border-outline-variant bg-surface-container-low p-3 font-mono text-xs text-on-surface outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
                  />
                </label>
                <button type="submit" className={btnPrimary}>
                  Save Standard
                </button>
              </form>
            </div>
          )}
        </div>
      )}

      {activeTab === 'users' && core.auth.canEditAssessments && (
        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
          <h3 className="text-sm font-bold text-on-surface">Provision Staff Account</h3>
          <form onSubmit={(e) => void handleCreateUser(e)} className="grid gap-3 sm:grid-cols-2">
            <label className="text-xs text-on-surface-variant">
              Full Name
              <input
                required
                value={staffName}
                onChange={(e) => setStaffName(e.target.value)}
                placeholder="Examiner full name"
                className={inputCls}
              />
            </label>
            <label className="text-xs text-on-surface-variant">
              Email
              <input
                required
                type="email"
                value={staffEmail}
                onChange={(e) => setStaffEmail(e.target.value)}
                placeholder="staff@institution.org"
                className={inputCls}
              />
            </label>
            <label className="text-xs text-on-surface-variant">
              Role
              <select
                value={staffRole}
                onChange={(e) => setStaffRole(e.target.value as 'ADMIN' | 'INSTRUCTOR' | 'COACH')}
                className={inputCls}
              >
                <option value="INSTRUCTOR">INSTRUCTOR</option>
                <option value="COACH">COACH</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </label>
            <label className="text-xs text-on-surface-variant">
              Initial Password (min 12 chars)
              <input
                required
                type="password"
                value={staffPassword}
                onChange={(e) => setStaffPassword(e.target.value)}
                placeholder="Initial password"
                className={inputCls}
              />
            </label>
            <div className="sm:col-span-2">
              <button type="submit" className={btnPrimary}>
                <UserPlus className="h-4 w-4" /> Create Staff User
              </button>
            </div>
          </form>
        </div>
      )}

      {activeTab === 'audit' && (
        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
          <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
            Local Audit Events ({core.workspace.localAudit.length})
          </h3>
          {core.workspace.localAudit.length === 0 ? (
            <p className="py-6 text-center text-xs text-on-surface-variant">
              No local audit events recorded in this browser yet.
            </p>
          ) : (
            <div className="max-h-[480px] divide-y divide-outline-variant/50 overflow-y-auto aq-scroll">
              {[...core.workspace.localAudit].reverse().map((entry) => (
                <div key={entry.id} className="py-3 text-xs">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-bold text-primary">{entry.action}</span>
                    <span className="text-on-surface-variant tnum">
                      {new Date(entry.createdAtIso).toLocaleString()}
                    </span>
                  </div>
                  {entry.detail && <p className="mt-0.5 text-on-surface-variant">{entry.detail}</p>}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
