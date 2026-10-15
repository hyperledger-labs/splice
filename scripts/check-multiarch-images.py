#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Fail if any digest-pinned container image is not a multi-arch digest."""

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from dockerfile_parse import DockerfileParser

# Matches concrete image references that pin a digest, e.g. ubuntu:24.04@sha256:<digest>
# This intentionally does not match references that use variable digests
IMAGE_REF_RE = re.compile(
    r"([a-zA-Z0-9][a-zA-Z0-9._/-]*):([^@\s]+)@sha256:([a-f0-9]{64})"
)

# Some Dockerfiles reuse the ARG name `image_sha256` with a different value for each image
IMAGE_SHA256_MAP = {
    "cluster/images/canton/Dockerfile": "CANTON_BASE_IMAGE_SHA256",
    "cluster/images/canton-mediator/Dockerfile": "CANTON_MEDIATOR_IMAGE_SHA256",
    "cluster/images/canton-participant/Dockerfile": "CANTON_PARTICIPANT_IMAGE_SHA256",
    "cluster/images/canton-sequencer/Dockerfile": "CANTON_SEQUENCER_IMAGE_SHA256",
}

# List of file-path regexes. Any digest-pinned image coming from a matching file is skipped by the check
IGNORED_FILE_PATTERNS = [
    re.compile(r"^cluster/images/cometbft/Dockerfile$"),
    re.compile(r"^cluster/images/splice-test-cometbft/Dockerfile$"),
]


def main() -> int:
    _setup_env()

    # Collect all digest-pinned references and keep the first file they were seen in.
    # The set semantics of the dict keys give us deduplication for free.
    refs_by_file: dict[tuple[str, str, str], str] = {}

    # Dockerfiles may use variable digests (e.g. ARG $cometbft_sha), so parse all of them.
    for file in _tracked_dockerfiles():
        path = Path(file)
        if not path.is_file() or _file_is_ignored(file):
            continue
        for ref in _refs_from_dockerfile(path):
            refs_by_file.setdefault(ref, file)

    # Other files only need to be inspected if they contain a concrete digest reference.
    for file in _tracked_files_with_digest_refs():
        path = Path(file)
        if not path.is_file() or _file_is_ignored(file):
            continue
        for ref in _refs_from_plain_file(path):
            refs_by_file.setdefault(ref, file)

    failures = 0
    for (image, tag, digest), file in refs_by_file.items():
        ref = f"{image}:{tag}@sha256:{digest}"
        print(f"Inspecting {ref} (from {file})")
        if _inspect(image, digest):
            print(f"  OK: {ref} is multi-arch")
        else:
            print(f"ERROR: {ref} (from {file}) is not pinned to a multi-arch digest")
            failures += 1

    if failures > 0:
        print(f"FAIL: {failures} image(s) are not pinned to multi-arch digests")
        return 1

    print("OK: all pinned images are multi-arch")
    return 0


def _setup_env() -> None:
    """Export lowercase aliases for Dockerfile ARG references"""
    os.environ.setdefault("canton_version", os.environ.get("CANTON_VERSION", ""))
    os.environ.setdefault(
        "cometbft_version", os.environ.get("COMETBFT_RELEASE_VERSION", "")
    )
    os.environ.setdefault("cometbft_sha", os.environ.get("COMETBFT_IMAGE_SHA256", ""))


def _image_sha256_for(file: str) -> str | None:
    key = IMAGE_SHA256_MAP.get(file)
    if not key:
        return None
    return os.environ.get(key)


INSPECT_ATTEMPTS = 20
INSPECT_RETRY_WAIT_SECONDS = 5


def _inspect(image: str, digest: str) -> bool:
    """Return True if the digest resolves to a multi-arch manifest list/index.

    Retries `_inspect_once`, because a transient registry failure must not be
    reported as a wrongly pinned digest.
    """
    # skopeo does not accept both a tag and a digest, so drop the tag.
    ref = f"docker://{image}@sha256:{digest}"
    for attempt in range(1, INSPECT_ATTEMPTS + 1):
        if _inspect_once(ref):
            return True
        if attempt < INSPECT_ATTEMPTS:
            print(
                f"Retrying inspect of {ref} in {INSPECT_RETRY_WAIT_SECONDS}s (attempt {attempt}/{INSPECT_ATTEMPTS} failed)",
                file=sys.stderr,
            )
            time.sleep(INSPECT_RETRY_WAIT_SECONDS)
    return False


def _inspect_once(ref: str) -> bool:
    """Return True if a single `skopeo inspect --raw` of the reference yields a
    multi-arch manifest list/index. A multi-arch digest has a `.manifests` array
    with at least one entry; a single-arch digest has no `.manifests`.
    """
    try:
        raw = subprocess.run(
            ["skopeo", "inspect", "--raw", "--no-creds", ref],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as e:
        print(f"ERROR: unable to inspect {ref}: {e.stderr.strip() or e}", file=sys.stderr)
        return False

    try:
        manifest = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"ERROR: invalid manifest JSON for {ref}: {e}", file=sys.stderr)
        return False

    manifests = manifest.get("manifests")
    if isinstance(manifests, list) and len(manifests) > 0:
        return True
    print(
        f"ERROR: {ref} resolved to a manifest without a manifests list: "
        f"mediaType={manifest.get('mediaType')} schemaVersion={manifest.get('schemaVersion')} keys={sorted(manifest.keys())}",
        file=sys.stderr,
    )
    return False


def _extract_refs_from_text(text: str) -> set[tuple[str, str, str]]:
    """Extract (image_name, tag, digest) tuples from arbitrary text."""
    return set(IMAGE_REF_RE.findall(text))


def _refs_from_dockerfile(path: Path) -> set[tuple[str, str, str]]:
    """Parse a Dockerfile and resolve any variable references in FROM images."""
    # Set image_sha256 for this specific Dockerfile before resolving variables
    image_sha256 = _image_sha256_for(str(path))
    if image_sha256:
        os.environ["image_sha256"] = image_sha256
    elif "image_sha256" in os.environ:
        del os.environ["image_sha256"]

    refs: set[tuple[str, str, str]] = set()
    parser = DockerfileParser(str(path))
    for instruction in parser.structure:
        if instruction["instruction"] != "FROM":
            continue
        value = instruction["value"]
        # Drop stage aliases: "image:tag AS stage"
        value = re.sub(r"\s+AS\s+\S+$", "", value, flags=re.IGNORECASE)
        # Resolve $var / ${var} references using environment variables
        resolved = os.path.expandvars(value)
        refs.update(_extract_refs_from_text(resolved))
    return refs


def _refs_from_plain_file(path: Path) -> set[tuple[str, str, str]]:
    """Scan a non-Dockerfile for digest-pinned image references."""
    refs: set[tuple[str, str, str]] = set()
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            if line.lstrip().startswith("#"):
                continue
            refs.update(_extract_refs_from_text(line))
    return refs


def _tracked_dockerfiles() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "--", "**/Dockerfile"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


def _tracked_files_with_digest_refs() -> list[str]:
    result = subprocess.run(
        ["git", "grep", "-l", "-E", r"@sha256:[a-f0-9]{64}", "--", ":!**/Dockerfile"],
        check=False,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


def _file_is_ignored(file: str) -> bool:
    for pattern in IGNORED_FILE_PATTERNS:
        if pattern.search(file):
            return True
    return False


if __name__ == "__main__":
    sys.exit(main())
