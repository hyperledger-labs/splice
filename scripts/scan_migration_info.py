#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Query scan API endpoints for migration info and ACS snapshot timestamps,
then output a CSV summary table.

Requires: pip install requests rich
"""

import csv
import hashlib
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from rich.console import Console
from rich.progress import Progress, TaskProgressColumn, TextColumn, TimeElapsedColumn
from rich.table import Table

console = Console(stderr=True)

# ---------------------------------------------------------------------------
# Configuration – edit the list of base URLs below
# ---------------------------------------------------------------------------
BASE_URLS = [
    "https://scan.sv-1.global.canton.network.digitalasset.com",
    "https://scan.sv-2.global.canton.network.digitalasset.com",
    "https://scan.sv-1.global.canton.network.mpch.io",
    "https://scan.sv-1.global.canton.network.lcv.mpch.io",
    "https://scan.sv.global.canton.network.sv-nodeops.com",
    "https://scan.sv-1.global.canton.network.c7.digital",
    "https://scan.sv-1.global.canton.network.proofgroup.xyz",
    "https://scan.sv-2.global.canton.network.cumberland.io",
    "https://scan.sv-1.global.canton.network.tradeweb.com",
    "https://scan.sv-1.global.canton.network.sync.global",
    "https://scan.sv-1.global.canton.network.orb1lp.mpch.io",
    "https://scan.sv-1.global.canton.network.fivenorth.io",
    "https://scan.sv-1.global.canton.network.cumberland.io"
]

MIGRATIONS = range(0, 5)
MAX_RETRIES = 8
ACS_PAGE_SIZE = 1000
TIMEOUT = 30  # seconds per HTTP request

# Far-future / far-past sentinels used for snapshot-timestamp queries
FAR_FUTURE = "9999-12-31T23:59:59Z"
EPOCH = "1970-01-01T00:00:00Z"


# ---------------------------------------------------------------------------
# Helpers with retries
# ---------------------------------------------------------------------------


def _request_with_retries(method, url, progress=None, task_id=None, **kwargs):
    """Execute an HTTP request with exponential-backoff retries on ConnectionError."""
    kwargs.setdefault("timeout", TIMEOUT)
    for attempt in range(MAX_RETRIES):
        try:
            if method == "GET":
                return requests.get(url, **kwargs)
            else:
                return requests.post(url, **kwargs)
        except requests.exceptions.ConnectionError:
            if attempt < MAX_RETRIES - 1:
                if progress and task_id is not None:
                    progress.update(task_id, status="[orange3]Backoff")
                time.sleep(1.5 ** attempt)
            else:
                raise


def fetch_acs_snapshot_summary(base_url, migration_id, record_time, progress, task_id):
    """Page through the full ACS snapshot, returning (event_count, sha256_hex).

    Uses bounded memory: each page's ``created_events`` JSON is fed into a
    streaming SHA-256 hasher and then discarded.
    """
    hasher = hashlib.sha256()
    event_count = 0
    next_page_token = None

    page = 0
    while True:
        page += 1
        progress.update(task_id, status=f"[cyan]acs p={page} ev={event_count}")

        payload = {
            "migration_id": migration_id,
            "record_time": record_time,
            "page_size": ACS_PAGE_SIZE,
        }
        if next_page_token is not None:
            payload["after"] = next_page_token

        try:
            resp = _request_with_retries(
                "POST",
                f"{base_url}/api/scan/v2/state/acs",
                progress=progress, task_id=task_id,
                json=payload,
            )
        except requests.RequestException:
            return event_count, None

        if resp.status_code != 200:
            return event_count, None

        body = resp.json()
        events = body.get("created_events", [])
        event_count += len(events)

        # Feed the canonical JSON of each event into the hasher so that
        # ordering is preserved and memory stays bounded.
        for ev in events:
            hasher.update(json.dumps(ev, sort_keys=True, separators=(",", ":")).encode())

        next_page_token = body.get("next_page_token")
        if next_page_token is None or len(events) == 0:
            break

    progress.update(task_id, status=f"[cyan]acs done p={page} ev={event_count}")
    return event_count, hasher.hexdigest()


def fetch_row(base_url, migration_id, progress, task_id):
    """Fetch migration-info, first-snapshot, last-snapshot, and ACS summary for one (base_url, migration_id) pair."""
    progress.update(task_id, status=f"[cyan]m={migration_id} migration-info")

    # --- migration-info ---
    info = None
    try:
        resp = _request_with_retries(
            "POST",
            f"{base_url}/api/scan/v0/backfilling/migration-info",
            progress=progress, task_id=task_id,
            json={"migration_id": migration_id},
        )
        if resp.status_code == 200:
            info = resp.json()
    except requests.RequestException:
        pass

    # --- first snapshot ---
    progress.update(task_id, status=f"[cyan]m={migration_id} first-snapshot")
    first_snapshot = None
    try:
        resp = _request_with_retries(
            "GET",
            f"{base_url}/api/scan/v0/state/acs/snapshot-timestamp-after",
            progress=progress, task_id=task_id,
            params={"after": EPOCH, "migration_id": migration_id},
        )
        if resp.status_code == 200:
            first_snapshot = resp.json().get("record_time")
    except requests.RequestException:
        pass

    # --- last snapshot ---
    progress.update(task_id, status=f"[cyan]m={migration_id} last-snapshot")
    last_snapshot = None
    try:
        resp = _request_with_retries(
            "GET",
            f"{base_url}/api/scan/v0/state/acs/snapshot-timestamp",
            progress=progress, task_id=task_id,
            params={"before": FAR_FUTURE, "migration_id": migration_id},
        )
        if resp.status_code == 200:
            last_snapshot = resp.json().get("record_time")
    except requests.RequestException:
        pass

    # --- first ACS snapshot size & hash ---
    acs_size = None
    acs_hash = None
    if first_snapshot is not None:
        progress.update(task_id, status=f"[cyan]m={migration_id} acs-snapshot")
        acs_size, acs_hash = fetch_acs_snapshot_summary(
            base_url, migration_id, first_snapshot, progress, task_id,
        )

    return migration_id, info, first_snapshot, last_snapshot, acs_size, acs_hash


def fetch_all_migrations_for_url(base_url, progress, task_id):
    """Process all migrations sequentially for a single base URL.

    This ensures at most one request is in-flight per server at any time.
    """
    url_results = {}
    for migration_id in MIGRATIONS:
        result = fetch_row(base_url, migration_id, progress, task_id)
        url_results[migration_id] = result[1:]  # (info, first_snap, last_snap, acs_size, acs_hash)
        progress.advance(task_id)
    progress.update(task_id, status="[green]Done")
    return base_url, url_results


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    fieldnames = [
        "Base URL",
        "Migration",
        "Min Record Time",
        "Max Record Time",
        "Last Import Update ID",
        "Complete",
        "Import Updates Complete",
        "First Snapshot Timestamp",
        "First Snapshot Size",
        "First Snapshot Hash",
        "Last Snapshot Timestamp",
    ]

    # Collect all (base_url, migration_id) pairs for ordered output
    jobs = [(url, mid) for url in BASE_URLS for mid in MIGRATIONS]
    # results[(base_url, migration_id)] = (info, first_snap, last_snap, acs_size, acs_hash)
    results = {}

    console.print(f"Fetching data for {len(BASE_URLS)} scan(s) × {len(MIGRATIONS)} migration(s) …")
    console.print(f"Processing migrations sequentially per server (max 1 request per server at a time).")
    console.print()

    with Progress(
        TextColumn("[progress.description]{task.description}"),
        TextColumn("{task.fields[status]}"),
        TaskProgressColumn(),
        TimeElapsedColumn(),
        console=console,
        transient=False,
    ) as progress:
        # One worker per base URL – migrations are processed sequentially within each worker
        with ThreadPoolExecutor(max_workers=len(BASE_URLS)) as executor:
            futures = []
            for base_url in BASE_URLS:
                task_id = progress.add_task(
                    f"{base_url}",
                    total=len(MIGRATIONS),
                    status="[yellow]Waiting",
                )
                futures.append(
                    executor.submit(fetch_all_migrations_for_url, base_url, progress, task_id)
                )
            for future in as_completed(futures):
                try:
                    base_url, url_results = future.result()
                    for migration_id, data in url_results.items():
                        results[(base_url, migration_id)] = data
                except Exception as exc:
                    console.print(f"[red]Unexpected error: {exc}")

    console.print()

    # Build rows (ordered by original job order)
    rows = []
    for base_url, migration_id in jobs:
        info, first_snapshot, last_snapshot, acs_size, acs_hash = results.get((base_url, migration_id), (None, None, None, None, None))

        if info is None:
            rows.append({
                "Base URL": base_url,
                "Migration": str(migration_id),
                "Min Record Time": "",
                "Max Record Time": "",
                "Last Import Update ID": "",
                "Complete": "",
                "Import Updates Complete": "",
                "First Snapshot Timestamp": first_snapshot or "",
                "First Snapshot Size": str(acs_size) if acs_size is not None else "",
                "First Snapshot Hash": acs_hash or "",
                "Last Snapshot Timestamp": last_snapshot or "",
            })
            continue

        record_time_ranges = info.get("record_time_range", [])
        last_import_update_id = info.get("last_import_update_id", "")
        complete = info.get("complete", "")
        import_updates_complete = info.get("import_updates_complete", "")

        if not record_time_ranges:
            rows.append({
                "Base URL": base_url,
                "Migration": str(migration_id),
                "Min Record Time": "",
                "Max Record Time": "",
                "Last Import Update ID": str(last_import_update_id),
                "Complete": str(complete),
                "Import Updates Complete": str(import_updates_complete),
                "First Snapshot Timestamp": first_snapshot or "",
                "First Snapshot Size": str(acs_size) if acs_size is not None else "",
                "First Snapshot Hash": acs_hash or "",
                "Last Snapshot Timestamp": last_snapshot or "",
            })
        else:
            for idx, rtr in enumerate(record_time_ranges):
                rows.append({
                    "Base URL": base_url,
                    "Migration": str(migration_id),
                    "Min Record Time": rtr.get("min", ""),
                    "Max Record Time": rtr.get("max", ""),
                    "Last Import Update ID": str(last_import_update_id) if idx == 0 else "",
                    "Complete": str(complete) if idx == 0 else "",
                    "Import Updates Complete": str(import_updates_complete) if idx == 0 else "",
                    "First Snapshot Timestamp": (first_snapshot or "") if idx == 0 else "",
                    "First Snapshot Size": (str(acs_size) if acs_size is not None else "") if idx == 0 else "",
                    "First Snapshot Hash": (acs_hash or "") if idx == 0 else "",
                    "Last Snapshot Timestamp": (last_snapshot or "") if idx == 0 else "",
                })

    # Write CSV to stdout
    writer = csv.DictWriter(sys.stdout, fieldnames=fieldnames)
    writer.writeheader()
    for row in rows:
        writer.writerow(row)

    # Also print a rich table to stderr for interactive viewing
    console.print()
    table = Table(title="Scan Migration Info", show_lines=True)
    for col in fieldnames:
        table.add_column(col)
    for row in rows:
        table.add_row(*[row[col] for col in fieldnames])
    console.print(table)


if __name__ == "__main__":
    main()

