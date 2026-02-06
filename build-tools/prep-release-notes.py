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

upcoming_notes_filename = f"{os.environ['SPLICE_ROOT']}/docs/src/release_notes_upcoming.rst"
# with open(f"{os.environ['SPLICE_ROOT']}/VERSION", "r") as f:
#     new_version = f.read().strip()
with open(f"{os.environ['SPLICE_ROOT']}/LATEST_RELEASE", "r") as f:
    prev_version = f.read().strip()
with open(upcoming_notes_filename, "r") as f:
    release_notes = f.read()
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


    repo = git.Repo(".")

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
            console.print("Coming soon...")
        elif choice == "2":
            open_in_editor(upcoming_notes_filename)
            print_release_notes_and_git_log()
        else:
            console.print("[bold red]Exiting. Goodbye![/bold red]")
            break

if __name__ == "__main__":
    main()
