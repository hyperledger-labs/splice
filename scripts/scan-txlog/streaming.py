#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

#
# A simple proof-of-concept script for lossless streaming from a scan `updates` endpoint.
#
# Outputs transactions to STDOUT.
# See `scripts/scan-txlog/scan_txlog.py` for pointers on further parsing.
#
# Supports rudimentary filtering based on template name. Extend as needed.
#
# Run with `-h` for supported options.
#

import requests
import argparse
import json
import time


def stream_updates(scan_url, after_record_time, after_migration_id, filter_template_names):

    while True:
        # query endpoint
        transactions = get_some_transactions(scan_url, after_record_time, after_migration_id)

        # output filtered results
        output_filtered(transactions, filter_template_names)

        if not transactions:
            # No new transactions, wait a bit before trying again
            time.sleep(10)

        else:
            # The sequencer enforces strict monotonicity of record times
            # so this approach is guaranteed to not miss any updates.
            after_record_time = transactions[-1]["record_time"]
            after_migration_id = transactions[-1]["migration_id"]


def get_some_transactions(scan_url, after_record_time, after_migration_id):
    response = requests.post(
        f"{scan_url}/api/scan/v1/updates",
        json=dict(
            after=dict(
                after_record_time=after_record_time,
                after_migration_id=after_migration_id,
            ),
            lossless=False,
            page_size=100,
        )
    )
    response.raise_for_status()
    return response.json()["transactions"]


def output_filtered(transactions, filter_template_names):
    for tx in filter_templates(transactions, filter_template_names):
        print(json.dumps(tx, indent=2))


# Tweak this to filter out things you're not interested in
def filter_templates(transactions, template_names):
    if template_names:
        return filter(lambda tx: matches_templates(tx, template_names), transactions)
    else:
        return transactions


def matches_templates(tx, template_names):
    return any([
        event["template_id"].split(":")[-1] in template_names
        for event in tx["events_by_id"].values()
    ])


def parse_cli_args():
    parser = argparse.ArgumentParser(
        description="Proof-of-concept script for streaming from a scan `updates` endpoint"
    )
    parser.add_argument("--scan-url", help="Address of Scan instance", required=True)
    parser.add_argument("--after-record-time", help="Time to start from",
                        default="1970-01-01T00:00:00Z")
    parser.add_argument("--after-migration-id",
                        help="Migration ID to start from", type=int, default=0)
    parser.add_argument("--filter-template-names",
                        help="Template names to filter on, comma-separated (default: show all)")

    return parser.parse_args()


def main():
    args = parse_cli_args()
    filter_template_names = set(args.filter_template_names.split(",")
                                ) if args.filter_template_names else None
    try:
        stream_updates(args.scan_url, args.after_record_time,
                       args.after_migration_id, filter_template_names)
    except KeyboardInterrupt:
        print("Exiting...")


if __name__ == "__main__":
    main()
