#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
from rich.console import Console
from rich.markdown import Markdown
from rich.table import Table
from rich.panel import Panel
import pypandoc
import git

def main():
    with open(f"{os.environ['SPLICE_ROOT']}/VERSION", "r") as f:
        new_version = f.read().strip()
    with open(f"{os.environ['SPLICE_ROOT']}/LATEST_RELEASE", "r") as f:
        prev_version = f.read().strip()
    with open(f"{os.environ['SPLICE_ROOT']}/docs/src/release_notes_upcoming.rst", "r") as f:
        release_notes = f.read()

    console = Console()
    release_notes_md = pypandoc.convert_text(release_notes, 'md', format='rst')


    repo = git.Repo(".")

    log_entries = [f" * {c.summary}" for c in repo.iter_commits(f"release-line-{prev_version}..")]
    log_text = "\n".join(log_entries)

    # 1. Create a grid for layout
    layout_grid = Table.grid(expand=True)
    layout_grid.add_column()
    layout_grid.add_column()

    # 2. Add the content
    # We can even wrap them in Panels for better visual separation
    layout_grid.add_row(
        Panel(log_text, title="Column B", border_style="green"),
        Panel(Markdown(release_notes_md), title="Column A", border_style="blue")
    )

    console.print(layout_grid)

if __name__ == "__main__":
    main()
