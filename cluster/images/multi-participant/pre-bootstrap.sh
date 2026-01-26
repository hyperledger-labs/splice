#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

function write_participant_config() {
    local index
    index="$(printf %02d "$1")"

    local base_port=$(( 5000 + ( $1 * 100 ) ))

    local health_port=$(( base_port + 61 ))
    local ledger_port=$(( base_port + 1 ))
    local admin_port=$(( base_port + 2 ))
    local http_ledger_port=$(( base_port + 3))

    local user="${VALIDATOR_USERNAME_PREFIX}_${index}"

    cat <<EOF >> /app/app.conf
canton.participants.participant_$index = {
    init = {
        generate-topology-transactions-and-keys = false
        identity.type = manual
    }

    sequencer-client {
      acknowledgement-interval = 10m
      # Use a higher number of in flight batches to increase throughput
      maximum-in-flight-event-batches = 50
      enable-amplification-improvements = true
    }

    monitoring.grpc-health-server {
        address = "0.0.0.0"
        port = $health_port
    }

    storage {
        type = postgres
        config {
            dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
            properties = {
                serverName = \${CANTON_PARTICIPANT_POSTGRES_SERVER}
                portNumber = "5432"
                databaseName = "cantonnet"
                databaseName = \${?CANTON_PARTICIPANT_POSTGRES_DB}_$index
                currentSchema = \${CANTON_PARTICIPANT_POSTGRES_SCHEMA}_participant_$index
                user = "cnadmin"
                password = "cnadmin"
                user = \${?CANTON_PARTICIPANT_POSTGRES_USER}
                password = \${?CANTON_PARTICIPANT_POSTGRES_PASSWORD}
                tcpKeepAlive = true
            }
        }
        parameters {
            max-connections = 32
            migrate-and-start = yes
        }
    }

    admin-api {
        address = "0.0.0.0"
        port = $admin_port
    }

    init {
        ledger-api.max-deduplication-duration = 30s
    }

    ledger-api {
        address = "0.0.0.0"
        admin-token-config.admin-claim = true
        port = $ledger_port
        user-management-service.additional-admin-user-id = ${user}
        auth-services = [{
            type = unsafe-jwt-hmac-256
            secret = "test"

            # TODO(DACH-NY/canton-network-internal#502) Use different audiences per participant.
            target-audience = \${AUTH_TARGET_AUDIENCE}
        }]
        # We need to bump this because we run one stream per user +
        # polling for domain connections which can add up quite a bit
        # once you're around ~100 users.
        rate-limit.max-api-services-queue-size = 80000
    }

    http-ledger-api {
      address = 0.0.0.0
      port = $http_ledger_port
    }

    parameters {
        # tune the synchronisation protocols contract store cache
        caching {
            contract-store {
            maximum-size = 1000 # default 1e6
            expire-after-access = 120s # default 10 minutes
            }
        }
    }

    # TODO(DACH-NY/canton-network-node#8331) Tune cache sizes
    # from https://docs.daml.com/2.8.0/canton/usermanual/performance.html#configuration
    # tune caching configs of the ledger api server
    ledger-api {
        index-service {
            max-contract-state-cache-size = 1000 # default 1e4
            max-contract-key-state-cache-size = 1000 # default 1e4

            # The in-memory fan-out will serve the transaction streams from memory as they are finalized, rather than
            # using the database. Therefore, you should choose this buffer to be large enough such that the likeliness of
            # applications having to stream transactions from the database is low. Generally, having a 10s buffer is
            # sensible. Therefore, if you expect e.g. a throughput of 20 tx/s, then setting this number to 200 is sensible.
            # The default setting assumes 100 tx/s.
            max-transactions-in-memory-fan-out-buffer-size = 200 # default 1000
        }
        # Restrict the command submission rate (mainly for SV participants, since they are granted unlimited traffic)
        command-service {
          max-commands-in-flight = 30 # default = 256
        }
    }

    topology {
      broadcast-batch-size = 1
      validate-initial-topology-snapshot = false
    }
}
EOF

    for cfg in ${!MULTI_PARTICIPANT_ADDITIONAL_CONFIG@}; do
      # shellcheck disable=SC2001
      echo "${!cfg}" | sed "s/INDEX/$index/g" >> /app/app.conf
    done
}

nodes=${NUM_NODES:-1}
for i in $( seq 0 $(( nodes - 1 )) )
do
    write_participant_config "$i"
done
