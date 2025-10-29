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

1.  Backup information to be preserved across the reset

    a. Take a backup of the DSO configuration (replace YOUR_SCAN_URL with your own scan e.g. |gsf_scan_url|)::

        curl -sSL --fail-with-body https://YOUR_SCAN_URL/api/scan/v0/dso > backup.json

       The backup allows you to verify that the SV weights and package versions do not change as part of the reset.
    b. Make a note of your desired amulet price in the SV UI.
    c. Make a note of all ongoing votes in the SV UI.
       Ongoing votes will be lost as part of the reset and need to be recreated manually after the reset.
    d. Make a note of all featured apps:

        curl -sSL --fail-with-body https://YOUR_SCAN_URL/api/scan/v0/featured-apps > featured.json

       Featured app rights will be lost as part of the reset and need to be recreated manually after the reset.

    e. Make a note of the current round in the Scan UI **on MainNet**.
       The current round number affects the reward distribution.
       We typically want TestNet to be one week (approximately 1008 rounds) ahead of MainNet,
       whereas DevNet is always reset to round 0.

2.  Decommission your old node

    a. Uninstall all helm charts.
    b. Delete all PVCs, docker volumes and databases (including databases
       in Amazon AWS, GCP CloudSQL or similar).

4.  Deploy your new node

    a. Set the migration id to 0 in helm chart values. The migration id appears in all helm charts,
       both as its own value, e.g.::

           migration:
             id: "MIGRATION_ID"

       and as part of various values, e.g.::

           sequencerPublicUrl: "https://sequencer-MIGRATION_ID.sv.YOUR_HOSTNAME"

    b. Set ``initialAmuletPrice`` to your desired price in ``sv-values.yaml`` (see step 1.b).
    c. Set ``chainIdSuffix`` to the new value in ``cometbft-values.yaml`` and ``info-values.yaml``.
       Usually this will just increase by 1 on a network reset but double check with
       the other SV operators on what has been agreed upon.
    d. Founding node only: Set all helm chart values that affect network parameters,
       such that the verification steps listed below pass.
    e. Install all helm charts.
    f. Wait until your SV node is sending status reports.

5.  Verify that network parameters were preserved

    a. Confirm that the reset did not change the dso rules
       by repeating step 1.a and comparing the result:

       .. code-block:: bash

           curl -sSL --fail-with-body https://YOUR_SCAN_URL/api/scan/v0/dso > current_state.json

       The reset should preserve SV reward weights, i.e., the following diff should be empty:

       .. code-block:: bash

           jq '.dso_rules.contract.payload.svs.[] | [.[1].name, .[1].svRewardWeight]' backup.json > weights_backup.json
           jq '.dso_rules.contract.payload.svs.[] | [.[1].name, .[1].svRewardWeight]' current_state.json > weights_current.json
           diff -C2 weights_backup.json weights_current.json

       The reset should also preserve the amulet rules modulo cryptographic keys, i.e., the following diff should
       only show changes to the dso and synchronizer namespaces:

       .. code-block:: bash

           jq '.amulet_rules.contract.payload' backup.json > amulet_backup.json
           jq '.amulet_rules.contract.payload' current_state.json > amulet_current.json
           diff amulet_backup.json amulet_current.json

    b. Check your desired coin price in the SV UI, and verify that it matches
       the value from before the reset (see step 1.b.)
    c. Check the current round in the Scan UI, and verify that it matches the expected value.
       This can either be roughly the same value as before the reset (see step 1.e.), or
       a different value if the SV operators agreed on that, e.g., to match the minting curve
       to a different network.

6.  Take a backup of your node identities as they change as part of the
    reset.

7.  Other post-reset actions

    a. Recreate votes that were ongoing at the time of the reset, see step 1.c.
    b. Re-issue onboarding secrets to validators you are sponsoring (TestNet only, on DevNet they can self-issue secrets).
    c. Recreate votes for featured apps when requested by validators.
       The expectation is that validators reach out to their sponsor and the sponsor initiates the vote.
       If necessary, consult the list of featured apps you backed up in step 1.d.
    d. Update your auto-sweeping configuration, as party ids change as part
       of the reset.
