"""Offline physical accuracy validation metrics (MAE, RMSE, Bland-Altman, ICC(2,1)).

Computes concurrent validity statistics comparing AthletiQ measurements against
physical reference instruments (stadiometer, Vertec/wall tape, measuring tape,
timing gates) without fabricating data or allowing small pilots to pass as
examination-grade evidence.
"""

from __future__ import annotations

import csv
import math
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class PairedTrial:
    trial_id: str
    subject_id: str
    device_model: str
    lighting_condition: str
    test_type: str
    reference_value: float
    athletiq_value: float
    unit: str

    def __post_init__(self) -> None:
        if not self.trial_id.strip() or not self.subject_id.strip():
            raise ValueError("trial_id and subject_id must not be blank")
        if not math.isfinite(self.reference_value) or not math.isfinite(self.athletiq_value):
            raise ValueError("Measurement values must be finite")


@dataclass(frozen=True)
class ValidationReport:
    test_type: str
    unit: str
    trial_count: int
    subject_count: int
    distinct_devices: int
    distinct_lighting_conditions: int
    mae: float
    rmse: float
    bias: float  # mean(athletiq - reference)
    sd_diff: float
    bland_altman_loa_lower: float
    bland_altman_loa_upper: float
    pearson_r: float | None
    icc_2_1: float | None
    evidence_tier: str  # "PILOT_ONLY" | "MULTI_CONDITION_VALIDATION"
    limitations: list[str]


def compute_validation_report(
    trials: list[PairedTrial],
    *,
    min_subjects_full_study: int = 30,
    min_devices_full_study: int = 2,
    min_lighting_full_study: int = 2,
) -> ValidationReport:
    """Compute MAE, RMSE, Bland-Altman bias/LoA, Pearson r, and ICC(2,1)."""
    if len(trials) < 2:
        raise ValueError("At least 2 paired trials are required for statistical validation")

    test_types = {t.test_type for t in trials}
    units = {t.unit for t in trials}
    if len(test_types) != 1:
        raise ValueError(f"All trials in a report must share one test_type (got {sorted(test_types)})")
    if len(units) != 1:
        raise ValueError(f"All trials in a report must share one unit (got {sorted(units)})")

    n = len(trials)
    refs = [t.reference_value for t in trials]
    meas = [t.athletiq_value for t in trials]
    diffs = [m - r for r, m in zip(refs, meas)]
    abs_errs = [abs(d) for d in diffs]
    sq_errs = [d * d for d in diffs]

    mae = sum(abs_errs) / n
    rmse = math.sqrt(sum(sq_errs) / n)
    bias = sum(diffs) / n
    sd_diff = math.sqrt(sum((d - bias) ** 2 for d in diffs) / (n - 1))
    loa_lower = bias - 1.96 * sd_diff
    loa_upper = bias + 1.96 * sd_diff

    pearson_r = _pearson_correlation(refs, meas)
    icc_2_1 = _icc_two_way_random_single_agreement(refs, meas)

    subjects = {t.subject_id for t in trials}
    devices = {t.device_model for t in trials}
    lighting = {t.lighting_condition for t in trials}

    limitations: list[str] = []
    if len(subjects) < min_subjects_full_study:
        limitations.append(
            f"Subject count ({len(subjects)}) is below full-study threshold ({min_subjects_full_study}); treat as pilot only"
        )
    if len(devices) < min_devices_full_study:
        limitations.append(
            f"Tested on {len(devices)} device model(s); requires >= {min_devices_full_study} smartphones for generalizability"
        )
    if len(lighting) < min_lighting_full_study:
        limitations.append(
            f"Tested under {len(lighting)} lighting condition(s); requires >= {min_lighting_full_study} environments"
        )

    evidence_tier = "MULTI_CONDITION_VALIDATION" if not limitations else "PILOT_ONLY"

    return ValidationReport(
        test_type=next(iter(test_types)),
        unit=next(iter(units)),
        trial_count=n,
        subject_count=len(subjects),
        distinct_devices=len(devices),
        distinct_lighting_conditions=len(lighting),
        mae=mae,
        rmse=rmse,
        bias=bias,
        sd_diff=sd_diff,
        bland_altman_loa_lower=loa_lower,
        bland_altman_loa_upper=loa_upper,
        pearson_r=pearson_r,
        icc_2_1=icc_2_1,
        evidence_tier=evidence_tier,
        limitations=limitations,
    )


def load_paired_trials_csv(csv_path: Path) -> list[PairedTrial]:
    """Load real paired physical-vs-AthletiQ trial rows from a CSV file."""
    required_cols = {
        "trial_id",
        "subject_id",
        "device_model",
        "lighting_condition",
        "test_type",
        "reference_value",
        "athletiq_value",
        "unit",
    }
    trials: list[PairedTrial] = []
    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not required_cols.issubset(set(reader.fieldnames)):
            raise ValueError(f"CSV must contain columns: {sorted(required_cols)}")
        for row in reader:
            trials.append(
                PairedTrial(
                    trial_id=row["trial_id"],
                    subject_id=row["subject_id"],
                    device_model=row["device_model"],
                    lighting_condition=row["lighting_condition"],
                    test_type=row["test_type"],
                    reference_value=float(row["reference_value"]),
                    athletiq_value=float(row["athletiq_value"]),
                    unit=row["unit"],
                )
            )
    return trials


def _pearson_correlation(xs: list[float], ys: list[float]) -> float | None:
    n = len(xs)
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    num = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    den_x = math.sqrt(sum((x - mean_x) ** 2 for x in xs))
    den_y = math.sqrt(sum((y - mean_y) ** 2 for y in ys))
    if den_x <= 1e-12 or den_y <= 1e-12:
        return None
    return max(-1.0, min(1.0, num / (den_x * den_y)))


def _icc_two_way_random_single_agreement(refs: list[float], meas: list[float]) -> float | None:
    """Compute ICC(2,1) (two-way random effects, absolute agreement, single measure) for k=2 raters."""
    n = len(refs)
    if n < 2:
        return None
    k = 2.0
    grand_mean = (sum(refs) + sum(meas)) / (2.0 * n)

    row_means = [(r + m) / 2.0 for r, m in zip(refs, meas)]
    col_means = [sum(refs) / n, sum(meas) / n]

    ss_rows = k * sum((rm - grand_mean) ** 2 for rm in row_means)
    ss_cols = n * sum((cm - grand_mean) ** 2 for cm in col_means)
    ss_total = sum((r - grand_mean) ** 2 + (m - grand_mean) ** 2 for r, m in zip(refs, meas))
    ss_err = max(0.0, ss_total - ss_rows - ss_cols)

    ms_rows = ss_rows / (n - 1)
    ms_cols = ss_cols / (k - 1)
    ms_err = ss_err / ((n - 1) * (k - 1))

    denom = ms_rows + (k - 1) * ms_err + (k * (ms_cols - ms_err) / n)
    if denom <= 1e-12:
        return None
    return (ms_rows - ms_err) / denom
