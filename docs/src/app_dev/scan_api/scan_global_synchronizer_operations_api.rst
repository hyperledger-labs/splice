..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scan_global_synchronizer_operations_api:

Scan Global Synchronizer Operations API
=======================================

Scan provides endpoints to query about the ongoing operations of the Global Synchronizer, based on information recorded on-ledger.

Validator Liveness
------------------

Rather than :ref:`Listing_all_Validators`, which can yield a large number of irrelevant records, `/v0/validators/validator-faucets?validator_ids=... <scan_openapi.html#get--v0-validators-validator-faucets>`_ yields only liveness information about the specified validators.

For example, querying for a few validators might yield

.. code-block:: json

    {
      "validatorsReceivedFaucets": [
        {
          "validator": "digitalasset-testValidator-1::12201bca369bee8df7a32ee53c6433d437396c9f69c269a1bb51383c0a279ca90626",
          "numRoundsCollected": 36,
          "numRoundsMissed": 0,
          "firstCollectedInRound": 19830,
          "lastCollectedInRound": 19865
        },
        /* similar structures for the other two validators */
      ]
    }

The key properties are ``numRoundsCollected``, indicating how many rounds the validator was active for, and ``lastCollectedInRound``, which is a close approximation for how recently the validator was in operation.

The output order does not necessarily match the input order; use the ``validator`` property to correlate multiple-validator requests.

DSO Info
--------

The DSO coordinates its operations around a set of on-ledger contracts.
These contracts change over time, so retrieving the latest copies of the contracts is the only effective way to check the current rules for interacting with the DSO-controlled aspects of a Splice network.
Fetching the `/v0/dso <scan_openapi.html#get--v0-dso>`_ endpoint simply returns all of this information, mostly in Daml contract JSON format.

Here's an example:

.. code-block:: json

    {
      "sv_user": "bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn@clients",
      "sv_party_id": "DA-Helm-Test-Node::12201094994818f3b4a165f4b391736a9c2f7c5f4ee926b5a3179cc224fe47cc92f3",
      "dso_party_id": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
      "voting_threshold": 11,
      "latest_mining_round": /* OpenMiningRound Daml contract */,
      "amulet_rules": /* AmuletRules Daml contract */,
      "dso_rules": /* DsoRules Daml contract */,
      "sv_node_states": [
          /* several SvNodeState contracts, one for each SV */
      ]
    }

In most use cases, the ``amulet_rules``, ``latest_mining_round``, and ``dso_rules`` will be the most interesting properties; the former two for interacting with Amulet, and the latter for checking scheduled network rule changes.
Consult their respective Daml templates for details on included data.
