..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _feat_app_act_marker_tokenomics:

Featured Application Activity Marker
=====================================

Featured application activity markers
(:ref:`FeaturedAppActivityMarker <type-splice-amulet-featuredappactivitymarker-16451>`)
can be created for a transaction that adds value
but does not involve a CC Transfer (e.g., a stable coin transfer or the settlement of a trade).
A ``FeaturedAppActivityMarker`` is immediately converted into an
``AppRewardCoupon`` by the automation run by the Super Validators and the
``AppRewardCoupon`` is minted just like in a :ref:`cc_xfer_tokenomics`.
Please review the application activity marker
`CIP <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`__
for details. A summary follows.

There are two prerequisites for an application to create a
``FeaturedAppActivityMarker``. The first is to become an approved featured
application which was described in the
:ref:`app_val_tokenomics`
section. The second is to update the application code:

   1. Find the fully qualified package-id of the interface definition for the FeaturedAppRight
   :ref:`FeaturedAppRight <type-splice-api-featuredapprightv1-featuredappright-34177>`
   interface which is
   ``7804375fe5e4c6d5afe067bd314c42fe0b7d005a1300019c73154dd939da4dda:Splice.Api.FeaturedAppRightV1:FeaturedAppRight``
   for ``Splice.Api.FeaturedAppRightV1``. The command ``daml damlc
   inspect-dar`` can be used to find this.

   2. Query the ledger using this ID to retrieve contracts from the Daml
   ledger that implement the
   :ref:`FeaturedAppRight <type-splice-api-featuredapprightv1-featuredappright-34177>`
   interface. The ``curl`` example below illustrates this approach.

   .. code-block:: bash

      curl "http://$lapiParticipant/v2/state/active-contracts" \
      "$jwtToken" "application/json" \
         --data-raw '{
         "filter": {
         "filtersByParty": {
               "'$holderPartyId'": {
               "cumulative":
               [
                  {
                     "identifierFilter": {
                     "InterfaceFilter": {
                        "value": {
                           "interfaceId": "'7804375fe5e4c6d5afe067bd314c42fe0b7d005a1300019c73154dd939da4dda:Splice.Api.FeaturedAppRightV1:FeaturedAppRight'",
                           "includeInterfaceView": true,
                           "includeCreatedEventBlob": false
                        }
                     }
                     }
                  }
               ]}
         }
         },
         "verbose": false,
         "activeAtOffset":"'$latestOffset'"
      }'

   3. The application's Daml code will have to depend on the ``splice-api-featured-app-v1.dar`` and take an argument of type ``ContractId FeaturedAppRight`` on the choice
   whose execution should be featured, which allows that choice's body to call the ``FeaturedAppRight_CreateActivityMarker`` in the next step.

   3. In the application's Daml code, using the ``FeaturedAppRight`` interface, exercise
   the ``FeaturedAppRight_CreateActivityMarker`` choice. Set the
   ``templateId`` to the fully qualified interface ID above.

   4. For testing examples, please review the example DamlScript test
   `here <https://github.com/hyperledger-labs/splice/blob/a32995a0df2d447b9e76d81b770a06c296295ab5/daml/splice-dso-governance-test/daml/Splice/Scripts/TestFeaturedAppActivityMarkers.daml#L4>`__.

A non-featured app cannot accrue a ``FeaturedAppActivityMarker``.

Consider a single, simple transaction of a RWA which creates a single
``FeaturedAppActivityMarker`` activity record for one ``provider`` and
the ``beneficiary`` is the ``provider``:

      1. A :ref:`FeaturedAppActivityMarker <type-splice-amulet-featuredappactivitymarker-16451>` contract is created in the business transaction. The
      ``provider`` is set to the featured application provider's party. The ``beneficiary`` must be set (unlike the
      :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`) to the party that should be eligible to mint the CC for that activity. The ``provider`` field of the
      FeaturedAppActivityMarker is set by calling the interface choice :ref:`FeaturedAppRightreateActivityMarker <type-splice-api-featuredapprightv1-featuredapprightcreateactivitymarker-36646>`.

      2. No ``ValidatorRewardCoupon`` is created.

There can be several ``FeaturedAppActivityMarkers`` per transaction tree
which increases the total reward. However,  this is only allowed for composed transactions (e.g. a settlement transaction) where trading
venue and all the registries of the transferred assets would get featured app rewards. It is also possible for a single
Canton transaction tree to include
:ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`,
an
:ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`
and a
:ref:`FeaturedAppActivityMarker <type-splice-amulet-featuredappactivitymarker-16451>`\ (s)
if there are sub-transcations that create each separately.

It is possible to share the attribution of activity for the ``FeaturedAppActivityMarker``. The
``FeaturedAppRight_CreateActivityMarker`` choice accepts a list of
:ref:`AppRewardBeneficiary <type-splice-api-featuredapprightv1-apprewardbeneficiary-32645>`
contracts. Then a ``FeaturedAppActivityMarker`` is created for each
``beneficiary`` with the ``weight`` field set appropriately.
