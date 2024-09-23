#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -Eeo pipefail
docker-ensure-initdb.sh # provided by upstream image
source docker-entrypoint.sh # provided by upstream image

execute() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" "$@"
}

create_database_if_not_exists() {
  local db_name="$1"
  if ! execute -tc "SELECT 1 FROM pg_database WHERE datname = '$db_name'" | grep -q 1; then
    execute -c "CREATE DATABASE \"$db_name\""
    echo "Database '$db_name' created."
  else
    echo "Skipping '$db_name' creation since it already exists."
  fi
}

# allows to run setup scripts
docker_temp_server_start

# create a database for each CREATE_DATABASE_* env var
for var in $(compgen -v | grep '^CREATE_DATABASE_'); do
  create_database_if_not_exists "${!var}"
done
docker_temp_server_stop

# run the given command
exec "$@"
