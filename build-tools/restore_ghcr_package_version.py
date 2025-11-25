#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import json
import os
import requests
import sys
from typing import List, Dict

# A script to restore a package version in the Github Container Registry.
# The package can be a docker image or a helm chart.
#
# The script requires a GH_TOKEN environment variable with the `read:packages` and `write:packages` scopes.
#
# Example usage:
# GH_TOKEN=<token> ./build-tools/restore_ghcr_package_version.py packages_to_restore.json
#
# The JSON file should be an array of objects, each with "image_name" and "package_version_id":
# [
#   { "image_name": "my-image", "package_version_id": "12345" },
#   { "image_name": "another-image", "package_version_id": "67890" }
# ]

def restore_package_version(org: str, package_name: str, version_id: str, token: str) -> bool:
    """
    Restores a specific package version for an organization.
    Returns True if successful, False otherwise.
    """
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    url = f"https://api.github.com/orgs/{org}/packages/container/{package_name}/versions/{version_id}/restore"

    print(f"Attempting to restore package '{package_name}' with version ID {version_id}...")

    response = requests.post(url, headers=headers)

    if response.status_code == 204:
        print(f"Successfully restored package '{package_name}' with version ID {version_id}.")
        return True
    else:
        # Don't print an error here, as we expect one of the types (docker/helm) to fail.
        # The calling function will report the final failure.
        return False

def main(packages_to_restore: List[Dict[str, str]]):
    token = os.getenv("GH_TOKEN")
    if not token:
        print("Error: GH_TOKEN environment variable not set.", file=sys.stderr)
        sys.exit(1)

    org = "digital-asset"
    helm_prefix = "decentralized-canton-sync-dev%2Fhelm%2F"
    docker_prefix = "decentralized-canton-sync-dev%2Fdocker%2F"

    for package in packages_to_restore:
        image_name = package.get("image_name")
        version_id = package.get("package_version_id")

        if not image_name or not version_id:
            print(f"Skipping invalid entry in JSON file: {package}", file=sys.stderr)
            continue

        print(f"--- Processing image '{image_name}' with version ID: {version_id} ---")

        # Try restoring as a Docker package first
        docker_package_name = f"{docker_prefix}{image_name}"
        if restore_package_version(org, docker_package_name, version_id, token):
            continue  # Success, move to the next item

        # If docker failed, try restoring as a Helm package
        helm_package_name = f"{helm_prefix}{image_name}"
        if restore_package_version(org, helm_package_name, version_id, token):
            continue # Success, move to the next item

        print(f"--- Could not restore image '{image_name}' with version ID {version_id} as either Docker or Helm. ---", file=sys.stderr)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Restore package versions in GHCR from a JSON file."
    )
    parser.add_argument(
        "json_file",
        metavar="JSON_FILE",
        type=str,
        help="Path to a JSON file containing an array of objects with 'image_name' and 'package_version_id'.",
    )
    args = parser.parse_args()

    try:
        with open(args.json_file, 'r') as f:
            data = json.load(f)
            if not isinstance(data, list):
                raise ValueError("JSON file must contain a list of objects.")
            main(data)
    except FileNotFoundError:
        print(f"Error: JSON file not found at '{args.json_file}'", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError:
        print(f"Error: Could not decode JSON from '{args.json_file}'", file=sys.stderr)
        sys.exit(1)
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
