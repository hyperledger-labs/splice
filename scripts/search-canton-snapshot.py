#!/usr/bin/env python3

"""
Search for a Canton snapshot by git SHA across all Canton Docker images.

Queries skopeo for tags of canton-participant, canton-base, canton-mediator,
and canton-sequencer, then finds tags whose embedded git SHA prefix matches
the given CANTON_SHA argument.
"""

import argparse
import json
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

REGISTRY = "europe-docker.pkg.dev/da-images/public-all/docker"
IMAGES = [
    "canton-participant",
    "canton-base",
    "canton-mediator",
    "canton-sequencer",
]


def parse_args():
    parser = argparse.ArgumentParser(
        description="Search for a Canton snapshot tag by git SHA."
    )
    parser.add_argument(
        "canton_sha",
        metavar="CANTON_SHA",
        help="Git SHA (any length prefix) to search for in Canton Docker image tags.",
    )
    parser.add_argument(
        "--allow-multiple",
        action="store_true",
        default=False,
        help="Allow more than one matching tag without failing.",
    )
    return parser.parse_args()


def fetch_tags(image: str) -> list[str]:
    """Run skopeo list-tags for the given image and return the Tags list."""
    repo = f"docker://{REGISTRY}/{image}"
    result = subprocess.run(
        [
            "skopeo",
            "list-tags",
            "--override-os",
            "linux",
            "--override-arch",
            "amd64",
            repo,
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(result.stdout)
    return data.get("Tags", [])


def find_matching_tags(tags: list[str], sha: str) -> list[str]:
    """Return tags whose embedded git SHA starts with the given sha prefix.

    Tags look like ``3.5.0-snapshot.20260407.18559.0.vb6fb1fc6`` where the
    trailing ``vXXXX`` portion is ``v`` + a git SHA prefix.
    """
    pattern = re.compile(r"\.v" + re.escape(sha) + r"[0-9a-f]*$")
    return [t for t in tags if pattern.search(t)]


def main():
    args = parse_args()
    sha = args.canton_sha.lower()

    # Validate that sha looks hex
    if not re.fullmatch(r"[0-9a-f]+", sha):
        print(f"error: CANTON_SHA must be a hex string, got: {args.canton_sha}", file=sys.stderr)
        sys.exit(1)

    # Fetch tags in parallel
    matches_by_image: dict[str, list[str]] = {}
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=len(IMAGES)) as pool:
        futures = {pool.submit(fetch_tags, img): img for img in IMAGES}
        for future in as_completed(futures):
            img = futures[future]
            try:
                tags = future.result()
            except subprocess.CalledProcessError as exc:
                errors.append(f"skopeo failed for {img}: {exc.stderr.strip()}")
                continue
            matches_by_image[img] = find_matching_tags(tags, sha)

    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        sys.exit(1)

    # Determine if all images agree on the same set of matching tags
    match_sets = {img: set(tags) for img, tags in matches_by_image.items()}
    all_sets = list(match_sets.values())
    all_agree = all(s == all_sets[0] for s in all_sets)
    common = all_sets[0] if all_agree else set()

    if all_agree and len(common) > 0:
        sorted_tags = sorted(common)
        print("Canton snapshot found", file=sys.stderr)
        for tag in sorted_tags:
            print(tag)

        if len(sorted_tags) > 1 and not args.allow_multiple:
            print(
                f"error: {len(sorted_tags)} matching tags found; "
                "pass --allow-multiple to allow this",
                file=sys.stderr,
            )
            sys.exit(1)
        sys.exit(0)
    else:
        # Report per-image details
        print("error: tags do not match across all images", file=sys.stderr)
        for img in IMAGES:
            tags = matches_by_image.get(img, [])
            if tags:
                print(f"  {img}: {', '.join(sorted(tags))}", file=sys.stderr)
            else:
                print(f"  {img}: (no matching tags)", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
