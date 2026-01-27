..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    .. important::

       The validator APIs ``/v0/admin/external-party/transfer-preapproval/prepare-send``
       and ``/v0/admin/external-party/transfer-preapproval/submit-send`` are deprecated and will be removed in a future version.
       Replace any usages you  by the :ref:`Token Standard APIs <token_standard>`.

    - Wallet UI

      - The wallet UI transaction hisory now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenace overhead.

    - Scan UI

      - The scan UI transaction hisory now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenace overhead.

    - Validator app

       - The endpoints
         ``/v0/admin/external-party/transfer-preapproval/prepare-send``
         and
         ``/v0/admin/external-party/transfer-preapproval/submit-send``
         are deprecated and will be removed in a future version. Use the token standard APIs for initiating transfers instead.

    - Daml:

       - Restrict ``AmuletConfig`` to not allow fees as part of CIP FIXME. This has no functional effect
         as `CIP 78 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`_ set the fees to zero already.

         This also disables the choice ``AmuletRules_ComputeFees`` as it always returned 0. Application providers that statically
         link against ``splice-amulet`` will need to remove usages of this choice when recompiling against the new ``splice-amulet`` version.

       - Support 24h signing delays for token standard CC transfers and allocations, see CIP FIXME

         - This change concern the CC implementation of the token standard APIs,
           so no change is required for clients of these APIs to make use of the
           new 24h submission delay. However, Scan returns a slightly different choice
           context, so make sure that your app passes that along opaquely.
         - As part of this change additional constraints are imposed on ``AmuletConfig``. All of these constraints
           are satisfied by the current configs on DevNet, TestNet and MainNet:

           - CC usage fees can no longer be set to non-zero values. They were set to zero in CIP 78.

           - ``extraFeaturedAppRewardAmount`` can no longer be set to a different value than ``featuredAppActivityMarkerAmount``.
             Both of those are currently set to $1.

           - The config schedule on ``AmuletRules`` can no longer contain ``futureValues``. The ability to do so through the UI was removed in CIP 51 but
             in theory it would have still been possible to set this through internal APIs.

         - This does change transaction structure, in particular, ``AmuletRules_Transfer`` is no longer a child node of the token standard operations and some other choices.
           Token standard compliant history parsing should not require adjustments. However apps and wallets that parse the Splice choices directly may need to be adjusted.

       - ``TransferCommand`` is deprecated and will removed in a future
         version. It was originally introduced to support 24h signing
         delays and is no longer required now that this is also available
         through the token standard APIs. This also applies to the
         corresponding validator APIs
         ``/v0/admin/external-party/transfer-preapproval/prepare-send``
         and
         ``/v0/admin/external-party/transfer-preapproval/submit-send``
         which should be replaced by the :ref:`Token Standard APIs
         <token_standard>`.

       - FIXME: Add versions required for this change

    .. important::

      **Action recommended for validator operators:** upgrade to this release
      before the SVs start testing traffic-based app rewards in dry-run mode
      (see `SV Longterm Operations Schedule <https://docs.google.com/document/d/1QhLL5bL0u8temBL86y957VbWDtZJhH9udH-_C7nBlvc/edit?tab=t.0#heading=h.ripdn5ydglli>`__ for dates for the different networks).
      Otherwise, CC transfers and reward collection will stop working for parties on your node until you upgrade.

      **Action recommended for app devs:**

      **App devs whose app's Daml code statically depends on** ``splice-amulet`` should recompile their Daml code
      to link against the new version of ``splice-amulet`` listed below. Otherwise, code involving CC transfers
      will stop working as both ``OpenMiningRound`` and ``AmuletRules`` include newly introduced config fields.

      Apps that build against the :ref:`token_standard` API are not required to change except for upgrading
      their validator node.

    - Daml

      - Add ``RewardCouponV2`` to represent rewards available from traffic-based app rewards that are computed
        by the SV apps off-ledger as described in `CIP 104 <https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md>`__.
        They are created in an efficient batched fashion once per-round for every party that is eligible for traffic-based app rewards.

        In contrast to the existing reward coupons, these new coupons are using time based expiry,
        and can be minted by default up to 36h after their creation. Thereby allowing their beneficiaries
        to batch the minting to save traffic costs.

        They can be minted like all other coupon types using one of the following methods:

          1. Automated minting via the Splice Wallet backend that is part of the validator app,
             which works for onboarded internal parties and for external parties with a :ref:`minting delegation <minting-delegations>`.
          2. Direct minting by constructing calls to ``AmuletRules_Transfer`` that uses them as
             an transfer input. These calls can be made directly against the Ledger API, or indirectly
             via custom Daml code deployed to the validator node.

        Apps whose ultimate beneficiaries are different from the app provider party (e.g., decentralized apps) can use the
        :ref:`Reward Assignment API <reward_assignment_api>` to assign the rewards to their ultimate beneficiaries.

      - Add a new field ``rewardConfig`` to the ``AmuletConfig`` for configuring whether rounds should use
        traffic-based app rewards or on-ledger reward accounting, and whether traffic-based app reward coupon creation
        should be simulated in a dry-run mode. See the
        :ref:`RewardConfig <type-splice-amuletconfig-rewardconfig-87101>`
        data type definition for the list reward configuration fields and their semantics.

      - Store the current ``rewardConfig`` and ``trafficPrice`` on every ``OpenMiningRound`` contract when creating it.
        This information serves to synchronize the SV apps on the parameters to use for processing traffic-based app rewards.

      - Add ``CalculateRewardsV2`` and ``ProcessRewardsV2`` templates together with supporting code
        to implement the creation of the new reward coupons based on the reward
        values computed off-ledger by the SV apps.

      - Adjust the CC transfer implementation such that it stops creating featured app activity markers
        when it runs against a round (or external party configuration state) where traffic-based app rewards
        are enabled.
        Due to the propagation delay of updating the external party configuration state in the ``splice-amulet`` code,
        there will be a transition phase where token standard CC transfers still create featured app markers.
        These will be automatically archived as soon as traffic-based app rewards are enabled.
        Thus no double-issuance of rewards will occur.


    - FIXME: Add Daml versions implementing the CIP-104 change


