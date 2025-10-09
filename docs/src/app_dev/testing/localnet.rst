..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _localnet:

Docker-Compose Based Deployment of a Local Network
==================================================

Localnet provides a straightforward topology comprising three participants, three validators, a PostgreSQL database, and several web applications (wallet, sv, scan) behind an NGINX gateway. Each validator plays a distinct role within the Splice ecosystem:

- **app-provider**: for the user operating their application
- **app-user**: for a user wanting to use the app from the App Provider
- **sv**: for providing the Global Synchronizer and handling AMT

Designed primarily for development and testing, Localnet is not intended for production use.

Setup
-----

Before starting, ensure you have configured the following environment variables:

- **IMAGE_TAG**: Specifies the version of Splice to be used in Localnet.
- **LOCALNET_DIR**: Specifies the path to the Localnet directory.

You can enable or disable any of the three validators using Docker Compose profiles (e.g., ``--profile app-provider``) alongside the corresponding environment variables (e.g., ``APP_PROVIDER_PROFILE=on/off``). By default, all three validators are active.

Additional environment variables include:

- **LOCALNET_ENV_DIR**: Overrides the default environment file directory. The default is ``$LOCALNET_DIR/env``.
- **LOCALNET_DIR/compose.env**: Contains Docker Compose configuration variables.
- **LOCALNET_ENV_DIR/common.env**: Shared environment variables across Docker Compose and container configurations. It sets default ports, DB credentials, and Splice UI configurations.

Resource constraints for containers can be configured via:
- **LOCALNET_DIR/resource-constraints.yaml**

Exposed Ports
-------------

The following section details the ports used by various services. The default database port is **DB_PORT=5432**.

Other ports are generated using specific patterns based on the validator:

- For the Super Validator (sv), the port is specified as ``4${PORT_SUFFIX}``.
- For the App Provider, the port is specified as ``3${PORT_SUFFIX}``.
- For the App User, the port is specified as ``2${PORT_SUFFIX}``.

These patterns apply to the following ports suffixes:

- **PARTICIPANT_LEDGER_API_PORT_SUFFIX**: 901
- **PARTICIPANT_ADMIN_API_PORT_SUFFIX**: 902
- **PARTICIPANT_JSON_API_PORT_SUFFIX**: 975
- **VALIDATOR_ADMIN_API_PORT_SUFFIX**: 903
- **CANTON_HTTP_HEALTHCHECK_PORT_SUFFIX**: 900
- **CANTON_GRPC_HEALTHCHECK_PORT_SUFFIX**: 961


UI Ports are defined as follows:

- **APP_USER_UI_PORT**: 2000
- **APP_PROVIDER_UI_PORT**: 3000
- **SV_UI_PORT**: 4000

Database
--------

Localnet uses a single PostgreSQL database for all components. Database configurations are sourced from ``LOCALNET_ENV_DIR/postgres.env``.

Application UIs
---------------

- **App User Wallet UI**

    - **URL**: `http://wallet.localhost:2000 <http://wallet.localhost:2000>`_
    - **Description**: Interface for managing user wallets.

- **App Provider Wallet UI**

    - **URL**: `http://wallet.localhost:3000 <http://wallet.localhost:3000>`_
    - **Description**: Interface for managing user wallets.

- **Super Validator Web UI**

    - **URL**: `http://sv.localhost:4000 <http://sv.localhost:4000>`_
    - **Description**: Interface for super validator functionalities.

- **Scan Web UI**

    - **URL**: `http://scan.localhost:4000 <http://scan.localhost:4000>`_
    - **Description**: Interface to monitor transactions.

    .. note::
         `LocalNet` rounds may take up to 6 rounds (equivalent to one hour) to display in the scan UI.

The ``*.localhost`` domains will resolve to your local host IP ``127.0.0.1``.

Default Wallet Users
--------------------

- **App User**: app-user
- **App Provider**: app-provider
- **SV**: sv

Swagger UI
----------

When the ``swagger-ui`` profile is enabled, the Swagger UI for the ``JSON Ledger API HTTP Endpoints`` across all running participants is available at `http://localhost:9090 <http://localhost:9090>`_.
Note: Some endpoints require a JWT token when using the **Try it out** feature. One method to obtain this token is via the Canton Console. Start the Canton Console `make canton-console` and execute the following command:

.. code-block:: none

     `app-provider`.adminToken

For proper functionality, Swagger UI relies on a localhost nginx proxy for ``canton.localhost`` configured for each participant. For example, the ``JSON Ledger API HTTP Endpoints`` for the app-provider can be accessed at the nginx proxy URL ``http://canton.localhost:${APP_PROVIDER_UI_PORT}`` via Swagger UI, which corresponds to accessing ``localhost:3${PARTICIPANT_JSON_API_PORT}`` directly. The nginx proxy only adds additional headers to resolve CORS issues within Swagger UI.

Run in localnet
----------------

start
^^^^^

.. code-block:: bash

   docker compose --env-file $LOCALNET_DIR/compose.env \
                  --env-file $LOCALNET_DIR/env/common.env \
                  -f $LOCALNET_DIR/compose.yaml \
                  -f $LOCALNET_DIR/resource-constraints.yaml \
                  --profile sv \
                  --profile app-provider \
                  --profile app-user up -d

stop
^^^^

.. code-block:: bash

   docker compose --env-file $LOCALNET_DIR/compose.env \
                  --env-file $LOCALNET_DIR/env/common.env \
                  -f $LOCALNET_DIR/compose.yaml \
                  -f $LOCALNET_DIR/resource-constraints.yaml \
                  --profile sv \
                  --profile app-provider \
                  --profile app-user down -v

start with swagger-ui
^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: bash

   docker compose --env-file $LOCALNET_DIR/compose.env \
                  --env-file $LOCALNET_DIR/env/common.env \
                  -f $LOCALNET_DIR/compose.yaml \
                  -f $LOCALNET_DIR/resource-constraints.yaml \
                  --profile sv \
                  --profile app-provider \
                  --profile app-user \
                  --profile swagger-ui up -d

stop with swagger-ui
^^^^^^^^^^^^^^^^^^^^^

.. code-block:: bash

   docker compose --env-file $LOCALNET_DIR/compose.env \
                  --env-file $LOCALNET_DIR/env/common.env \
                  -f $LOCALNET_DIR/compose.yaml \
                  -f $LOCALNET_DIR/resource-constraints.yaml \
                  --profile sv \
                  --profile app-provider \
                  --profile app-user \
                  --profile swagger-ui down -v

console
^^^^^^^

.. code-block:: bash

   docker compose --env-file $LOCALNET_DIR/compose.env \
                  --env-file $LOCALNET_DIR/env/common.env \
                  -f $LOCALNET_DIR/compose.yaml \
                  -f $LOCALNET_DIR/resource-constraints.yaml \
                  run --rm console

