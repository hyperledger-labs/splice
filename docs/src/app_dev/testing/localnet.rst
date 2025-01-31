..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _compose_sv:

Docker-Compose Based Deployment of a Local Network
==================================================

This section describes how to deploy a local network using docker-compose.
This is useful for app developers who want a complete standalone environment to develop against. We will be deploying 2 main nodes namely:

1. A super validator node
2. A validator node

Each node will be deployed with all its dependencies.
The details of what each node deploys can be seen in the `docker-compose.yaml` files in the `docker-compose` directory of the release bundle described below.


Requirements
------------

1) A working docker and docker-compose installation
2) The release artifacts that can be downloaded from here: |bundle_download_link|. Extract the bundle once complete.


Depoying the nodes
------------------

You can spin up a docker-compose based Super-Validator as follows:

.. code-block:: bash

   cd splice-node/docker-compose/sv
   ./start.sh -w

It will take a few minutes for the SV to be ready, after which you can use it to onboard a new
validator. You can verify that the SV is up and running by opening a browser at http://sv.localhost:8080.

.. note::

    If you have already deployed a validator against an existing network, you will need to first
    tear it down and wipe all its data, as a validator cannot be moved between networks.
    To do that, stop the validator with `./stop.sh` from the `compose/validator` directory,
    and wipe out all its data with `docker volume rm compose_postgres-splice`.


Before you can onboard a new validator, you need to generate an onboarding secret for it and create a party_hint.
You can generate the secret by running the following command:

.. code-block:: bash

   curl -X POST http://sv.localhost:8080/api/sv/v0/devnet/onboard/validator/prepare

You should get a 200 OK text response that contains the onboarding secret in the body.

The `party_hint` should be chosen by you and should match the format ``<organization>-<function>-<enumerator>``, where organization & function are alphanumerical, and enumerator is an integer.

Once you have the onboarding secret and the party hint, you can start and onboard a new validator with the following command:

.. code-block:: bash

   cd ../validator
   ./start.sh -o "<onboarding_secret>" -p <party_hint> -l -w

Note that ``-l`` automatically configures the validator with the correct configuration required in order for it to use the docker-compose SV created above.
You can verify the validator is up and running by opening a browser at http://wallet.localhost:8080.

To tear everything down, run ``./stop.sh`` from both the ``compose/validator`` and ``compose/sv`` directories.
As above, this will retain the data for reuse. In order to completely wipe out
the network's and validator's data, also run ``docker volume rm splice-validator_postgres-splice splice-sv_postgres-splice-sv``.


UIs
---

The following table lists the UIs available for the deployed nodes.


.. list-table::
   :widths: 25 35 40
   :header-rows: 1

   * - Application
     - URL
     - Credentials
   * - SV UI
     - http://sv.localhost:8080
     - ``administrator``
   * - Wallet
     - http://wallet.localhost:8080
     - ``administrator`` and ``alice``
   * - Scan
     - http://scan.localhost:8080
     - N/A
   * - ANS
     - http://ans.localhost:8080
     - ``administrator`` and ``alice``
