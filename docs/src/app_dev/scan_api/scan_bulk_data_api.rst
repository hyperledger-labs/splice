..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scan_bulk_data_api:

Scan Bulk Data API
==================

At a high level, participant nodes provide two separate endpoints through a Ledger API:

* An endpoint to submit commands to the ledger that allow an application to submit transactions and change state, by creating, exercising or archiving contracts.
* An endpoint that provides a stream of transactions and corresponding events
  — which are called **updates** for short — **from** the ledger that indicate all state changes that have taken place on the ledger
  as seen by the parties hosted on the participant node.

The **state** at a given point in time is the set of contracts that were active at that time.
This ACS (Active Contract Set) can be reconstructed by applying all updates up to the given time.

The Scan App connects to the Canton Participant of its SV node and makes this data available through the Bulk Data Scan API.
It provides the following streams of low-level data:

* A stream to the history of **updates**
* A stream of **ACS snapshots** that are periodically taken

The Scan App ingests new updates as they occur on the ledger, and also back-fills updates from other SVs that have occurred before the SV joined the network,
so it can serve data from the network’s genesis.
This update history contains data from the beginning of the network, which is distributed amongst SVs in :term:`BFT` fashion by the Scan Apps.
Once the update history of a Scan App is complete, ACS snapshots are periodically created from the update history.
The Scan App continues to periodically add new ACS snapshots.
This makes it possible for every Scan App hosted by every SV to provide all update history and ACS snapshots from the network’s genesis,
in a decentralized and consistent manner.

The Bulk Data Scan API provides access to the update history and ACS snapshots as they were recorded.
Since the API exposes the underlying Daml transactions directly, there are a couple of concerns to keep in mind:

* The API does not hide or abstract any detail in the data, it provides the raw events as they occurred.
  This means that you get direct access to all information in the transactions, and you need to familiarize yourself
  with the Daml code that underlies these events.
  Please see `Reference Documentation`_ for a refresher on how the Ledger changes through actions grouped in transactions,
  and how UTXO contract states are created, exercised and archived in general. We'll discuss the specifics of the Daml models in more detail in the `Scan TxLog Script`_ section.

* The events need to be parsed in a flexible manner. Choices and optional fields can be added over time, and new templates and data types can be introduced.
  A parser should be able to ignore these kinds of changes, and not fail when they occur.
  This means that parsing logic should be selective, and not fail when unknown fields or templates are encountered.
  Please see `Daml upgrades <https://docs.daml.com/upgrade/index.html>`_ for more information.
  The rules for Daml upgrades align well with a relaxed, intuitive approach to JSON parsing, e.g. not asserting absence of unexpected fields and not assuming key-presence for optional fields.

.. _Reference Documentation:
.. note::
   Please see `Ledger Structure <https://docs.daml.com/concepts/ledger-model/ledger-structure.html>`_ on how actions are executed in transactions to change the ledger, creating contracts and exercising choices,
   which gets described further in `Transform Data Using Choices <https://docs.daml.com/daml/intro/4_Transformations.html>`_.

Open API Specification
----------------------
Please see the Scan `Open API specification <https://github.com/hyperledger-labs/splice/blob/08fc692cf2952a52cce00473793d1dca08c0fba5/apps/scan/src/main/openapi/scan-internal.yaml>`_.
Look for APIs starting with `/v1/updates/ <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L511>`_
and `/v0/state/ <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L603>`_, respectively for updates and ACS snapshots.

Example URLs for accessing the Scan Bulk Data API are:

- |gsf_scan_url|/api/scan/v1/updates
- |gsf_scan_url|/api/scan/v0/state/acs/snapshot-timestamp

Please note the `api/scan` prefix in the URLs, which is the base path for the Scan API.

Updates
~~~~~~~

An update can be one of two things:

* A **transaction**, which is a tree of events, where an event either creates a contract or exercises a choice on a contract.
  Exercises can have child events, which results in a tree structure.
* A **reassignment**, which assigns the processing of an active contract state (UTXOs) to another synchronizer, as part of a Splice network app's multi-synchronizer workflow or a rolling upgrade.
  They will begin to appear in the update stream as the global synchronizer introduces rolling upgrades later in 2025 or early 2026;
  for this reason we'll omit further details for now, you can safely ignore reassignments and only handle transactions.

`/v1/updates <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L511>`_
provides a JSON encoded version of the recorded update history. Once you have an ``update_id`` for a specific update, you can retrieve the details by using
`/v1/updates/{update_id} <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L571>`_.

The Open API spec for `/v1/updates <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L511>`_
and `/v1/updates/{update_id} <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L571>`_
describe the APIs in detail.

POST /v1/updates
^^^^^^^^^^^^^^^^

Post a paged `UpdateHistoryRequestV1 <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L1672>`_
request to get all updates up to ``page_size``, optionally specifying an ``after`` object with ``after_migration_id`` and ``after_record_time`` fields.
(for the beginning of the network you can omit the ``after`` field).
An example of a ``UpdateHistoryRequestV1`` request body is shown below, getting a page of max 10 updates from the beginning of the network:

.. code-block:: json

    {
      "page_size": 10
    }

Another example of a ``UpdateHistoryRequestV1`` request body is shown below, getting a page of max 10 updates after the specified record time:

.. code-block:: json

    {
      "page_size": 10,
      "after": {
        "after_migration_id": 0,
        "after_record_time": "2024-09-20T13:31:28.405180Z"
      }
    }

Take the ``migration_id`` and the ``record_time`` from the last update in the response of the previous successful request and use this
in a subsequent request in the ``after`` object, to read the next page. Once you receive less updates than the requested ``page_size``, you have reached the end of the stream.

The response returns a list of transactions. Every transaction contains the following fields:

* **migration_id** : This ID increments with every hard synchronizer migration. A hard synchronizer migration is performed for every significant Canton version upgrade.
  The current migration ID can be inquired from an SV,
  or by using the ``/v0/dso-sequencers`` API at |gsf_scan_url|/api/scan/v0/dso-sequencers,
  which returns a ``migrationId`` per sequencer.
* **synchronizer_id**: The instance ID of a Synchronizer (for example the ID of the Global Synchronizer).
  When contracts get reassigned, they become unavailable for processing via the old instance of the synchronizer
  and become available via the new instance of the synchronizer.
  Every contract is assigned to a ``synchronizer_id``, which represents the synchronizer that the stakeholders agreed
  on to use for sequencing future transactions on this contract.
* **update_id**: Uniquely identifies an update (globally unique across networks and synchronizers).
* **record_time**: The time at which the update was sequenced.
  Within a given migration and synchronizer, the record time of updates is strictly monotonically increasing (and thus unique).
  The update history is mainly traversed by ``migration_id``, ``synchronizer_id``, and ``record_time``.
* **root_event_ids**: These represent the top level events of the update tree that are directly caused by commands submitted to the ledger.
  They are the starting points for all actions within the transaction, and need to be read in the order given.
  To traverse the update tree, start with the ``root_event_ids`` in order, get event by ID from ``events_by_id``,
  traverse events in preorder, process ``child_event_ids`` recursively.
  Note: event ids are stored as a string of the format ``<update_id>:<event_index>``.
  The event index exposed by scan is consistent cross all SVs for the same event.
  Note that it differs from the event index that the ledger API exposes on an individual participant
  as those can differ between different participants for the same event.
* **events_by_id**: This object contains all events in the transaction update tree, indexed by their event ID.

An example list of transactions response for the beginning of the network is shown below:

.. code-block:: json

        {
          "transactions": [
            {
              "update_id": "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7",
              "migration_id": 0,
              "workflow_id": "",
              "record_time": "2024-09-20T13:31:28.405180Z",
              "synchronizer_id": "global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
              "effective_at": "2024-09-20T13:31:29.552807Z",
              "offset": "000000000000000001",
              "root_event_ids": [
                "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:0",
                "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:1"
              ],
              "events_by_id": {
                "..." : "events omitted for brevity"
              }
            }
          ]
        }

The ``events_by_id`` object contains created and exercised events.
We'll now go through a couple of example events to highlight the most important fields to process.

Exercised Event
"""""""""""""""

An exercised event has the following important fields:

* **template_id**: The template ID uniquely identifies the Daml template.
* **contract_id**: The contract ID uniquely identifies the contract.
* **choice**: The name of the choice exercised on the contract
* **choice_argument**: The choice argument, encoded in JSON
* **exercise_result**: The result of the exercise, encoded in JSON
* **child_event_ids**: These represent events that were directly caused by the given exercised event.
  These event IDs have to be read in the order given. Get event by ID from ``events_by_id``,
  traverse events in preorder (process them recursively).
* **consuming**: A boolean indicating whether the contract is archived by the exercise. If true, the contract is archived. This is important if you want to track the ACS.

See the `ExercisedEvent <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/canton/community/ledger-api/src/main/protobuf/com/daml/ledger/api/v2/event.proto#L166>`_ protobuf message definition for a complete description of the event.

An example of an exercised event in the ``events_by_id`` object is shown below:

.. code-block:: json


    "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:4": {
      "event_type": "exercised_event",
      "event_id": "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:4",
      "contract_id": "0036a147673cc66b5e7d27811084897d6eaf1807c2bc024b9c7c9359dbfb25c790ca101220bf3bfb7315fe33fc0bafa88087a8af6794674f2a02a4690ef2897325efd9e973",
      "template_id": "a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.AmuletRules:AmuletRules",
      "package_name": "splice-amulet",
      "choice": "AmuletRules_Bootstrap_Rounds",
      "choice_argument": {
        "amuletPrice": "0.0050000000",
        "round0Duration": {
          "microseconds": "97200000000"
        }
      },
      "child_event_ids": [
        "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:5",
        "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:6",
        "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:7"
      ],
      "exercise_result": {
        "openMiningRoundCid": "004eba336d6bbaed0e866e2dd11351fc989b1043b09c34ce3ac16fe08ff9fc1cfaca101220e8339d816712ba0294cdce13216494bb50dd1070be12ede312133003e0f1252d"
      },
      "consuming": false,
      "acting_parties": [
        "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17"
      ],
      "interface_id": null
    },

This example exercised event shows a choice that is exercised on a contract of the ``AmuletRules`` template (See `Splice.AmuletRules <../api/splice-amulet/splice-amuletrules.html>`_).
The choice is ``AmuletRules_Bootstrap_Rounds`` and the choice argument contains the ``amuletPrice`` and ``round0Duration`` fields,
which are the price and duration of round zero.

The exercise result contains the ``openMiningRoundCid`` field, which is the contract ID of the open mining round that is bootstrapped at the beginning of the network.
The ``child_event_ids`` shows the events that were directly caused by this exercised event.

Created Event
"""""""""""""

Let's have a look at the last child event ID ``1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:7`` in the ``events_by_id`` object from the previous section, which is shown below:

.. code-block:: json

    "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:7": {
      "event_type": "created_event",
      "event_id": "1220e04f50c4b00024dd3a225611ad96441abd854e461c144b872c0eedac1dc784c7:7",
      "contract_id": "004eba336d6bbaed0e866e2dd11351fc989b1043b09c34ce3ac16fe08ff9fc1cfaca101220e8339d816712ba0294cdce13216494bb50dd1070be12ede312133003e0f1252d",
      "template_id": "a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668:Splice.Round:OpenMiningRound",
      "package_name": "splice-amulet",
      "create_arguments": {
        "dso": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
        "round": {
          "number": "2"
        },
        "amuletPrice": "0.0050000000",
        "opensAt": "2024-09-21T16:31:29.552807Z",
        "targetClosesAt": "2024-09-21T16:51:29.552807Z",
        "issuingFor": {
          "microseconds": "1200000000"
        },
        "transferConfigUsd": {
          "createFee": {
            "fee": "0.0300000000"
          },
          "holdingFee": {
            "rate": "0.0000190259"
          },
          "transferFee": {
            "initialRate": "0.0100000000",
            "steps": [
              {
                "_1": "100.0000000000",
                "_2": "0.0010000000"
              },
              {
                "_1": "1000.0000000000",
                "_2": "0.0001000000"
              },
              {
                "_1": "1000000.0000000000",
                "_2": "0.0000100000"
              }
            ]
          },
          "lockHolderFee": {
            "fee": "0.0050000000"
          },
          "extraFeaturedAppRewardAmount": "1.0000000000",
          "maxNumInputs": "100",
          "maxNumOutputs": "100",
          "maxNumLockHolders": "50"
        },
        "issuanceConfig": {
          "amuletToIssuePerYear": "40000000000.0000000000",
          "validatorRewardPercentage": "0.0500000000",
          "appRewardPercentage": "0.1500000000",
          "validatorRewardCap": "0.2000000000",
          "featuredAppRewardCap": "100.0000000000",
          "unfeaturedAppRewardCap": "0.6000000000",
          "optValidatorFaucetCap": "2.8500000000"
        },
        "tickDuration": {
          "microseconds": "600000000"
        }
      },
      "created_at": "2024-09-20T13:31:29.552807Z",
      "signatories": [
        "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17"
      ],
      "observers": []
    }

The example child event ID points to a ``created_event``, which creates a contract of the ``OpenMiningRound`` template.
A created event has the following important fields:

* **template_id**: The template ID uniquely identifies the Daml template.
* **contract_id**: The contract ID uniquely identifies the contract.
* **create_arguments**: The arguments used to create the contract, encoded in JSON

See the `CreatedEvent <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/canton/community/ledger-api/src/main/protobuf/com/daml/ledger/api/v2/event.proto#L33>`_ protobuf message definition for a complete description of the event.

In this case the ``create_arguments`` contains the fields that are used to create the contract, such as the round number, the price of the Amulet,
the time the round opens and closes, and the configuration for Amulet transfers and issuance.
See `Splice.Round <../api/splice-amulet/splice-round.html>`_ for more information about the ``Splice.Round:OpenMiningRound`` template.
We can also note that in this case the ``contract_id`` of the ``created_event`` is the same as the ``openMiningRoundCid`` in the exercise result of the previous event.

In general, processing the updates involves the following steps:

* Traverse the update tree, starting with the root event IDs, and then process the child event IDs in preorder,
* Selectively parse the events as they are encountered, based on template IDs and the structure of the contracts that you can expect,
  which you can map against the template definitions in Daml source code and the `Daml API <../daml_api/index.html>`_ documentation of templates, choices and data types.
* Accumulate state changes, such as:

  - Keep track of contract ids and their state
  - Keep track of Canton Coin balances and activity records
  - Keep track of mining rounds and their configuration
  - Keep track of governance decisions

* And ensure that parsing does not break when new fields or templates are introduced.

ACS Snapshots
~~~~~~~~~~~~~

The `Open API spec <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml>`_
for `/v0/state/acs/snapshot-timestamp <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L603>`_
and `/v0/state/acs <https://github.com/hyperledger-labs/splice/blob/7345124f9f05395ab4797c0478c7e1dd37186369/apps/scan/src/main/openapi/scan-internal.yaml#L642>`_ describes the APIs in detail.

The ACS snapshots are periodically taken and stored in the Scan App. This endpoint only provides the snapshots that have been periodically taken.
You can compute the state at any point in time by starting from a periodic snapshot and
then stream updates from the timestamp of that snapshot. We'll discuss this in more detail in the `Scan TxLog Script`_ section.

GET /v0/state/acs/snapshot-timestamp
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The ``/v0/state/acs/snapshot-timestamp`` endpoint returns the timestamp of the most recent snapshot before the given date,
for the given ``migration_id``. Specify ``migration_id = 0`` for the beginning of the network.
The returned timestamp corresponds to the record time of the last transaction in the snapshot.

An example request to get the timestamp of the most recent snapshot before a given date is shown below:

.. parsed-literal::

    curl |gsf_scan_url|/api/scan/v0/state/acs/snapshot-timestamp\?before\="2025-02-12T00:00:00.000000Z"\&migration_id\=4

The response returns the timestamp of the most recent snapshot before the given date:

.. code-block:: json

   {
     "record_time" : "2025-02-11T18:00:00Z"
   }

POST /v0/state/acs
^^^^^^^^^^^^^^^^^^

The ``/v0/state/acs`` endpoint returns the ACS in creation date ascending order, paged, for a given migration id and record time.
Post an ``AcsRequest`` with a ``migration_id``, ``record_time`` and ``page_size`` to get a page of contracts.
An optional ``templates`` field filters the ACS by a set of ``template_id``\ s.

An example request body of type ``AcsRequest`` to get a page of ACS snapshots is shown below:

.. code-block:: json

    {
      "migration_id": 4,
      "record_time": "2025-02-11T18:00:00Z",
      "page_size": 10
    }

The response is of type ``AcsResponse`` which is a list of ``CreatedEvent``\ s along with a ``next_page_token`` which can be used to request a subsequent page.
When there are no more pages, the ``next_page_token`` is empty.

An example response is shown below:

.. code-block:: json

    {
      "record_time": "2025-02-11T18:00:00Z",
      "migration_id": 4,
      "created_events": [
        {
          "event_type": "created_event",
          "event_id": "#122098355fd6741a763f23fa0b7758d2a59cfce54aef07808ef42d366bdd6296db2d:0",
          "contract_id": "001c9216c7194bb6180968abdae59b1718a44857b005613cb47cdbc4a459b3a4caca10122019fd0561c858eac85e7e3374ec8cb27ee6f410f9260d4f89c7a3a398a1d2a37f",
          "template_id": "053c7f4c2a77312e7d465a4fa7dc8cb298754ad12c0c987a7c401bd724e65efc:Splice.Ans:AnsRules",
          "package_name": "splice-amulet-name-service",
          "create_arguments": {
            "dso": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
            "config": {
              "renewalDuration": {
                "microseconds": "2592000000000"
              },
              "entryLifetime": {
                "microseconds": "7776000000000"
              },
              "entryFee": "1.0000000000",
              "descriptionPrefix": "CNS entry: "
            }
          },
          "created_at": "2024-09-20T13:31:29.552807Z",
          "signatories": [
            "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17"
          ],
          "observers": []
        },
        {
          "..." : "more created events, omitted for brevity"
        }
      ],
      "next_page_token": 61329223
    }

The ``created_events`` object contains the created events in the ACS snapshot.
A ``created_event`` is encoded exactly as explained in `Created Event`_.

Scan TxLog Script
-----------------

To help with understanding the raw :term:`Canton Network` events, the `scan_txlog.py <https://github.com/hyperledger-labs/splice/blob/main/scripts/scan-txlog/scan_txlog.py>`_
Python script functions as a comprehensive example of how to interact with the Scan API to read and process update history and ACS snapshots.

Note: The python script is intended as a reference for understanding the transactions and their structure. Production-ready implementations may need to take additional points into consideration like not keeping the full ACS in memory or certain indices to speed up queries.

The script is centered around :term:`Canton Coin` balances and tracks all contracts involved in :term:`Canton Coin` transfers.
Please also see the `Canton Coin whitepaper <https://www.digitalasset.com/hubfs/Canton%20Network%20Files/Documents%20(whitepapers%2C%20etc...)/Canton%20Coin_%20A%20Canton-Network-native%20payment%20application.pdf>`_
for more information.

The next sections describe an overview of its functionality and usage.

Overview
~~~~~~~~
The script performs the following key tasks:

* Connects to the Scan API to fetch update history and ACS snapshots.
* Processes transactions to interpret and handle various events such as contract creation, exercise, and archival.
* Generates reports based on the processed data, which can be output to a CSV file.
* Maintains state across multiple runs by saving and restoring application state from a cache file.

Usage
~~~~~
To use the script, you need to provide the URL of the Scan App and other optional parameters such as log level, cache file path, and report output file.
The script can be run from the command line with the appropriate arguments.
Example command:

.. parsed-literal::

    python3 scan_txlog.py |gsf_scan_url| \
      --verbose \
      --cache-file-path cache.json \
      --report-output report.csv \
      --log-file-path scan_tx.log

Execute ``python3 scan_txlog.py --help`` for a list of all available options.
Let's have a look at the key components of the script in the next section.

Key Components
~~~~~~~~~~~~~~

* **ScanClient**: This class handles the communication with the Scan API. It includes methods to fetch updates and ACS snapshots.
* **State**: This class represents the state of the system at a given point in time. It includes methods to handle transactions and update the state accordingly.
* **TransactionTree**: This class represents a transaction and its associated events. It includes methods to parse and interpret the transaction data.
* **LfValue**: This class provides utility methods to extract specific fields from the transaction data.

Processing Overview
^^^^^^^^^^^^^^^^^^^

The script fetches updates and processes them to maintain an up-to-date view of the state of the Canton Network focused on
:term:`Canton Coin` balances and tracks all contracts involved in :term:`Canton Coin` transfers.

.. note::
   Please see the `Daml API <../daml_api/index.html>`_ for the templates, choices and data types that you will need to familiarize yourself
   with for parsing the events returned by the updates and snapshots APIs, especially the modules listed below:

   .. list-table::
       :widths: 50 50
       :header-rows: 1

       * - Module
         - Description
       * - `Splice.Amulet <../api/splice-amulet/splice-amulet.html>`_
         - The contracts representing the long-term state of Splice.
       * - `Splice.AmuletRules <../api/splice-amulet/splice-amuletrules.html>`_
         - The rules governing how Amulet users can modify the Amulet state.
       * - `Splice.Round <../api/splice-amulet/splice-round.html>`_
         - The contracts representing mining rounds.
       * - `Splice.DsoRules <../api/splice-dso-governance/splice-dsorules.html>`_
         - DSO governance, including decisions regarding amulet, activity records, and rounds.


* **Fetching Updates**: The ``ScanClient`` class's ``updates`` method fetches updates from the server.
* **Processing Updates**: The ``State`` class's ``handle_transaction`` method processes each transaction, updating the state and handling various events.

The script filters and parses transaction events for a set of templates specified in the ``TemplateQualifiedNames`` class,
which are involved in canton coin transfers.

.. note::

    Another potential useful filter that could be of interest is one that focuses on governance operations, which is left as
    an exercise to the reader after thoroughly analyzing the `scan_txlog.py <https://github.com/hyperledger-labs/splice/blob/main/scripts/scan-txlog/scan_txlog.py>`_ script.

The script maintains a state that contains the following information:

* The ACS at the most recently processed ``record_time``.
* A CSV report of Canton Coin balances and activity records.
* Totals of minted and burnt Canton Coins.

It also logs summaries of the processed transactions to a log file.
Most notably it logs the amulets and activity records per party, keyed by the owner of amulets and activity records.
Please see the ``PerPartyState`` class for more details on what is reported per party.
It also logs the total minted and burnt Canton Coins at the end of each round.

The script also maintains a cache file to store the state across multiple runs, allowing it to resume processing from the last known state.

The script can fetch ACS snapshots to compare with the current state or to initialize the state (when the ``--compare-acs-with-snapshot <snapshot_time>`` argument is used).

* **Fetching ACS Snapshots**: The ``ScanClient`` class's ``get_acs_snapshot_page_at`` method fetches ACS snapshots from the server.
* **Comparing ACS Snapshots**: The script can compare the fetched ACS snapshot with the current state to ensure consistency.

when the ``--compare-acs-with-snapshot <snapshot_time>`` argument is used, the script will get the ACS snapshot
for the given ``snapshot_time`` and compare it to the state that has been built up in the ``active_contracts`` dictionary.
The ``active_contracts`` dictionary is a mapping of contract IDs to their respective contract data.

The script processes ``created_event``\ s and ``exercised_event``\ s. A ``created_event`` is added to the ``active_contracts`` dictionary
under its ``contract_id`` key.
If the ``exercised_event`` is consuming, the contract is removed from the ``active_contracts`` dictionary by ``contract_id``.

.. note::
    To build up an ACS snapshot for any ``record_time``, first
    get a periodic snapshot using the ``/v0/state/acs`` endpoint, store the ACS in a dictionary keyed by ``contract_id``
    and then process the updates from the timestamp of that snapshot via the ``/v1/updates``, adding
    ``created_event``\ s to the dictionary under its ``contract_id`` key and
    remove the contract from the dictionary by ``contract_id`` if ``exercised_event``\ s are consuming.
