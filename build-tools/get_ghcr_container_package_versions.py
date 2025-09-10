#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Script to retrieve all container package versions from GitHub Container Registry.
Uses pagination and properly handles the Link response header.
Compatible with macOS and Linux.
"""

import argparse
import json
import re
import sys
import urllib.parse
from dataclasses import dataclass, asdict, field
from typing import Dict, List, Optional, Set, Tuple, Any

import requests


@dataclass
class PackageVersion:
    """Represents a container package version with its tags."""
    package_name: str
    tags: List[str]

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "name": self.package_name,
            "versions": sorted(self.tags)
        }


class GithubPackageClient:
    """Client for GitHub Packages API."""

    def __init__(self, organization: str, token: str, verbose: bool = False):
        """
        Initialize GitHub Package client.

        Args:
            organization: GitHub organization name
            token: GitHub token with packages read permission
            verbose: Whether to print verbose output
        """
        self.organization = organization
        self.token = token
        self.verbose = verbose
        self.headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28"
        }

    def list_packages(self, pattern: Optional[str] = None) -> List[str]:
        """
        List all container packages in the organization.

        Args:
            pattern: Optional pattern to filter packages by prefix
                     Can be repository name only (part before first '/') or a more specific path

        Returns:
            List of package names
        """
        if self.verbose:
            print(f"Listing container packages for organization '{self.organization}'...", file=sys.stderr)
            if pattern:
                print(f"Filtering by pattern '{pattern}'...", file=sys.stderr)

        url = f"https://api.github.com/orgs/{self.organization}/packages?package_type=container&per_page=100"
        packages = []

        while url:
            try:
                response = requests.get(url, headers=self.headers)
                response.raise_for_status()

                data = response.json()

                # Extract package names, filtering by pattern if specified
                if pattern:
                    # Check if pattern is just a repository name (no slashes) or a more specific path
                    if "/" not in pattern:
                        # Repository-only filter: check if package starts with "pattern/"
                        for item in data:
                            pkg_name = item["name"]
                            parts = pkg_name.split("/", 1)
                            if len(parts) > 0 and parts[0] == pattern:
                                packages.append(pkg_name)
                    else:
                        # Path filter: check if package starts with the specified pattern
                        for item in data:
                            pkg_name = item["name"]
                            if pkg_name.startswith(pattern):
                                packages.append(pkg_name)
                else:
                    packages.extend(item["name"] for item in data)

                # Get next page URL from Link header
                url = self._get_next_page_url(response.headers.get("Link", ""))
            except requests.RequestException as e:
                if self.verbose:
                    print(f"Warning: Failed to fetch packages: {str(e)}", file=sys.stderr)
                break

        return packages

    def get_package_versions(self, package_name: str, include_snapshots: bool = True) -> PackageVersion:
        """
        Get all versions for a specific package.

        Args:
            package_name: Name of the package
            include_snapshots: Whether to include snapshot versions

        Returns:
            PackageVersion object with all tags
        """
        # URL encode the package name
        encoded_package = urllib.parse.quote(package_name, safe='')

        if self.verbose:
            print(f"Fetching versions for '{self.organization}/{package_name}'...", file=sys.stderr)
            print(f"Encoded package name: {encoded_package}", file=sys.stderr)

        url = f"https://api.github.com/orgs/{self.organization}/packages/container/{encoded_package}/versions?per_page=100"
        all_tags: Set[str] = set()

        while url:
            try:
                response = requests.get(url, headers=self.headers)
                response.raise_for_status()

                data = response.json()

                # Extract tags from metadata
                for version in data:
                    # Check if metadata exists
                    if not version.get("metadata"):
                        continue

                    # Check if container metadata exists
                    container = version.get("metadata", {}).get("container")
                    if not container:
                        continue

                    # Check if tags exist
                    tags = container.get("tags", [])
                    if not tags:
                        continue

                    # Filter out snapshot versions if required
                    if not include_snapshots:
                        tags = [tag for tag in tags if "-snapshot" not in tag]

                    all_tags.update(tags)

                # Get next page URL from Link header
                url = self._get_next_page_url(response.headers.get("Link", ""))
            except requests.RequestException as e:
                if self.verbose:
                    print(f"Warning: Failed to fetch versions for {package_name}: {str(e)}", file=sys.stderr)
                break

        return PackageVersion(package_name=package_name, tags=list(all_tags))

    def _get_next_page_url(self, link_header: str) -> str:
        """
        Parse Link header to extract the next page URL.

        Args:
            link_header: Link header from GitHub API response

        Returns:
            URL for the next page or empty string if no next page
        """
        if not link_header:
            return ""

        # Extract URLs and their relation types from the Link header
        matches = re.findall(r'<([^>]+)>;\s*rel="([^"]+)"', link_header)

        # Look for the link with rel="next"
        for url, rel in matches:
            if rel == "next":
                return url

        return ""


def display_package_versions(package_version: PackageVersion, ascending: bool = False) -> None:
    """
    Display versions for a package.

    Args:
        package_version: PackageVersion object
        ascending: Whether to sort versions in ascending order
    """
    print(f"Package: {package_version.package_name}")
    print("Versions:")

    if not package_version.tags:
        print("  No tags found")
        print("")
        return

    # Sort versions
    tags = sorted(package_version.tags, reverse=not ascending)

    # Display sorted versions
    for tag in tags:
        print(f"  - {tag}")

    print("")


def collect_package_versions(client: GithubPackageClient, packages: List[str],
                             include_snapshots: bool, ascending: bool) -> List[PackageVersion]:
    """
    Collect versions for all packages.

    Args:
        client: GitHub Package client
        packages: List of package names
        include_snapshots: Whether to include snapshot versions
        ascending: Whether to sort versions in ascending order

    Returns:
        List of PackageVersion objects with versions
    """
    result = []

    for package in packages:
        try:
            package_version = client.get_package_versions(
                package_name=package,
                include_snapshots=include_snapshots
            )

            # Only include packages with tags
            if package_version.tags:
                result.append(package_version)

        except Exception as e:
            if client.verbose:
                print(f"Error fetching versions for {package}: {str(e)}", file=sys.stderr)

    return result


def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Retrieve container package versions from GitHub Container Registry")
    parser.add_argument("-o", "--organization", required=True, help="GitHub organization")
    parser.add_argument("-p", "--package", help="Container package name (optional)")
    parser.add_argument("-f", "--filter", help="Filter packages by pattern (can be repository or path prefix)")
    parser.add_argument("-t", "--token", required=True, help="GitHub token with packages read permission")
    parser.add_argument("-l", "--list-only", action="store_true", help="List packages only (don't fetch versions)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    parser.add_argument("-n", "--no-snapshots", action="store_true", help="Filter out versions containing '-snapshot'")
    parser.add_argument("-a", "--ascending", action="store_true", help="Sort versions in ascending order (default is descending)")
    parser.add_argument("-j", "--json", action="store_true", help="Output in JSON format (array of packages with versions)")

    # Keep the -r/--repository option for backward compatibility
    parser.add_argument("-r", "--repository", help="Filter by repository (part before first '/' in package name)")

    args = parser.parse_args()

    # Create GitHub Package client
    client = GithubPackageClient(
        organization=args.organization,
        token=args.token,
        verbose=args.verbose
    )

    # Use filter if provided, otherwise fall back to repository
    pattern = args.filter if args.filter is not None else args.repository

    # If list_only is true, just list packages and exit
    if args.list_only:
        packages = client.list_packages(pattern=pattern)
        if args.json:
            print(json.dumps(packages))
        else:
            for package in packages:
                print(package)
        return

    # If a specific package is provided, just get versions for that package
    if args.package:
        package_version = client.get_package_versions(
            package_name=args.package,
            include_snapshots=not args.no_snapshots
        )

        if args.json:
            # Only output if the package has tags
            if package_version.tags:
                result = [package_version.to_dict()]
                print(json.dumps(result))
        else:
            display_package_versions(package_version, ascending=args.ascending)
    else:
        # Otherwise, get all packages and process each one
        if args.verbose:
            print(f"Processing all packages in organization '{args.organization}'...", file=sys.stderr)
            if pattern:
                print(f"Filtering by pattern '{pattern}'...", file=sys.stderr)
            print("----------------------------------------", file=sys.stderr)

        packages = client.list_packages(pattern=pattern)

        if args.json:
            # Collect all package versions and convert to JSON
            package_versions = collect_package_versions(client, packages, not args.no_snapshots, args.ascending)

            # Convert to dictionaries for JSON serialization, only including packages with tags
            result = [pv.to_dict() for pv in package_versions if pv.tags]

            # Output as JSON
            print(json.dumps(result))
        else:
            # Process each package and display versions
            for package in packages:
                try:
                    package_version = client.get_package_versions(
                        package_name=package,
                        include_snapshots=not args.no_snapshots
                    )
                    display_package_versions(package_version, ascending=args.ascending)
                except Exception as e:
                    print(f"Package: {package}")
                    print(f"Versions: Error fetching versions ({str(e)})")
                    print("")


if __name__ == "__main__":
    main()
