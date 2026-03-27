#!/usr/bin/env python3
"""Push store performance metrics to the Prometheus Pushgateway using the prometheus_client library.

Usage:
  python3 push_store_ingestion_perf_metrics.py
"""

import json
import os
import sys
from pathlib import Path

from prometheus_client import CollectorRegistry, Gauge, push_to_gateway

METRICS_DIR = Path("/tmp/store-ingestion-perf-metrics")
PUSHGATEWAY_URL="http://prometheus-pushgateway.observability.svc.cluster.local:9091"


def push_metrics() -> None:
    if not METRICS_DIR.is_dir():
        print(f"No metrics directory found at {METRICS_DIR}, nothing to push.")
        return

    json_files = sorted(METRICS_DIR.glob("*.json"))
    if not json_files:
        print(f"No metric files found in {METRICS_DIR}, nothing to push.")
        return

    for metrics_file in json_files:
        print(f"Processing {metrics_file} ...")
        with open(metrics_file) as f:
            data = json.load(f)

        test_name = data["test_name"]
        registry = CollectorRegistry()

        avg_item_time = Gauge(
            "splice_perf_ingestion_avg_item_time_ns",
            "Average nanoseconds per ingested item",
            registry=registry,
        )
        avg_item_time.set(float(data["avg_item_time_ns"]))

        total_items = Gauge(
            "splice_perf_ingestion_total_items",
            "Total number of items ingested",
            registry=registry,
        )
        total_items.set(data["total_items"])

        total_time = Gauge(
            "splice_perf_ingestion_total_time_ns",
            "Total ingestion time in nanoseconds",
            registry=registry,
        )
        total_time.set(float(data["total_time_ns"]))

        total_batches = Gauge(
            "splice_perf_ingestion_total_batches",
            "Total number of batches ingested",
            registry=registry,
        )
        total_batches.set(data["total_batches"])

        grouping_key = {"test_name": test_name}

        try:
            push_to_gateway(
                PUSHGATEWAY_URL,
                job="splice_perf",
                registry=registry,
                grouping_key=grouping_key,
            )
            print(f"  Pushed metrics for '{test_name}' to {PUSHGATEWAY_URL}")
        except Exception as e:
            print(f"  ERROR pushing metrics for '{test_name}': {e}", file=sys.stderr)
            raise


def main() -> None:
    print("Starting metrics push...")
    push_metrics()
    print("Metrics push done.")


if __name__ == "__main__":
    main()
