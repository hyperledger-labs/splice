#!/usr/bin/env bash

# Postgres settings
DOCKER_POSTGRES_CONTAINER_NAME="postgres-for-canton-coin"
LOCAL_POSTGRES_DATA_DIRECTORY="$REPO_ROOT/temp/postgres"
LOCAL_POSTGRES_LOG_FILE="$REPO_ROOT/log/postgres.log"

# Logging - disabled by default
# LOG_FILE="$REPO_ROOT/log/postgres-startup-script.log"
# mkdir -p "$LOG_FILE"
LOG_FILE="/dev/null"

# User used by Canton
POSTGRES_CANTON_USER=canton
POSTGRES_CANTON_PASSWORD=supersafe


function docker_start() {
  echo "Starting Postgres docker container"
  # From https://docs.daml.com/canton/usermanual/docker.html#running-postgres-in-docker
  docker run -d --rm \
    --name "$DOCKER_POSTGRES_CONTAINER_NAME" \
    -e POSTGRES_USER="$POSTGRES_USER" \
    -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -p 5432:5432 \
    postgres:11 \
    postgres -c max_connections=500 \
    >> "$LOG_FILE"
}

function pgctl_start() {
  echo "Creating local Postgres instance"
  initdb -D "$LOCAL_POSTGRES_DATA_DIRECTORY" -U "$POSTGRES_USER" >> "$LOG_FILE"

  echo "Starting local Postgres instance"
  pg_ctl -D "$LOCAL_POSTGRES_DATA_DIRECTORY" -l "$LOCAL_POSTGRES_LOG_FILE" start >> "$LOG_FILE"
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
  until docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" $DOCKER_POSTGRES_CONTAINER_NAME psql -U "$POSTGRES_USER" -c "select 1" >> "$LOG_FILE" 2>&1 ; do
    echo "Waiting for PostgreSQL to start"
    sleep 1
  done
}

function psql_wait() {
  export PGPASSWORD="$POSTGRES_PASSWORD"
  until psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -c "select 1" >> "$LOG_FILE" 2>&1 ; do
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
  psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -w \
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

function psql_createdb() {
  echo "Creating database $1"
  export PGPASSWORD="$POSTGRES_PASSWORD"
  psql -U "$POSTGRES_USER" -h "$POSTGRES_HOST" -w \
    -c "CREATE DATABASE $1;" \
    -c "GRANT ALL PRIVILEGES ON DATABASE $1 TO $POSTGRES_CANTON_USER;" \
    >> "$LOG_FILE"
}

case "$1_$2" in
    docker_start)
        docker_start
        docker_wait
        docker_create_user

        # These environment variables are used by the Canton configuration file mixin for Postgres
        export CANTON_DB_USER=$POSTGRES_CANTON_USER
        export CANTON_DB_PASSWORD=$POSTGRES_CANTON_PASSWORD
    ;;
    local_start)
        pgctl_start
        psql_wait
        psql_create_user

        # These environment variables are used by the Canton configuration file mixin for Postgres
        export CANTON_DB_USER=$POSTGRES_CANTON_USER
        export CANTON_DB_PASSWORD=$POSTGRES_CANTON_PASSWORD
    ;;
    external_start)
        psql_wait
        psql_create_user

        # These environment variables are used by the Canton configuration file mixin for Postgres
        export CANTON_DB_USER=$POSTGRES_CANTON_USER
        export CANTON_DB_PASSWORD=$POSTGRES_CANTON_PASSWORD
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
    docker_stop)
        docker_stop
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
        echo "    createdb <name>  creates a new database with the given name"
        echo "    stop             removes the postgres instance along with all data"
    ;;
esac




