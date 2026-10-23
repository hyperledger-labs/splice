#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Given the "Reference commit" from a digital-asset/canton snapshot commit
message, list the closest Canton release tags in DACH-NY/canton that contain it,
nearest first.

The first tag, without its leading "v" (v3.6.0 -> 3.6.0), is the preferred value
for `canton_library_version`.  Only final vX.Y.Z tags are considered, not -rc
tags. A release is after the snapshot's reference commit, never before it. Skim
`git log <reference-commit>..<tag>` in the closed checkout before accepting a
tag.

RELEASE_LINE is the release line from the OSS commit subject, e.g. 3.6 for
`[release-line-3.6]`; only tags of that line are considered.

If no release contains the commit yet, the script falls back to the most recent
OSS commit whose reference commit has a release.  If its choice is satisfactory,
bump to that older commit instead. If even that finds nothing, the script exits
with status 1. In any case, the exact snapshot of the given commit is still
available through search-canton-snapshot.py.

Needs local checkouts of DACH-NY/canton (the closed-source repo), given with
--closed-canton, and of digital-asset/canton, given with --oss-canton. Their
`origin` is fetched first, so brand-new releases and snapshots are found.

Example, to check the fallback for a commit not yet released on
release-line-3.6:

  scripts/find-canton-release.py \\
      --closed-canton ~/canton-closed --oss-canton ~/canton-oss \\
      $(git -C ~/canton-closed rev-parse origin/release-line-3.6) 3.6

"""

import argparse
import re
import sys

import git

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
        "--oss-canton",
        metavar="PATH",
        required=True,
        help="Local checkout of the digital-asset/canton repo, to fall back to an older "
        "snapshot when REFERENCE_COMMIT has no release yet.",
    )
    parser.add_argument(
        "-n", "--max-results", type=int, default=5, help="Number of tags to print."
    )
    return parser.parse_args()


def open_repo(path: str, option: str) -> git.Repo:
    """Open the checkout at `path`, after fetching its origin."""
    try:
        repo = git.Repo(path)
    except (git.NoSuchPathError, git.InvalidGitRepositoryError):
        sys.exit(f"error: {option} {path!r} is not a git checkout")
    repo.remotes.origin.fetch(tags=True)
    return repo


def print_table(rows: list[tuple[str, ...]]):
    widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]))]
    for row in rows:
        print("  ".join(cell.ljust(width) for cell, width in zip(row, widths)).rstrip())


def main():
    args = parse_args()
    closed = open_repo(args.closed_canton, "--closed-canton")
    oss = open_repo(args.oss_canton, "--oss-canton")

    # Only final releases (v3.6.1), not -rc tags, and only for the given release line.
    pattern = re.compile(rf"v{re.escape(args.release_line)}\.\d+")

    def resolve_closed(commit: str) -> git.Commit | None:
        if not re.fullmatch(r"[0-9a-fA-F]+", commit):
            return None
        try:
            return closed.commit(commit)
        except (git.BadName, ValueError):
            return None

    def releases_containing(ref: git.Commit) -> list[tuple[str, ...]]:
        """Table rows (tag, distance, date) of the releases containing `ref`, nearest first."""
        results = []
        for tag in closed.tags:
            # Tags that contain the reference commit, i.e. include everything in the snapshot.
            if pattern.fullmatch(tag.name) and closed.is_ancestor(ref, tag.commit):
                distance = sum(1 for _ in closed.iter_commits(f"{ref.hexsha}..{tag.commit.hexsha}"))
                results.append((distance, tag.name, tag.commit.committed_datetime.date().isoformat()))
        return [
            (name, str(distance), date)
            for distance, name, date in sorted(results)[: args.max_results]
        ]

    header = ("TAG", "COMMITS_AFTER_REF", "TAG_DATE")

    if not re.fullmatch(r"[0-9a-fA-F]+", args.reference_commit):
        sys.exit(f"error: reference commit must be a hex string, got: {args.reference_commit!r}")
    ref = resolve_closed(args.reference_commit)
    if ref is None:
        sys.exit(f"error: {args.reference_commit} is not a commit in DACH-NY/canton")

    rows = releases_containing(ref)
    if rows:
        print_table([header, *rows])
        return

    print(f"No release of line {args.release_line} contains {ref.hexsha[:10]} yet.", file=sys.stderr)
    print(
        f"The snapshot of that commit can be found with: scripts/search-canton-snapshot.py {ref.hexsha[:10]}",
        file=sys.stderr,
    )

    # Walk the OSS release line, newest first, for the first snapshot whose reference
    # commit is released.
    branch = f"origin/release-line-{args.release_line}"
    try:
        oss.commit(branch)
    except git.BadName:
        sys.exit(f"error: no branch {branch} in --oss-canton {args.oss_canton!r}")
    for snapshot in oss.iter_commits(branch):
        match = re.search(r"^Reference commit: ([0-9a-f]+)", snapshot.message, re.MULTILINE)
        older = resolve_closed(match.group(1)) if match else None
        if older is None or older == ref:
            continue
        rows = releases_containing(older)
        if rows:
            print(
                f"\nMost recent older snapshot with a release:\n"
                f"  OSS commit (digital-asset/canton): {snapshot.hexsha[:10]} {snapshot.summary}\n"
                f"  Reference commit (DACH-NY/canton): {older.hexsha[:10]}\n"
            )
            print_table([header, *rows])
            return

    print(f"No older snapshot of line {args.release_line} has a release either.", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
