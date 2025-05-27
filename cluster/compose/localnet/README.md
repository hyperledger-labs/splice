# Splice Localnet

Localnet provides a straightforward topology comprising three participants, three validators, a PostgreSQL database, and several web applications (wallet, sv, scan) behind an NGINX gateway. Each validator plays a distinct role within the Splice ecosystem:

- **app-provider**
- **app-user**
- **sv**

Designed primarily for development and testing, Localnet is not intended for production use.

## Setup

Before starting, ensure you have configured the following environment variables:

- **IMAGE_TAG**: Specifies the version of Splice to be used in Localnet.
- **LOCALNET_DIR**: Specifies the path to the Localnet directory.

You can enable or disable any of the three validators using Docker Compose profiles (e.g., `--profile app-provider`) alongside the corresponding environment variables (e.g., `APP_PROVIDER_PROFILE=on/off`). By default, all three validators are active.

Additional environment variables include:

- **LOCALNET_ENV_DIR**: Overrides the default environment file directory. The default is `$LOCALNET_DIR/env`.
- **LOCALNET_DIR/compose.env**: Contains Docker Compose configuration variables.
- **LOCALNET_ENV_DIR/common.env**: Shared environment variables across Docker Compose and container configurations. It sets default ports, DB credentials, and Splice UI configurations.

Depending on the desired environment **ENV** (local or dev), either `LOCALNET_ENV_DIR/dev.env` or `LOCALNET_ENV_DIR/local.env` will be applied to both Docker Compose and Splice containers, with `local` set as the default.

Resource constraints for containers can be configured via:
- **LOCALNET_DIR/resource-constraints.yaml**


## Exposed Ports

- The default database port is **DB_PORT=5432**.

Other ports follow a specific pattern based on the validator:
- `4${PORT}`: Super Validator (sv) port
- `3${PORT}`: App Provider port
- `2${PORT}`: App User port

These patterns apply to the following ports:
- **PARTICIPANT_LEDGER_API_PORT**: 5001
- **PARTICIPANT_ADMIN_API_PORT**: 5002
- **PARTICIPANT_JSON_API_PORT**: 7575
- **VALIDATOR_ADMIN_API_PORT**: 5003
- **CANTON_HTTP_HEALTHCHECK_PORT**: 7000
- **CANTON_GRPC_HEALTHCHECK_PORT**: 5061


UI Ports are defined as follows:
- **APP_USER_UI_PORT**: 2000
- **APP_PROVIDER_UI_PORT**: 3000
- **SV_UI_PORT**: 4000

## Database

Localnet uses a single PostgreSQL database for all components. Database configurations are sourced from `LOCALNET_ENV_DIR/postgres.env`.

## Application UIs

- **App User Wallet UI**
    - **URL**: [http://wallet.localhost:2000](http://wallet.localhost:2000)
    - **Description**: Interface for managing user wallets.

- **App Provider Wallet UI**
    - **URL**: [http://wallet.localhost:3000](http://wallet.localhost:3000)
    - **Description**: Interface for managing user wallets.

- **Super Validator Web UI**
    - **URL**: [http://sv.localhost:4000](http://sv.localhost:4000)
    - **Description**: Interface for super validator functionalities.

- **Scan Web UI**
    - **URL**: [http://scan.localhost:4000](http://scan.localhost:4000)
    - **Description**: Interface to monitor transactions.

  > **Note**: `LocalNet` rounds may take up to 6 rounds (equivalent to one hour) to display in the scan UI.

All the Super Validator UIs are accessible via a gateway at [http://localhost:4000](http://localhost:4000).

The `*.localhost` domains will resolve to your local host IP `127.0.0.1`.

## Default Wallet Users

- **App User**: app-user
- **App Provider**: app-provider
- **SV**: sv


## Run in localnet
### start
```
docker compose --env-file $LOCALNET_DIR/compose.env \
               --env-file $LOCALNET_DIR/env/common.env \
               --env-file $LOCALNET_DIR/env/local.env \
               -f $LOCALNET_DIR/compose.yaml \
               -f $LOCALNET_DIR/resource-constraints.yaml \
               --profile sv \
               --profile app-provider \
               --profile app-user up -d
```
### stop
```
docker compose --env-file $LOCALNET_DIR/compose.env \
               --env-file $LOCALNET_DIR/env/common.env \
               --env-file $LOCALNET_DIR/env/local.env \
               -f $LOCALNET_DIR/compose.yaml \
               -f $LOCALNET_DIR/resource-constraints.yaml \
               --profile sv \
               --profile app-provider \
               --profile app-user down -v
```

### console
```
docker compose --env-file $LOCALNET_DIR/compose.env \
               --env-file $LOCALNET_DIR/env/common.env \
               --env-file $LOCALNET_DIR/env/local.env \
               -f $LOCALNET_DIR/compose.yaml \
               -f $LOCALNET_DIR/resource-constraints.yaml \
               run --rm console
```

## Run in devnet
```
docker compose --env-file ${LOCALNET_DIR}/compose.env \
               --env-file ${LOCALNET_DIR}/env/common.env \
               --env-file ${LOCALNET_DIR}/env/dev.env \
               -f ${LOCALNET_DIR}/compose.yaml \
               -f ${LOCALNET_DIR}/resource-constraints.yaml \
               --profile app-provider \
               --profile app-user up -d
```
