..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_network_reset:

Network Resets of DevNet and Testnet
====================================

DevNet and TestNet get reset roughly every 3 months with the resets
spread out such that they never happen at the same time on DevNet and
TestNet. The exact time is announced in the ``#validator-operations`` channel run by the
`Global Synchronizer Foundation <https://sync.global/>`_.

A reset requires a full redeployment of your node and loses any data you had on the node.
Your node will not be functional until you complete the reset.

To complete the reset, go through the following steps:

1. Uninstall all helm charts.
2. Delete all PVCs, docker volumes and databases (including databases
   in Amazon AWS, GCP CloudSQL or similar).
3. Acquire a fresh onboarding secret (on DevNet you can do that
   yourself by calling the respective endpoint, on TestNet contact
   your SV sponsor).
4. Redeploy your node with migration id 0. Note that this requires
   changes to both the migration id in the validator helm chart values
   as well as the participant helm chart values.
5. Take a backup of your node identities as they change as part of the
   reset.
