..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _overview_tokenomics:

Steps in a Round
****************

Canton Network tokenomics is based on an *Activity Record* which
identifies a party that performed an action which provides value to the
network. An activity record has a *weight* which is the relative share of CC minting
associated with this activity record.

Creating an activity record and minting the associated Canton
Coin (CC) are two distinct steps. The creation and minting steps are
performed in a cycle that is called a *round* which has five phases. In
the first phase, any fee values for that round are written to the ledger
(the fees can be obtained from the :ref:`Scan State API <scan_current_state_api>`).
The second phase is called the *activity recording* and it is when
activity records are created; records created in this phase belong to that round. The next phase calculates
a `CC-issuance-per-activity-weight <https://github.com/hyperledger-labs/splice/blob/332e06a7ae9e13fde5bba0bf7dcb059aa36f979e/daml/splice-amulet/daml/Splice/Issuance.daml#L67>`__
for each kind of activity record which is the share of total CC
that can be minted for this type of activity record.
This is followed by
a *minting phase* where the owners of an activity record can mint CC proportional to its minting weight.

There are several rounds active
concurrently with each round being in a different phase. A round starts
every 10 minutes, which is a configuration parameter that the Super Validators may change in the future via a governance vote. See the CC
whitepaper for the details.

There is no difference in activity record creation for an `external party
<https://docs.digitalasset.com/build/3.4/tutorials/app-dev/external_signing_onboarding.html#tutorial-onboard-external-party>`__ or a
local party, but there is a difference in the automation support used in the minting phase. For local parties onboarded to a
validator, the validator application runs background automation to mint all activity records automatically. An external party signs
transactions using a key they control. As a consequence, the validator automation is not able to perform minting for external
parties directly. For external parties, there are two options:

1. Use a :ref:`minting delegation <validator-delegations>` to delegate reward collection to a validator,
   avoiding the need to build custom automation.
2. Develop custom automation to call :ref:`AmuletRules_Transfer
   <type-splice-amuletrules-amuletrulestransfer-23235>` at least once per round with all activity records as inputs.

An approved CIP called `Weighted Validator Liveness Rewards for SV-Determined Parties
<https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0073/cip-0073.md>`__ describes providing this support.

As an aside, some interesting templates are important to the tokenomics are:

- :ref:`AmuletRules <type-splice-amuletrules-amuletrules-32426>` which stores the fees schedules;

- :ref:`OpenMiningRound <type-splice-round-openmininground-90060>` which stores the price and fees as of opening the round;

- :ref:`IssuingMiningRound <type-splice-round-issuingmininground-98097>` which stores the amount-to-mint-per-activity-weight.

.. _types_of_activity_records:

Types of Activity Records
*************************

There are five key templates involved in the accounting for network activity:

-  Two templates are application related:

      - :ref:`FeaturedAppActivityMarker <type-splice-amulet-featuredappactivitymarker-16451>`

      - :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`

-  Three templates relate to providing the infrastructure for the applications:

      - :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`

      - :ref:`ValidatorLivenessActivityRecord <type-splice-validatorlicense-validatorlivenessactivityrecord-17293>`

      - :ref:`SvRewardCoupon <type-splice-amulet-svrewardcoupon-68580>`

The last four are activity records while a ``FeaturedAppActivityMarker`` is not considered an activity record. As discussed later, a
``FeaturedAppActivityMarker`` is converted into an ``AppRewardCoupon`` via automation run by the Super Validators.  The featured CC transfer and `FeaturedAppActivityMarker`` both generate the same reward. The ``FeaturedAppActivityMarker`` is the
preferred way to generate app activity records.

The ``FeaturedAppActivityMarker``,
``AppRewardCoupon``, and ``ValidatorRewardCoupon`` contracts are created when an
application's transaction succeeds. In general, an application receives rewards when its Daml code directly creates ``FeaturedAppActivityMarker`` contracts
or interacts with Daml models that feature the application provider's party.  A ``ValidatorRewardCoupon`` is created for every call to ``AmuletRules_Transfer``
(e.g., a CC Transfer using the Splice Wallet UI) or when CC is burned.

Aside from the minting weight, an application's reward also depends on whether it is designated as *featured* or *unfeatured* (the
default state). After CIP-0078 was implemented, only featured applications get a reward.
A featured application receives a minting weight with a total
equivalent value of about $1 US (the SuperValidators may adjust this in the future).

.. _how_to_become_a_featured_application:

How to become a featured application
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To become a
featured application you need an *application provider's party ID* which
is an input to the application. That process starts by filling in `this
form <https://sync.global/featured-app-request/>`__. The request goes to
the tokenomics committee who reviews the application and responds to it.
This `webpage <https://lists.sync.global/g/tokenomics/topics>`__ lists
the tokenomics committees topics for tracking. Here’s an `example of a
successful
submission <https://lists.sync.global/g/tokenomics/topic/new_featured_app_request/112787885>`__.
Note that, for testing purposes, you can self-feature your application
on DevNet.

For some of the templates, the attribution of activity can be shared with multiple beneficiary parties. For example, a featured application reward can
be shared between the application provider and application user, based
on a given ``weight`` for each. The general pattern for this is:

-  A list of beneficiaries, each with a ``weight``, is provided. The weights sum up to ``1.0``.

-  Later processing creates a separate contract for each beneficiary and weight pair,
   setting the contract's ``beneficiary`` and ``weight`` fields accordingly.

Beneficiaries are discussed further in the following sections.

`CIP-0078 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`__ eliminated
almost all fees for Canton Coin transfers and locks so unfeatured applications no longer receive any reward.
The holding fee remains but its behavior changed so that no holding fees are charged when using a coin as an input to a transfer.

The holding fees is a fixed fee, per separate coin contract (UTXO) per unit
of time, that is independent of the coin amount.  It promotes merging of CC to reduce network storage use by incentivizing merging or removal of dust coins.
Holding fees are not charged when transferring coins but only explicitly on expired coin contracts via the ``Amulet_Expire`` choice.
A coin contract (UTXO) may be expired by the Super Validators once its accrued holding fees are greater than its coin value.
This makes accounting for holding fees simple as the value of the coin contract is constant and independent of the holding fee.
