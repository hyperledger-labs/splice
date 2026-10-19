#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Given the "Reference commit" from a digital-asset/canton snapshot commit message,
list the closest Canton release tags in DACH-NY/canton that contain it, nearest first.

The first tag, without its leading "v" (v3.6.0 -> 3.6.0), is the preferred value for
`canton_library_version`; it is a published, stable release, unlike a daily snapshot.
Only final vX.Y.Z tags are considered, not -rc tags. A release is usually a handful to a
few dozen commits after the snapshot's reference commit, never before it. Skim
`git log <reference-commit>..<tag>` in the clone (see below) before accepting a tag.

RELEASE_LINE is the release line from the OSS commit subject, e.g. 3.6 for
`[release-line-3.6]`; only tags of that line are considered.

Exits with status 1 if no release contains the commit yet (typical for snapshots of
`main`); use search-canton-snapshot.py then.

Needs a local checkout of https://github.com/DACH-NY/canton (the closed-source repo),
given with --closed-canton. Its `origin` is fetched first, so brand new releases are found.
"""

import argparse
import os
import re
import subprocess
import sys


def parse_args():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "reference_commit",
        metavar="REFERENCE_COMMIT",
        help="'Reference commit' from the digital-asset/canton snapshot commit message.",
    )
    parser.add_argument(
        "release_line",
        metavar="RELEASE_LINE",
        help="Release line from the OSS commit subject, e.g. 3.6 for [release-line-3.6].",
    )
    parser.add_argument(
        "--closed-canton",
        metavar="PATH",
        required=True,
        help="Local checkout of the closed-source DACH-NY/canton repo.",
    )
    parser.add_argument(
        "-n", "--max-results", type=int, default=5, help="Number of tags to print."
    )
    return parser.parse_args()


def main():
    args = parse_args()
    repo = args.closed_canton

    def git(*cmd: str, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", repo, *cmd], capture_output=True, text=True, check=check
        )

    if not os.path.isdir(repo) or git("rev-parse", "--git-dir", check=False).returncode != 0:
        sys.exit(f"error: --closed-canton {repo!r} is not a git checkout")
    # So that releases newer than the checkout are found.
    git("fetch", "--quiet", "--tags", "origin")

    if not re.fullmatch(r"[0-9a-fA-F]+", args.reference_commit):
        sys.exit(f"error: reference commit must be a hex string, got: {args.reference_commit!r}")
    rev = git("rev-parse", "--verify", "--quiet", f"{args.reference_commit}^{{commit}}", check=False)
    if rev.returncode != 0:
        sys.exit(f"error: {args.reference_commit} is not a commit in DACH-NY/canton")
    ref = rev.stdout.strip()

    # Only final releases (v3.6.1), not -rc tags, and only for the given release line.
    pattern = re.compile(rf"v{re.escape(args.release_line)}\.\d+")

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
