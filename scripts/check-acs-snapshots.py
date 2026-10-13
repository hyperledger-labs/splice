#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Checks data consistency across all scans of a network.

Checks:
  migration_info  - /v0/backfilling/migration-info
  updates         - /v2/updates (within a record time window)
  import_updates  - /v0/backfilling/import-updates (limited by number of contracts)
  acs             - /v2/state/acs on the latest snapshot available on all scans (limited by number of contracts)

Guarantees at most one in-flight request per scan.

Examples:
  check-acs-snapshots.py mainnet 4
  check-acs-snapshots.py testnet 5 --profile balanced --acs-limit 200000
  check-acs-snapshots.py https://scan.sv-2.dev.global.canton.network.digitalasset.com 3 --checks acs

Exit codes: 0 = consistent, 1 = inconsistencies found, 2 = some scans failed.

Requires: pip install requests
"""

import argparse
import hashlib
import json
import logging
import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Callable, Dict, List, Optional

import requests

log = logging.getLogger("scan-consistency")

NETWORKS = {
    "cilr": "https://scan.sv-2.cilr.global.canton.network.digitalasset.com",
    "devnet": "https://scan.sv-2.dev.global.canton.network.digitalasset.com",
    "testnet": "https://scan.sv-2.test.global.canton.network.digitalasset.com",
    "mainnet": "https://scan.sv-2.global.canton.network.digitalasset.com",
}

PROFILES = {
    "fast":     dict(update_window="1m",  import_limit=1_000,  acs_limit=1_000,  max_tracked_ids=100_000),
    "balanced": dict(update_window="30m", import_limit=20_000, acs_limit=50_000, max_tracked_ids=500_000),
    "full":     dict(update_window="24h", import_limit=0,      acs_limit=0,      max_tracked_ids=1_000_000),
}

ALL_CHECKS = ["migration_info", "updates", "import_updates", "acs"]
UNITS = {"updates": "updates", "import_updates": "import updates", "acs": "contracts"}
MAX_EXAMPLES = 5
MASK256 = (1 << 256) - 1


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def parse_duration(s: str) -> timedelta:
    units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    return timedelta(seconds=float(s[:-1]) * units[s[-1]]) if s[-1] in units else timedelta(seconds=float(s))


def parse_ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def fmt_ts(d: datetime) -> str:
    return d.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def sha256_int(data: bytes) -> int:
    return int.from_bytes(hashlib.sha256(data).digest(), "big")


def short(h: int) -> str:
    return format(h, "064x")[:16]


def examples(items) -> str:
    items = sorted(items)
    s = ", ".join(items[:MAX_EXAMPLES])
    return s + (f", ... ({len(items) - MAX_EXAMPLES} more)" if len(items) > MAX_EXAMPLES else "")


class ScanError(Exception):
    pass


class ScanClient:
    """HTTP client for a single scan. Only ever used from one thread at a time."""

    def __init__(self, url: str, args):
        self.url = url.rstrip("/")
        self.args = args
        self.session = requests.Session()

    def request(self, method: str, path: str, **kwargs) -> Optional[dict]:
        """Returns parsed JSON, None on 404, raises ScanError on permanent failure."""
        full = f"{self.url}/api/scan{path}"
        for attempt in range(self.args.max_retries + 1):
            try:
                r = self.session.request(method, full, timeout=self.args.request_timeout, **kwargs)
                if r.status_code == 200:
                    return r.json()
                if r.status_code == 404:
                    return None
                if r.status_code != 429 and r.status_code < 500:
                    raise ScanError(f"{method} {full} -> HTTP {r.status_code}: {r.text[:300]}")
                reason = f"HTTP {r.status_code}"
            except (requests.ConnectionError, requests.Timeout,
                    requests.exceptions.ChunkedEncodingError, ValueError) as e:
                reason = type(e).__name__
            if attempt == self.args.max_retries:
                raise ScanError(f"{method} {full} failed after {attempt + 1} attempts ({reason})")
            delay = min(60.0, 2 ** attempt) * (0.5 + random.random() / 2)
            log.warning("%s: %s %s failed (%s), retry %d/%d in %.1fs",
                        self.url, method, path, reason, attempt + 1, self.args.max_retries, delay)
            time.sleep(delay)


@dataclass
class Digest:
    """Bounded-memory summary of an ordered collection of (id, item) pairs."""
    max_tracked: int
    count: int = 0
    id_agg: int = 0        # order-independent hash over ids (set equality)
    content_agg: int = 0   # order-independent hash over canonical item contents (semantic equality)
    seq_ids: "hashlib._Hash" = field(default_factory=hashlib.sha256)  # ordered ids
    seq_raw: "hashlib._Hash" = field(default_factory=hashlib.sha256)  # ordered items, as received
    ids: set = field(default_factory=set)  # first `max_tracked` ids, for set diffs
    truncated: bool = False
    error: Optional[str] = None

    def add(self, item_id: str, item) -> None:
        self.count += 1
        id_bytes = item_id.encode()
        self.id_agg = (self.id_agg + sha256_int(id_bytes)) & MASK256
        canonical = json.dumps(item, sort_keys=True, separators=(",", ":")).encode()
        self.content_agg = (self.content_agg + sha256_int(canonical)) & MASK256
        # Length-prefix each entry so concatenation is unambiguous.
        self.seq_ids.update(len(id_bytes).to_bytes(4, "big") + id_bytes)
        raw = json.dumps(item, separators=(",", ":"), ensure_ascii=False).encode()  # preserves key order
        self.seq_raw.update(len(raw).to_bytes(8, "big") + raw)
        if len(self.ids) < self.max_tracked:
            self.ids.add(item_id)
        else:
            self.truncated = True

    def id_key(self):
        return (self.count, self.id_agg)

    def summary(self) -> str:
        if self.error:
            return f"error: {self.error}"
        return (f"count={self.count} ids={short(self.id_agg)} content={short(self.content_agg)} "
                f"seq={self.seq_raw.hexdigest()[:16]}")


class Progress:
    def __init__(self, url: str, what: str, interval: float):
        self.url, self.what, self.interval = url, what, interval
        self.last = time.monotonic()
        self.start = self.last

    def tick(self, msg: str, force=False):
        now = time.monotonic()
        if force or now - self.last >= self.interval:
            self.last = now
            log.info("%s: [%s] %s (%.0fs)", self.url, self.what, msg, now - self.start)


def run_per_scan(clients: List[ScanClient], fn: Callable[[ScanClient], object]) -> Dict[str, object]:
    """Run fn once per scan, one thread per scan => at most one request per scan in flight."""
    with ThreadPoolExecutor(max_workers=max(1, len(clients))) as ex:
        futures = {c.url: ex.submit(fn, c) for c in clients}
    out = {}
    for url, f in futures.items():
        try:
            out[url] = f.result()
        except Exception as e:  # noqa: BLE001
            log.error("%s: unexpected error: %s", url, e)
            out[url] = e
    return out


# ---------------------------------------------------------------------------
# Fetchers
# ---------------------------------------------------------------------------

def fetch_migration_info(c: ScanClient, mid: int):
    return c.request("POST", "/v0/backfilling/migration-info", json={"migration_id": mid})


def fetch_latest_snapshot(c: ScanClient, mid: int, before: str) -> Optional[str]:
    r = c.request("GET", "/v0/state/acs/snapshot-timestamp", params={"before": before, "migration_id": mid})
    return r.get("record_time") if r else None


def fetch_updates(c: ScanClient, args, start: str, end: datetime) -> Digest:
    """All updates with start < record_time <= end in the given migration. Record times are unique."""
    d = Digest(args.max_tracked_ids)
    prog = Progress(c.url, "updates", args.progress_interval)
    after = {"after_migration_id": args.migration_id, "after_record_time": start}
    try:
        while True:
            body = c.request("POST", "/v2/updates", json={"page_size": args.page_size, "after": after}) or {}
            txs = body.get("transactions", [])
            if not txs:
                break
            done = False
            for tx in txs:
                tx_mid = tx.get("migration_id", tx.get("event", {}).get("migration_id"))
                if tx_mid != args.migration_id or parse_ts(tx["record_time"]) > end:
                    done = True
                    break
                d.add(tx["update_id"], tx)
            if done:
                break
            last = txs[-1]
            after = {"after_migration_id": args.migration_id, "after_record_time": last["record_time"]}
            prog.tick(f"{d.count} updates, at {last['record_time']}")
    except ScanError as e:
        d.error = str(e)
    prog.tick(f"finished: {d.summary()}", force=True)
    return d


def fetch_import_updates(c: ScanClient, args) -> Digest:
    d = Digest(args.max_tracked_ids)
    prog = Progress(c.url, "import_updates", args.progress_interval)
    after, contracts = "", 0
    try:
        while True:
            body = c.request("POST", "/v0/backfilling/import-updates",
                             json={"migration_id": args.migration_id, "after_update_id": after,
                                   "limit": args.page_size}) or {}
            txs = body.get("transactions", [])
            if not txs:
                break
            stop = False
            for tx in txs:
                d.add(tx["update_id"], tx)
                contracts += sum(1 for e in tx.get("events_by_id", {}).values()
                                 if e.get("event_type") == "created_event")
                if args.import_limit and contracts >= args.import_limit:
                    stop = True
                    break
            if stop:
                break
            after = txs[-1]["update_id"]
            prog.tick(f"{d.count} updates, {contracts} contracts")
    except ScanError as e:
        d.error = str(e)
    prog.tick(f"finished: {d.summary()} contracts={contracts}", force=True)
    return d


def fetch_acs(c: ScanClient, args, record_time: str) -> Digest:
    d = Digest(args.max_tracked_ids)
    prog = Progress(c.url, "acs", args.progress_interval)
    token = None
    try:
        while True:
            payload = {"migration_id": args.migration_id, "record_time": record_time, "page_size": args.page_size}
            if token is not None:
                payload["after"] = token
            body = c.request("POST", "/v2/state/acs", json=payload) or {}
            events = body.get("created_events", [])
            for ev in events:
                d.add(ev["contract_id"], ev)
                if args.acs_limit and d.count >= args.acs_limit:
                    break
            token = body.get("next_page_token")
            if token is None or not events or (args.acs_limit and d.count >= args.acs_limit):
                break
            prog.tick(f"{d.count} contracts")
    except ScanError as e:
        d.error = str(e)
    prog.tick(f"finished: {d.summary()}", force=True)
    return d


# ---------------------------------------------------------------------------
# Comparison / reporting
# ---------------------------------------------------------------------------

def compare_digests(name: str, unit: str, results: Dict[str, Digest], max_tracked: int) -> bool:
    log.info("=" * 100)
    log.info("Result [%s]", name)

    failed = [u for u, d in results.items() if d.error]
    for url in failed:
        log.error("  ✖ %s: failed: %s", url, results[url].error)

    # Group scans by their set of ids.
    id_groups: Dict[object, List[str]] = {}
    for url, d in results.items():
        if not d.error:
            id_groups.setdefault(d.id_key(), []).append(url)
    if not id_groups:
        log.error("  no scan returned data")
        return False

    ref_key = max(id_groups, key=lambda k: len(id_groups[k]))
    ref_url = id_groups[ref_key][0]
    ref = results[ref_url]
    log.info("  reference: %s (%d of %d scans have the same set of %d %s)",
             ref_url, len(id_groups[ref_key]), len(results), ref.count, unit)

    consistent = not failed and len(id_groups) == 1

    for key, urls in id_groups.items():
        for url in urls:
            d = results[url]
            if key == ref_key:
                if d.seq_raw.digest() == ref.seq_raw.digest():
                    log.info("  ✔ %s: identical %s (same set, content, order)", url, unit)
                elif d.content_agg != ref.content_agg:
                    consistent = False
                    log.error("  ✖ %s: same set of %d %s, but their content differs", url, d.count, unit)
                elif d.seq_ids.digest() != ref.seq_ids.digest():
                    consistent = False
                    log.error("  ✖ %s: same set of %d %s with the same content, but in a different order",
                              url, d.count, unit)
                else:
                    consistent = False
                    log.error("  ✖ %s: same %d %s in the same order with the same content, "
                              "but serialized differently (e.g. JSON field order)", url, d.count, unit)
                continue

            log.error("  ✖ %s: different set of %s (%d here vs %d in reference)", url, unit, d.count, ref.count)
            missing = ref.ids - d.ids
            extra = d.ids - ref.ids
            if d.truncated or ref.truncated:
                log.error("    note: only the first %d %s per scan are tracked; "
                          "differences near that boundary may be spurious", max_tracked, unit)
            if missing:
                log.error("    %d %s missing: %s", len(missing), unit, examples(missing))
            if extra:
                log.error("    %d extra %s: %s", len(extra), unit, examples(extra))
            if not missing and not extra:
                log.error("    the first %d %s agree; the difference is beyond the tracked range",
                          max_tracked, unit)
    return consistent


def normalize_migration_info(info, live_tolerance: timedelta):
    """Replace record time range ends that are close to wall clock time: on the current
    migration they just track the current time and naturally differ between scans."""
    if not isinstance(info, dict):
        return info
    now = datetime.now(timezone.utc)
    info = dict(info)
    ranges = []
    for r in info.get("record_time_range", []):
        r = dict(r)
        if now - parse_ts(r["max"]) < live_tolerance:
            r["max"] = "<live>"
        ranges.append(r)
    info["record_time_range"] = sorted(ranges, key=lambda r: r.get("synchronizer_id", ""))
    return info


def compare_migration_info(infos: Dict[str, object], live_tolerance: timedelta) -> bool:
    log.info("=" * 100)
    log.info("Result [migration_info] (range ends less than %s behind now are ignored)", live_tolerance)
    groups: Dict[str, List[str]] = {}
    for url, info in infos.items():
        if isinstance(info, Exception):
            key = f"error: {info}"
        else:
            key = json.dumps(normalize_migration_info(info, live_tolerance), sort_keys=True)
        groups.setdefault(key, []).append(url)
    ref = max(groups, key=lambda k: len(groups[k]))
    log.info("  reference (%d of %d scans): %s", len(groups[ref]), len(infos), ref)
    for key, urls in groups.items():
        for url in urls:
            if key == ref:
                log.info("  ✔ %s", url)
            else:
                log.error("  ✖ %s: %s", url, key)
    return len(groups) == 1


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("network", help=f"One of {list(NETWORKS)} or a scan URL used to discover all scans")
    p.add_argument("migration_id", type=int)
    p.add_argument("--profile", choices=list(PROFILES), default="fast")
    p.add_argument("--checks", default=",".join(ALL_CHECKS), help=f"Comma-separated subset of {ALL_CHECKS}")
    p.add_argument("--scans", help="Comma-separated scan URLs (skips DSO discovery)")
    p.add_argument("--exclude-scans", default="", help="Comma-separated scan URLs to skip")
    p.add_argument("--migration-info-live-tolerance", default="1h",
                   help="Ignore migration-info record time range ends less than this behind wall clock time")
    # Profile overrides
    p.add_argument("--update-window", help="Window length, e.g. 30s, 10m, 2h, 1d")
    p.add_argument("--updates-from", help="Window start (ISO 8601). Default: end - window")
    p.add_argument("--updates-to", help="Window end (ISO 8601). Default: min over scans of the latest record time")
    p.add_argument("--import-limit", type=int, help="Max import contracts to compare (0 = all)")
    p.add_argument("--acs-limit", type=int, help="Max ACS contracts to compare (0 = all)")
    p.add_argument("--acs-before", default=None, help="Use latest common snapshot before this time (default: now)")
    p.add_argument("--max-tracked-ids", type=int, help="Max ids per scan kept in memory for set diffs")
    # Transport
    p.add_argument("--page-size", type=int, default=100)
    p.add_argument("--max-retries", type=int, default=8)
    p.add_argument("--request-timeout", type=float, default=60)
    p.add_argument("--progress-interval", type=float, default=30)
    a = p.parse_args()
    for k, v in PROFILES[a.profile].items():
        if getattr(a, k) is None:
            setattr(a, k, v)
    a.checks = [c.strip() for c in a.checks.split(",") if c.strip()]
    unknown = set(a.checks) - set(ALL_CHECKS)
    if unknown:
        p.error(f"unknown checks: {unknown}")
    return a


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", stream=sys.stdout)
    args = parse_args()
    mid = args.migration_id
    log.info("Parameters: %s", vars(args))

    # --- discover scans ---
    if args.scans:
        urls = [u.strip().rstrip("/") for u in args.scans.split(",") if u.strip()]
    else:
        seed = ScanClient(NETWORKS.get(args.network, args.network), args)
        dso = seed.request("GET", "/v0/dso")
        urls = sorted({n[1]["scan"]["publicUrl"].rstrip("/")
                       for s in dso["sv_node_states"]
                       for n in s["contract"]["payload"]["state"]["synchronizerNodes"]
                       if n[1].get("scan")})
    excluded = {u.strip().rstrip("/") for u in args.exclude_scans.split(",") if u.strip()}
    urls = [u for u in urls if u not in excluded]
    log.info("Scans (%d): %s", len(urls), ", ".join(urls))
    clients = [ScanClient(u, args) for u in urls]

    ok = True
    had_errors = False

    # --- phase 1: migration info + latest snapshot, per scan ---
    acs_before = args.acs_before or fmt_ts(datetime.now(timezone.utc))

    def phase1(c: ScanClient):
        info = fetch_migration_info(c, mid)
        snap = fetch_latest_snapshot(c, mid, acs_before) if "acs" in args.checks else None
        log.info("%s: migration_info=%s latest_snapshot=%s", c.url, json.dumps(info), snap)
        return info, snap

    p1 = run_per_scan(clients, phase1)
    infos = {u: (r if isinstance(r, Exception) else r[0]) for u, r in p1.items()}
    if "migration_info" in args.checks:
        ok &= compare_migration_info(infos, parse_duration(args.migration_info_live_tolerance))

    # --- choose update window ---
    window = None
    if "updates" in args.checks:
        valid = [i for i in infos.values() if isinstance(i, dict) and i.get("record_time_range")]
        if not valid and not (args.updates_from and args.updates_to):
            log.error("No migration info with record time ranges; skipping updates check")
            had_errors = True
        else:
            max_common = min(max(parse_ts(r["max"]) for r in i["record_time_range"]) for i in valid) if valid else None
            min_common = max(min(parse_ts(r["min"]) for r in i["record_time_range"]) for i in valid) if valid else None
            end = parse_ts(args.updates_to) if args.updates_to else max_common
            start = parse_ts(args.updates_from) if args.updates_from else end - parse_duration(args.update_window)
            if min_common and start < min_common:
                start = min_common - timedelta(microseconds=1)  # 'after' is exclusive
            window = (fmt_ts(start), end)
            secs = (end - start).total_seconds()
            log.info("Update window: (%s, %s] (%.0fs, expect ~%d-%d updates)",
                     window[0], fmt_ts(end), secs, secs, 100 * secs)

    # --- choose common snapshot ---
    snapshot = None
    if "acs" in args.checks:
        snaps = {u: r[1] for u, r in p1.items() if not isinstance(r, Exception)}
        missing = [u for u, s in snaps.items() if s is None]
        if missing:
            log.warning("Scans without snapshot for migration %d: %s", mid, examples(missing))
        candidates = [s for s in snaps.values() if s]
        if candidates:
            snapshot = min(candidates, key=parse_ts)
            for _ in range(5):  # converge on a snapshot that exists on all scans
                probe = fmt_ts(parse_ts(snapshot) + timedelta(microseconds=1))
                got = run_per_scan(clients, lambda c: fetch_latest_snapshot(c, mid, probe))
                found = [g for g in got.values() if isinstance(g, str)]
                if found and all(g == snapshot for g in found):
                    break
                snapshot = min(found, key=parse_ts) if found else None
                if snapshot is None:
                    break
        if snapshot:
            log.info("Using common ACS snapshot %s", snapshot)
        else:
            log.error("No common ACS snapshot found; skipping acs check")
            had_errors = True

    # --- phase 2: heavy checks, sequential per scan ---
    def phase2(c: ScanClient):
        res = {}
        if window:
            res["updates"] = fetch_updates(c, args, window[0], window[1])
        if "import_updates" in args.checks:
            res["import_updates"] = fetch_import_updates(c, args)
        if snapshot:
            res["acs"] = fetch_acs(c, args, snapshot)
        return res

    p2 = run_per_scan(clients, phase2)
    for check in ["updates", "import_updates", "acs"]:
        per_scan = {}
        for u, r in p2.items():
            if isinstance(r, Exception):
                per_scan[u] = Digest(0, error=str(r))
            elif check in r:
                per_scan[u] = r[check]
        if per_scan:
            ok &= compare_digests(check, UNITS[check], per_scan, args.max_tracked_ids)
            had_errors |= any(d.error for d in per_scan.values())

    log.info("=" * 100)
    if ok:
        log.info("RESULT: all checks consistent")
        return 0
    log.error("RESULT: inconsistencies found%s", " (some scans failed)" if had_errors else "")
    return 2 if had_errors else 1


if __name__ == "__main__":
    sys.exit(main())
