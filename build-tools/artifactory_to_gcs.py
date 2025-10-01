#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Script to copy non-snapshot versions of Docker images or Helm charts
from JFrog Artifactory to Google Cloud Storage.

Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
SPDX-License-Identifier: Apache-2.0
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple, Union
import logging
import requests
from google.cloud import storage
from google.api_core import exceptions as gcp_exceptions

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Define version regex pattern (matches semantic versioning)
VERSION_PATTERN = re.compile(r'^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$')
# Regex to match git hash versions (git- followed by a hash)
GIT_HASH_PATTERN = re.compile(r'^git-[0-9a-f]{5,40}$')

@dataclass
class ArtifactInfo:
    """Information about an artifact (Docker image or Helm chart)"""
    name: str
    version: str
    path: str
    repository: str
    type: str  # 'docker' or 'helm'
    is_snapshot: bool = False

    def __str__(self) -> str:
        return f"{self.name}:{self.version} ({self.type})"


def parse_args():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Copy non-snapshot Docker images or Helm charts from JFrog Artifactory to Google Cloud Storage"
    )
    parser.add_argument(
        "--artifactory-url", required=True,
        help="JFrog Artifactory URL (e.g., https://artifactory.example.com)"
    )
    parser.add_argument(
        "--repository", required=True,
        help="Artifactory repository name containing Docker images or Helm charts"
    )
    parser.add_argument(
        "--bucket", required=True,
        help="Google Cloud Storage bucket name"
    )
    parser.add_argument(
        "--type", choices=["docker", "helm"], required=True,
        help="Artifact type (docker or helm)"
    )
    parser.add_argument(
        "--api-key",
        help="JFrog Artifactory API key (can also be set via ARTIFACTORY_API_KEY environment variable)"
    )
    parser.add_argument(
        "--username",
        help="JFrog Artifactory username (can also be set via ARTIFACTORY_USERNAME environment variable)"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show what would be copied without actually copying"
    )
    parser.add_argument(
        "--force", action="store_true",
        help="Skip confirmation prompts"
    )
    parser.add_argument(
        "--allow-overwrite", action="store_true",
        help="Allow overwriting existing files in the bucket"
    )
    parser.add_argument(
        "--prefix", default="",
        help="Prefix to add to the destination path in the bucket"
    )
    parser.add_argument(
        "--max-retries", type=int, default=5,
        help="Maximum number of retries for failed operations"
    )
    parser.add_argument(
        "--verbose", action="store_true",
        help="Enable verbose logging"
    )
    parser.add_argument(
        "--include-path-pattern",
        help="Regular expression to filter artifacts by path"
    )
    parser.add_argument(
        "--exclude-path-pattern",
        help="Regular expression to exclude artifacts by path"
    )
    parser.add_argument(
        "--policy-file", default="skopeo_policy.json",
        help="Path to the skopeo policy JSON file"
    )
    parser.add_argument(
        "--save-artifacts-file",
        help="Save fetched artifacts to a file for later reuse"
    )
    parser.add_argument(
        "--load-artifacts-file",
        help="Load artifacts from a file instead of fetching from Artifactory"
    )
    parser.add_argument(
        "--docker-registry-url", required=True,
        help="External URL of the Docker registry (e.g., myregistry.jfrog.io), required for skopeo copy command"
    )
    return parser.parse_args()


def check_credentials(args):
    """Check and retrieve credentials for Artifactory and Google Cloud."""
    # Check Artifactory credentials
    artifactory_api_key = args.api_key or os.environ.get('ARTIFACTORY_API_KEY')
    artifactory_username = args.username or os.environ.get('ARTIFACTORY_USERNAME')

    if not artifactory_api_key and not artifactory_username:
        logger.error("Artifactory credentials not provided. Set --api-key/--username or ARTIFACTORY_API_KEY/ARTIFACTORY_USERNAME environment variables.")
        sys.exit(1)

    # Verify Google Cloud credentials are available
    try:
        # Attempt to initialize a client to check if credentials are available
        storage.Client()
    except Exception as e:
        logger.error(f"Google Cloud credentials not found or invalid: {e}")
        logger.error("Make sure you have set up Google Cloud credentials (run 'gcloud auth application-default login')")
        sys.exit(1)

    return {
        "artifactory_api_key": artifactory_api_key,
        "artifactory_username": artifactory_username
    }


def fetch_artifacts(artifactory_url: str, repository: str, artifact_type: str, credentials: Dict,
                   include_pattern: Optional[str] = None, exclude_pattern: Optional[str] = None) -> List[ArtifactInfo]:
    """
    Fetch non-snapshot artifacts from Artifactory.

    Args:
        artifactory_url: Base URL of the Artifactory instance
        repository: Repository name in Artifactory
        artifact_type: 'docker' or 'helm'
        credentials: Dictionary containing authentication credentials
        include_pattern: Optional regex pattern to include artifacts
        exclude_pattern: Optional regex pattern to exclude artifacts

    Returns:
        List of ArtifactInfo objects representing non-snapshot artifacts
    """

    logger.info(f"Fetching {artifact_type} artifacts from {artifactory_url}/artifactory/{repository}")

    api_url = f"{artifactory_url}/artifactory/api/storage/{repository}"
    headers = {}

    # Set up authentication
    if credentials.get("artifactory_api_key"):
        headers["X-JFrog-Art-Api"] = credentials["artifactory_api_key"]
    elif credentials.get("artifactory_username"):
        auth = requests.auth.HTTPBasicAuth(
            credentials["artifactory_username"],
            os.environ.get("ARTIFACTORY_PASSWORD", "")
        )
    else:
        auth = None

    # Compile regex patterns if provided
    include_regex = re.compile(include_pattern) if include_pattern else None
    exclude_regex = re.compile(exclude_pattern) if exclude_pattern else None

    # For recursive traversal of the repository
    artifacts = []

    def traverse_directory(path: str, current_depth: int = 0, current_name: str = None):
        """
        Traverse directory recursively with optimized strategy:
        - For Docker: Only go into version-named directories, stop once manifest.json is found
        - For Helm: Regular traversal looking for .tgz files

        Args:
            path: Current path in Artifactory repository
            current_depth: Track depth in traversal for Docker optimization
            current_name: Keep track of the container/chart name while traversing
        """
        url = f"{api_url}{path}"
        try:
            if credentials.get("artifactory_api_key"):
                response = requests.get(url, headers=headers)
            else:
                response = requests.get(url, auth=auth)

            response.raise_for_status()
            data = response.json()

            for child in data.get("children", []):
                child_path = f"{path}/{child['uri']}" if path else child["uri"]

                if logger.level <= logging.DEBUG:
                    logger.debug(f"Examining path: {child_path}")

                # Skip if path doesn't match include pattern
                if include_regex and not include_regex.search(child_path):
                    continue

                # Skip if path matches exclude pattern
                if exclude_regex and exclude_regex.search(child_path):
                    continue

                # For Docker repositories, use optimized traversal strategy
                if artifact_type == "docker":
                    if child["folder"]:
                        # Skip any sha256 directories
                        if "/sha256:" in child_path or "sha256:" in child_path or "sha256__" in child_path or GIT_HASH_PATTERN.match(child_path.split("/")[-1]):
                            logger.debug(f"Skipping directory: {child_path}")
                            continue

                        # Get the folder name (the last part of the path)
                        folder_name = child['uri'].strip("/")

                        # Check if this folder looks like a semantic version
                        is_version = VERSION_PATTERN.match(folder_name) is not None
                        is_version = is_version and GIT_HASH_PATTERN.match(folder_name) is None

                        # If it's a version directory, traverse into it but remember we're in a version dir
                        # Otherwise, continue regular traversal without changing depth
                        next_depth = current_depth + 1 if is_version else current_depth

                        # If we're at depth 0, this is potentially a container name folder
                        next_name = folder_name if current_depth == 0 else current_name

                        traverse_directory(child_path, next_depth, next_name)
                    # For files, only consider manifest files in version directories (depth = 1)
                    elif current_depth == 1 and ('manifest.json' in child['uri'] or child['uri'] == 'manifest.json'):
                        # We found a manifest in a version directory - this is what we're looking for
                        logger.debug(f"Found manifest in version directory: {child_path}")

                        # Extract version from path (should be the directory containing manifest.json)
                        # Handle paths that might have double slashes by normalizing the path first
                        normalized_path = child_path.replace("//", "/").strip("/")
                        logger.debug(f"Found normalized path: {normalized_path}")
                        path_parts = [part for part in normalized_path.split("/") if part]
                        if len(path_parts) < 2:
                            continue
                        logger.debug(f"Found normalized path: {path_parts}")
                        version = path_parts[-2]  # Version is the directory containing manifest.json

                        name = "/".join(path_parts[:-2])
                        if not name:
                            logger.debug(f"Could not determine artifact name from path: {normalized_path}")
                            continue

                        # Create artifact if it's a valid semantic version
                        if VERSION_PATTERN.match(version):
                            is_snapshot = "SNAPSHOT" in version or "-snapshot" in version.lower()
                            if not is_snapshot:
                                logger.debug(f"Found Docker artifact - Name: {name}, Version: {version}")
                                artifact = ArtifactInfo(
                                    name=name,
                                    version=version,
                                    path=child_path,
                                    repository=repository,
                                    type=artifact_type,
                                    is_snapshot=False
                                )
                                logger.debug(f"Adding artifact: {artifact}")
                                artifacts.append(artifact)

                # For Helm repositories, use regular traversal looking for .tgz files
                else:
                    if child["folder"]:
                        traverse_directory(child_path, current_depth + 1, current_name)
                    elif is_version_artifact(child_path, artifact_type):
                        logger.debug(f"Found potential helm artifact: {child_path}")
                        artifact = get_artifact_details(child_path, artifactory_url, repository,
                                                      artifact_type, credentials)
                        if artifact and not artifact.is_snapshot:
                            logger.debug(f"Adding helm artifact: {artifact}")
                            artifacts.append(artifact)

        except requests.RequestException as e:
            logger.error(f"Error fetching artifacts from {url}: {e}")

    # Start traversal from the repository root
    traverse_directory("")

    logger.info(f"Found {len(artifacts)} non-snapshot {artifact_type} artifacts")
    return artifacts


def is_version_artifact(path: str, artifact_type: str) -> bool:
    """
    Check if the path appears to point to a versioned artifact.

    This is a preliminary check before fetching detailed metadata.
    """
    if artifact_type == "docker":
        # Docker repositories typically have a manifest.json file for each tag
        return path.endswith("manifest.json")
    elif artifact_type == "helm":
        # Helm charts typically have a .tgz extension
        return path.endswith(".tgz")

    return False


def get_artifact_details(path: str, artifactory_url: str, repository: str,
                       artifact_type: str, credentials: Dict) -> Optional[ArtifactInfo]:
    """
    Get detailed information about an artifact.

    Args:
        path: Path to the artifact within the repository
        artifactory_url: Base URL of the Artifactory instance
        repository: Repository name in Artifactory
        artifact_type: 'docker' or 'helm'
        credentials: Dictionary containing authentication credentials

    Returns:
        ArtifactInfo object if the artifact is a valid release version, None otherwise
    """
    # Extract version from the path
    if artifact_type == "docker":
        # For Docker, extract from path like "myimage/1.2.3/manifest.json"
        path_parts = path.strip("/").split("/")
        if len(path_parts) < 2:
            return None

        # The version should be the part before the manifest.json
        potential_version = path_parts[-2]
        # The name is everything except the version and manifest.json
        name = "/".join(path_parts[:-2])

    elif artifact_type == "helm":
        # For Helm, extract from path like "charts/mychart-1.2.3.tgz"
        if not path.endswith(".tgz"):
            return None

        # Extract the filename without extension
        filename = os.path.basename(path)[:-4]  # Remove .tgz

        # Try to split name and version (usually separated by a hyphen)
        name_version = filename.rsplit("-", 1)
        if len(name_version) != 2:
            return None

        name = name_version[0]
        potential_version = name_version[1]
    else:
        return None

    # Verify this is a semantic version and not a snapshot
    if not VERSION_PATTERN.match(potential_version):
        return None

    is_snapshot = "SNAPSHOT" in potential_version or "-snapshot" in potential_version.lower()

    return ArtifactInfo(
        name=name,
        version=potential_version,
        path=path,
        repository=repository,
        type=artifact_type,
        is_snapshot=is_snapshot
    )


def check_destination_exists(bucket_name: str, prefix: str, repository: str) -> bool:
    """
    Check if the destination folder already exists in the bucket.

    Args:
        bucket_name: Name of the GCS bucket
        prefix: Optional prefix to add to the destination path
        repository: Repository name which will be part of the destination path

    Returns:
        True if the destination folder exists and contains files, False otherwise
    """
    client = storage.Client()

    try:
        bucket = client.get_bucket(bucket_name)
    except gcp_exceptions.NotFound:
        logger.warning(f"Bucket {bucket_name} not found. Will be created if not in dry-run mode.")
        return False

    # Construct the destination prefix
    destination = f"{prefix}/{repository}".strip("/")
    if not destination:
        # If we're copying to the root of the bucket, we need to check if there are any files
        blobs = list(bucket.list_blobs(max_results=1))
        return len(blobs) > 0

    # Check if there are any objects with this prefix
    blobs = list(bucket.list_blobs(prefix=destination, max_results=1))
    return len(blobs) > 0


def download_artifact(artifact: ArtifactInfo, artifactory_url: str, credentials: Dict, policy_file: str, docker_registry_url: str) -> str:
    """
    Download an artifact from Artifactory to a temporary file.

    Args:
        artifact: ArtifactInfo object for the artifact to download
        artifactory_url: Base URL of the Artifactory instance
        credentials: Dictionary containing authentication credentials
        policy_file: Path to the skopeo policy JSON file
        docker_registry_url: External Docker registry URL (required for Docker artifacts)

    Returns:
        Path to the downloaded temporary file

    Raises:
        Exception: If download fails
    """
    # For docker, we need to construct the download URL differently
    if artifact.type == "docker":
        # We need to download the Docker image using skopeo
        # First create a temp directory
        temp_dir = tempfile.mkdtemp()
        # Construct the Docker source URL
        docker_url = f"{docker_registry_url}/{artifact.name}:{artifact.version}"

        # Prepare credentials for skopeo
        creds_args = []
        if credentials.get("artifactory_username"):
            creds_args = ["--src-creds", f"{credentials['artifactory_username']}:{os.environ.get('ARTIFACTORY_PASSWORD', '')}"]

        # Run skopeo to download the image
        cmd = [
            "skopeo", "copy", "--policy" , policy_file, "--all",
            f"docker://{docker_url}",
            f"dir:{temp_dir}",
            *creds_args
        ]

        logger.debug(f"Running command: {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True)

        if result.returncode != 0:
            logger.error(f"Failed to download Docker image: {result.stderr}")
            raise Exception(f"Failed to download {artifact}: {result.stderr}")

        return temp_dir

    else:  # Helm chart
        # Construct the download URL
        download_url = f"{artifactory_url}/artifactory/{artifact.repository}/{artifact.path}"

        # Prepare authentication
        if credentials.get("artifactory_api_key"):
            headers = {"X-JFrog-Art-Api": credentials["artifactory_api_key"]}
            auth = None
        else:
            headers = {}
            auth = requests.auth.HTTPBasicAuth(
                credentials["artifactory_username"],
                os.environ.get("ARTIFACTORY_PASSWORD", "")
            )

        # Download the file
        response = requests.get(download_url, headers=headers, auth=auth, stream=True)
        response.raise_for_status()

        # Create a temporary file and write the content
        fd, temp_path = tempfile.mkstemp()
        with os.fdopen(fd, 'wb') as temp_file:
            for chunk in response.iter_content(chunk_size=8192):
                temp_file.write(chunk)

        return temp_path


def upload_to_gcs(local_path: str, artifact: ArtifactInfo, bucket_name: str,
                 prefix: str, allow_overwrite: bool) -> bool:
    """
    Upload an artifact to Google Cloud Storage.

    Args:
        local_path: Path to the local file or directory to upload
        artifact: ArtifactInfo object for the artifact being uploaded
        bucket_name: Name of the GCS bucket
        prefix: Optional prefix to add to the destination path
        allow_overwrite: Whether to allow overwriting existing files

    Returns:
        True if upload was successful, False otherwise
    """
    client = storage.Client()

    try:
        bucket = client.get_bucket(bucket_name)
    except gcp_exceptions.NotFound:
        logger.warning(f"Bucket {bucket_name} not found, creating it...")
        bucket = client.create_bucket(bucket_name)

    # Construct the destination path
    destination_base = f"{prefix}/{artifact.repository}/{artifact.name}/{artifact.version}".strip("/")

    if artifact.type == "docker":
        # For Docker, we need to upload all files in the directory
        success = True
        for root, _, files in os.walk(local_path):
            for file in files:
                local_file_path = os.path.join(root, file)
                rel_path = os.path.relpath(local_file_path, local_path)
                destination_path = f"{destination_base}/{rel_path}"

                blob = bucket.blob(destination_path)

                # Check if the blob already exists
                if blob.exists() and not allow_overwrite:
                    logger.warning(f"File {destination_path} already exists in bucket and overwrite not allowed")
                    success = False
                    continue

                # Upload the file
                blob.upload_from_filename(local_file_path)
                logger.info(f"Uploaded {rel_path} to gs://{bucket_name}/{destination_path}")

        return success
    else:  # Helm chart
        # For Helm charts, just upload the single file
        chart_name = os.path.basename(artifact.path)
        destination_path = f"{destination_base}/{chart_name}"

        blob = bucket.blob(destination_path)

        # Check if the blob already exists
        if blob.exists() and not allow_overwrite:
            logger.warning(f"File {destination_path} already exists in bucket and overwrite not allowed")
            return False

        # Upload the file
        blob.upload_from_filename(local_path)
        logger.info(f"Uploaded {chart_name} to gs://{bucket_name}/{destination_path}")

        return True


def process_artifact(artifact: ArtifactInfo, artifactory_url: str, docker_registry_url: str, bucket_name: str,
                   prefix: str, credentials: Dict, policy_file: str, allow_overwrite: bool,
                   dry_run: bool, max_retries: int) -> bool:
    """
    Process a single artifact - download and upload to GCS.

    Args:
        artifact: ArtifactInfo object for the artifact to process
        artifactory_url: Base URL of the Artifactory instance
        bucket_name: Name of the GCS bucket
        prefix: Optional prefix to add to the destination path
        credentials: Dictionary containing authentication credentials
        policy_file: Path to the skopeo policy JSON file
        allow_overwrite: Whether to allow overwriting existing files
        dry_run: If True, don't actually download or upload
        max_retries: Maximum number of retry attempts

    Returns:
        True if successful, False otherwise
    """
    if dry_run:
        logger.info(f"Would copy {artifact} to gs://{bucket_name}/{prefix}/{artifact.repository}/{artifact.name}/{artifact.version}")
        return True

    temp_path = None
    success = False

    for attempt in range(max_retries):
        try:
            # Download the artifact
            logger.info(f"Downloading {artifact}")
            temp_path = download_artifact(artifact, artifactory_url, credentials, policy_file, docker_registry_url)

            # Upload to GCS
            logger.info(f"Uploading {artifact} to gs://{bucket_name}")
            success = upload_to_gcs(temp_path, artifact, bucket_name, prefix, allow_overwrite)

            if success:
                break

        except Exception as e:
            logger.warning(f"Attempt {attempt + 1}/{max_retries} failed: {e}")
            if attempt == max_retries - 1:
                logger.error(f"Failed to process {artifact} after {max_retries} attempts")
                return False

            # Wait before retrying
            time.sleep(2 ** attempt)  # Exponential backoff

        finally:
            # Clean up temporary files
            if temp_path:
                if artifact.type == "docker":
                    # Remove the directory
                    subprocess.run(["rm", "-rf", temp_path], check=False)
                else:
                    # Remove the file
                    os.remove(temp_path)

    return success
def save_artifacts_to_file(artifacts: List[ArtifactInfo], filename: str):
    """Save artifacts to a JSON file for later reuse."""
    artifacts_data = [
        {
            "name": a.name,
            "version": a.version,
            "path": a.path,
            "repository": a.repository,
            "type": a.type,
            "is_snapshot": a.is_snapshot
        }
        for a in artifacts
    ]

    with open(filename, 'w') as f:
        json.dump(artifacts_data, f, indent=2)

    logger.info(f"Saved {len(artifacts)} artifacts to {filename}")


def load_artifacts_from_file(filename: str) -> List[ArtifactInfo]:
    """Load artifacts from a previously saved JSON file."""
    with open(filename, 'r') as f:
        artifacts_data = json.load(f)

    artifacts = [
        ArtifactInfo(
            name=item["name"],
            version=item["version"],
            path=item["path"],
            repository=item["repository"],
            type=item["type"],
            is_snapshot=item.get("is_snapshot", False)
        )
        for item in artifacts_data
    ]

    logger.info(f"Loaded {len(artifacts)} artifacts from {filename}")
    return artifacts


def main():
    args = parse_args()

    if args.verbose:
        logger.setLevel(logging.DEBUG)

    logger.info("Starting artifact copy from JFrog Artifactory to Google Cloud Storage")

    # Check credentials
    credentials = check_credentials(args)
    artifacts = []
    if args.load_artifacts_file:
        artifacts = load_artifacts_from_file(args.load_artifacts_file)
    else:
        # Fetch artifacts from Artifactory
        artifacts = fetch_artifacts(
            args.artifactory_url,
            args.repository,
            args.type,
            credentials,
            args.include_path_pattern,
            args.exclude_path_pattern
        )
        if args.save_artifacts_file:
            save_artifacts_to_file(artifacts, args.save_artifacts_file)

    if not artifacts:
        logger.warning(f"No non-snapshot {args.type} artifacts found in {args.repository}")
        return

    # Check if destination exists
    destination_exists = check_destination_exists(args.bucket, args.prefix, args.repository)

    # In dry-run mode, just print what would be copied
    if args.dry_run:
        print("\nThe following artifacts would be copied:")
        for artifact in artifacts:
            print(f"  {artifact} -> gs://{args.bucket}/{args.prefix}/{args.repository}/{artifact.name}/{artifact.version}")
        print(f"\nTotal: {len(artifacts)} artifacts")

        if destination_exists:
            print("\nWARNING: Destination folder already exists and may contain files that would be overwritten.")

        return

    # Ask for confirmation
    if not args.force:
        if destination_exists and not args.allow_overwrite:
            print("\nWARNING: Destination folder already exists and may contain files.")
            print("Use --allow-overwrite to allow overwriting existing files.")
            print("Use --force to skip this confirmation.")
            response = input("\nDo you want to continue without overwriting? (yes/no): ").lower()
        else:
            print(f"\nAbout to copy {len(artifacts)} artifacts from {args.artifactory_url}/artifactory/{args.repository}")
            print(f"to gs://{args.bucket}/{args.prefix}/{args.repository}/")

            if destination_exists and args.allow_overwrite:
                print("\nWARNING: Destination folder exists and files may be overwritten.")

            print("\nArtifacts to be copied:")
            for i, artifact in enumerate(artifacts[:10]):  # Show first 10
                print(f"  {artifact}")

            if len(artifacts) > 10:
                print(f"  ... and {len(artifacts) - 10} more")

            response = input("\nDo you want to continue? (yes/no): ").lower()

        if response != "yes":
            print("Operation cancelled.")
            return

    # Process all artifacts
    success_count = 0
    failed_artifacts = []

    for i, artifact in enumerate(artifacts, 1):
        logger.info(f"Processing artifact {i}/{len(artifacts)}: {artifact}")

        success = process_artifact(
            artifact,
            args.artifactory_url,
            args.docker_registry_url,
            args.bucket,
            args.prefix,
            credentials,
            args.policy_file,
            args.allow_overwrite,
            False,  # Not dry-run
            args.max_retries
        )

        if success:
            success_count += 1
        else:
            failed_artifacts.append(artifact)

    # Print summary
    print("\nCopy operation completed:")
    print(f"  Successfully copied: {success_count}/{len(artifacts)} artifacts")

    if failed_artifacts:
        print(f"  Failed to copy: {len(failed_artifacts)} artifacts")
        print("\nFailed artifacts:")
        for artifact in failed_artifacts:
            print(f"  {artifact}")

    if success_count == len(artifacts):
        print("\nAll artifacts copied successfully!")
    else:
        print("\nNot all artifacts were copied successfully. See log for details.")
        sys.exit(1)


if __name__ == "__main__":
    main()

