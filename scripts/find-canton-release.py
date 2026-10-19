#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Given the "Reference commit" from a digital-asset/canton snapshot commit message,
list the closest Canton release tags in DACH-NY/canton that contain it, nearest first.
The first one is a candidate for `canton_library_version` as an alternative to the
snapshot version found by search-canton-snapshot.py.

Needs read access to https://github.com/DACH-NY/canton. The clone is a blobless bare
clone cached in $CANTON_CLOSED_CACHE (default: ~/.cache/canton-closed.git).
"""

import argparse
import os
import re
import subprocess
import sys

REPO_URL = "https://github.com/DACH-NY/canton.git"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Find the closest Canton release tags containing a reference commit."
    )
    parser.add_argument(
        "reference_commit",
        metavar="REFERENCE_COMMIT",
        help="'Reference commit' from the digital-asset/canton snapshot commit message.",
    )
    parser.add_argument(
        "release_line",
        metavar="RELEASE_LINE",
        nargs="?",
        help="Release line from the OSS commit subject, e.g. 3.6 for [release-line-3.6]. "
        "If omitted, tags of every line are considered.",
    )
    parser.add_argument(
        "-n", "--max-results", type=int, default=5, help="Number of tags to print."
    )
    return parser.parse_args()


def default_cache() -> str:
    cache_home = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
    return os.environ.get("CANTON_CLOSED_CACHE") or os.path.join(
        cache_home, "canton-closed.git"
    )


def main():
    args = parse_args()
    cache = default_cache()

    def git(*cmd: str, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", cache, *cmd], capture_output=True, text=True, check=check
        )

    if not os.path.isdir(cache):
        subprocess.run(
            ["git", "clone", "--bare", "--filter=blob:none", "--no-tags", REPO_URL, cache],
            check=True,
        )
    git("fetch", "--quiet", "--filter=blob:none", "origin",
        "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")

    if not re.fullmatch(r"[0-9a-fA-F]+", args.reference_commit):
        sys.exit(f"error: reference commit must be a hex string, got: {args.reference_commit!r}")
    rev = git("rev-parse", "--verify", "--quiet", f"{args.reference_commit}^{{commit}}", check=False)
    if rev.returncode != 0:
        sys.exit(f"error: {args.reference_commit} is not a commit in DACH-NY/canton")
    ref = rev.stdout.strip()

    # Only final releases (v3.6.1), not -rc tags; optionally only for one release line.
    line = re.escape(args.release_line) if args.release_line else r"\d+\.\d+"
    pattern = re.compile(rf"v{line}\.\d+")

    tags = [t for t in git("tag", "--list").stdout.split() if pattern.fullmatch(t)]
    # Tags that contain the reference commit, i.e. include everything in the snapshot.
    containing = git("tag", "--contains", ref).stdout.split()
    results = []
    for tag in sorted(set(tags) & set(containing)):
        distance = int(git("rev-list", "--count", f"{ref}..{tag}").stdout)
        date = git("log", "-1", "--format=%cs", tag).stdout.strip()
        results.append((distance, tag, date))
    results.sort()

    print("TAG\tCOMMITS_AFTER_REF\tTAG_DATE")
    for distance, tag, date in results[: args.max_results]:
        print(f"{tag}\t{distance}\t{date}")
    if not results:
        print("no release tag contains this commit yet; use the snapshot version", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
