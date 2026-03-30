#!/usr/bin/env python3
"""Push store performance metrics to the Prometheus Pushgateway.
Usage:
  python3 push_store_ingestion_perf_metrics.py

  TODO: Add git info (commit hash etc)
"""

import json
import sys
from pathlib import Path

from prometheus_client import CollectorRegistry, Gauge, push_to_gateway

METRICS_DIR = Path("/tmp/store-ingestion-perf-metrics")
PUSHGATEWAY_URL = "http://prometheus-pushgateway.observability.svc.cluster.local:9091"
JOB_NAME = "splice_store_ingest_perf"


def _build_registry(data: dict) -> CollectorRegistry:
    registry = CollectorRegistry()
    for entry in data["metrics"]:
        gauge = Gauge(entry["name"], entry["description"], registry=registry)
        gauge.set(float(entry["value"]))
    return registry


def _read_metrics_file(metrics_file: Path) -> dict:
    with open(metrics_file) as f:
        return json.load(f)


def _push_metrics_for_test(test_name: str, data: dict) -> None:
    registry = _build_registry(data)
    grouping_key = {"test": test_name}
    push_to_gateway(
        PUSHGATEWAY_URL,
        job=JOB_NAME,
        registry=registry,
        grouping_key=grouping_key,
    )
    print(f"Pushed {len(data['metrics'])} metric(s) for '{test_name}'")


def push_metrics() -> None:
    if not METRICS_DIR.is_dir():
        print(f"No metrics directory found at {METRICS_DIR}, nothing to push.")
        return

    json_files = sorted(METRICS_DIR.glob("*.json"))
    if not json_files:
        print(f"No metric files found in {METRICS_DIR}, nothing to push.")
        return

    error_files = 0
    for metrics_file in json_files:
        try:
            data = _read_metrics_file(metrics_file)
            test_name = data.get("test_name", metrics_file.stem)
            _push_metrics_for_test(test_name, data)
        except Exception as e:
            error_files += 1
            print(f"Error processing {metrics_file}: {e}", file=sys.stderr)

    if error_files:
        print(f"{error_files} file(s) failed to push.", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    print("Starting metrics push...")
    push_metrics()
    print("Metrics push done.")


if __name__ == "__main__":
    main()
