..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV App

        - Package versions newer than the version specified in the AmuletRules configuration are now automatically unvetted by the SV app after a successful downgrade vote.

    - Deployment

        - SV apps now support a ``copyVotesFrom`` setting that automatically mirrors governance votes
          from another named SV, which can help operators keep votes in sync when they run multiple SV nodes.

        - The SV helm chart now supports a new ``synchronizers`` value that replaces the previous ``domain`` value.
          The new structure allows configuring ``current``, ``successor``, and ``legacy`` synchronizer nodes, each with
          ``sequencerPublicUrl``, ``sequencerAddress``, ``mediatorAddress``, optional ``sequencerPruningConfig``,
          ``enableBftSequencer``, and inline ``cometBFT``.
          The ``synchronizers.skipInitialization`` field replaces ``domain.skipInitialization``.
          The previous ``domain`` value is still accepted for backwards compatibility but cannot be combined with ``synchronizers``.
          We strongly recommend updating your ``sv-values.yaml`` to use the new ``synchronizers`` structure, as
          the ``domain`` value will be removed in a future release.
          See :ref:`helm-sv-install` for the updated configuration instructions.

        - The Scan helm chart now supports a new ``synchronizers`` value that replaces the previous top-level
          ``sequencerAddress``, ``mediatorAddress``, and ``bftSequencers`` values.
          The new structure requires ``synchronizers.current.sequencer`` and ``synchronizers.current.mediator``, and
          optionally supports ``successor`` and ``legacy`` entries with the same fields, as well as per-synchronizer
          ``bftSequencerConfig.p2pUrl``.
          The previous ``sequencerAddress`` and ``mediatorAddress`` values are still accepted for backwards compatibility
          but cannot be combined with ``synchronizers``.
          We strongly recommend updating your ``scan-values.yaml`` to use the new ``synchronizers`` structure, as
          the previous values will be removed in a future release.
          See :ref:`helm-sv-install` for the updated configuration instructions.

    - Scan

        - Added a new ``GET /v2/updates/hash/{hash}`` endpoint that returns the update associated with a given external transaction hash of a prepared transaction.
          This endpoint is not always BFT safe, see the Scan OpenAPI documentation for details.

        - ``POST /v0/state/acs`` has been labeled as deprecated, and replaced by a newer ``POST /v1/state/acs``.
          The new `/v1` endpoint replaces the event ID in the response from `/v0` by an (optional) update ID. The update ID for
          each contract in the ACS refers to the update in which the contract has been created. This value is
          guaranteed to be consistent across all instances of Scan, therefore is suitable for BFT reads.
          The update ID will be omitted for contracts created in a prior migration ID, or potentially in the
          future in extreme cases of disaster recovery.

    - LocalNet

        - Added support for configuring the protocol version used in LocalNet.

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
