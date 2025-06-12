..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _docker_compose_helm_chart:

Docker Compose and Helm Chart Deployment
========================================

This guide introduces a community-contributed Docker Compose solution for deploying Canton validator nodes and supporting infrastructure, following the ``x-docker`` standard used by Mario Delgado’s team for onboarding blockchains. 
This solution emphasizes extensibility, operational flexibility, and integration with broader infrastructure. 
For a simpler, more minimal setup, refer to :ref:`Docker Compose-Based Deployment of a Validator Node <compose_validator>`.

Thanks to Mario Delgado for sharing this solution in a `community discussion <https://daholdings.slack.com/archives/C08AP9QR7K4/p1748679114726079>`_. 
For more details and future updates, see the `CryptoManufaktur-io/canton-docker <https://github.com/CryptoManufaktur-io/canton-docker>`_ repository.

Key Features of This Solution
-----------------------------

- Custom Docker Compose setup for Canton, aligning with ``x-docker`` standards.
- Validator migration support via ``validator-migration.yaml`` for upgrades.
- Automated installation and update scripts for setup and maintenance.
- Support for custom overrides (``custom.yml``) and environment variables, enabling advanced configuration without modifying the base files.
- Integration guidance for Traefik (proxy) and Prometheus (monitoring) via ``central-proxy-docker`` and ``:ext-network.yml``.
- Experimental Helm chart for Kubernetes environments (in progress).
- Disaster recovery features (in progress).

:ref:`Official Docker Compose <compose_validator>` vs. This Solution
--------------------------------------------------------------------

+-------------------+--------------------------+--------------------------------------------------+
| Aspect            | Official Docker Compose  | This Solution                                    |
+===================+==========================+==================================================+
| Customization     | Limited (env, flags)     | Extensive (custom.yml, ext-network, etc.)        |
+-------------------+--------------------------+--------------------------------------------------+
| Integration       | Minimal, no proxy/       | Traefik, Prometheus, Helm/K8s                    |
|                   | monitoring               |                                                  |
+-------------------+--------------------------+--------------------------------------------------+
| Upgrade Support   | Manual                   | Automated migration layer                        |
+-------------------+--------------------------+--------------------------------------------------+
| Security          | Insecure by default,     | Designed for integration, user responsibility    |
|                   | can be hardened          |                                                  |
+-------------------+--------------------------+--------------------------------------------------+
| Automation        | Some scripts, mostly     | Automated install, update, launch                |
|                   | manual                   |                                                  |
+-------------------+--------------------------+--------------------------------------------------+