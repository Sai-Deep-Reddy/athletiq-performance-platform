"""CLI tool to evaluate real physical reference vs. AthletiQ validation CSV exports."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from metrics import compute_validation_report, load_paired_trials_csv


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate AthletiQ accuracy against physical reference instruments."
    )
    parser.add_argument("csv_path", type=Path, help="Path to paired validation CSV file")
    args = parser.parse_args()

    if not args.csv_path.exists():
        raise SystemExit(f"Validation CSV not found: {args.csv_path}")

    trials = load_paired_trials_csv(args.csv_path)
    grouped: dict[str, list] = {}
    for t in trials:
        grouped.setdefault(t.test_type, []).append(t)

    reports = [asdict(compute_validation_report(group)) for group in grouped.values()]
    print(json.dumps({"reports": reports}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
