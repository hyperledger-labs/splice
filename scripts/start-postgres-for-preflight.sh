#!/usr/bin/env bash
set -eou pipefail

scripts/postgres.sh docker start
scripts/postgres.sh docker createdb "self_hosted_participant"
