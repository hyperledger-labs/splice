#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

DOCKER_POSTGRES_IMAGE_NAME="postgres:17"

# Postgres settings
DOCKER_POSTGRES_CONTAINER_NAME="postgres-for-splice-node"
LOCAL_POSTGRES_DATA_DIRECTORY="$SPLICE_ROOT/temp/postgres"
LOCAL_POSTGRES_LOG_FILE="$LOGS_PATH/postgres.log"
: "${POSTGRES_PORT:=5432}"

# Logging - disabled by default
# LOG_FILE="$SPLICE_ROOT/log/postgres-startup-script.log"
# mkdir -p "$LOG_FILE"
LOG_FILE="/dev/null"

# User used by Canton
POSTGRES_CANTON_USER=canton
POSTGRES_CANTON_PASSWORD=supersafe

# This pulls the image used by the `docker run` command in the `docker_start` step.
# It is not necessary to run this command as docker automatically pulls the image if it is not present,
# however, `docker pull` is more likely to be idempotent and can safely be retried.
function docker_pull() {
  echo "Pulling Postgres docker image"
  docker pull $DOCKER_POSTGRES_IMAGE_NAME
}

function docker_start() {
  echo "Starting Postgres docker container"
  # From https://docs.daml.com/canton/usermanual/docker.html#running-postgres-in-docker
  docker run -d --rm \
    --log-driver json-file \
    --name "$DOCKER_POSTGRES_CONTAINER_NAME" \
    -e POSTGRES_USER="$POSTGRES_USER" \
    -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -p "$POSTGRES_PORT":5432 \
    $DOCKER_POSTGRES_IMAGE_NAME \
    postgres \
    -c max_connections=16000 \
    -c logging_collector=on \
    -c log_destination=csvlog \
    -c log_filename='postgresql.log' \
    >> "$LOG_FILE"
}

function pgctl_start() {
  echo "Creating local Postgres instance"
  initdb -D "$LOCAL_POSTGRES_DATA_DIRECTORY" -U "$POSTGRES_USER" >> "$LOG_FILE"

  echo "Starting local Postgres instance"
  pg_ctl -D "$LOCAL_POSTGRES_DATA_DIRECTORY" -l "$LOCAL_POSTGRES_LOG_FILE" -o "-k '' -c port=$POSTGRES_PORT -c max_connections=16000 -c shared_buffers=4GB -c effective_cache_size=6GB -c maintenance_work_mem=512MB -c checkpoint_completion_target=0.9 -c wal_buffers=16MB -c default_statistics_target=100 -c random_page_cost=1.1 -c effective_io_concurrency=200 -c work_mem=4194kB -c huge_pages=off -c min_wal_size=2GB -c max_wal_size=8GB -c max_worker_processes=4 -c max_parallel_workers_per_gather=2 -c max_parallel_workers=4 -c max_parallel_maintenance_workers=2" start >> "$LOG_FILE"
}

function docker_stop() {
  echo "Removing Postgres docker container"
  docker stop "$DOCKER_POSTGRES_CONTAINER_NAME" >> "$LOG_FILE"
}

function pgctl_stop() {
  echo "Stopping local Postgres instance"
  pg_ctl -D "$LOCAL_POSTGRES_DATA_DIRECTORY" stop >> "$LOG_FILE"

  echo "Removing local Postgres instance"
  rm -r "$LOCAL_POSTGRES_DATA_DIRECTORY" >> "$LOG_FILE"
}

function docker_wait() {
  until docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" $DOCKER_POSTGRES_CONTAINER_NAME psql -h localhost -U "$POSTGRES_USER" -c "select 1" >> "$LOG_FILE" 2>&1 ; do
    echo "Waiting for PostgreSQL to start"
    sleep 1
  done
}

function psql_wait() {
  export PGPASSWORD="$POSTGRES_PASSWORD"
  # shellcheck disable=SC2153
  until psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -c "select 1" >> "$LOG_FILE" 2>&1 ; do
    echo "Waiting for PostgreSQL to start"
    sleep 1
  done
}

function docker_create_user() {
  echo "Creating database user for Canton"
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" $DOCKER_POSTGRES_CONTAINER_NAME \
    psql -U "$POSTGRES_USER" \
    -c "create user $POSTGRES_CANTON_USER with encrypted password '$POSTGRES_CANTON_PASSWORD';" \
    >> "$LOG_FILE"
}

function psql_create_user() {
  echo "Creating database user for Canton"
  export PGPASSWORD="$POSTGRES_PASSWORD"
  psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -w \
    -c "create user $POSTGRES_CANTON_USER with encrypted password '$POSTGRES_CANTON_PASSWORD';" \
    >> "$LOG_FILE"
}

function docker_createdb() {
  echo "Creating database $1"
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" $DOCKER_POSTGRES_CONTAINER_NAME \
    psql -U "$POSTGRES_USER" \
    -c "CREATE DATABASE $1;" \
    -c "GRANT ALL PRIVILEGES ON DATABASE $1 TO $POSTGRES_CANTON_USER;" \
    >> "$LOG_FILE"
}

function docker_psql_shell () {
  echo "Starting PSQL shell for dockerized Postgres"
  docker exec -it -e PGPASSWORD="$POSTGRES_PASSWORD" $DOCKER_POSTGRES_CONTAINER_NAME \
    psql -U "$POSTGRES_USER"
}

function psql_createdb() {
  echo "Creating database $1"
  export PGPASSWORD="$POSTGRES_PASSWORD"
  psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -w \
    -c "CREATE DATABASE $1;" \
    -c "GRANT ALL PRIVILEGES ON DATABASE $1 TO $POSTGRES_CANTON_USER;" \
    >> "$LOG_FILE"
}

function psql_dropdb() {
  export PGPASSWORD="$POSTGRES_PASSWORD"

  # see: https://stackoverflow.com/questions/17449420/postgresql-unable-to-drop-database-because-of-some-auto-connections-to-db/68982312
  echo "Terminating connections to database $1"
  psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -w \
    -c "SELECT pid, pg_terminate_backend(pid)
        FROM pg_stat_activity
        WHERE datname = '$1' AND pid <> pg_backend_pid();" \
    >> "$LOG_FILE"

  echo "Dropping database $1"
  psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -w \
    -c "DROP DATABASE $1;" \
    >> "$LOG_FILE"
}

case "$1_$2" in
    docker_pull)
        docker_pull
    ;;
    docker_start)
        docker_start
        docker_wait
        docker_create_user
    ;;
    docker_wait)
        docker_wait
    ;;
    local_start)
        pgctl_start
        psql_wait
        psql_create_user
    ;;
    local_wait)
        psql_wait
    ;;
    external_start)
        psql_wait
        psql_create_user
    ;;
    external_wait)
        psql_wait
    ;;
    docker_createdb)
        docker_createdb "$3"
    ;;
    local_createdb)
        psql_createdb "$3"
    ;;
    external_createdb)
        psql_createdb "$3"
    ;;
    docker_dropdb)
        psql_dropdb "$3"
    ;;
    local_dropdb)
        psql_dropdb "$3"
    ;;
    external_dropdb)
        psql_dropdb "$3"
    ;;
    docker_stop)
        docker_stop
    ;;
    docker_psql)
        docker_psql_shell
    ;;
    local_stop)
        pgctl_stop
    ;;
    external_stop)
        echo "Not stopping Postgres, the CI will kill it at the end of the job"
    ;;
    *)
        echo "Usage: ./postgres.sh MODE COMMAND"
        echo ""
        echo "  MODE"
        echo "    docker           postgres is running in docker"
        echo "                     this scripts manages the container using docker"
        echo "    local            postgres is running locally"
        echo "                     this scripts manages the instance using initdb and pg_ctl"
        echo "    external         the postgres process is managed externally"
        echo "                     this script assumes POSTGRES_HOST, POSTGRES_USER, POSTGRES_PASSWORD are set"
        echo ""
        echo "  COMMAND"
        echo "    start            makes sure the postgres instance is running"
        echo "    wait             waits for a postgres instance to be ready"
        echo "    createdb <name>  creates a new database with the given name"
        echo "    dropdb   <name>  drops an existing database with the given name"
        echo "    stop             removes the postgres instance along with all data"
        echo "    pull             (docker mode only) makes sure the postgres image is available locally"
        echo "    psql             (docker mode only) starts an interactive psql session for the dockerized postgres"
    ;;
esac
