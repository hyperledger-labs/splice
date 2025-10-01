..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _docker_compose_helm_chart:

Alternative Docker Compose Deployment
=====================================

.. warning::

   This section features solutions shared by community members. While they haven’t been formally tested by the Splice maintainers, 
   users are encouraged to verify the information independently. Contributions to enhance accuracy and completeness are always welcome.

This guide introduces a community-contributed Docker Compose solution for deploying Canton validator nodes and supporting infrastructure, following the ``x-docker`` standard used by Mario Delgado’s team for onboarding blockchains.
Note that while it was tested during a scheduled upgrade on DevNet, that required
updating the ``.env`` file with the migration ID, renaming the databases (with a planned change to use standardized names like ``participant_${MIGRATION_ID}`` and ``validator_${MIGRATION_ID}``), and including ``validator-migration.yaml`` as part of the migration setup. For further information, please contact the authors of the contribution.
The solution emphasizes extensibility, operational flexibility, and integration with broader infrastructure.
For the officially supported docker-compose deployment, refer to :ref:`Docker Compose-Based Deployment of a Validator Node <compose_validator>`.

Thanks to Mario Delgado for sharing this solution in a community discussion.
For more details and future updates, see the `CryptoManufaktur-io/canton-docker <https://github.com/CryptoManufaktur-io/canton-docker>`_ repository.

Key Features of the Solution
----------------------------

- Custom Docker Compose setup for Canton, aligning with ``x-docker`` standards.
- Validator migration support via ``validator-migration.yaml`` for upgrades.
- Automated installation and update scripts for setup and maintenance.
- Support for custom overrides (``custom.yml``) and environment variables, enabling advanced configuration without modifying the base files.
- Integration guidance for Traefik (proxy) and Prometheus (monitoring) via ``central-proxy-docker`` and ``:ext-network.yml``.
- Experimental Helm chart for Kubernetes environments (in progress as of June 2025).
- Disaster recovery features (in progress as of June 2025).
