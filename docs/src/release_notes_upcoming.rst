..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    .. important::

        **SV node operators must only deploy this release on networks where
        all other SV nodes run Splice 0.5.10 or higher!**
        Otherwise the sequencers will disagree on the traffic cost of confirmation responses and fork the ledger.

        The reason for this is that sequencer nodes running code prior to Splice 0.5.10
        do not parse the new dynamic domain parameter that is used to perform a coordinated update
        of the traffic cost of confirmation responses across all SV nodes. These sequencers would thus
        ignore the change in the traffic cost computation.

        Validator node operators are not affected by this requirement, as the change only affects sequencer nodes.

    - SV app

      - Add a new config parameter to control whether the SV app should enable free confirmation responses
        in the dynamic domain parameters.
        This new parameter is set to ``true`` by default, so that no manual config changes
        by SV operators are required to enable free confirmation responses on networks running this version of Splice.

        This change implements Increment 1 "Make confirmation responses free using the heuristic implementation" from
        `CIP-104 - Traffic-Based App Rewards <https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md#incremental-roll-out>`__.

    - Daml

      - Optimize the number of views in the automation run by the SV app to convert ``FeaturedAppActivityMarker`` contracts into ``AppRewardCoupon`` contracts.

        This requires a Daml upgrade to

          ================== =======
          name               version
          ================== =======
          amulet             0.1.16
          amuletNameService  0.1.17
          dsoGovernance      0.1.22
          splitwell          0.1.16
          validatorLifecycle 0.1.6
          wallet             0.1.17
          walletPayments     0.1.16
          ================== =======
