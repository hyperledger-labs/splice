#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Benchmark istio ingress routing performance across two dimensions:
  1. Number of extra IPs in the whitelist (ip_count)
  2. chunkSize used in istioAccessPolicies (chunk_size)

Sampling mode (default):
  ./benchmark_istio_routing.py --ip-count 10000 --chunk-size 100

  Makes 20 fresh HTTPS requests to the scan readyz endpoint, records
  timing, and appends results to a persistent JSON file.  Errors out if
  a sample already exists for the given (ip_count, chunk_size) pair.

Report mode:
  ./benchmark_istio_routing.py --report

  Reads the stored results and emits a CSV to stdout with columns:
    ip_count, chunk_size, avg_time_s, std_dev_s, pct_diff_vs_baseline
  The baseline is the sample at (ip_count=0, chunk_size=100).
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import statistics
import sys
import time
import urllib.request
import ssl
import http.client
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_RESULTS_FILE = Path(__file__).resolve().parent / "benchmark_results.json"
SAMPLE_SIZE = 20


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_fresh_request(url: str) -> float:
    """Open a brand-new HTTPS connection, GET *url*, and return elapsed seconds."""
    parsed = urlparse(url)
    ctx = ssl.create_default_context()
    conn = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, context=ctx)
    try:
        start = time.monotonic()
        conn.request("GET", parsed.path or "/")
        resp = conn.getresponse()
        _ = resp.read()
        elapsed = time.monotonic() - start
        if resp.status != 200:
            raise RuntimeError(
                f"Unexpected HTTP {resp.status} from {url}"
            )
        return elapsed
    finally:
        conn.close()


def take_sample(url: str, count: int = SAMPLE_SIZE) -> list[float]:
    """Return *count* request-time measurements (seconds), each on a fresh connection."""
    times: list[float] = []
    for i in range(count):
        t = _make_fresh_request(url)
        times.append(t)
        print(f"  request {i + 1}/{count}: {t:.4f}s", file=sys.stderr)
    return times


def _key(ip_count: int, chunk_size: int) -> str:
    return f"{ip_count}:{chunk_size}"


def load_results(path: Path) -> dict:
    if path.exists():
        with open(path) as f:
            return json.load(f)
    return {"samples": {}}


def save_results(path: Path, data: dict) -> None:
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
    print(f"Results saved to {path}", file=sys.stderr)


def generate_report(data: dict) -> str:
    """Return a CSV string summarising all stored samples."""
    samples = data.get("samples", {})
    if not samples:
        return "No samples recorded yet.\n"

    baseline_key = _key(0, 100)
    baseline_avg: float | None = None
    baseline_max: float | None = None
    if baseline_key in samples:
        baseline_avg = statistics.mean(samples[baseline_key]["times"])
        baseline_max = max(samples[baseline_key]["times"])

    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["ip_count", "chunk_size", "avg_time_s", "std_dev_s", "pct_diff_vs_baseline", "max_time_s", "pct_diff_max_vs_baseline"])

    for key in sorted(samples, key=lambda k: tuple(int(x) for x in k.split(":"))):
        entry = samples[key]
        ip_count = entry["ip_count"]
        chunk_size = entry["chunk_size"]
        times = entry["times"]
        avg = statistics.mean(times)
        sd = statistics.stdev(times) if len(times) > 1 else 0.0
        max_time = max(times)
        if baseline_avg is not None and baseline_avg > 0:
            pct_diff = ((avg - baseline_avg) / baseline_avg) * 100.0
        else:
            pct_diff = float("nan")
        if baseline_max is not None and baseline_max > 0:
            pct_diff_max = ((max_time - baseline_max) / baseline_max) * 100.0
        else:
            pct_diff_max = float("nan")
        writer.writerow([
            ip_count,
            chunk_size,
            f"{avg:.6f}",
            f"{sd:.6f}",
            f"{pct_diff:.2f}",
            f"{max_time:.6f}",
            f"{pct_diff_max:.2f}",
        ])

    return buf.getvalue()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark istio ingress routing performance."
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="Instead of sampling, print a CSV report of all stored results.",
    )
    parser.add_argument(
        "--ip-count",
        type=int,
        help="Number of extra IPs currently configured in generateExtraIps.",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        help="chunkSize currently configured in istioAccessPolicies.",
    )
    parser.add_argument(
        "--endpoint",
        type=str,
        default=None,
        help=(
            "Full URL to probe.  Defaults to "
            "https://scan.sv-1.$GCP_CLUSTER_HOSTNAME/api/scan/readyz"
        ),
    )
    parser.add_argument(
        "--results-file",
        type=Path,
        default=DEFAULT_RESULTS_FILE,
        help="Path to the JSON file that stores benchmark results.",
    )

    args = parser.parse_args()

    # --- report mode ---
    if args.report:
        data = load_results(args.results_file)
        sys.stdout.write(generate_report(data))
        return

    # --- sampling mode ---
    if args.ip_count is None or args.chunk_size is None:
        parser.error("--ip-count and --chunk-size are required when not using --report")

    endpoint = args.endpoint
    if endpoint is None:
        hostname = os.environ.get("GCP_CLUSTER_HOSTNAME")
        if not hostname:
            parser.error(
                "GCP_CLUSTER_HOSTNAME is not set and --endpoint was not provided"
            )
        endpoint = f"https://scan.sv-1.{hostname}/api/scan/readyz"

    data = load_results(args.results_file)
    key = _key(args.ip_count, args.chunk_size)

    if key in data.get("samples", {}):
        print(
            f"ERROR: A sample already exists for ip_count={args.ip_count}, "
            f"chunk_size={args.chunk_size}.  Delete it from {args.results_file} "
            f"to re-run.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(
        f"Sampling {SAMPLE_SIZE} requests to {endpoint} "
        f"(ip_count={args.ip_count}, chunk_size={args.chunk_size})…",
        file=sys.stderr,
    )
    times = take_sample(endpoint, SAMPLE_SIZE)

    if "samples" not in data:
        data["samples"] = {}
    data["samples"][key] = {
        "ip_count": args.ip_count,
        "chunk_size": args.chunk_size,
        "times": times,
    }
    save_results(args.results_file, data)

    avg = statistics.mean(times)
    sd = statistics.stdev(times)
    print(
        f"Done.  avg={avg:.4f}s  std_dev={sd:.4f}s",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()

