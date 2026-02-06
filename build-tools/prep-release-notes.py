#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
from rich.console import Console
from rich.markdown import Markdown
from rich.table import Table
from rich.panel import Panel
from rich.prompt import Prompt
import pypandoc
import git
import shutil
import subprocess
from github import Github
import re

upcoming_notes_filename = f"{os.environ['SPLICE_ROOT']}/docs/src/release_notes_upcoming.rst"
release_notes_filename = f"{os.environ['SPLICE_ROOT']}/docs/src/release_notes.rst"
with open(f"{os.environ['SPLICE_ROOT']}/VERSION", "r") as f:
    new_version = f.read().strip()
with open(f"{os.environ['SPLICE_ROOT']}/LATEST_RELEASE", "r") as f:
    prev_version = f.read().strip()
with open(upcoming_notes_filename, "r") as f:
    release_notes = f.read()
repo = git.Repo('.')
branch_name = f"{os.getlogin()}/release-notes-{new_version}"
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

    release_notes_md = pypandoc.convert_text(release_notes, 'md', format='rst')

    log_entries = [f" * {c.summary}" for c in repo.iter_commits(f"release-line-{prev_version}..")]
    log_text = "\n".join(log_entries)

    layout_grid = Table.grid(expand=True)
    layout_grid.add_column()
    layout_grid.add_column()

    layout_grid.add_row(
        Panel(log_text, title="Git log since `main`", border_style="green"),
        Panel(Markdown(release_notes_md), title="Upcoming release notes", border_style="blue")
    )

    console.print(layout_grid)

def move_upcoming_notes():
    with open(upcoming_notes_filename, 'r') as f:
        lines_upcoming = f.readlines()

    split_index_upcoming = -1
    for i, line in enumerate(lines_upcoming):
        if line.startswith('.. release-notes:: Upcoming'):
            split_index_upcoming = i + 1
            break

    if split_index_upcoming == -1:
        print("ERROR! upcoming file missing the header")
        os.exit(1)

    upcoming_header = lines_upcoming[:split_index_upcoming]
    upcoming_content = lines_upcoming[split_index_upcoming:]

    with open(release_notes_filename, 'r') as f:
        lines_release_notes = f.readlines()

    insert_index_b = -1
    for i, line in enumerate(lines_release_notes):
        if line.startswith('.. _release_notes:'):
            insert_index_b = i + 1
            break

    if insert_index_b == -1:
        print("ERROR! release notes file missing the release notes header")
        os.exit(1)

    release_header = ".. release-notes:: " + new_version

    new_b_content = lines_release_notes[:insert_index_b] + ["\n", release_header, "\n"] + upcoming_content + lines_release_notes[insert_index_b:]

    with open(release_notes_filename, 'w') as fb:
        fb.writelines(new_b_content)

    with open(upcoming_notes_filename, 'w') as fa:
        fa.writelines(upcoming_header)

def create_branch_and_push():
    branch = repo.create_head(f"{os.getlogin()}/release-notes-{new_version}")
    repo.head.reference = branch
    repo.git.add(update=True)
    repo.index.commit(f"[static] release notes for {new_version}", skip_hooks=True)
    origin = repo.remote(name='origin')
    origin.push()

def create_pr():
    # 1. Authenticate
    # Replace 'your_token' with your actual Personal Access Token
    g = Github(os.environ['GITHUB_TOKEN'])
    github_url = re.search(r"[:/]([^/]+/[^/]+)\.git$", repo.remotes.origin.url)
    print(f"github url: ${github_url}")
    github_repo = g.get_repo(github_url)

    # 3. Create the Pull Request
    # title: The name of your PR
    # body: The description/markdown content
    # base: The branch you are merging INTO (e.g., 'main')
    # head: The branch you are merging FROM (e.g., 'feature-branch')
    pr = github_repo.create_pull(
        title="Release Notes for " + new_version,
        base="main",
        head=branch_name
    )

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
            create_branch_and_push()
            create_pr()
        elif choice == "2":
            open_in_editor(upcoming_notes_filename)
            print_release_notes_and_git_log()
        else:
            console.print("[bold red]Exiting. Goodbye![/bold red]")
            break

if __name__ == "__main__":
    main()
