..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv_network_reset:

Network Resets of DevNet and Testnet
====================================

DevNet and TestNet get reset roughly every 3 months with the resets
spread out such that they never happen at the same time on DevNet and
TestNet. The exact time is announced in the ``#supervalidator-operations`` channel run by the
`Global Synchronizer Foundation <https://sync.global/>`_.

A reset requires a full redeployment of your node and loses any data
you had on the node.  Your node will not be functional until you
complete the reset. Wait for the bootstrapping SV-1 to announce that
they completed redeployment of their node before attempting to
redeploy your node.

To complete the reset, go through the following steps:

1. Take a backup of the DSO configuration (replace YOUR_SCAN_URL with your own scan e.g. |gsf_scan_url|)::

    curl -sSL --fail-with-body https://YOUR_SCAN_URL/api/scan/v0/dso > backup.json

   The backup allows you to verify that the SV weights and package versions do not change as part of the reset.
2. Check your desired coin price in the SV UI.
3. Uninstall all helm charts.
4. Delete all PVCs, docker volumes and databases (including databases
   in Amazon AWS, GCP CloudSQL or similar).
5. Find the new ``chainIdSuffix`` for cometbft. Usually this will just increase by 1 on a network
   reset but double check with the other SV operators on what has been agreed upon.
6. Redeploy your node with migration id 0, the new ``chainIdSuffix``, and ``initialAmuletPrice``
   in the SV helm values. Note that this requires changes in the helm values of all charts.
7. Take a backup of your node identities as they change as part of the
   reset.
8. Verify that the SV weights (after all SVs rejoined after the reset) and package versions did not change by querying scan again after the reset.
9. Verify that your coin price vote has been set as desired.

.. code-block:: bash

    curl -sSL --fail-with-body https://YOUR_SCAN_URL/api/scan/v0/dso > current_state.json
    diff -C2 <(jq '.dso_rules.contract.payload.svs.[] | [.[1].name, .[1].svRewardWeight]' < backup.json) <(jq '.dso_rules.contract.payload.svs.[] | [.[1].name, .[1].svRewardWeight]' < current_state.json)
    diff <(jq '.amulet_rules.contract.payload.configSchedule.initialValue.packageConfig' < backup.json) <(jq '.amulet_rules.contract.payload.configSchedule.initialValue.packageConfig' < current_state.json)
