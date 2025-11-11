..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _overview_tokenomics:

Steps in a Round
****************

Canton Network tokenomics is based on an *Activity Record* which
identifies a party that performed an action which provides value to the
network. An activity record has a *weight* which is the the relative share of CC minting
associated with this activity record.

Creating an activity record and minting the associated Canton
Coin (CC) are two distinct steps. The creation and minting steps are
performed in a cycle that is called a *round* which has five phases. In
the first phase, any fee values for that round are written to the ledger
(the fees can be obtained from the :ref:`Scan State API <scan_current_state_api>`.
The second phase is called the *activity recording* and it is when
activity records are created; records created in this phase belong to that round. The next phase calculates
a `CC-issuance-per-activity-weight <https://github.com/hyperledger-labs/splice/blob/332e06a7ae9e13fde5bba0bf7dcb059aa36f979e/daml/splice-amulet/daml/Splice/Issuance.daml#L67>`__
for each kind of activity record which is the share of total CC
that can be minted for this type of activity record.
This is followed by
a *minting phase* where the owners of an activity record can mint CC proportional to its minting weight.

There are several rounds active
concurrently with each round being in a different phase. A round starts
every 10 minutes, which is configuration parameter that the Super Validators may change in the future via a governance vote. See the CC
whitepaper for the details.

There is no difference in activity record creation for an `external
party <https://docs.digitalasset.com/build/3.3/tutorials/app-dev/external_signing_onboarding.html#tutorial-onboard-external-party>`__
or local party but there is a difference in the automation support used
in the minting phase. For local parties onboarded to a validator, the
validator application runs background automation to mint all activity
records automatically. An external party signs transactions using a key
they control. As a consequence, the validator automation is not able to
perform minting for external parties. For external parties, automation needs
to be developed to call :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>` at least once per round
with all activity records as inputs.  An accepted CIP, called `Weighted Validator Liveness Rewards for SV-Determined Parties <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0073/cip-0073.md>`__,
is available for comment soon which describes providing this support.

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
``FeaturedAppActivityMarker`` is converted into an ``AppRewardCoupon`` via
automation run by the Super Validators.

The ``FeaturedAppActivityMarker``,
``AppRewardCoupon``, and ``ValidatorRewardCoupon`` contracts are created when an
application's transaction succeeds. In general, an application receives rewards when its Daml code directly creates ``FeaturedAppActivityMarker`` contracts
or interacts with Daml models that feature the application provider's party.  A ``ValidatorRewardCoupon`` is created for every call to ``AmuletRules_Transfer``
(e.g., a CC Transfer using the Splice Wallet UI) or when CC is burned.

Aside from the minting weight, an application's reward also depends on
whether it is designated as *featured* or *unfeatured* (the default
state). An unfeatured application receives a smaller reward and has a
lower cap on the amount it can mint. A featured application receives
larger rewards and has a higher cap. A *featured application* also
receives an additional minting weight with a total equivalent value of about
$1 US (the SuperValidators may adjust this in the future).

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

-  A list of beneficiaries, each with a ``weight``, is provided. The weights  sum up to ``1.0``.

-  Later processing creates a separate contract for each beneficiary and weight pair,
   setting the contract's ``beneficiary`` and ``weight`` fields accordingly.

Beneficiaries are discussed further in the following sections.

Fees
****

The weight on a ``ValidatorRewardCoupon`` and ``AppRewardCoupon`` is the sum of the create, lock and transfer fees,
excluding the base transfer fees for the “change”.  These fees eligible for reward are:

-  The *percentage transfer fee* which is proportional to the total amount of CC burnt in the transaction.

-  A *base transfer fee* that is a fixed cost for each new CC contract created for the receivers or the sender.

-  A coin *locking fee* for when CC is locked during a transfer.

See the CC whitepaper for the details. Relevant fee values for a round
can be obtained from the :ref:`Scan State API <scan_current_state_api>`.

A holding fee is paid by a CC holder but is not eligible for rewards.  This is a fixed fee, per separate coin contract (UTXO) per unit
of time, that is independent of the coin amount.  It promotes merging of CC to reduce network storage use. The holding
fee is tallied per round since creation
and is paid on the next transaction involving that record. So if
you earned or created the CC activity record in round 1000 and you
spent it in round 1010 then you pay 10 rounds of holding fees.

Please note that the reward from usage fees are very small in comparison to the reward for being a featured application.  To simplify the tokenomics and implementation, usages fees and their corresponding rewards may be dropped as part of a future CIP.
