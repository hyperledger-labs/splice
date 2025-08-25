..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _app_val_tokenomics:

Tokenomics Overview
===================

Following is an overview of the application and validator tokenomics for
application developers or validator operators. It builds on the "`Canton
Coin: A Canton-Network-native payment
application <https://www.digitalasset.com/hubfs/Canton%20Network%20Files/Documents%20(whitepapers%2c%20etc...)/Canton%20Coin_%20A%20Canton-Network-native%20payment%20application.pdf?__hstc=169870847.16854726061d8b28be85af48c17588c4.1750870506327.1750870506327.1750870506327.1&__hssc=169870847.1.1750870506328&__hsfp=1243925796&_gl=1*1fkzmuj*_gcl_au*MTkyOTE1NjAyNC4xNzUwODcwNTA2*_ga*NDU1NzM2NzgyLjE3NTA4NzA1MDY.*_ga_GVK9ZHZSMR*czE3NTA4NzA1MDUkbzEkZzAkdDE3NTA4NzA1MDUkajYwJGwwJGgw>`__"
whitepaper and assumes you have read the whitepaper.

Canton Network tokenomics is based on an *Activity Record* which
identifies a party that performed an action which provides value to the
network. Creating an activity record and minting the associated Canton
Coin (CC) are two distinct steps. The creation and minting steps are
performed in a cycle that is called a *round* which has five phases. In
the first phase, any fee values for that round are written to the ledger
(the fees can be obtained from the `Scan State
API <https://docs.sync.global/app_dev/scan_api/scan_current_state_api.html>`__).
The second phase is called the *activity summary* and it is when
activity records are created. The next phase calculates a *minting
weight* for each kind of activity record which is the share of total CC
that can be minted for this type of activity record.
This is followed by
a *minting phase* where the owners of an activity record can mint CC proportional to its minting weight. There are several rounds active
concurrently with each round being in a different phase. A round starts
every 10 minutes, which is configuration parameter that the Super Validators may change in the future via a governance vote. See the CC
whitepaper for the details.

There are five key templates involved in the accounting for network activity:

-  Two templates are application related:

      - :ref:`FeaturedAppActivityMarker <type-splice-amulet-featuredappactivitymarker-16451>`

      - :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`

-  Three templates relate to providing the infrastructure for the activity:

      - :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`

      - :ref:`ValidatorLivenessActivityRecord <type-splice-validatorlicense-validatorlivenessactivityrecord-17293>`

      - :ref:`SvRewardCoupon <type-splice-amulet-svrewardcoupon-68580>`

The last four are activity records while an ``FeaturedAppActivityMarker`` is not considered an activity record. As discussed later, a
``FeaturedAppActivityMarker`` is converted into an ``AppRewardCoupon`` via
automation.

The ``FeaturedAppActivityMarker``,
``AppRewardCoupon``, and ``ValidatorRewardCoupon`` contracts are created when an
application's transaction succeeds. In general, an application receives rewards when its Daml code directly creates ``FeaturedAppActivityMarker`` contracts
or interacts with Daml models that feature the application provider's party.

As an aside, other templates that are important to the tokenomics are:

- :ref:`Amulet <type-splice-amulet-amulet-63582>`, which stores the fees schedules;

- :ref:`OpenMiningRound <type-splice-round-openmininground-90060>`, which stores the price and fees as of opening the round;

- :ref:`IssuingMiningRound <type-splice-round-issuingmininground-98097>`, which stores the amount-to-mint-per-activity-right.

There is no difference in activity record creation for an `external
party <https://docs.digitalasset.com/build/3.3/tutorials/app-dev/external_signing_onboarding.html#tutorial-onboard-external-party>`__
or local party but there is a difference in the automation support used
in the minting phase. For local parties onboarded to a validator, the
validator application runs background automation to mint all activity
records automatically. An external party signs transactions using a key
they control. As a consequence, the validator automation is not able to
perform minting for external parties. For external parties, automation needs
to be developed to call ``AmuletRules_Transfer`` at least once per round
with all activity records as inputs.  A draft CIP, called "Weighted Validator Liveness Rewards for SV-Determined Parties",
will be available for comment soon which describes providing this support.
cip-0073/cip-0073.md

Aside from the minting weight, an application's reward also depends on
whether it is designated as *featured* or *unfeatured* (the default
state). Featured applications create activity records that record the featured designation
which is why  minting a featured activity record has a larger minting weight.
So, an unfeatured application receives a smaller reward and has a
lower cap on the amount it can mint. A featured application receives
larger rewards and has a higher cap. A *featured application* also
receives an additional minting weight with a total equivalent value of about
$1 US (the SuperValidators may adjust this in the future). To become a
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

More detail is provided in the following sections.

The following use cases are used in explaining the application and
validator tokenomics below:

.. toctree::
   cc_transfer_tokenomics
   feat_app_act_marker_tokenomics
   cc_transfer_splice_wallet_tokenomics
   traffic_tokenomics
   val_live_tokenomics
   sv_live_tokenomics
