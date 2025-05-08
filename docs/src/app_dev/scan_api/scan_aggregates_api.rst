..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scan_aggregates_api:

Scan Aggregates API
===================

Scan provides a couple shortcuts for using the :ref:`scan_bulk_data_api` to look up Amulet and Amulet Name Service records.

Amulet Summaries
----------------

While it's possible to figure total Amulet balance from the :ref:`scan_bulk_data_api`, figuring the effect of holding fees can be tricky for a new Splice app unfamiliar with the on-ledger data model of Amulet.

With a valid time from :ref:`v0_state_acs_snapshot-timestamp`, knowing the latest migration ID, and one or more party IDs to look up Amulet holdings for, Scan will gather Amulet statistics for each matching party and yield them.
Here's an example payload passed to `/v0/holdings/summary <scan_openapi.html#post--v0-holdings-summary>`_ on a test network:

.. code-block:: json

    {
      "migration_id": 4,
      "record_time": "2025-02-14T15:00:00Z",
      "owner_party_ids": [
        "digitalasset-testValidator-1::1220e92bbc9d80cb6e283184017b307b9f44f23d32d7d195cdbcac033ae91eac2f28",
        "digitalasset-testValidator-1::12201bca369bee8df7a32ee53c6433d437396c9f69c269a1bb51383c0a279ca90626",
        "digitalasset-testValidator-1::122079c06f2c4128d44d1ad0b201452c0bb67858bcb9db2cf2d6310cf02960f03eea"
      ]
    }

which yielded a response:

.. code-block:: json

    {
      "record_time": "2025-02-14T15:00:00Z",
      "migration_id": 4,
      "computed_as_of_round": 20203,
      "summaries": [
        {
          "party_id": "digitalasset-testValidator-1::122079c06f2c4128d44d1ad0b201452c0bb67858bcb9db2cf2d6310cf02960f03eea",
          "total_unlocked_coin": "23765.0066688730",
          "total_locked_coin": "0.0000000000",
          "total_coin_holdings": "23765.0066688730",
          "accumulated_holding_fees_unlocked": "1.4193321400",
          "accumulated_holding_fees_locked": "0.0000000000",
          "accumulated_holding_fees_total": "1.4193321400",
          "total_available_coin": "23763.5873367330"
        },
        /* similar records for the other party IDs */
      ]
    }

``total_available_coin`` is the most useful element for most Splice apps, as it represents how much Amulet the party has available for transfers right now, taking fees and locks into account.
Note that the first summary above isn't the first of the ``owner_party_ids`` argument list; you must correlate with the ``party_id`` response property when you look up Amulet for more than one party ID.

Detailed Amulet Holdings
------------------------

For more information about Amulet on the ledger, use `/v0/holdings/state <scan_openapi.html#post--v0-holdings-state>`_ instead.
Repeating the request for `Amulet Summaries`_ above, but adding in the separately-introduced and required ``page_size`` parameter as this API is paginated, Scan would yield something like

.. code-block:: json

    {
      "record_time": "2025-02-14T15:00:00Z",
      "migration_id": 4,
      "created_events": [
        {
          "event_type": "created_event",
          "event_id": "#1220da14f2feb58bd88ab07d12feb2c483b2eba91e696ddbacf8196288a32cfcc96b:7",
          "contract_id": "0078f4eeb4bc4dd4fe111cc5ed35594db14e70fc9f262158d97df699cb347b3a0eca101220712cd87d67727c259c698cc3ec33eb773cf77bb03a1e4d5ca9b19f7cbf4e250d",
          "template_id": "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f:Splice.Amulet:Amulet",
          "package_name": "splice-amulet",
          "create_arguments": {
            "dso": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
            "owner": "digitalasset-testValidator-1::122079c06f2c4128d44d1ad0b201452c0bb67858bcb9db2cf2d6310cf02960f03eea",
            "amount": {
              "initialAmount": "23765.0066688730",
              "createdAt": {
                "number": "19830"
              },
              "ratePerRound": {
                "rate": "0.0038051800"
              }
            }
          },
          "created_at": "2025-02-12T00:42:56.627908Z",
          "signatories": [
            "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
            "digitalasset-testValidator-1::122079c06f2c4128d44d1ad0b201452c0bb67858bcb9db2cf2d6310cf02960f03eea"
          ],
          "observers": []
        },
        /* similar records for other parties */
      ],
      "next_page_token": null
    }

This response is in the same format as :ref:`v0_state_acs` but is restricted to contracts of Daml templates ``Amulet`` and ``LockedAmulet``.

Looking up ANS Entries
----------------------

`/v0/ans-entries/by-party <scan_openapi.html#get--v0-ans-entries-by-party-party>`_ and `/v0/ans-entries/by-name <scan_openapi.html#get--v0-ans-entries-by-name-name>`_ are quick ways to look up ANS entries, by owner party ID and entry name, respectively, without searching the ACS.
They share a response format in common with `/v0/ans-entries <scan_openapi.html#get--v0-ans-entries>`_, which searches by name *prefix* rather than exact name.

For example, with a test of ``/v0/ans-entries?name_prefix=dso&page_size=5``, Scan responds with something like

.. code-block:: json

    {
      "entries": [
        {
          "contract_id": null,
          "user": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
          "name": "dso.cns",
          "url": "",
          "description": "",
          "expires_at": null
        }
      ]
    }

As contract ID is ``null``, this is an ANS entry provided by the DSO; in fact, this is the DSO's own special entry.
(Note that there is also no expiry.)
Here's a more ordinary response yielded by changing the prefix to ``alice_2e5``:

.. code-block:: json

    {
      "entries": [
        {
          "contract_id": "00ab54b0bfc5a70f1fa421b4fd76cd6860d061e0fff2a0ef79d87521ab60215e2fca10122056e3bf53375a1323f296d8348057a0e1a063884e6330fd0b5476acf8811bc193",
          "user": "auth0_007c675a429eaf831f0991308d85::12201abe669faf7e657735cdcc96a1b0a98f3ba6ddca688739bcb90933b693c65a8c",
          "name": "alice_2e5bbb1c.unverified.cns",
          "url": "",
          "description": "",
          "expires_at": "2025-03-12T01:56:28.974046Z"
        }
      ]
    }
