#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Scan API stats collector.

For each network, `PROMETHEUS_BEARER_TOKEN` must be set to a valid bearer token
for the Prometheus API. You can create one from Grafana under Admin -> Users and
access -> Service accounts.
e.g:
 python3 scripts/performance/scan_api_stats.py --network devnet --days 30 --namespace sv-1 -o /tmp/0_devnet_sv_1.csv
"""

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    import yaml
except ImportError as exc:
    raise SystemExit(
        "PyYAML is required to run this script."
    ) from exc

DEFAULT_PROM_URLS = {
    "mainnet": "https://grafana.global.canton.network.digitalasset.com/api/datasources/proxy/uid/prometheus",
    "devnet": "https://grafana.dev.global.canton.network.digitalasset.com/api/datasources/proxy/uid/prometheus",
}
DEFAULT_SPEC = "apps/scan/src/main/openapi/scan.yaml"
DURATION_METRIC = "daml_http_requests_duration_seconds"

# prevent long-running queries from being rejected by prometheus
MAX_POINTS = 11_000
DEFAULT_HTTP_SERVICE = "scan"
HTTP_METHODS = {"get", "put", "post", "delete"}


def load_api_metadata(spec_path):
    """load api metadata from an OpenAPI spec file.

    returns a mapping of api operationId -> {method, path, tags}.
    """
    meta = {}

    if not os.path.exists(spec_path):
        print(f"warning: spec not found: {spec_path}")
        return meta

    with open(spec_path) as fh:
        doc = yaml.safe_load(fh) or {}

    for path, ops in (doc.get("paths") or {}).items():
        if not isinstance(ops, dict):
            continue

        for method, op in ops.items():
            if method.lower() not in HTTP_METHODS or not isinstance(op, dict):
                continue

            op_id = op.get("operationId")
            if op_id:
                meta[op_id] = {
                    "method": method.upper(),
                    "path": path,
                    "tags": "|".join(op.get("tags") or []),
                }

    return meta


def _auth():
    tok = os.environ.get("PROMETHEUS_BEARER_TOKEN")
    if not tok:
        print("warning: PROMETHEUS_BEARER_TOKEN not set")
    return {"Authorization": f"Bearer {tok}"} if tok else {}


def _get(url, timeout):
    req = urllib.request.Request(url, headers=_auth())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"{e.code} {e.reason}: {body}") from e


def prom_query(url, expr, at=None, timeout_seconds=60):
    """Run a Prometheus instant query and return the result vector."""
    params = {"query": expr}
    if at is not None:
        params["time"] = at

    query_url = url.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode(params)
    body = _get(query_url, timeout_seconds)
    if body.get("status") != "success":
        raise RuntimeError(f"Prometheus error: {body}")
    return body["data"]["result"]


def rate_win_seconds(w):
    if w.endswith("s"):
        try:
            return int(w[:-1])
        except ValueError:
            pass
    return 60


def _scalar(vec):
    """return the first scalar value from a Prometheus vector, if any."""
    for s in vec:
        v = s.get("value")
        if v and len(v) == 2:
            try:
                return float(v[1])
            except (TypeError, ValueError):
                return None
    return None


def _peak_over_range(url, expr, start, end, rate_win):
    """query a long range in chunks and return the peak scalar value."""

    step = max(rate_win_seconds(rate_win), 1)
    chunk = max(step * max(MAX_POINTS - 100, 1), step)

    best = 0.0
    chunk_start = start
    while chunk_start < end:
        chunk_end = min(chunk_start + chunk, end)
        window = max(chunk_end - chunk_start, 1)
        query = f"max_over_time({expr}[{window}s:{rate_win}])"
        value = _scalar(prom_query(url, query, at=chunk_end, timeout_seconds=120)) or 0.0
        if value > best:
            best = value
        chunk_start = chunk_end

    return best


def _label_selector(http_service, operation, namespace=None):
    parts = [f'operation="{operation}"']
    if http_service:
        parts.append(f'http_service="{http_service}"')
    if namespace:
        parts.append(f'namespace=~"{namespace}"')
    return "{" + ",".join(parts) + "}"


def discover_operation_targets(url, op, namespace=None):
    """find all (namespace, http_service) pairs that have data for an operation."""
    targets = set()

    selector = _label_selector(None, op, namespace)
    for series in prom_query(url, f"{DURATION_METRIC}{selector}"):
        metric = series.get("metric", {})
        targets.add((metric.get("namespace", ""), metric.get("http_service", "")))

    return sorted(targets)


def require_operation_metadata(op_meta, op):
    m = op_meta.get(op)
    if not m:
        raise KeyError(f"missing OpenAPI metadata for operationId: {op}")
    return m


def _make_row(network, namespace, http_service, operation, metadata, max_rps):
    """create one CSV row in the script's fixed output format."""
    return {
        "network": network,
        "namespace": namespace or "",
        "http_service": http_service or "",
        "operation": operation,
        "method": metadata.get("method", ""),
        "endpoint": metadata.get("path", ""),
        "tags": metadata.get("tags", ""),
        "max_rps": max_rps,
    }


def _iter_targets(targets, namespace):
    """keep the old behavior: if nothing is discovered, emit one default target."""
    if targets:
        return targets
    return [(namespace or "", DEFAULT_HTTP_SERVICE)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=90)
    parser.add_argument("--network", choices=sorted(DEFAULT_PROM_URLS), default="mainnet")
    parser.add_argument("--namespace", default=None)
    parser.add_argument("-o", "--output")
    args = parser.parse_args()

    op_meta = load_api_metadata(DEFAULT_SPEC)
    if not op_meta:
        print("warning: no operation metadata loaded")

    prom = DEFAULT_PROM_URLS[args.network]
    now = int(time.time())
    start = now - args.days * 86400
    ops = sorted(op_meta)

    if not ops:
        print("No operations found.")
        return 1

    print(f"querying {len(ops)} operation(s) over {args.days}d via {prom}")

    rows = []
    total_ops = len(ops)
    for i, op in enumerate(ops, start=1):
        print(f"[{i}/{total_ops}] {op}")
        m = require_operation_metadata(op_meta, op)
        try:
            discovered = discover_operation_targets(prom, op, args.namespace)
        except Exception as e:
            print(f"  ! {op}: {e}")
            discovered = []

        targets = _iter_targets(discovered, args.namespace)
        for ns, svc in targets:
            sel = _label_selector(svc, op, ns)
            try:
                rps = _peak_over_range(
                    prom,
                    f"sum(histogram_count(rate({DURATION_METRIC}{sel}[60s])))",
                    start,
                    now,
                    "60s",
                )
            except Exception as e:
                print(f"  ! {op} ({ns or '-'}, {svc or '-'}): {e}")
                continue

            rows.append(_make_row(args.network, ns, svc, op, m, round(rps, 4)))

    rows.sort(key=lambda r: r["max_rps"], reverse=True)

    fields = [
        "network",
        "namespace",
        "http_service",
        "operation",
        "method",
        "endpoint",
        "tags",
        "max_rps",
    ]

    out = open(args.output, "w", newline="") if args.output else sys.stdout
    try:
        w = csv.DictWriter(out, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    finally:
        if args.output:
            out.close()

    print(f"wrote {len(rows)} rows")
    return 0


if __name__ == "__main__":
    main()

