#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Script to delete GitHub Container Registry packages matching specific versions.

Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
SPDX-License-Identifier: Apache-2.0
"""

import argparse
import json
import os
import re
import sys
import urllib.parse
from typing import List, Optional, Set, Dict


def parse_arguments():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Delete packages from GHCR.io where tags match a pattern"
    )
    parser.add_argument(
        "-v", "--version", required=True, help="Version to delete"
    )
    parser.add_argument(
        "-p", "--package", help="Package name (optional, if not provided will process all packages)"
    )
    parser.add_argument(
        "-t", "--type", choices=["docker", "helm"],
        help="Package type ('docker' or 'helm', optional)"
    )
    parser.add_argument(
        "-V", "--verbose", action="store_true", help="Enable verbose output"
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="Show what would be deleted without actually deleting"
    )
    parser.add_argument(
        "-i", "--interactive", action="store_true",
        help="Interactive mode: ask for confirmation before each deletion"
    )
    parser.add_argument(
        "-y", "--yes-to-all", action="store_true",
        help="Skip initial confirmation prompt (useful for scripting)"
    )
    parser.add_argument(
        "--output-json-file",
        help="Path to a JSON file to save the details of deleted packages."
    )
    return parser.parse_args()


def get_next_page_url(headers):
    """Extract the next page URL from the Link header."""
    link_header = headers.get('Link') or headers.get('link')
    if not link_header:
        return None

    # Find the URL with rel="next" in the Link header
    for link in link_header.split(','):
        if 'rel="next"' in link:
            match = re.search(r'<([^>]+)>', link)
            if match:
                return match.group(1)
    return None


def get_version_ids(api_url: str, image_name: str, version_to_delete: str, gh_token: str, verbose: bool = False) -> List[dict]:
    """
    Get all version IDs for a package that match the specified version tag.

    Args:
        api_url: Base API URL for the package type (helm/docker)
        image_name: Name of the image/package
        version_to_delete: Version tag to match
        gh_token: GitHub token for authentication
        verbose: Whether to print verbose output

    Returns:
        List of dictionaries with version information (id, created_at, html_url)
    """
    import requests

    # URL encode the image name
    encoded_image = urllib.parse.quote(image_name)
    url = f"{api_url}%2F{encoded_image}/versions?per_page=100"
    matching_versions = []

    while url:
        if verbose:
            print(f"Fetching from URL: {url}", file=sys.stderr)

        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {gh_token}",
            "X-GitHub-Api-Version": "2022-11-28"
        }

        try:
            response = requests.get(url, headers=headers)
            response.raise_for_status()

            # Check if response is valid JSON
            try:
                data = response.json()
            except json.JSONDecodeError:
                if verbose:
                    print(f"Warning: Received invalid JSON response. Skipping.", file=sys.stderr)
                    print(f"Response: {response.text}", file=sys.stderr)
                break

            # Check if response is an array
            if not isinstance(data, list):
                if verbose:
                    print(f"Warning: Response is not an array. Skipping.", file=sys.stderr)
                    print(f"Response: {data}", file=sys.stderr)
                break

            # Process each version in the response
            for version in data:
                # Safely navigate the nested structure to find matching tags
                if (
                    "metadata" in version and
                    version["metadata"] is not None and
                    "container" in version["metadata"] and
                    version["metadata"]["container"] is not None and
                    "tags" in version["metadata"]["container"] and
                    version["metadata"]["container"]["tags"] is not None
                ):
                    tags = version["metadata"]["container"]["tags"]
                    if version_to_delete in tags:
                        # Collect more detailed information for interactive confirmation
                        matching_versions.append({
                            "id": str(version["id"]),
                            "created_at": version.get("created_at", "Unknown"),
                            "html_url": version.get("html_url", ""),
                            "tags": tags
                        })

            # Get the URL for the next page from the Link header
            url = get_next_page_url(response.headers)

        except requests.RequestException as e:
            if verbose:
                print(f"Warning: Failed to fetch versions: {e}", file=sys.stderr)
            break

    return matching_versions


def delete_version(version_id: str, api_url: str, image_name: str, gh_token: str,
                  dry_run: bool = False, verbose: bool = False):
    """
    Delete a specific version of a package.

    Args:
        version_id: ID of the version to delete
        api_url: Base API URL for the package type (helm/docker)
        image_name: Name of the image/package
        gh_token: GitHub token for authentication
        dry_run: If True, don't actually delete, just print what would be deleted
        verbose: Whether to print verbose output

    Returns:
        True if deletion was successful, False otherwise
    """
    import requests

    # URL encode the image name
    encoded_image = urllib.parse.quote(image_name)
    url = f"{api_url}%2F{encoded_image}/versions/{version_id}"

    if dry_run:
        print(f"Would delete version {version_id} for {image_name}")
        return True

    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {gh_token}",
        "X-GitHub-Api-Version": "2022-11-28"
    }

    try:
        if verbose:
            print(f"Sending DELETE request to: {url}", file=sys.stderr)
        response = requests.delete(url, headers=headers)
        response.raise_for_status()
        return True
    except requests.RequestException as e:
        if verbose:
            print(f"Delete request failed: {e}", file=sys.stderr)
        return False


def prompt_for_confirmation(version_info, image_name, version_to_delete):
    """
    Ask the user to confirm deletion of a specific version.

    Args:
        version_info: Dictionary containing version information
        image_name: Name of the image/package
        version_to_delete: Version tag to match

    Returns:
        True if the user confirms deletion, False otherwise
    """
    print("\n" + "-" * 60)
    print(f"Package:     {image_name}")
    print(f"Version:     {version_to_delete}")
    print(f"Version ID:  {version_info['id']}")
    print(f"Created at:  {version_info['created_at']}")
    print(f"All tags:    {', '.join(version_info['tags'])}")
    if version_info['html_url']:
        print(f"URL:         {version_info['html_url']}")
    print("-" * 60)

    response = input("Delete this version? [y/N/a(ll)/q(uit)]: ").lower()
    if response == 'y':
        return True, False
    elif response == 'a':
        # 'a' means yes to all future confirmations
        return True, True
    elif response == 'q':
        print("Operation aborted by user.")
        sys.exit(0)
    else:
        return False, False


def process_package(package_name: str, packages_repo: str, version_to_delete: str, gh_token: str,
                   deleted_packages_log: List[Dict[str, str]], verbose: bool = False, dry_run: bool = False, interactive: bool = False):
    """Process a single package to find and delete matching versions."""
    print(f"Checking {package_name}")

    # Get all version IDs that match the specified version
    version_infos = get_version_ids(
        packages_repo, package_name, version_to_delete, gh_token, verbose
    )

    if version_infos:
        print(f"Found {len(version_infos)} version(s) matching '{version_to_delete}' for {package_name}")

        # Flag to remember if user selected "all" in interactive mode
        yes_to_all = False

        for version_info in version_infos:
            version_id = version_info["id"]

            # In interactive mode, prompt for confirmation unless "all" was selected
            if interactive and not yes_to_all:
                confirmed, yes_to_all = prompt_for_confirmation(version_info, package_name, version_to_delete)
                if not confirmed:
                    print(f"Skipping version {version_id}")
                    continue

            print(f"Deleting version {version_id} for {package_name} with version {version_to_delete}")
            success = delete_version(version_id, packages_repo, package_name, gh_token, dry_run, verbose)
            if success:
                print(f"Successfully {'would delete' if dry_run else 'deleted'} version {version_id}")
                # Log the details for the JSON output
                deleted_packages_log.append({
                    "image_name": package_name,
                    "package_version_id": version_id
                })
            else:
                print(f"Failed to delete version {version_id}")
    else:
        print(f"No versions found matching version '{version_to_delete}' for {package_name}")


def main():
    """Main function."""
    args = parse_arguments()

    # Get GitHub token from environment
    gh_token = os.environ.get("GH_TOKEN")
    if not gh_token:
        print("Error: you need to set the GH_TOKEN environment variable.", file=sys.stderr)
        sys.exit(1)

    # List to store details of deleted packages
    deleted_packages_log = []

    splice_root = os.environ.get("SPLICE_ROOT")
    if not splice_root:
        print("Error: SPLICE_ROOT environment variable is not set.", file=sys.stderr)
        sys.exit(1)

    # Define directory paths
    cluster_images_dir = os.path.join(splice_root, "cluster", "images")
    cluster_helm_dir = os.path.join(splice_root, "cluster", "helm")

    # Define API URLs
    api_github_packages_repo_helm = "https://api.github.com/orgs/digital-asset/packages/container/decentralized-canton-sync%2Fhelm"
    api_github_packages_repo_docker = "https://api.github.com/orgs/digital-asset/packages/container/decentralized-canton-sync%2Fdocker"

    # Skip directories when processing
    skip_dirs = {"common", "splice-util-lib", "target"}

    # Skip initial confirmation in dry-run mode or if --yes-to-all is specified
    if not args.dry_run and not args.yes_to_all:
        # Customize confirmation message based on deletion scope
        if args.package:
            if args.type:
                confirmation_msg = f"Are you sure you want to delete version '{args.version}' of {args.type} package '{args.package}'?"
            else:
                confirmation_msg = f"Are you sure you want to delete version '{args.version}' of package '{args.package}'?"
        else:
            if args.type:
                confirmation_msg = f"Are you sure you want to delete all {args.type} packages with version '{args.version}'?"
            else:
                confirmation_msg = f"Are you sure you want to delete all packages with version '{args.version}'?"

        response = input(f"{confirmation_msg} Type 'yes' to continue: ")
        if response.lower() != "yes":
            print("Aborted.")
            sys.exit(0)

    # Process Helm packages if no package type is specified or if type is "helm"
    if not args.type or args.type == "helm":
        if args.package:
            # If specific package name is provided, only check that one
            package_path = os.path.join(cluster_helm_dir, args.package)
            if os.path.isdir(package_path):
                process_package(
                    args.package,
                    api_github_packages_repo_helm,
                    args.version,
                    gh_token,
                    deleted_packages_log,
                    args.verbose,
                    args.dry_run,
                    args.interactive
                )
            else:
                print(f"Warning: Helm package '{args.package}' not found in {cluster_helm_dir}")
        else:
            # Process all Helm packages
            for item in os.listdir(cluster_helm_dir):
                package_path = os.path.join(cluster_helm_dir, item)
                if os.path.isdir(package_path) and item not in skip_dirs:
                    process_package(
                        item,
                        api_github_packages_repo_helm,
                        args.version,
                        gh_token,
                        deleted_packages_log,
                        args.verbose,
                        args.dry_run,
                        args.interactive
                    )

    # Process Docker packages if no package type is specified or if type is "docker"
    if not args.type or args.type == "docker":
        if args.package:
            # If specific package name is provided, only check that one
            package_path = os.path.join(cluster_images_dir, args.package)
            if os.path.isdir(package_path):
                process_package(
                    args.package,
                    api_github_packages_repo_docker,
                    args.version,
                    gh_token,
                    deleted_packages_log,
                    args.verbose,
                    args.dry_run,
                    args.interactive
                )
            else:
                print(f"Warning: Docker package '{args.package}' not found in {cluster_images_dir}")
        else:
            # Process all Docker packages
            for item in os.listdir(cluster_images_dir):
                package_path = os.path.join(cluster_images_dir, item)
                if os.path.isdir(package_path) and item not in skip_dirs:
                    process_package(
                        item,
                        api_github_packages_repo_docker,
                        args.version,
                        gh_token,
                        deleted_packages_log,
                        args.verbose,
                        args.dry_run,
                        args.interactive
                    )

    # Write the log of deleted packages to a JSON file if requested
    if args.output_json_file and deleted_packages_log:
        try:
            with open(args.output_json_file, 'w') as f:
                json.dump(deleted_packages_log, f, indent=2)
            print(f"Successfully saved details of {len(deleted_packages_log)} deleted packages to '{args.output_json_file}'")
        except IOError as e:
            print(f"Error: Could not write to file '{args.output_json_file}': {e}", file=sys.stderr)

    print("Done.")


if __name__ == "__main__":
    main()
