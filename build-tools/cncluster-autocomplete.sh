# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# shellcheck shell=bash
## A basic Bash autocomplete for cncluster. Currently only autocompletes on cncluster's subcommands.
## To install, add a line: `source <this script>` to your ~/.bashrc



__cncluster() {
    local cur
    COMPREPLY=()
    # Variable to hold the current word
    cur="${COMP_WORDS[COMP_CWORD]}"

    cmds=""
    if [ -n "${GCP_CLUSTER_BASENAME-}" ]; then
        cmds=$("$SPLICE_ROOT"/build-tools/cncluster _autocomplete)
    fi

    # Generate possible matches and store them in the
    # array variable COMPREPLY
    # shellcheck disable=SC2086 disable=SC2207
    COMPREPLY=($(compgen -W "$cmds" $cur))
}

complete -F __cncluster cncluster
