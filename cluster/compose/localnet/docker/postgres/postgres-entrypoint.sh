#!/bin/bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -Eeo pipefail

docker-ensure-initdb.sh
source docker-entrypoint.sh

execute() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" "$@"
}

docker_temp_server_start
# create a database for each CREATE_DATABASE_* env var if it does not exist
for var in $(compgen -v | grep '^CREATE_DATABASE_'); do
  db_name="${!var}"
  execute -tc "SELECT 1 FROM pg_database WHERE datname = '$db_name'" | grep -q 1 || \
    { execute -c "CREATE DATABASE \"$db_name\"" && echo "Database '$db_name' created."; }
done

docker_temp_server_stop

# run the supplied command
exec "$@"