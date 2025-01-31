..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _compose_sv:

Docker-Compose Based Deployment of a Super Validator Node
---------------------------------------------------------

This section describes how to deploy a standalone super validator node on a local machine
using docker-compose. This is useful for app developers who want a complete standalone
environment to develop against.

If you have already deployed a validator against an existing network, you will need to first
tear it down and wipe all its data, as a validator cannot be moved between networks.
To do that, first stop the validator with `./stop.sh` from the `compose/validator` directory,
and wipe out all its data with `docker volume rm compose_postgres-splice`.

Now you can spin up a docker-compose based Super-Validator as follows:

.. code-block:: bash

   cd splice-node/docker-compose/sv
   ./start.sh -w

It will take a few minutes for the SV to be ready, after which you can use it to onboard a new
validator.

This is quite similar to the steps for spinning up a validator that were performed above.
First, fetch a new onboarding secret from the SV:

.. code-block:: bash

   curl -X POST http://sv.localhost:8080/api/sv/v0/devnet/onboard/validator/prepare

Now you can onboard a new validator to the SV:

.. code-block:: bash

   cd ../validator
   ./start.sh -o "<onboarding_secret>" -p <party_hint> -l -w

Note that ``-l`` automatically configures the validator with the correct configuration required
in order for it to use the docker-compose SV created above.

To tear everything down, run `./stop.sh` from both the `compose/validator` and `compose/sv`
directories. As above, this will retain the data for reuse. In order to completely wipe out
the network's and validator's data, also run `docker volume rm splice-validator_postgres-splice splice-sv_postgres-splice-sv`.
