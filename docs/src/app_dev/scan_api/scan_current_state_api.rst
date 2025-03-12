..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scan_current_state_api:

Scan API for Current state of CC and Synchronizer Traffic
=========================================================

Alongside information about the :ref:`overall Splice network operations <scan_global_synchronizer_operations_api>`, Scan provides some more specific details about current Amulet-related network state.

Validator Traffic Credits and Purchases
---------------------------------------

Sequencing messages on the global synchronizer,
which is an integral part of the Canton commit protocol for Daml transactions and validator operations more generally,
costs :ref:`traffic fees <traffic>` purchased in Amulet.
`/v0/domains/{domain_id}/members/{member_id}/traffic-status <scan_openapi.html#get--v0-domains-domain_id-members-member_id-traffic-status>`_ can be used to query the purchased and spent traffic credit.

Here's an example response from querying for a member participant.

.. code-block:: json

    {
      "traffic_status": {
        "actual": {
          "total_consumed": 0,
          "total_limit": 6000000
        },
        "target": {
          "total_purchased": 6000000
        }
      }
    }

``total_limit`` is the most important statistic; ``total_purchased`` is usually the same, but may be higher if a purchase is still in process.

Open Mining Rounds
------------------

Amulet activity and traffic are associated with rounds, which determine how rewards are allocated.
To check on the current rounds available for traffic or with rewards currently in process, check `/v0/open-and-issuing-mining-rounds <scan_openapi.html#post--v0-open-and-issuing-mining-rounds>`_.

This endpoint features a parameter for more efficient polling; see the ``MaybeCachedContractWithStateMap`` OpenAPI specification for more details.
The initial request is always empty:

.. code-block:: json

    {
      "cached_open_mining_round_contract_ids": [],
      "cached_issuing_round_contract_ids": []
    }

This might respond with something like

.. code-block:: json

    {
      "time_to_live_in_microseconds": 600000000,
      "open_mining_rounds": {
        "00dea42d6e8aa5cdd8110564774b740943c2f0d57ecef624f2f9ab881a847f3ebfca1012200109878982101a4f2b8106091bdde79793daf8548662f9962b371adc8c3aa294": {
          "contract": {
            "template_id": "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f:Splice.Round:OpenMiningRound",
            "contract_id": "00dea42d6e8aa5cdd8110564774b740943c2f0d57ecef624f2f9ab881a847f3ebfca1012200109878982101a4f2b8106091bdde79793daf8548662f9962b371adc8c3aa294",
            "payload": {
              "dso": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
              "tickDuration": {
                "microseconds": "600000000"
              },
              "issuingFor": {
                "microseconds": "12474000000000"
              },
              "amuletPrice": "0.005",
              "issuanceConfig": /* ... */,
              "opensAt": "2025-02-18T22:18:42.495769Z",
              "transferConfigUsd": {
                "holdingFee": {
                  "rate": "0.0000190259"
                },
                /* other configuration */
              },
              "targetClosesAt": "2025-02-18T22:38:42.495769Z",
              "round": {
                "number": "20790"
              }
            },
            /* other contract metadata */
          },
          "domain_id": "global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17"
        },
        /* other open rounds; there are usually 3 */
      },
      "issuing_mining_rounds": {
        "00f15ce85311175cc62acab235d768f5c8b1e02247a1d0e4c54635fc2b43726262ca101220cc210224ad72ee4a71ae887732898f0f1db81e530b3817e75500ebe5a8a4f7a6": {
          "contract": {
            "template_id": "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f:Splice.Round:IssuingMiningRound",
            "contract_id": "00f15ce85311175cc62acab235d768f5c8b1e02247a1d0e4c54635fc2b43726262ca101220cc210224ad72ee4a71ae887732898f0f1db81e530b3817e75500ebe5a8a4f7a6",
            "payload": {
              "dso": "DSO::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17",
              "optIssuancePerValidatorFaucetCoupon": "324.0134341208",
              "issuancePerFeaturedAppRewardCoupon": "100.0",
              "opensAt": "2025-02-18T22:29:35.850299Z",
              "issuancePerSvRewardCoupon": "0.4058853374",
              "targetClosesAt": "2025-02-18T22:49:35.850299Z",
              "issuancePerUnfeaturedAppRewardCoupon": "0.6",
              "round": {
                "number": "20788"
              },
              "issuancePerValidatorRewardCoupon": "0.2"
            },
            /* other contract metadata */
          },
          "domain_id": "global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17"
        },
        /* any other issuing rounds */
      }
    }

Key fields in the ``contract.payload`` of open and issuing mining rounds are ``round``, ``opensAt``, and ``targetClosesAt``, which gives you an idea of the open window for the round.
Open rounds also include many fields explaining the assigned fees for that round; see the Daml template ``OpenMiningRound`` for more details.
For followup polling requests, you'll want to pass the keys from the previously-returned maps so Scan responds more efficiently.

Conversion rate
^^^^^^^^^^^^^^^

The conversion rate for Amulet to USD is set for an open round.
The conversion rate is found in the ``amuletPrice`` field in the ``contract.payload`` of an open round. In the example above, the conversion rate is 0.005 USD per Amulet.

Closed Mining Rounds
--------------------

`/v0/closed-rounds <scan_openapi.html#get--v0-closed-rounds>`_ is more niche than `Open Mining Rounds`_; it usually yields an empty response.
However, it can yield some ``ClosedMiningRound`` Daml contracts if there are unclaimed rewards for that round, or a final confirmation for the round closure hasn't been created yet; as validators operate asynchronously around the Daml ledger to complete these operations, this can be briefly true, but ideally for as short a time as possible.
