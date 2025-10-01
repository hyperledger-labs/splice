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
The :ref:`scan_openapi` describes the Scan API in detail.
The below table provides a quick overview of the endpoints that the Scan Bulk Data API consists of:

.. list-table::
   :widths: 10 30
   :header-rows: 1

   * - Endpoint
     - Description
   * - `POST /v2/updates <scan_openapi.html#post--v2-updates>`_
     - Returns the update history
   * - `GET /v2/updates/\{update_id\} <scan_openapi.html#get--v2-updates-update_id>`_
     - Returns the update with the given update_id
   * - `GET /v0/state/acs/snapshot-timestamp <scan_openapi.html#get--v0-state-acs-snapshot-timestamp>`_
     - Returns the timestamp of the most recent snapshot
   * - `POST /v0/state/acs <scan_openapi.html#post--v0-state-acs>`_
     - Returns the ACS Snapshot for a given record time

If you would rather read the yaml Open API specification file directly, this can be found in the Splice repository at
`scan.yaml <https://github.com/hyperledger-labs/splice/blob/08fc692cf2952a52cce00473793d1dca08c0fba5/apps/scan/src/main/openapi/scan.yaml>`_.

Example URLs for accessing the Scan Bulk Data API are:

- |gsf_scan_url|/api/scan/v2/updates
- |gsf_scan_url|/api/scan/v0/state/acs/snapshot-timestamp

Please note the `api/scan` prefix in the URLs, which is the base path for the Scan API.

.. _updates_section:

Updates
~~~~~~~

An update can be one of two things:

* A **transaction**, which is a tree of events, where an event either creates a contract or exercises a choice on a contract.
  Exercises can have child events, which results in a tree structure.
* A **reassignment**, which assigns the processing of an active contract state (UTXOs) to another synchronizer, as part of a Splice network app's multi-synchronizer workflow or a rolling upgrade.
  They will begin to appear in the update stream as the global synchronizer introduces rolling upgrades later in 2025 or early 2026;
  for this reason we'll omit further details for now, you can safely ignore reassignments and only handle transactions.

`/v2/updates <scan_openapi.html#post--v2-updates>`_
provides a JSON encoded version of the recorded update history. Once you have an ``update_id`` for a specific update, you can retrieve the details by using
`/v2/updates/\{update_id\} <scan_openapi.html#get--v2-updates-update_id>`_.

.. _v2_updates:

POST /v2/updates
^^^^^^^^^^^^^^^^

Post a paged update history request to get all updates up to ``page_size``.
Please see `POST /v2/updates <scan_openapi.html#post--v2-updates>`_ for more details.

Requesting all updates
""""""""""""""""""""""

To get the first page of updates from the beginning of the network, omit the ``after`` field in the request body.

An example of a request body is shown below, getting a page of max 10 updates from the beginning of the network:

.. code-block:: json

    {
      "page_size": 10
    }

To get the next page of updates, take the ``migration_id`` and the ``record_time`` from the last update in the response of the previous successful request and use this
in a subsequent request in the ``after`` object, to read the next page.
Once you receive less updates than the requested ``page_size``, you have reached the end of the stream.

An example of a request body is shown below, getting a page of max 10 updates after the specified record time:

.. code-block:: json

    {
      "page_size": 10,
      "after": {
        "after_migration_id": 0,
        "after_record_time": "2024-09-20T13:31:28.405180Z"
      }
    }

Requesting updates from an arbitrary record time
""""""""""""""""""""""""""""""""""""""""""""""""

To get updates starting from an arbitrary record time, specify the ``after`` field in the request body,
where ``after_record_time`` is the time at which you want to start fetching updates (exclusive),
and ``after_migration_id`` is the migration ID that was active at that time.

Note that the record time ranges of different migrations may overlap,
i.e., the record time can go back after a hard domain migration.
Read the `OpenAPI documentation <https://github.com/hyperledger-labs/splice/blob/main/apps/scan/src/main/openapi/scan.yaml>`_
to understand how the ``after_migration_id`` field affects the response.

If you don't know what migration ID was active at the chose time,
start with migration ID 0 and keep incrementing it by one
until you find the lowest migration id that includes a higher record time than the one you specified in `after_record_time`.

After getting the first page of updates, use the ``after`` field as described in the above section to fetch subsequent pages.

.. _update_response_section:

Reading the response
""""""""""""""""""""

The response returns a list of transactions. Every transaction contains the following fields:

* **migration_id** : This ID increments with every hard synchronizer migration. A hard synchronizer migration is performed for every significant Canton version upgrade.
  The current migration ID can be inquired from an SV,
  or by :ref:`Listing_all_SV_Sequencers`\ , which returns a ``migrationId`` per sequencer.
* **synchronizer_id**: The instance ID of a Synchronizer (for example the ID of the Global Synchronizer).
  When contracts get reassigned, they become unavailable for processing via the old instance of the synchronizer
  and become available via the new instance of the synchronizer.
  Every contract is assigned to a ``synchronizer_id``, which represents the synchronizer that the stakeholders agreed
  on to use for sequencing future transactions on this contract.
* **update_id**: Uniquely identifies an update (globally unique across networks and synchronizers).
* **record_time**: The time at which the update was sequenced.
  Within a given migration and synchronizer, the record time of updates is strictly monotonically increasing (and thus unique).
  Record times between migrations can overlap; for example, if *rtn* is the latest record time for migration ID *n*, then there may exist updates with migration ID *n+1* but record time preceding *rtn*.
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

Events
~~~~~~

While Updates encapsulate all of the information required to determine the state of the network, the Scan App also exposes the history of consensus data flowing through the SV node mediator:

* A **verdict** is the result tabulated by the SV node mediator which confirms the validity of the private portions of the transactions the node processes.
* An **event** combines updates and verdicts. If a transaction is private, only the verdict information may be available. If it is partially private, the public portion will be included, possibly with the private portion's verdict. Reassignments do not have associated verdicts.

.. note:: Verdict data is ephemeral and routinely pruned within the mediator. The Scan App will save this historical data, but cannot reproduce anything that has already been pruned. This data is not essential for computing the current state of the network or any active contract state.

`/v0/events <scan_openapi.html#post--v0-events>`_
provides a JSON encoded version of the recorded event history. As with :ref:`Updates <updates_section>`, once you have an ``update_id`` for a specific event, you can retrieve the details by using
`/v0/events/\{update_id\} <scan_openapi.html#get--v0-events-update_id>`_.

Requesting all events, as well as requesting updates from an arbitrary record time, work the same way as with the :ref:`update api <v2_updates>`.

Reading the event response
^^^^^^^^^^^^^^^^^^^^^^^^^^

The response returns a list of objects, each of which may include an ``update`` field and a ``verdict`` field. At least one of these fields will be present. The contents of the ``update`` field follow the format of :ref:`update response objects <update_response_section>`. The contents of the ``verdict`` field is an object containing the following fields:

* **update_id** : This is the same as the ``update_id`` field of an update. If both an update and verdict are returned, they will agree.
* **migration_id** : This is the same as the ``migration_id`` field of an update. If both an update and verdict are returned, they will agree.
* **domain_id** : The synchronization domain that processed the transaction. If both an update and verdict are returned, this will agree with the update's ``synchronizer_id`` field.
* **record_time**: The time at which the update the verdict pertains to was sequenced. If both an update and verdict are returned, they will agree.
* **finalization_time**: The time at which the mediator finished gathering all the confirmations required to compute its verdict.
* **submitting_parties**: The parties on whose behalf the transaction was submitted.
* **verdict_result**: The final result computed by the mediator, whether the result was unspecified, accepted, or rejected.
* **mediator_group**: An opaque ID for the group of mediators which validated this transaction, including the one on the SV node.
* **transaction_views**: An array of objects which detail the (private) transaction views that needed to be confirmed by the mediator. Each transaction view contains:

   * **view_id**: An opaque ID identifying the view across involved mediators
   * **informees**: The parties informed of the contents of the transaction view
   * **confirming_parties**: The parties responsible for confirming the validity of the transaction view, along with their quorum threshold.
   * **sub_views**: Other views that the current one depends on, referred to by their ``view_id`` fields.

ACS Snapshots
~~~~~~~~~~~~~

The :ref:`scan_openapi` describes the relevant APIs for ACS Snapshots in detail, which are shown in the table below:

.. list-table::
   :widths: 10 30
   :header-rows: 1

   * - Endpoint
     - Description
   * - `GET /v0/state/acs/snapshot-timestamp <scan_openapi.html#get--v0-state-acs-snapshot-timestamp>`_
     - Returns the timestamp of the most recent snapshot
   * - `POST /v0/state/acs <scan_openapi.html#post--v0-state-acs>`_
     - Returns the ACS Snapshot for a given record time

The ACS snapshots are periodically taken and stored in the Scan App. This endpoint only provides the snapshots that have been periodically taken.
You can compute the state at any point in time by starting from a periodic snapshot and
then stream updates from the timestamp of that snapshot. We'll discuss this in more detail in the `Scan TxLog Script`_ section.

.. _v0_state_acs_snapshot-timestamp:

GET /v0/state/acs/snapshot-timestamp
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The `/v0/state/acs/snapshot-timestamp <scan_openapi.html#get--v0-state-acs-snapshot-timestamp>`_ endpoint returns the timestamp of the most recent snapshot before the given date,
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

.. _v0_state_acs:

POST /v0/state/acs
^^^^^^^^^^^^^^^^^^

The `/v0/state/acs <scan_openapi.html#post--v0-state-acs>`_ endpoint returns the ACS in creation date ascending order, paged, for a given migration id and record time.
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

POST /v0/state/acs/force
^^^^^^^^^^^^^^^^^^^^^^^^

.. note:: This is a **development environment only** endpoint, and is unavailable in production environments.

During testing, the :ref:`last snapshot timestamp <v0_state_acs_snapshot-timestamp>` can be inconveniently old.
A production app must be able to deal with this by using :ref:`v2_updates`, but an app's ability to deal with data in the snapshot is important too.
Therefore, on properly-configured testing Scans, `/v0/state/acs/force <scan_openapi.html#post--v0-state-acs-force>`_ will cause Scan to immediately snapshot the ACS, returning the new snapshot time in the ``record_time`` property.
But most environments will return an error, as this endpoint is disabled.

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
    get a periodic snapshot using the `/v0/state/acs <scan_openapi.html#post--v0-state-acs>`_ endpoint, store the ACS in a dictionary keyed by ``contract_id``
    and then process the updates from the timestamp of that snapshot via the ``/v2/updates``, adding
    ``created_event``\ s to the dictionary under its ``contract_id`` key and
    remove the contract from the dictionary by ``contract_id`` if ``exercised_event``\ s are consuming.


.. _total_burn:

Computing Total Burnt Canton Coin
---------------------------------

At a high level, there are several types of transactions in the network in which Canton Coin is burnt:
- Coin being burnt for purchasing traffic on the synchronizer, and other fees collected as part of that transaction
- Coin being burnt to account for accrued fees, charged as part of a Canton Coin transfer
- Purchasing and renewing CNS entries
- Fees paid for creating and renewing CC transfer pre-approvals

Below we provide further technical details about the relevant transactions, and how to
correctly account for the burnt Canton Coin in the network by parsing them.

For sanity check of your computation, it is strongly advised that you assert that
on any transaction for which you compute the burnt coin, the difference between the
sum of coin (or coin equivalent, e.g. various rewards used directly as input to the transaction
instead of coin contracts) in the input contracts and that of the output contracts is equal to the
sum of the burnt coin computed from the fees. The Daml models governing the transaction may
evolve over time, and such an assertion would help you catch cases where the model has
changed and your computation is no longer precise.


Coin Burnt for Purchasing Traffic on the Synchronizer
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Traffic on the synchronizer is purchased by executing the ``AmuletRules_BuyMemberTraffic``
choice on the ``Splice.AmuletRules:AmuletRules`` template. Coin is burnt in that transaction
both for purchase of the traffic, as well as for other fees collected as part of that same transaction.

Below is an example of an exercised event for purchasing traffic (with some irrelevant fields omitted):

.. code-block:: json

        "1220299075b2251a542c4ff0a6aec03dbd3e69041da7d85cd62be9d665f3a959cd25:1": {
          "event_type": "exercised_event",
          "event_id": "1220299075b2251a542c4ff0a6aec03dbd3e69041da7d85cd62be9d665f3a959cd25:1",
          "contract_id": "00aec43c48f896adb70550e22a5bd44f290534058aa9fa1ba939aa17f622639d31ca101220b56087539ec11e1b7803b726e1d833ef9685dfdffb7570644b44d1074882e0fd",
          "template_id": "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f:Splice.AmuletRules:AmuletRules",
          "package_name": "splice-amulet",
          "choice": "AmuletRules_BuyMemberTraffic",
          "choice_argument": {
            "inputs": [
              {
                "tag": "InputAmulet",
                "value": "0019bc6f3f9b53f1e4e3af43e47a35f3fe43507e861490c0b58656fc08a1408c32ca101220ed59a33d6a79962a0924a1ecae3f539c4003d808a0a564dada0437018adb8c6d"
              },
              {
                "tag": "InputAmulet",
                "value": "00b0acb28b679855d0cab28c662663ccfbb22e78873424ec49eddc18e81a4f5fe9ca10122053517786a087da1055ab0f2adf0d20b6d02238c13fee8d2a773e2dc0514976a9"
              }
            ],
            "context": "<...>",
            "provider": "<...>",
            "memberId": "<...>",
            "synchronizerId": "global-domain::1220e1e594cdb287aeac3e1e6d62e7d2db46b756a5d01656c26f1f1a151345bf2e53",
            "migrationId": "1",
            "trafficAmount": "1999800"
          },
          "child_event_ids": "<...>",
          "exercise_result": {
            "round": {
              "number": "10470"
            },
            "summary": {
              "inputAppRewardAmount": "0E-10",
              "inputValidatorRewardAmount": "0E-10",
              "inputSvRewardAmount": "0E-10",
              "inputAmuletAmount": "224019632.4829619323",
              "balanceChanges": "<...>",
              "holdingFees": "0.0076103600",
              "outputFees": [
                "209.9976000000"
              ],
              "senderChangeFee": "6.0000000000",
              "senderChangeAmount": "223995628.8753515723",
              "amuletPrice": "0.0050000000",
              "inputValidatorFaucetAmount": "0E-10"
            },
            "amuletPaid": "23997.6000000000",
            "purchasedTraffic": "00f040b550e04734b36ca89f3d89f77192566bd3b41ead2435b94f9ab32d9eb013ca101220147966510802349805e611d2b88a289decd32ff9dc80268e0e8de5e84661e4f1",
            "senderChangeAmulet": "004997a51da7d833e7122ddd0a10800857c0370a4cc28bcb5f4bc554ec79fb42ccca1012209b58a59bb36a8ad1667a23809bebbc090b9cd5a97612a59a0ad05f2edb129c63"
          },
          "consuming": false,
          "acting_parties": [
            "Cumberland-GasStation-1::12203f6faf84f106d90b87775def701c39734fe26ce5fb01892c73f45ce8fecc8e86"
          ],
          "interface_id": null
        },


To compute all the burnt Canton Coin in this transaction, you should sum up the following fields
from the ``summary`` field in the ``exercise_result``:

- `holdingFees`: Holding fees accrued on the input coin contracts (and charged as part of this transaction), if they were held for longer than a mining round.
- `senderChangeFee`: Fees charged for creation of the coin contract holding the change to the sender.
- `amuletPaid`: The amount of Canton Coin paid for purchasing the traffic credit.


Coin Burnt in Canton Coin Transfers
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Similarly to traffic purchases, also as part of Canton Coin transfer transactions, various
fees are charged. Below is an example of an exercised event for a Canton Coin transfer (with some irrelevant fields omitted):

.. code-block:: json

        "1220f207fb8c58969e51c99af570f99302ad4f5adf513de0b70dc93e358371f25bc2:2": {
          "event_type": "exercised_event",
          "event_id": "1220f207fb8c58969e51c99af570f99302ad4f5adf513de0b70dc93e358371f25bc2:2",
          "contract_id": "00aec43c48f896adb70550e22a5bd44f290534058aa9fa1ba939aa17f622639d31ca101220b56087539ec11e1b7803b726e1d833ef9685dfdffb7570644b44d1074882e0fd",
          "template_id": "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f:Splice.AmuletRules:AmuletRules",
          "package_name": "splice-amulet",
          "choice": "AmuletRules_Transfer",
          "choice_argument": {
            "transfer": {
              "sender": "<...>",
              "provider": "<...>",
              "inputs": [
                {
                  "tag": "InputAmulet",
                  "value": "0020459e63b92ded757b2d271ae527285d97a28edacd15e2ce9d4b9f209167190cca1012203226f23d0e205f4aa9ffbc9445b39c7a3a2d1ea5b94326adbcd94d7f2862a802"
                }
              ],
              "outputs": [
                {
                  "receiver": "<...>",
                  "receiverFeeRatio": "0E-10",
                  "amount": "9600.2116486069",
                  "lock": null
                }
              ]
            },
            "context": "<...>"
          },
          "child_event_ids": "<...>",
          "exercise_result": {
            "round": {
              "number": "10468"
            },
            "summary": {
              "inputAppRewardAmount": "0E-10",
              "inputValidatorRewardAmount": "0E-10",
              "inputSvRewardAmount": "0E-10",
              "inputAmuletAmount": "223970271.6123234793",
              "balanceChanges": "<...>",
              "holdingFees": "0E-10",
              "outputFees": [
                "102.0021164861"
              ],
              "senderChangeFee": "6.0000000000",
              "senderChangeAmount": "223960563.3985583863",
              "amuletPrice": "0.0050000000",
              "inputValidatorFaucetAmount": "0E-10"
            },
            "createdAmulets": [
              {
                "tag": "TransferResultAmulet",
                "value": "0062dfd0dd4e814762c67c4f8264d9f752f1e3291535546bc33a7d1a5d748c9a6cca1012204a3e156db434b9b56af11b7d9b4dca8ba182baf6d161c2c35dac328e58bebe5a"
              }
            ],
            "senderChangeAmulet": "0085965dcb855eb24cc28bdf455aa77c4a60d9ffbbc35d9ac43c64f6bf6667448aca10122018a84ec51c73894877b3897cb415b4569520a6ca32eb992fbf98e72155d62cf9"
          },
          "consuming": false,
          "acting_parties": "<...>",
          "interface_id": null
        },


Similarly to the case of traffic purchases above, to compute all the burnt Canton Coin in this
transaction, you should sum up the following fields from the ``summary`` field in the ``exercise_result``:

- `holdingFees`: Holding fees accrued on the input coin contracts (and charged as part of this transaction), if they were held for longer than a mining round.
- `outputFees`: Fees charged per output coin contract in the transaction.
- `senderChangeFee`: Fees charged for creation of the coin contract holding the change to the sender.

Purchasing CNS Entries
~~~~~~~~~~~~~~~~~~~~~~

Canton Name Service (CNS) entries may be purchased on the network, and paid for by burning Canton Coin.
Like other transactions, other fees are also charged as part of the transaction.

Below is an example of the sub-transactions of the ``AnsEntryContext_CollectInitialEntryPayment``
transactions, with some irrelevant fields omitted. Note that there is a similar transaction,
``AnsEntryContext_CollectRenewalEntryPayment``, for renewing existing CNS entries.

.. code-block:: json

          "1220088866741e05b6ee333fad8fb505856ad78e836aa812afb1ca4a00deae5d50b3:7": {
          "event_type": "exercised_event",
          "event_id": "1220088866741e05b6ee333fad8fb505856ad78e836aa812afb1ca4a00deae5d50b3:7",
          "contract_id": "00ec1e0685d269b19064c9ca45294f4d03024988d3d9e3e86e9fd9d4b8b35db8a3ca1012205aa122cbfb63e63310fcfd024daabea1c379d942261033ab34dd1bfc5405e6af",
          "template_id": "4e3e0d9cdadf80f4bf8f3cd3660d5287c084c9a29f23c901aabce597d72fd467:Splice.Wallet.Subscriptions:SubscriptionInitialPayment",
          "package_name": "splice-wallet-payments",
          "choice": "SubscriptionInitialPayment_Collect",
          "choice_argument": {
            "transferContext": "<...>"
          },
          "child_event_ids": "<...>",
          "exercise_result": {
            "subscription": "<...>",
            "subscriptionState": "<...>",
            "amulet": "0064d6918d973d69c626a6d9020c625e30199309bcf36460a8e3a4cc0775b44738ca10122090b18bbdc6ac5da9338e547c3f1c310de27184877cb6c4b1daf91c77cfb3356b"
          },
          "consuming": true,
          "acting_parties": "<...>",
          "interface_id": null
        },
        "1220088866741e05b6ee333fad8fb505856ad78e836aa812afb1ca4a00deae5d50b3:17": {
          "event_type": "created_event",
          "event_id": "1220088866741e05b6ee333fad8fb505856ad78e836aa812afb1ca4a00deae5d50b3:17",
          "contract_id": "0064d6918d973d69c626a6d9020c625e30199309bcf36460a8e3a4cc0775b44738ca10122090b18bbdc6ac5da9338e547c3f1c310de27184877cb6c4b1daf91c77cfb3356b",
          "template_id": "4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.Amulet:Amulet",
          "package_name": "splice-amulet",
          "create_arguments": {
            "dso": "DSO::122062ff91be08e836bfdc34e0c76ecc786e0f7c0fe40528a220c5dc2ac5a5337961",
            "owner": "<...>",
            "amount": {
              "initialAmount": "200.0000000000",
              "createdAt": "<...>",
              "ratePerRound": "<...>"
            }
          },
          "created_at": "2025-02-27T18:35:36.389123Z",
          "signatories": "<...>",
          "observers": []
        },
        "1220088866741e05b6ee333fad8fb505856ad78e836aa812afb1ca4a00deae5d50b3:13": {
          "event_type": "exercised_event",
          "event_id": "1220088866741e05b6ee333fad8fb505856ad78e836aa812afb1ca4a00deae5d50b3:13",
          "contract_id": "0062bacc032e3f6191070940cddec7b0d34fdf0f4d8ff49a2e28bbc51462ef9c35ca1012205bb34d8c4609b3172094908c35107e3d774517054efae35b8c6847f4487e95a7",
          "template_id": "4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.AmuletRules:AmuletRules",
          "package_name": "splice-amulet",
          "choice": "AmuletRules_Transfer",
          "choice_argument": {
            "transfer": {
              "sender": "<...>",
              "provider": "<...>",
              "inputs": "<...>",
              "outputs": "<...>"
            },
            "context": "<...>"
          },
          "child_event_ids": "<...>",
          "exercise_result": {
            "round": {
              "number": "28"
            },
            "summary": {
              "inputAppRewardAmount": "0E-10",
              "inputValidatorRewardAmount": "0E-10",
              "inputSvRewardAmount": "0E-10",
              "inputAmuletAmount": "208.0000000000",
              "balanceChanges": "<...>",
              "holdingFees": "0E-10",
              "outputFees": [
                "8.0000000000"
              ],
              "senderChangeFee": "0E-10",
              "senderChangeAmount": "0E-10",
              "amuletPrice": "0.0050000000",
              "inputValidatorFaucetAmount": "0E-10"
            },
            "createdAmulets": "<...>",
            "senderChangeAmulet": null
          },
          "consuming": false,
          "acting_parties": "<...>",
          "interface_id": null
        },

The two sources of coin burn in this transaction are:

- A temporary coin contract is created and immediately burnt as part of this transaction,
  for the payment for the CNS itself. To find the amount burnt, look at the Amulet contract ID
  in the ``exercise_result`` field of the ``SubscriptionInitialPayment_Collect`` choice
  (the first sub-transaction in the example above), then find the corresponding ``create`` event
  with the same contract ID (the second sub-transaction in the example above). The amount
  in this coin contract is the amount burnt for the CNS entry (you should find a corresponding ``archive``
  of the same contract ID in the transaction).

- Fees collected as part of the ``transfer`` sub-transaction (the third sub-transaction in the example above),
  similar to transfers explained in the "Coin Burnt in Canton Coin Transfers" above section.

Note that in a separate, earlier, transaction, a coin was locked for this CNS entry, and further fees were charged then.
However, that locking step is implemented as a ``transfer`` transaction, thus will be accounted for in the same way as
other transfers.

Fees Paid for Creating CC Transfer Pre-Approvals
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

When creating, or renewing a pre-approval for receiving Canton Coin transfers, fees are charged as part of the transaction.
Note that pre-approval payments may exist in executions of the ``AmuletRules_CreateTransferPreapproval``,
``AmuletRules_CreateExternalPartySetupProposal`` and ``TransferPreapproval_Renew`` choices.

Below is an example of an exercised event for creating a pre-approval (with some irrelevant fields omitted):

.. code-block:: json

        "1220986fb3a857bc92ca570bc11d7b3b8579cb4afe0e0c2ac3ac920057f2d968b99f:0": {
          "event_type": "exercised_event",
          "event_id": "1220986fb3a857bc92ca570bc11d7b3b8579cb4afe0e0c2ac3ac920057f2d968b99f:0",
          "contract_id": "0062bacc032e3f6191070940cddec7b0d34fdf0f4d8ff49a2e28bbc51462ef9c35ca1012205bb34d8c4609b3172094908c35107e3d774517054efae35b8c6847f4487e95a7",
          "template_id": "4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7:Splice.AmuletRules:AmuletRules",
          "package_name": "splice-amulet",
          "choice": "AmuletRules_CreateTransferPreapproval",
          "choice_argument": {
            "context": {
              "amuletRules": "0062bacc032e3f6191070940cddec7b0d34fdf0f4d8ff49a2e28bbc51462ef9c35ca1012205bb34d8c4609b3172094908c35107e3d774517054efae35b8c6847f4487e95a7",
              "context": "<...>"
            },
            "inputs": [
              {
                "tag": "InputAmulet",
                "value": "005b5fb07cdf97f2cc32190d0a5cc80176bb20161e581987848ac93c31a07d9932ca101220e3a7885db04643c34edb10fe11a0418810ba881d072c9b40a24a4106aa875a7d"
              }
            ],
            "receiver": "<...>",
            "provider": "<...>",
            "expiresAt": "2025-05-28T19:19:09.285322Z"
          },
          "child_event_ids": "<...>",
          "exercise_result": {
            "transferPreapprovalCid": "00361db2d2b07253f64deaa7c8db4319625227c27b97143c1b279cdc6294d5cb97ca101220d1c8f06741b5b99477861410e4d4bc460a207af3ea7fa179e903c78846b227e2",
            "transferResult": {
              "round": {
                "number": "32"
              },
              "summary": {
                "inputAppRewardAmount": "0E-10",
                "inputValidatorRewardAmount": "0E-10",
                "inputSvRewardAmount": "0E-10",
                "inputAmuletAmount": "253917.5244126799",
                "balanceChanges": [
                  [
                    "digitalasset-validator1-1::1220e6ee4d3f5387c9210ce50a46b3c4906335bae0083bb1dcc2819d4b52e178ec7e",
                    {
                      "changeToInitialAmountAsOfRoundZero": "-61.3199997000",
                      "changeToHoldingFeesRate": "0E-10"
                    }
                  ]
                ],
                "holdingFees": "0E-10",
                "outputFees": [
                  "6.0000000000"
                ],
                "senderChangeFee": "6.0000000000",
                "senderChangeAmount": "253856.2044129799",
                "amuletPrice": "0.0050000000",
                "inputValidatorFaucetAmount": "0E-10"
              },
              "createdAmulets": "<...>",
              "senderChangeAmulet": "00fe4c85fc36780c61281ffa37679a4580fe33df727e5614185e8aacde1bcd491eca101220b7e8a17df7ec6598d5b5d5b240513805cdbc31404b0fcef2309763237e7245d9"
            },
            "amuletPaid": "49.3199997000"
          },
          "consuming": false,
          "acting_parties": "<...>",
          "interface_id": null
        },

You can notice a similar structure to the previous cases, where certain fees are charged, plus
an "amuletPaid" field that indicates the amount of Canton Coin burnt for creating the pre-approval.
Accounting for the burnt coin in this transaction is therefore also similar to the previous cases.

Note, however a subtle difference: In these transactions, as opposed to traffic purchases above,
the `outputFee` is not included in `amuletPaid`, therefore needs to be added separately to the total burn.

Note About Daml Versions
~~~~~~~~~~~~~~~~~~~~~~~~

Daml models evolve over time. The examples and text here are correct for the models in use on the network
at the time of writing (February 2025), and were also correct since network genesis. However, as the models
evolve, the structure of the transactions may change, and future transactions may need to be processed
differently than described here. Specifically, at the time of writing, there is already a planned change
where traffic purchases do not go through an intermediate ``transfer`` transaction, but are directly
burning coin.

