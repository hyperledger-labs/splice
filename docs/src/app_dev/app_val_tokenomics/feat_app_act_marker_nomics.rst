..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _feat_app_act_marker_nomics:

Featured Application Activity Marker
=====================================

Featured application activity markers
(`FeaturedAppActivityMarker <https://docs.dev.sync.global/app_dev/api/splice-api-featured-app-v1/Splice-Api-FeaturedAppRightV1.html#splice-api-featuredapprightv1>`__)
are created in a transaction for activity on the network that adds value
but does not involve a CC Transfer (i.e., transfers a RWA like a USDC).
A ``FeaturedAppActivityMarker`` is immediately converted into an
``AppRewardCoupon`` by the automation run by the SuperValidators and the
``AppRewardCoupon`` is minted just like in a :ref:`cc_xfer_nomics`.
Please review the application activity marker
`CIP <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`__
for details. A summary follows.

There are two prerequisites for an application to create a
``FeaturedAppActivityMarker``. The first is to become an approved featured
application which was described in the
:ref:`app_val_tokenomics`
section. The second is to update the application code:

   1. Find the fully qualified interface ID for
   `FeaturedAppRight <https://docs.dev.sync.global/app_dev/api/splice-api-featured-app-v1/Splice-Api-FeaturedAppRightV1.html#type-splice-api-featuredapprightv1-featuredappright-34177>`__
   which is
   ``7804375fe5e4c6d5afe067bd314c42fe0b7d005a1300019c73154dd939da4dda:Splice.Api.FeaturedAppRightV1:FeaturedAppRight``
   for ``Splice.Api.FeaturedAppRightV1``. The command ``daml damlc
   inspect-dar`` can be used to find this.

   2. Query the ledger using this ID to retrieve contracts from the Daml
   ledger that implement the
   `FeaturedAppRight <https://docs.dev.sync.global/app_dev/api/splice-api-featured-app-v1/Splice-Api-FeaturedAppRightV1.html#type-splice-api-featuredapprightv1-featuredappright-34177>`__
   interface. See using
   `toInterface <https://docs.digitalasset.com/build/3.3/reference/daml/interfaces.html#tointerface>`__
   or
   `toInterfaceContractId <https://docs.digitalasset.com/build/3.3/reference/daml/interfaces.html#tointerfacecontractid>`__
   for more information.

   3. In the application, using the ``FeaturedAppRight`` interface, exercise
   the ``FeaturedAppRight_CreateActivityMarker`` choice. Set the
   templateId to the fully qualified interface ID above.

   4. For testing examples, please review the example DamlScript test
   `here <https://github.com/hyperledger-labs/splice/blob/main/daml/splice-dso-governance-test/daml/Splice/Scripts/TestFeaturedAppActivityMarkers.daml>`__.

A non-featured app cannot accrue a ``FeaturedAppActivityMarker``.

Consider a single, simple transaction of a RWA which creates a single
``FeaturedAppActivityMarker`` activity record for one ``provider`` and the
``beneficiary`` is the ``provider``:

      1. A `FeaturedAppActivityMarker <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-featuredappactivitymarker-16451>`__
         contract is created in the business transaction. The ``provider`` is
         set to the featured application provider's party. The ``beneficiary``
         must be set (unlike the
         `AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__)
         to the party that would receive the CC. The ``provider`` field of the
         `FeaturedAppActivityMarker <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-featuredappactivitymarker-16451>`__
         is set by calling the interface choice
         `FeaturedAppRight_CreateActivityMarker <https://docs.dev.sync.global/app_dev/api/splice-api-featured-app-v1/Splice-Api-FeaturedAppRightV1.html#type-splice-api-featuredapprightv1-featuredapprightcreateactivitymarker-36646>`__.

      2. No ``ValidatorRewardCoupon`` is created.

There can be several ``FeaturedAppActivityMarkers`` per transaction tree
which increases the total reward. It is also possible for a single
Canton transaction tree to include
`ValidatorRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-validatorrewardcoupon-76808>`__,
an
`AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__
and a
`FeaturedAppActivityMarker <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-featuredappactivitymarker-16451>`__\ (s)
if there are sub-transcations that create each separately.

It is possible to share the CC for the ``FeaturedAppActivityMarker``. The
``FeaturedAppRight_CreateActivityMarker`` choice accepts a list of
`AppRewardBeneficiary <https://docs.dev.sync.global/app_dev/api/splice-api-featured-app-v1/Splice-Api-FeaturedAppRightV1.html#type-splice-api-featuredapprightv1-apprewardbeneficiary-32645>`__
contracts. Then a ``FeaturedAppActivityMarker`` is created for each
``beneficiary`` with the ``weight`` field set appropriately.
