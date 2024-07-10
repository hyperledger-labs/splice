#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Format and lint typescript and javascript files.

Run npm format:fix and lint:fix on all files given on the command line
given by pre-commit.

Note, this script requires the files to be part of a package, and
the package needs to define a `format:fix` and `lint:fix` command.

Failing to do so, does not lead to an error, the files are just not
processed and a warning is issued.
"""

import json
import sys
import os
import shlex
import subprocess
from collections import defaultdict

def get_package_dir(path, package_file = 'package.json'):
    """walk up the directory tree until we find a package.json file"""

    directory = os.path.dirname(path)
    package_json = os.path.join(directory, package_file)

    if os.path.exists(package_json):
        return directory
    elif not directory:
        return None
    else:
        return get_package_dir(directory)

def strip_non_option_arguments(command):
    split_command = shlex.split(command)
    try:
        dash_dash = split_command.index('--')
        return split_command[:dash_dash + 1]
    except ValueError:
        # we require `--` to indicate the end of options
        # all arguments after this marker are chopped off to avoid
        # formatting all files every time.
        raise ValueError(f'The command `{command}` does not include a `--` marker to end the option arguments')

def get_command(package_json, scripts, cmd_name):
    command = scripts.get(cmd_name)

    if command:
        try:
            return strip_non_option_arguments(command)
        except ValueError as e:
            print(f'[error] `{package_json}` script entry `{cmd_name}` is invalid:\n   {e.args[0]}')
            sys.exit(1)

def npm_exec(command, files, cwd):
    if command:
        subprocess.check_call(['npm', 'exec', '--'] + command + files, cwd=cwd)


def npm_install(cwd):
    # need to find the top-level worktree directory first
    toplevel = get_package_dir(cwd, 'package-lock.json') or cwd

    subprocess.check_call(['npm', 'install'], cwd=toplevel)


def main():
    runs = defaultdict(list)

    for arg in sys.argv[1:]:
        package_dir = get_package_dir(arg)

        if package_dir:
            runs[package_dir].append(os.path.relpath(arg, start=package_dir))
        else:
            print("[warn] No package found for file:", arg, file=sys.stderr)

    ret = 0
    for package_dir, files in runs.items():
        package_json = os.path.join(package_dir, "package.json")
        scripts = json.load(open(package_json)).get('scripts', {})
        format_command = get_command(package_json, scripts, 'format:fix')
        lint_command = get_command(package_json, scripts, 'lint:fix')

        print("[info] directory:", package_dir, file=sys.stderr)

        try:
            if not os.path.isdir(os.path.join(package_dir, 'node_modules')):
                npm_install(package_dir)

            npm_exec(format_command, files, cwd=package_dir)
            npm_exec(lint_command, files, cwd=package_dir)
        except subprocess.CalledProcessError:
            ret = 1

    sys.exit(ret)

if __name__ == '__main__':
    main()
