"""Unit tests for Stage Final physical validation metrics and pilot-study gates."""

from __future__ import annotations

import math
from pathlib import Path

import pytest

from validation.metrics import PairedTrial, compute_validation_report, load_paired_trials_csv


def test_perfect_agreement_gives_zero_error_and_unit_icc() -> None:
    trials = [
        PairedTrial(f"t-{i}", f"s-{i}", "Pixel-8", "indoor", "HEIGHT", 160.0 + i, 160.0 + i, "cm")
        for i in range(10)
    ]
    report = compute_validation_report(trials)
    assert report.mae == 0.0
    assert report.rmse == 0.0
    assert report.bias == 0.0
    assert report.icc_2_1 == pytest.approx(1.0)
    assert report.pearson_r == pytest.approx(1.0)
    # 10 subjects & 1 device MUST be labelled PILOT_ONLY
    assert report.evidence_tier == "PILOT_ONLY"
    assert len(report.limitations) == 3


def test_systematic_bias_and_bland_altman_limits_of_agreement() -> None:
    # Known constant +2.0 cm offset across 4 subjects -> MAE=2.0, RMSE=2.0, bias=+2.0, SD=0.0
    refs = [40.0, 45.0, 50.0, 55.0]
    trials = [
        PairedTrial(f"t-{i}", f"s-{i}", "DeviceA", "gym", "VERTICAL_JUMP", r, r + 2.0, "cm")
        for i, r in enumerate(refs)
    ]
    report = compute_validation_report(trials)
    assert report.mae == pytest.approx(2.0)
    assert report.rmse == pytest.approx(2.0)
    assert report.bias == pytest.approx(2.0)
    assert report.sd_diff == pytest.approx(0.0)
    assert report.bland_altman_loa_lower == pytest.approx(2.0)
    assert report.bland_altman_loa_upper == pytest.approx(2.0)
    # Pearson r is 1.0 even with bias, whereas absolute-agreement ICC(2,1) drops below 1.0!
    assert report.pearson_r == pytest.approx(1.0)
    assert report.icc_2_1 is not None and report.icc_2_1 < 0.96


def test_multi_condition_gate_passes_only_with_diverse_population(tmp_path: Path) -> None:
    csv_file = tmp_path / "validation_trials.csv"
    lines = ["trial_id,subject_id,device_model,lighting_condition,test_type,reference_value,athletiq_value,unit"]
    for i in range(30):
        dev = "Pixel-8" if i % 2 == 0 else "Galaxy-A54"
        light = "indoor_gym" if i % 3 == 0 else "outdoor_daylight"
        ref = 150.0 + i * 1.2
        meas = ref + (0.5 if i % 2 == 0 else -0.5)
        lines.append(f"tr-{i},sub-{i},{dev},{light},STANDING_BROAD_JUMP,{ref},{meas},cm")
    csv_file.write_text("\n".join(lines), encoding="utf-8")

    trials = load_paired_trials_csv(csv_file)
    report = compute_validation_report(trials)
    assert report.evidence_tier == "MULTI_CONDITION_VALIDATION"
    assert report.limitations == []
    assert report.mae == pytest.approx(0.5)
    assert report.rmse == pytest.approx(0.5)
    assert report.bland_altman_loa_upper > report.bland_altman_loa_lower


def test_rejects_mixed_units_and_non_finite_values() -> None:
    with pytest.raises(ValueError):
        PairedTrial("t1", "s1", "Dev", "gym", "HEIGHT", math.nan, 170.0, "cm")

    t1 = PairedTrial("t1", "s1", "Dev", "gym", "HEIGHT", 170.0, 170.5, "cm")
    t2 = PairedTrial("t2", "s2", "Dev", "gym", "HEIGHT", 1.75, 1.76, "m")
    with pytest.raises(ValueError):
        compute_validation_report([t1, t2])
