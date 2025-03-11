..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scan_global_synchronizer_connectivity_api:

Scan Global Synchronizer Connectivity API
=========================================

Splice network applications and validators need to be able to connect to multiple supervalidators' Scan apps and Canton sequencers.
If you have the base URL for a single SV Scan app, you can query for all other connected Scans and the SV sequencers.
This lets you dynamically update your app configuration based on changing network SV configurations, and easily check that your own deployments can successfully connect to all needed endpoints.
Additionally, you can query for all network-approved validators and get their details.

Listing all SV Scans
--------------------

Every Scan can list all approved SV scans connected to the network.
For example, query from `/v0/scans <scan_openapi.html#get--v0-scans>`_ from |gsf_scan_url|, and the response will be something like

.. code-block:: json

    {
      "scans": [
        {
          "domainId": "global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
          "scans": [
            /* several scans */,
            {
              "publicUrl": "https://scan.sv.dev.global.canton.network.digitalasset.com",
              "svName": "DA-Helm-Test-Node"
            },
            /* several other scans */,
            {
              "publicUrl": "https://scan.sv-1.dev.global.canton.network.sync.global",
              "svName": "Global-Synchronizer-Foundation"
            }
          ]
        }
      ]
    }

``scans`` is a list of synchronizer IDs, each with an associated list of SVs and their Scan base URLs.
In this case, ``Global-Synchronizer-Foundation`` matches the originally-used Scan.
Take any of these ``publicUrl``\ s and query ``/api/scan/v0/scans``, and the same set will be returned.

.. _Listing_all_SV_Sequencers:

Listing all SV Sequencers
-------------------------

Likewise, Canton sequencers for all approved SVs are published by every Scan.
For example, query from `/v0/dso-sequencers <scan_openapi.html#get--v0-dso-sequencers>`_, and the result will be something like

.. code-block:: json

    {
      "domainSequencers": [
        {
          "domainId": "global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
          "sequencers": [
            /* several sequencers */,
            {
              "migrationId": 4,
              "id": "SEQ::DA-Helm-Test-Node::122054dda14cc07dbf8cd56b392263c73b10630c0040f185b737ee5526c8d479ffab",
              "url": "https://sequencer-4.sv.dev.global.canton.network.digitalasset.com",
              "svName": "DA-Helm-Test-Node",
              "availableAfter": "2024-11-29T15:25:05.771558Z"
            },
            /* several other sequencers */,
            {
              "migrationId": 4,
              "id": "SEQ::Global-Synchronizer-Foundation::1220408be05ce20b9f7023ec1cad8af61f0280fd30a050ccf052660da2adf35549ed",
              "url": "https://sequencer-4.sv-1.dev.global.canton.network.sync.global",
              "svName": "Global-Synchronizer-Foundation",
              "availableAfter": "2024-12-02T13:30:27.774745Z"
            }
          ]
        }
      ]
    }

As ``svName`` is the same as the ``svName`` in `Listing all SV Scans`_, it can be used to join the results of these endpoints, if necessary.
``url`` is the base URL of that SV's sequencer.

.. _Listing_all_Validators:

Listing all Validators
----------------------

Every validator approved on the network has a "license" on the ledger.
The `/v0/admin/validator/licenses <scan_openapi.html#get--v0-admin-validator-licenses>`_ endpoint lists all of these in the form of ``Splice.ValidatorLicense:ValidatorLicense`` Daml contracts.
These include:

* onboarding identification, in the form of ``validator``, ``sponsor``, and ``dso``,
* activity statistics, such as ``faucetState`` and ``lastActiveAt``, and
* other tertiary validator information in ``metadata``.

All of these, and most other important data, are within the ``payload`` property.
Other fields reflect that this is a Daml contract, and are useful for other Daml interactions.

These licenses can only record information about the past state of the network.
However, ``lastActiveAt`` can be used to infer which validators are currently likely to be active on the network and which are inactive or disconnected.

Here's a snippet of an example response from an active network:

.. code-block:: json

    {
      "validator_licenses": [
        /* many similar records */,
        {
          "template_id": "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f:Splice.ValidatorLicense:ValidatorLicense",
          "contract_id": "00e17d6e36499b656f8e248c31f18130e00db30332820b6b91da9f222049f3d078ca101220d189c2482b5018eae7c656561bf201a54deb276f81e8a0d715b7305d3a03abc3",
          "payload": {
            "dso": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
            "sponsor": "Digital-Asset-2::1220dcf294bffd10439167f7460b1b6926b7a87d3237b748c7de6d7367fad3e33b20",
            "lastActiveAt": "2025-01-23T12:48:21.220193Z",
            "validator": "digitalasset-testValidator-1::1220167abbb792b81e5fb1dc45311f32eb9d796c46a5100a1cd446979176f699e117",
            "faucetState": {
              "firstReceivedFor": {
                "number": "17087"
              },
              "lastReceivedFor": {
                "number": "17122"
              },
              "numCouponsMissed": "0"
            },
            "metadata": {
              "lastUpdatedAt": "2025-01-23T07:03:50.820769Z",
              "version": "0.3.6",
              "contactPoint": "sv-support@example.com"
            }
          },
          "created_event_blob": "CgMyLjESwgYKRQDhfW42SZtlb44kjDHxgTDgDbMDMoILa5HanyIgSfPQeMoQEiDRicJIK1AY6ufGVlYb8gGlTesnb4HooNcVtzBdOgOrwxINc3BsaWNlLWFtdWxldBpuCkA5NzllYzcxMGMzYWUzYTA1Y2I0NGVkZjg0NjFhOWI0ZDdkZDIwNTNhZGQ5NTY2NGY5NGZjODllNWYxOGRmODBmEgZTcGxpY2USEFZhbGlkYXRvckxpY2Vuc2UaEFZhbGlkYXRvckxpY2Vuc2UilQNqkgMKZgpkOmJkaWdpdGFsYXNzZXQtdGVzdFZhbGlkYXRvci0xOjoxMjIwMTY3YWJiYjc5MmI4MWU1ZmIxZGM0NTMxMWYzMmViOWQ3OTZjNDZhNTEwMGExY2Q0NDY5NzkxNzZmNjk5ZTExNwpZClc6VURpZ2l0YWwtQXNzZXQtMjo6MTIyMGRjZjI5NGJmZmQxMDQzOTE2N2Y3NDYwYjFiNjkyNmI3YTg3ZDMyMzdiNzQ4YzdkZTZkNzM2N2ZhZDNlMzNiMjAKTQpLOklEU086OjEyMjA4NDE3NzY3NzM1MDM4OWRkMDcxMGQ2NTE2ZjcwMGEzM2ZlMzQ4YzVmMjcwMmRmZmVmNmQzNmUxZGVkY2JmYzE3CioKKFImCiRqIgoMCgpqCAoGCgQY/ooCCgwKCmoICgYKBBjEiwIKBAoCGAAKQQo/Uj0KO2o5CgsKCSmhJ1o2WiwGAAoJCgdCBTAuMy42Ch8KHUIbc3Ytc3VwcG9ydEBkaWdpdGFsYXNzZXQuY29tCg8KDVILCgkpYWtnBl8sBgAqSURTTzo6MTIyMDg0MTc3Njc3MzUwMzg5ZGQwNzEwZDY1MTZmNzAwYTMzZmUzNDhjNWYyNzAyZGZmZWY2ZDM2ZTFkZWRjYmZjMTcyYmRpZ2l0YWxhc3NldC10ZXN0VmFsaWRhdG9yLTE6OjEyMjAxNjdhYmJiNzkyYjgxZTVmYjFkYzQ1MzExZjMyZWI5ZDc5NmM0NmE1MTAwYTFjZDQ0Njk3OTE3NmY2OTllMTE3OWFrZwZfLAYAQioKJgokCAESIBhgA7BpKZodv42OaTYfxCqk4WQhgRJ+scPIQtmbh96gEB4=",
          "created_at": "2025-01-23T12:48:21.220193Z"
        },
        /* many similar records */,
      ],
      "next_page_token": 6033
    }

Passing this ``next_page_token`` as the ``after`` query parameter, you might see another page;
the sequence of pages terminates with an absent or null token as follows:

.. code-block:: json

    {
      "validator_licenses": [],
      "next_page_token": null
    }

Parties' Hosting Participants
-----------------------------

In any Canton deployment, `each party is hosted on a participant <https://docs.daml.com/canton/architecture/overview.html#synchronization-domain-entities>`_.
This can be accessed through Scan with `/v0/domains/{domain_id}/parties/{party_id}/participant-id <scan_openapi.html#get--v0-domains-domain_id-parties-party_id-participant-id>`_.

For example, looking up ``/v0/domains/global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17/parties/digitalasset-testValidator-1::1220e92bbc9d80cb6e283184017b307b9f44f23d32d7d195cdbcac033ae91eac2f28/participant-id`` on a test network yields

.. code-block:: json

    {
      "participant_id": "PAR::validator-runbook::1220e92bbc9d80cb6e283184017b307b9f44f23d32d7d195cdbcac033ae91eac2f28"
    }
