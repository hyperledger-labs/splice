..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_operator:

Validators
==========

This section describes deployment and operations of a validator node.

Before moving onto the deployment of your node, familiarize yourself with the general :ref:`validator_onboarding_process`.

There are two primary ways to deploy a validator node: Either use a
docker-compose based deployment or deploy it in a kubernetes cluster
using Helm charts.

The :ref:`docker-compose based deployment <compose_validator>` is easier to get started with in
particular on your local laptop. However, that ease comes with some
limitations with regards to security and reliability. Refer to the
:ref:`docs <compose_validator>` for a more detailed description of the
limitations.

The :ref:`kubernetes based deployment <k8s_validator>` is great if you
are already familiar with kubernetes and are looking to build a
production-ready deployment, but it can be challenging to setup if you
never used kubernetes before.

You can find hardware requirements for both options in a :ref:`dedicated section <validator_hardware_requirements>`.

.. toctree::
   :maxdepth: 2

   validator_onboarding.rst
   validator_hardware_requirements.rst
   validator_compose.rst
   validator_helm.rst
   validator_upgrades.rst
   validator_network_resets.rst
   validator_backups.rst
   validator_disaster_recovery.rst
   validator_security.rst
   validator_users.rst
   validator_delegations.rst
   validator_networking.rst

.. todo:: Add top-level sections on node onboarding, validator functionality
