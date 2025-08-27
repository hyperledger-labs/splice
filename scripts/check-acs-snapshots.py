import requests
import json
import argparse
import time
from itertools import islice
import concurrent.futures
from concurrent.futures import ThreadPoolExecutor, as_completed
from rich.progress import Progress, TaskProgressColumn, TextColumn, TimeElapsedColumn
from rich.console import Console

console = Console()

separator = "=" * 80

# Read command line arguments
parser = argparse.ArgumentParser(description="Checks the consistency of update history and ACS snapshots across all SV nodes.")
parser.add_argument("--max-concurrent-downloads", type=int, default=16, help="Maximum number of concurrent downloads")
parser.add_argument("--num-examples", type=int, default=1, help="Number of example items to print for mismatches")
parser.add_argument("--first-page-only", action="store_true", default=False, help="Compare only the first page of results")
parser.add_argument("trusted_scan", type=str, help="Trusted scan URL. Can be one of 'cilr', 'devnet', 'testnet', 'mainnet', or a custom URL")
parser.add_argument("migration_id", type=int, help="Migration ID")
parser.add_argument("before_time", type=str, help="Before time (ISO 8601 format, e.g., 2025-12-31T17:10:55Z)")
args = parser.parse_args()

max_concurrent_downloads = args.max_concurrent_downloads
num_examples = args.num_examples
all_pages = not args.first_page_only
before_time = args.before_time
migration_id = args.migration_id

if args.trusted_scan == "cilr":
    trusted_scan = "https://scan.sv-2.cilr.global.canton.network.digitalasset.com"
elif args.trusted_scan == "devnet":
    trusted_scan = "https://scan.sv-2.dev.global.canton.network.digitalasset.com"
elif args.trusted_scan == "testnet":
    trusted_scan = "https://scan.sv-2.test.global.canton.network.digitalasset.com"
elif args.trusted_scan == "mainnet":
    trusted_scan = "https://scan.sv-2.global.canton.network.digitalasset.com"
else:
    trusted_scan = args.trusted_scan

console.print(separator)
console.print("Starting check with the following parameters:")
console.print(f"Migration ID: [cyan]{migration_id}[/cyan]")
console.print(f"Before time: [cyan]{before_time}[/cyan]")
console.print(f"Trusted scan URL: [cyan]{trusted_scan}[/cyan]")
console.print(f"All pages: [cyan]{all_pages}[/cyan]")
console.print(separator)

console.print("Fetching list of all scans...")
console.print(separator)
dso = requests.get(f"{trusted_scan}/api/scan/v0/dso").json()
scan_urls = [
    node[1]["scan"]["publicUrl"]
    for state in dso["sv_node_states"]
    for node in state["contract"]["payload"]["state"]["synchronizerNodes"]
]
for url in scan_urls:
    print(f"{url}")
console.print(separator)
console.print("")


# Compare migration info
console.print("Checking migration info...")
console.print(separator)
migration_infos={}
for url in scan_urls:
    payload={
        "migration_id": migration_id
    }
    result=requests.post(f"{url}/api/scan/v0/backfilling/migration-info", json=payload)
    if result.status_code == 200:
        migration_infos[url]=result.text
    else:
        console.print(f"Error fetching migration info from {url}: {result.status_code}")
        migration_infos[url]=f"{result.status_code}"

console.print("")

console.print("Trusted response:")
console.print_json(migration_infos[trusted_scan])

console.print("")
console.print("Other responses:")
for url in [url for url in migration_infos if url != trusted_scan]:
    a = migration_infos[trusted_scan]
    b = migration_infos[url]
    if a != b:
        console.print(f"✖ {url} differs!")
        console.print_json(b)
    else:
        console.print(f"✔ {url} agrees.")
console.print(separator)
console.print("")

# Compare import updates
console.print("Checking import updates...")
console.print(separator)
def fetch_import_updates(url, task_id, progress):
    import_updates=set()
    import_update_ids=set()
    next_page_token=""
    try:
        while True:
            payload={
                "migration_id": migration_id,
                "after_update_id": next_page_token,
                "limit": 500
            }

            # For some reason `request.post` tends to sometimes fail with a `ConnectionError/NameResolutionError`
            # even though the URL is correct. This is mitigated by retries here, plus a limit to the number
            # of concurrently processed scan URLs.
            progress.update(task_id, update_id=next_page_token[:8], status=f"[cyan]Loading")
            for attempt in range(8):
                try:
                    result = requests.post(f"{url}/api/scan/v0/backfilling/import-updates", json=payload)
                    break  # Success, exit retry loop
                except requests.exceptions.ConnectionError as e:
                    # console.print_exception()
                    if attempt < 7:
                        progress.update(task_id, update_id=next_page_token[:8], status=f"[orange]Backoff")
                        time.sleep(1.5 ** attempt)  # Wait before retrying
                    else:
                        raise  # Re-raise after the last failure

            if result.status_code == 200:
                j=result.json()
                new_transactions=[json.dumps(t) for t in j["transactions"]]
                new_transactions_ids=[t["update_id"] for t in j["transactions"]]
                if len(new_transactions) > 0:
                    import_updates.update(new_transactions)
                    import_update_ids.update(new_transactions_ids)
                    progress.update(task_id, advance=len(new_transactions), count=len(import_updates))
                    if all_pages:
                        next_page_token = j["transactions"][-1]["update_id"]
                    else:
                        break
                else:
                    break
            else:
                break
        progress.update(task_id, status=f"[green]Done")
        return url, import_updates, import_update_ids
    except Exception as e:
        console.print_exception() # Prints a VERY fancy stack trace of the last exception
        progress.update(task_id, status=f"[red]Error")
        return url, None, None


import_updates={}
import_update_ids={}
with Progress(
    TextColumn("[progress.description]{task.description}"),
    TextColumn("{task.fields[status]}"),
    TextColumn("{task.fields[update_id]}"),
    TextColumn("{task.fields[count]}"),
    TaskProgressColumn(show_speed=True),
    TimeElapsedColumn(),
    console=console,
    transient=False,
) as progress:
    tasks = {}
    with ThreadPoolExecutor(max_workers=max_concurrent_downloads) as executor:
        futures = []
        for url in scan_urls:
            task_id = progress.add_task(
                f"{url}",
                total=None,
                status="[yellow]Waiting",
                update_id="",

                count=0,
            )
            tasks[url] = task_id
            futures.append(executor.submit(fetch_import_updates, url, task_id, progress))
        for future in as_completed(futures):
            url, updates, update_ids = future.result()
            import_updates[url] = updates
            import_update_ids[url] = update_ids

console.print("")
console.print("Trusted response (examples):")
if import_updates[trusted_scan] is not None:
    for item in list(import_updates[trusted_scan])[:num_examples]:
        console.print_json(item)
else:
    console.print(f"{trusted_scan} failed to fetch import updates!")

console.print("")
console.print("Other responses (update ids only):")
for url in [url for url in import_update_ids if url != trusted_scan]:
    if import_update_ids[url] is None:
        console.print(f"✖ {url} failed to fetch import updates!")
        continue
    if import_update_ids[trusted_scan] is None:
        console.print(f"? {url}: trusted scan failed to fetch import updates!")
        continue
    diff1=import_update_ids[url].difference(import_update_ids[trusted_scan])
    diff2=import_update_ids[trusted_scan].difference(import_update_ids[url])
    if len(diff1) > 0 or len(diff2) > 0:
        console.print(f"✖ {url} differs!")
        if len(diff1) > 0:
            console.print(f"  {len(diff1)} updates exist in {url} but not {trusted_scan}")
            console.print(f"  Examples:")
            for item in list(diff1)[:num_examples]:
                console.print(item)
        if len(diff2) > 0:
            console.print(f"  {len(diff2)} updates exist in {trusted_scan} but not {url}")
            console.print(f"  Examples:")
            for item in list(diff2)[:num_examples]:
                console.print(item)
    else:
        console.print(f"✔ {url} agrees.")

console.print("")
console.print("Other responses (with payloads):")
for url in [url for url in import_updates if url != trusted_scan]:
    if import_updates[url] is None:
        console.print(f"✖ {url} failed to fetch import updates!")
        continue
    if import_updates[trusted_scan] is None:
        console.print(f"? {url}: trusted scan failed to fetch import updates!")
        continue
    diff1=import_updates[url].difference(import_updates[trusted_scan])
    diff2=import_updates[trusted_scan].difference(import_updates[url])
    if len(diff1) > 0 or len(diff2) > 0:
        console.print(f"✖ {url} differs!")
        if len(diff1) > 0:
            console.print(f"  {len(diff1)} updates exist in {url} but not {trusted_scan}")
            console.print(f"  Examples:")
            for item in list(diff1)[:num_examples]:
                console.print_json(item)
        if len(diff2) > 0:
            console.print(f"  {len(diff2)} updates exist in {trusted_scan} but not {url}")
            console.print(f"  Examples:")
            for item in list(diff2)[:num_examples]:
                console.print_json(item)
    else:
        console.print(f"✔ {url} agrees.")

console.print(separator)
console.print("")

# Get latest snapshot time before `before_time` that is available on all scans
console.print("Picking snapshot time...")
console.print(separator)
snapshot_times={}
for url in scan_urls:
    latest=requests.get(f"{url}/api/scan/v0/state/acs/snapshot-timestamp?before={before_time}&migration_id={migration_id}").json()
    if latest.get("record_time") is not None:
        snapshot_times[url]=latest["record_time"]
    else:
        console.print(f"This scan has no snapshots: {url}")

snapshot_time=min(snapshot_times.values())
console.print(f"Using {snapshot_time}")
console.print(separator)
console.print("")

# Compare snapshot
console.print("Comparing snapshots...")
console.print(separator)
def fetch_acs_snapshots(url, task_id, progress):
    cids = set()
    all_events = {}
    next_page_token = None
    progress.update(task_id, status=f"[cyan]Loading")
    try:
        while True:
            payload={
                "migration_id": migration_id,
                "record_time": snapshot_time,
                "page_size": 1000,
                "after": next_page_token
            }

            # For some reason `request.post` tends to sometimes fail with a `ConnectionError/NameResolutionError`
            # even though the URL is correct. This is mitigated by retries here, plus a limit to the number
            # of concurrently processed scan URLs.
            progress.update(task_id, status=f"[cyan]Loading")
            for attempt in range(8):
                try:
                    result = requests.post(f"{url}/api/scan/v0/state/acs", json=payload)
                    break  # Success, exit retry loop
                except requests.exceptions.ConnectionError as e:
                    # console.print_exception()
                    if attempt < 7:
                        progress.update(task_id, update_id=next_page_token, status=f"[orange]Backoff")
                        time.sleep(1.5 ** attempt)  # Wait before retrying
                    else:
                        raise  # Re-raise after the last failure

            if result.status_code == 200:
                j = result.json()
                new_cids = [e["contract_id"] for e in j["created_events"]]
                cids.update(new_cids)
                all_events.update({e["contract_id"]: e for e in j["created_events"]})
                next_page_token = j["next_page_token"]
                progress.update(task_id, advance=len(new_cids), cids=len(cids), next_page_token=next_page_token)
                if next_page_token is None or not all_pages:
                    break
            else:
                break
        progress.update(task_id, status=f"[green]Done")
        return url, cids, all_events
    except Exception as e:
        console.print_exception() # Prints a VERY fancy stack trace of the last exception
        progress.update(task_id, status=f"[red]Error")
        return url, None, None

cids={}
all_events={}
with Progress(
    TextColumn("[progress.description]{task.description}"),
    TextColumn("{task.fields[status]}"),
    TextColumn("{task.fields[next_page_token]}"),
    TextColumn("{task.fields[cids]}"),
    TaskProgressColumn(show_speed=True),
    TimeElapsedColumn(),
    console=console,
    transient=False,
) as progress:
    tasks = {}
    with ThreadPoolExecutor(max_workers=max_concurrent_downloads) as executor:
        futures = []
        for url in scan_urls:
            task_id = progress.add_task(
                f"{url}",
                total=None,
                status="[yellow]Waiting",
                next_page_token=None,
                cids=0,
            )
            tasks[url] = task_id
            futures.append(executor.submit(fetch_acs_snapshots, url, task_id, progress))
        for future in as_completed(futures):
            url, url_cids, url_all_events = future.result()
            cids[url] = url_cids
            all_events[url] = url_all_events

console.print("")

console.print("Responses:")
console.print("")

for url in [url for url in cids if url != trusted_scan]:
    if cids[url] is None:
        console.print(f"✖ {url} failed to fetch import updates!")
        continue
    if cids[trusted_scan] is None:
        console.print(f"? {url}: trusted scan failed to fetch import updates!")
        continue

    diff1=cids[url].difference(cids[trusted_scan])
    diff2=cids[trusted_scan].difference(cids[url])
    if len(diff1) > 0 or len(diff2) > 0:
        console.print(f"✖ {url} differs!")
        if len(diff1) > 0:
            console.print(f" Diff: {len(diff1)} for {url} vs {trusted_scan}")
            console.print(f" Examples:")
            items = [all_events[url][e] for e in islice(diff1, num_examples)]
            for item in items:
                console.print_json(data=item)
        if len(diff2) > 0:
            console.print(f" Diff: {len(diff2)} for {trusted_scan} vs {url}")
            console.print(f" Examples:")
            items = [all_events[trusted_scan][e] for e in islice(diff2, num_examples)]
            for item in items:
                console.print_json(data=item)
    else:
        console.print(f"✔ {url} agrees.")
console.print(separator)
console.print("")
