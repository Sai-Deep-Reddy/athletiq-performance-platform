import { type FormEvent, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardList,
  FileDown,
  TrendingUp,
  Users,
  XCircle,
} from 'lucide-react';
import type {
  AttemptResponse,
  ExaminationSummaryResponse,
  StudentHistoryResponse,
} from '../../core/api/schemas';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';
import type { TestType } from '../../core/model/types';

function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function ValidityBadge({ status }: { status: string }) {
  const isValid = status === 'VALID';
  const isFlagged = status === 'FLAGGED';
  return (
    <span
      className={[
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-bold uppercase',
        isValid ? 'bg-emerald-500/15 text-tertiary' : '',
        isFlagged ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300' : '',
        !isValid && !isFlagged ? 'bg-error-container text-error' : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {isValid ? <CheckCircle2 className="h-3 w-3" /> : <AlertTriangle className="h-3 w-3" />}
      {status}
    </span>
  );
}

function ApprovalBadge({ status }: { status: string }) {
  if (status === 'APPROVED') return <span className="text-[11px] font-bold text-tertiary">APPROVED</span>;
  if (status === 'REJECTED') return <span className="text-[11px] font-bold text-error">REJECTED</span>;
  return <span className="text-[11px] font-semibold text-on-surface-variant">PENDING</span>;
}

type Tab = 'exams' | 'students' | 'history';

export function ScoringReview({ core }: { core: ReturnType<typeof useAthletiqCore> }) {
  const [activeTab, setActiveTab] = useState<Tab>('exams');
  const [studentCode, setStudentCode] = useState('');
  const [studentName, setStudentName] = useState('');
  const [examTitle, setExamTitle] = useState('');
  const [selectedExamId, setSelectedExamId] = useState('');
  const [examAttempts, setExamAttempts] = useState<AttemptResponse[]>([]);
  const [examSummary, setExamSummary] = useState<ExaminationSummaryResponse | null>(null);
  const [studentHistory, setStudentHistory] = useState<StudentHistoryResponse | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const inspectExam = async (examId: string) => {
    setSelectedExamId(examId);
    setError(null);
    try {
      const [attempts, summary] = await Promise.all([
        core.loadExamAttempts(examId),
        core.loadExamSummary(examId),
      ]);
      setExamAttempts(attempts);
      setExamSummary(summary);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load examination data.');
    }
  };

  const handleAddStudent = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await core.registerStudent({
        institution_student_code: studentCode,
        full_name: studentName,
      });
      setStudentCode('');
      setStudentName('');
      setNotice('Athlete registered.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to register athlete.');
    }
  };

  const handleCreateExam = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const created = await core.createExamination({
        title: examTitle,
        test_types: ['HEIGHT', 'VERTICAL_JUMP', 'STANDING_BROAD_JUMP', 'SPRINT'] as TestType[],
        status: 'ACTIVE',
      });
      setExamTitle('');
      await inspectExam(created.id);
      setNotice('Examination created.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create examination.');
    }
  };

  const handleReview = async (attemptId: string, status: 'APPROVED' | 'REJECTED') => {
    if (!selectedExamId) return;
    setError(null);
    try {
      await core.reviewAttempt(selectedExamId, attemptId, status);
      await inspectExam(selectedExamId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to review attempt.');
    }
  };

  const inputCls =
    'min-h-[44px] w-full rounded-xl border border-outline-variant bg-surface-container-low px-3 py-2 text-xs text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20';

  const tabs: { key: Tab; label: string; Icon: typeof Users }[] = [
    { key: 'exams', label: 'Examinations', Icon: ClipboardList },
    { key: 'students', label: 'Athletes', Icon: Users },
    { key: 'history', label: 'History', Icon: TrendingUp },
  ];

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
        <div>
          <h2 className="text-sm font-bold text-on-surface sm:text-base">
            Scoring, Athlete Cohorts &amp; Verification
          </h2>
          <p className="text-[11px] text-on-surface-variant">
            Manage registered athletes, inspect examination attempts, approve/reject scores, and export PDF dossiers.
          </p>
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

      {core.auth.isGuest && (
        <div className="rounded-xl border border-primary/30 bg-surface-container-low p-3 text-xs text-on-surface-variant">
          Guest Mode active — institutional cohort rosters, official approvals, and PDF exports require signing in with an institutional account. Local guest captures appear in Sync Outbox &amp; Overview.
        </div>
      )}

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

      {activeTab === 'exams' && (
        <div className="space-y-4">
          {core.auth.canEditAssessments && (
            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
              <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
                Create Examination
              </h3>
              <form onSubmit={(e) => void handleCreateExam(e)} className="flex flex-col gap-2 sm:flex-row">
                <input
                  required
                  value={examTitle}
                  onChange={(e) => setExamTitle(e.target.value)}
                  placeholder="Examination title (e.g. SAI Fitness Assessment 2026)"
                  className={inputCls}
                />
                <button
                  type="submit"
                  className="min-h-[44px] shrink-0 rounded-xl bg-primary-container px-4 text-xs font-bold text-white transition hover:brightness-110 active:scale-[0.98]"
                >
                  Create Exam
                </button>
              </form>
            </div>
          )}

          <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
              Examinations ({core.workspace.examinations.length})
            </h3>
            {core.workspace.examinations.length === 0 ? (
              <p className="py-6 text-center text-xs text-on-surface-variant">
                No examinations available.
              </p>
            ) : (
              <div className="divide-y divide-outline-variant/50">
                {core.workspace.examinations.map((ex) => (
                  <div key={ex.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <div>
                      <p className="text-sm font-bold text-on-surface">{ex.title}</p>
                      <p className="mt-0.5 text-xs text-on-surface-variant">
                        {ex.status} • {ex.test_types_json.join(', ')}
                      </p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={() => void inspectExam(ex.id)}
                        className="min-h-[38px] rounded-xl bg-surface-container px-3 py-1.5 text-xs font-bold text-primary hover:brightness-95"
                      >
                        Inspect
                      </button>
                      <button
                        type="button"
                        onClick={() =>
                          void core.api
                            .downloadExaminationPdf(ex.id)
                            .then((b) => triggerBlobDownload(b, `exam_${ex.id}.pdf`))
                        }
                        className="inline-flex min-h-[38px] items-center gap-1 rounded-xl border border-outline-variant bg-surface-container-low px-3 py-1.5 text-xs font-semibold text-on-surface"
                      >
                        <FileDown className="h-3.5 w-3.5" /> PDF
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {examSummary && (
            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
              <div>
                <h3 className="text-sm font-bold text-on-surface">
                  Exam Summary: {examSummary.examination.title}
                </h3>
                <p className="mt-0.5 text-xs text-on-surface-variant tnum">
                  Students tested: {examSummary.total_students_tested} • Approved:{' '}
                  {examSummary.approved_attempts_count} • Pending: {examSummary.pending_attempts_count}
                </p>
              </div>
              {examAttempts.length === 0 ? (
                <p className="py-4 text-center text-xs text-on-surface-variant">
                  No attempts synced to this examination yet.
                </p>
              ) : (
                <div className="divide-y divide-outline-variant/50">
                  {examAttempts.map((att) => (
                    <div key={att.id} className="flex flex-wrap items-center gap-3 py-3">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-bold text-on-surface tnum">
                            {att.test_type}: {att.value.toFixed(2)} {att.unit}
                          </span>
                          <ValidityBadge status={att.validity_status} />
                          <ApprovalBadge status={att.approval_status} />
                          {att.is_best_attempt && (
                            <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-bold text-amber-700 dark:text-amber-300">
                              BEST
                            </span>
                          )}
                        </div>
                        <p className="mt-0.5 text-xs text-on-surface-variant tnum">
                          ±{att.uncertainty.toFixed(2)} {att.unit}
                        </p>
                      </div>
                      {core.auth.canEditAssessments && (
                        <div className="flex shrink-0 gap-2">
                          <button
                            type="button"
                            onClick={() => void handleReview(att.id, 'APPROVED')}
                            className="inline-flex min-h-[38px] items-center gap-1 rounded-xl bg-tertiary-container px-3 py-1.5 text-xs font-bold text-white"
                          >
                            <CheckCircle2 className="h-3.5 w-3.5" /> Approve
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleReview(att.id, 'REJECTED')}
                            className="inline-flex min-h-[38px] items-center gap-1 rounded-xl bg-red-600 px-3 py-1.5 text-xs font-bold text-white"
                          >
                            <XCircle className="h-3.5 w-3.5" /> Reject
                          </button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {activeTab === 'students' && (
        <div className="space-y-4">
          {core.auth.canEditAssessments && (
            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
              <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
                Register Athlete
              </h3>
              <form onSubmit={(e) => void handleAddStudent(e)} className="grid gap-2 sm:grid-cols-3">
                <input
                  required
                  value={studentCode}
                  onChange={(e) => setStudentCode(e.target.value)}
                  placeholder="ID (e.g. ATH-001)"
                  className={inputCls}
                />
                <input
                  required
                  value={studentName}
                  onChange={(e) => setStudentName(e.target.value)}
                  placeholder="Full name"
                  className={inputCls}
                />
                <button
                  type="submit"
                  className="min-h-[44px] rounded-xl bg-primary-container px-4 text-xs font-bold text-white transition hover:brightness-110 active:scale-[0.98]"
                >
                  Register Athlete
                </button>
              </form>
            </div>
          )}

          <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
              Registered Athletes ({core.workspace.students.length})
            </h3>
            {core.workspace.students.length === 0 ? (
              <p className="py-6 text-center text-xs text-on-surface-variant">
                No athletes registered yet.
              </p>
            ) : (
              <div className="divide-y divide-outline-variant/50">
                {core.workspace.students.map((st) => (
                  <div key={st.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <div>
                      <p className="text-sm font-bold text-on-surface">{st.full_name}</p>
                      <p className="text-xs text-on-surface-variant tnum">{st.institution_student_code}</p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          void core.loadStudentHistory(st.id).then((h) => {
                            setStudentHistory(h);
                            setActiveTab('history');
                          });
                        }}
                        className="min-h-[38px] rounded-xl bg-surface-container px-3 py-1.5 text-xs font-bold text-primary"
                      >
                        History
                      </button>
                      <button
                        type="button"
                        onClick={() =>
                          void core.api
                            .downloadStudentPdf(st.id)
                            .then((b) =>
                              triggerBlobDownload(b, `student_${st.institution_student_code}.pdf`),
                            )
                        }
                        className="inline-flex min-h-[38px] items-center gap-1 rounded-xl border border-outline-variant bg-surface-container-low px-3 py-1.5 text-xs font-semibold text-on-surface"
                      >
                        <FileDown className="h-3.5 w-3.5" /> PDF
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {activeTab === 'history' && (
        <div className="space-y-4">
          {!studentHistory ? (
            <div className="rounded-2xl border border-dashed border-outline-variant bg-surface-container-lowest p-10 text-center">
              <TrendingUp className="mx-auto mb-2 h-10 w-10 text-outline" />
              <p className="text-xs text-on-surface-variant">
                Select an athlete from the Athletes tab to view longitudinal scoring trends.
              </p>
            </div>
          ) : (
            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-4">
              <div>
                <h3 className="text-sm font-bold text-on-surface">{studentHistory.student.full_name}</h3>
                <p className="text-xs text-on-surface-variant tnum">
                  {studentHistory.student.institution_student_code}
                </p>
              </div>
              {studentHistory.trends.length === 0 ? (
                <p className="py-4 text-xs text-on-surface-variant">No valid attempts recorded yet.</p>
              ) : (
                <div className="grid gap-3 sm:grid-cols-2">
                  {studentHistory.trends.map((t) => (
                    <div
                      key={t.test_type}
                      className="rounded-xl border border-outline-variant bg-surface-container-low p-4"
                    >
                      <p className="mb-2 text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
                        {t.test_type}
                      </p>
                      <div className="flex gap-6 tnum">
                        <div>
                          <p className="text-[11px] text-on-surface-variant">Latest</p>
                          <p className="text-xl font-bold text-primary">
                            {t.latest_value.toFixed(2)}{' '}
                            <span className="text-xs font-normal text-on-surface-variant">
                              {t.unit}
                            </span>
                          </p>
                        </div>
                        <div>
                          <p className="text-[11px] text-on-surface-variant">Best</p>
                          <p className="text-xl font-bold text-tertiary">
                            {t.best_value.toFixed(2)}{' '}
                            <span className="text-xs font-normal text-on-surface-variant">
                              {t.unit}
                            </span>
                          </p>
                        </div>
                      </div>
                      <p className="mt-2 text-xs text-on-surface-variant">
                        {t.attempt_count} attempt{t.attempt_count !== 1 ? 's' : ''} •{' '}
                        {t.scoring.status === 'NO_STANDARD_CONFIGURED'
                          ? 'No standard configured'
                          : `${t.scoring.standard_name ?? ''} — ${t.scoring.category_label ?? 'Uncategorized'}`}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
