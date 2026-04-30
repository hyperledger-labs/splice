#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Compare perf metrics against absolute thresholds. Exit non-zero on breach.

This script *only detects* breaches; reporting is delegated to the existing
`failure_notifications` GH action at the bottom of each job, which fires on
`if: failure()`. So this script:
  - read every metrics.json in the given dirs,
  - compare each metric to its `max:` rule in `.github/perf-thresholds.yaml`,
  - print a per-(test, metric) result to stdout,
  - append a Markdown summary of breaches to GITHUB_STEP_SUMMARY (visible in
    the GHA UI and linked to from the failure notification),
  - exit 1 if any breach occurred.

Usage:
  python3 check_perf_thresholds.py <metrics_dir> [<metrics_dir> ...]

Exit codes:
  0  no breaches
  1  one or more breaches
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Iterable

import yaml

DEFAULT_THRESHOLDS = Path(".github/perf-thresholds.yaml")


def load_thresholds(path: Path) -> dict:
    with open(path) as f:
        data = yaml.safe_load(f) or {}
    if not isinstance(data, dict):
        raise ValueError(f"thresholds file {path} must be a YAML mapping")
    return data


def iter_metric_files(dirs: Iterable[Path]) -> Iterable[Path]:
    for d in dirs:
        if not d.is_dir():
            print(f"warning: metrics dir {d} does not exist, skipping", file=sys.stderr)
            continue
        yield from sorted(d.glob("*.json"))


def metric_value(data: dict, name: str) -> float | None:
    for m in data.get("metrics", []):
        if m.get("name") == name:
            try:
                return float(m["value"])
            except (TypeError, ValueError):
                return None
    return None


def append_step_summary(lines: list[str]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        print(
            "info: GITHUB_STEP_SUMMARY not set (running outside GitHub Actions?); "
            "skipping run-page summary",
            file=sys.stderr,
        )
        return
    try:
        with open(summary_path, "a") as f:
            f.write("\n".join(lines) + "\n")
    except OSError as e:
        print(f"warning: could not write GITHUB_STEP_SUMMARY ({summary_path}): {e}",
              file=sys.stderr)


def evaluate_breaches(
    metric_dirs: list[Path], thresholds: dict
) -> tuple[list[str], int]:
    """Compare every metrics.json under `metric_dirs` to `thresholds`.

    Returns (breach_lines, files_seen). Per-(test, metric) verdicts are
    printed to stdout as a side effect so they show up in the GHA step log.
    """
    breaches: list[str] = []
    files_seen = 0

    for f in iter_metric_files(metric_dirs):
        files_seen += 1
        try:
            data = json.loads(f.read_text())
        except Exception as e:
            print(f"warning: cannot parse {f}: {e}", file=sys.stderr)
            continue

        test_name = data.get("test_name") or f.stem
        rules = thresholds.get(test_name)
        if not rules:
            print(f"info: no thresholds configured for '{test_name}', skipping ({f.name})")
            continue

        for metric_name, rule in rules.items():
            if "max" not in rule:
                print(
                    f"warning: rule {test_name}::{metric_name} missing 'max', skipping",
                    file=sys.stderr,
                )
                continue
            limit = float(rule["max"])

            observed = metric_value(data, metric_name)
            if observed is None:
                print(
                    f"warning: metric '{metric_name}' not in {f.name} for '{test_name}'",
                    file=sys.stderr,
                )
                continue

            if observed <= limit:
                print(f"OK     {test_name} :: {metric_name} = {observed:.0f} (<= {limit:.0f})")
                continue

            pct = ((observed - limit) / limit * 100.0) if limit else 0.0
            line = (
                f"BREACH {test_name} :: {metric_name} = {observed:.0f} "
                f"(> {limit:.0f}, +{pct:.2f}%)"
            )
            print(line)
            breaches.append(line)

    return breaches, files_seen


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2

    thresholds_file = Path(os.environ.get("THRESHOLDS_FILE", str(DEFAULT_THRESHOLDS)))
    thresholds = load_thresholds(thresholds_file)
    metric_dirs = [Path(p) for p in sys.argv[1:]]

    breaches, files_seen = evaluate_breaches(metric_dirs, thresholds)

    print(f"Done. files_seen={files_seen} breaches={len(breaches)}")

    if breaches:
        append_step_summary(
            ["## Performance threshold breaches", "", "```", *breaches, "```"]
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
