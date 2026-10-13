#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import getpass
import os
import sys
from rich.console import Console
from rich.markdown import Markdown
from rich.table import Table
from rich.panel import Panel
from rich.prompt import Prompt
import git
import shutil
import subprocess
from github import Github
import re

patch_heading_re = re.compile(r"^## Upcoming for next patch release:[ \t]*$", re.MULTILINE)
minor_heading_re = re.compile(r"^## Upcoming for next minor release\b.*$", re.MULTILINE)
mdx_comment_re = re.compile(r"^\{/\*.*?\*/\}\n?", re.MULTILINE | re.DOTALL)

upcoming_notes_filename = f"{os.environ['SPLICE_ROOT']}/release-notes/release_notes_upcoming.mdx"

parser = argparse.ArgumentParser(
    description="Moves the upcoming patch release notes into the cf-docs repo and resets them in this repo."
)
parser.add_argument(
    "--cf-docs",
    required=True,
    metavar="DIR",
    help="Path to a local clone of https://github.com/canton-network/cf-docs (on an up-to-date main)",
)
args = parser.parse_args()
cf_docs_dir = os.path.abspath(args.cf_docs)
release_notes_filename = f"{cf_docs_dir}/docs-main/global-synchronizer/release-notes/splice.mdx"
if not os.path.isfile(release_notes_filename):
    sys.exit(f"Not found: {release_notes_filename}; is --cf-docs the root of a cf-docs clone?")
with open(f"{os.environ['SPLICE_ROOT']}/VERSION", "r") as f:
    new_version = f.read().strip()
with open(f"{os.environ['SPLICE_ROOT']}/LATEST_RELEASE", "r") as f:
    prev_version = f.read().strip()
with open(upcoming_notes_filename, "r") as f:
    release_notes = f.read()
repo = git.Repo('.')
cf_docs_repo = git.Repo(cf_docs_dir)
branch_name = f"{getpass.getuser()}/release-notes-{new_version}"
console = Console()

def open_in_editor(filepath):
    sensible_editor = shutil.which("sensible-editor")
    if sensible_editor:
        editor_cmd = [sensible_editor, filepath]
    else:
        editor_env = os.environ.get("EDITOR")
        if editor_env:
            editor_cmd = [editor_env, filepath]
        else:
            editor_cmd = ["nano", filepath]

    try:
        subprocess.run(editor_cmd, check=True)
    except FileNotFoundError:
        print(f"Error: Could not find an editor to open {filepath}")

def print_release_notes_and_git_log():

    release_notes_md = split_upcoming(release_notes)[1]

    release_branch = f"release-line-{prev_version}"
    origin_ref = f"origin/{release_branch}"

    # Verify local branch (if it exists) matches origin
    if release_branch in repo.refs:
        if origin_ref not in repo.refs:
            raise RuntimeError(
                f"Local branch '{release_branch}' exists but remote tracking branch '{origin_ref}' does not"
            )
        if repo.refs[release_branch].commit != repo.refs[origin_ref].commit:
            raise RuntimeError(
                f"Local branch '{release_branch}' ({repo.refs[release_branch].commit}) "
                f"differs from '{origin_ref}' ({repo.refs[origin_ref].commit}). "
                f"Please reconcile them before proceeding."
            )

    log_entries = [f" * {c.summary}" for c in repo.iter_commits(f"{origin_ref}..")]
    log_text = "\n".join(log_entries)

    layout_grid = Table.grid(expand=True)
    layout_grid.add_column()
    layout_grid.add_column()

    layout_grid.add_row(
        Panel(log_text, title="Git log since `main`", border_style="green"),
        Panel(Markdown(release_notes_md), title="Upcoming release notes", border_style="blue")
    )

    console.print(layout_grid)

def split_upcoming(mdx):
    """Splits the upcoming notes into (before, patch notes, after); the patch notes exclude the heading and mdx comments."""
    patch = patch_heading_re.search(mdx)
    minor = minor_heading_re.search(mdx, patch.end()) if patch else None
    if patch is None or minor is None:
        raise RuntimeError("upcoming file missing the patch or minor release headings")
    start, end = patch.end(), minor.start()
    return mdx[:start], mdx_comment_re.sub("", mdx[start:end]).strip("\n"), mdx[end:]

def move_upcoming_notes():
    before, patch_notes, after = split_upcoming(release_notes)

    with open(release_notes_filename, 'r') as f:
        cf_docs_notes = f.read()

    # new releases go above the latest one, i.e. before the first version heading
    first_version = re.search(r"^## ", cf_docs_notes, re.MULTILINE)
    if first_version is None:
        sys.exit(f"{release_notes_filename} has no version headings")
    insert_at = first_version.start()
    with open(release_notes_filename, 'w') as f:
        f.write(cf_docs_notes[:insert_at] + f"## {new_version}\n\n{patch_notes}\n\n" + cf_docs_notes[insert_at:])

    # leave the (now empty) patch section in place for the next release
    with open(upcoming_notes_filename, 'w') as f:
        f.write(before + "\n\n{/* Add all release notes in this section */}\n\n" + after)

def commit_branch_and_push(r, summary):
    branch = r.create_head(branch_name)
    r.head.reference = branch
    r.git.add(update=True)
    config_reader = r.config_reader()
    email = config_reader.get_value("user", "email")
    username = config_reader.get_value("user", "name")
    msg = f"""{summary}

Signed-off-by: {username} <{email}>
"""
    r.index.commit(msg, skip_hooks=True)
    r.remote(name='origin').push(f"{branch_name}:{branch_name}")

def create_pr(r, title):
    g = Github(os.environ['GITHUB_TOKEN'])
    github_repo_name = re.search(r"[:/]([^/]+/[^/]+?)(?:\.git)?$", r.remotes.origin.url).group(1)
    github_repo = g.get_repo(github_repo_name)

    pr = github_repo.create_pull(title=title, base="main", head=branch_name)

    print(f"Pull Request created successfully: {pr.html_url}")

def main():

    print_release_notes_and_git_log()

    while True:
        actions = '''
    1. All good! Create the PR (coming soon...)
    2. Edit release notes
    3. Cancel
    '''

        console.print(Panel(actions, title="Actions"))
        choice = Prompt.ask(
            "Please select an option",
            choices=["1", "2", "3"],
            default="3"
        )
        if choice == "1":
            move_upcoming_notes()
            commit_branch_and_push(cf_docs_repo, f"Splice release notes for {new_version}")
            create_pr(cf_docs_repo, f"Splice release notes for {new_version}")
            commit_branch_and_push(repo, f"[static] Reset upcoming release notes after {new_version}")
            create_pr(repo, f"Reset upcoming release notes after {new_version}")
            break
        elif choice == "2":
            open_in_editor(upcoming_notes_filename)
            print_release_notes_and_git_log()
            break
        else:
            console.print("[bold red]Exiting. Goodbye![/bold red]")
            break

if __name__ == "__main__":
    main()
