"""Configurable performance scoring service using persisted institutional standards."""

from dataclasses import dataclass
from datetime import date
from typing import Any

from app.db.models import ScoringStandard, TestType


@dataclass(frozen=True)
class ScoringEvaluation:
    status: str  # "EVALUATED" | "NO_STANDARD_CONFIGURED"
    standard_id: str | None
    standard_name: str | None
    source_citation: str | None
    category_label: str | None
    score_points: float | None
    qualified: bool | None


def compute_age_years(dob: date | None, reference_date: date | None = None) -> int | None:
    """Compute completed age in years from date of birth."""
    if dob is None:
        return None
    ref = reference_date or date.today()
    years = ref.year - dob.year - ((ref.month, ref.day) < (dob.month, dob.day))
    return max(0, years)


def find_matching_standard(
    standards: list[ScoringStandard],
    *,
    test_type: TestType,
    age_years: int | None,
    gender_category: str | None,
    discipline: str | None = None,
) -> ScoringStandard | None:
    """Select the highest-specificity active scoring standard matching student attributes."""
    candidates: list[tuple[int, ScoringStandard]] = []
    for std in standards:
        if not std.is_active or std.test_type != test_type:
            continue
        if age_years is not None and not (std.age_min <= age_years <= std.age_max):
            continue
        if std.gender_category is not None and std.gender_category != gender_category:
            continue
        if std.discipline is not None and std.discipline != discipline:
            continue

        specificity = 0
        if std.gender_category is not None:
            specificity += 2
        if std.discipline is not None:
            specificity += 1
        candidates.append((specificity, std))

    if not candidates:
        return None
    candidates.sort(key=lambda pair: pair[0], reverse=True)
    return candidates[0][1]


def evaluate_against_standard(value: float, standard: ScoringStandard | None) -> ScoringEvaluation:
    """Evaluate a verified measurement against an institution-configured ScoringStandard."""
    if standard is None:
        return ScoringEvaluation(
            status="NO_STANDARD_CONFIGURED",
            standard_id=None,
            standard_name=None,
            source_citation=None,
            category_label=None,
            score_points=None,
            qualified=None,
        )

    bands_config: dict[str, Any] = standard.bands_json or {}
    direction = bands_config.get("direction", "higher_is_better")
    bands = bands_config.get("bands", [])

    matched_band: dict[str, Any] | None = None
    for band in bands:
        min_val = band.get("min_value")
        max_val = band.get("max_value")
        min_ok = min_val is None or value >= float(min_val)
        max_ok = max_val is None or value <= float(max_val)
        if min_ok and max_ok:
            matched_band = band
            break

    if matched_band is None and bands:
        # Pick boundary category deterministically if outside explicit band range.
        if direction == "lower_is_better":
            sorted_bands = sorted(bands, key=lambda b: float(b.get("max_value", float("inf"))))
            matched_band = sorted_bands[0] if value <= float(sorted_bands[0].get("max_value", value)) else sorted_bands[-1]
        else:
            sorted_bands = sorted(bands, key=lambda b: float(b.get("min_value", float("-inf"))))
            matched_band = sorted_bands[0] if value < float(sorted_bands[0].get("min_value", value)) else sorted_bands[-1]

    return ScoringEvaluation(
        status="EVALUATED",
        standard_id=str(standard.id),
        standard_name=standard.name,
        source_citation=standard.source_citation,
        category_label=matched_band.get("label") if matched_band else None,
        score_points=float(matched_band["points"]) if matched_band and matched_band.get("points") is not None else None,
        qualified=bool(matched_band.get("qualified")) if matched_band and "qualified" in matched_band else None,
    )
