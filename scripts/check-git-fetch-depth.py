#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Fails on git fetches that download full history (#7831), i.e. in CI workflows and scripts:
# - `fetch-depth: 0` in .github
# - `git fetch` / `git clone` without --depth, --deepen, --shallow-* or --filter
# A finding is allowed if the line or the line above contains `# full-history-ok: <reason>`.
# Calls through wrapper functions are not checked; annotate the wrapper's own `git fetch`.

import os
import re
import sys

import git

PATHSPECS = [
    ".github",
    "scripts",
    "build-tools",
    "*.sh",
    "*.mk",
    "Makefile",
    "**/Makefile",
    ":!canton/*",
    ":!*.md",
    ":!*.mdx",
    ":!*.rst",
    ":!*.scala",
    ":!scripts/check-git-fetch-depth.py",
]

ALLOW_MARKER = "# full-history-ok:"
COMMENT_RE = re.compile(r"^\s*#")
FETCH_DEPTH_0_RE = re.compile(r"fetch-depth:\s*0\b")
FETCH_OR_CLONE_RE = re.compile(r"\bgit\s+(fetch|clone)\b(?![-_])")
BOUNDED_RE = re.compile(r"--(depth|deepen|shallow-|filter)")


def check_file(path):
    findings = []
    with open(path, encoding="utf-8", errors="replace") as f:
        prev = ""
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            allowed = ALLOW_MARKER in line or ALLOW_MARKER in prev
            prev = line
            if allowed or COMMENT_RE.match(line):
                continue
            if path.startswith(".github/") and FETCH_DEPTH_0_RE.search(line):
                findings.append(f"{path}:{lineno}: fetch-depth: 0: {line}")
            elif FETCH_OR_CLONE_RE.search(line) and not BOUNDED_RE.search(line):
                findings.append(f"{path}:{lineno}: unbounded git fetch/clone: {line}")
    return findings


def main():
    repo = git.Repo(search_parent_directories=True)
    os.chdir(repo.working_tree_dir)
    paths = repo.git.ls_files("--", *PATHSPECS).splitlines()
    findings = [
        finding
        for path in paths
        # skips submodules
        if os.path.isfile(path)
        for finding in check_file(path)
    ]

    if findings:
        print("ERROR: found git fetches that download full history:")
        print("\n".join(findings))
        print(
            "Bound them with --depth, --deepen, --shallow-* or --filter=blob:none, or annotate the line\n"
            f"or the line above with '{ALLOW_MARKER} <reason>'. See \"Git history in CI\" in TESTING.md."
        )
        sys.exit(1)
    else:
        print("No unbounded git fetches found.")


if __name__ == "__main__":
    main()
