#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import itertools
import json
import semver
import subprocess
import sys


def parse_dar_lock(content):
    return {
        k: [v[1] for v in vs]
        for k, vs in itertools.groupby(
            [
                line.split(" ")[:2]
                for line in content.splitlines()
                if "-test" not in line
            ],
            key=lambda x: x[0],
        )
    }


def filter_versions(k, vs, base):
    base_versions = base.get(k, [])
    return [v for v in vs if v not in base_versions]


# Given a splice version, output the latest Daml package versions from base-version to package-config-file
# and output a list of scalatest tag exclusions to tags-file that exclude all tests for package versions
# that are in HEAD but not in base-version.
def main():
    if len(sys.argv) != 4:
        print(
            "Usage: initial-package-config.py base-version package-config-file tags-file"
        )
        sys.exit(1)
    version = sys.argv[1]
    package_config_file = sys.argv[2]
    tags_file = sys.argv[3]

    process = subprocess.run(
        ["git", "show", f"refs/remotes/origin/release-line-{version}:daml/dars.lock"],
        capture_output=True,
        text=True,
    )
    if process.returncode != 0:
        print("Failed to read dars.lock file")
        print(process.stdout)
        print(process.stderr)
    base_dar_lock = parse_dar_lock(process.stdout)
    base_versions = {
        k: max(vs, key=semver.Version.parse) for k, vs in base_dar_lock.items()
    }
    with open(package_config_file, "w") as f:
        f.write(json.dumps(base_versions))
    with open("daml/dars.lock", "r") as f:
        dar_lock = parse_dar_lock(f.read())
    new_versions = {
        k: filter_versions(k, vs, base_dar_lock) for k, vs in dar_lock.items()
    }
    tag_exclusions = [to_tag_name(k, v) for k, vs in new_versions.items() for v in vs]
    with open(tags_file, "w") as f:
        f.write(
            f"-l org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck "
        )
        for tag in tag_exclusions:
            f.write(f"-l {tag} ")


def to_tag_name(k, v):
    pkg = "".join([part.capitalize() for part in k.split("-")])
    return f"org.lfdecentralizedtrust.splice.util.scalatesttags.{pkg}_{v.replace(".", "_")}"


if __name__ == "__main__":
    main()
