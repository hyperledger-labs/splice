..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _cc_xfer_tokenomics:

Canton Coin Transfer
====================

A CC ``Amulet`` :ref:`Transfer <type-splice-amuletrules-transfer-72721>`
is submitted as part of an application's business logic and it includes
a ``sender`` and a ``provider`` party. Typically, the application that
facilitated that transfer will put its own application party ID as the
``sender``.

Consider a single, simple CC ``TwoStepTransfer`` with: a single ``sender``
party, a single ``receiver`` party, one application provider, and no
beneficiaries:

      1. A :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`
         is created. The operator party of the validator that hosts the
         sender can mint the
         ``ValidatorRewardCoupon``
         in the minting step. This hosting relationship is tracked in the
         :ref:`ValidatorRight <type-splice-amulet-validatorright-15964>`\ ``(sender,
         operator)`` contract.

      2. An :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`
         is created. By default, the ``provider`` can mint CC in the minting
         step. However, if a ``beneficiary`` is specified then they will
         receive the minted CC instead of the ``provider``.
         The :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`
         is created with the DSO as the ``signatory`` and the ``provider`` field
         as an observer. For the application provider to receive the :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`, the Daml application code sets the ``provider`` value to the application provider's party in the :ref:`Transfer <type-splice-amuletrules-transfer-72721>` contract parameter of the :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>` choice.
         Setting the ``featured`` field to ``true`` indicates eligibility to
         receive featured application rewards. It is set to ``true`` by
         passing in the ``ContractId`` :ref:`FeaturedAppRight <type-splice-amulet-featuredappright-765>`
         to the
         :ref:`TransferContext <type-splice-amuletrules-transfercontext-68991>`
         for the ``provider`` that is set on an
         :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>`.

A Daml transaction tree can contain multiple CC transfers where each
transfer generates activity records.

It is possible to share the CC for the ``AppRewardCoupon`` across parties.
The :ref:`Transfer <type-splice-amuletrules-transfer-72721>` input to the :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>` choice has an optional ``beneficiaries`` field.
The
:ref:`Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529>`
choice has a :ref:`MetaData <module-splice-api-token-metadatav1-34807>` field that can take an optional list of
beneficiaries and a corresponding list of weights (see
``beneficiariesFromMetadata`` in ``TokenApiUtils.daml`` for details).

During the :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>` choice, the CC usage fees that are eligible for
rewards are:

-  The *percentage transfer fee* which is proportional to the total amount of CC burnt in the transaction.

-  A *base transfer fee* that is a fixed cost for each new CC contract created for the receivers or the sender.

-  A coin *locking fee* for when CC is locked during a transfer.

See the CC whitepaper for the details. Relevant fee values for a round
can be obtained from the :ref:`Scan State API <type-splice-amulet-amulet-63582>`
contract to pay for incurred network storage costs. The holding
fee is tallied per round since the the activity record has existed
and is paid on the next transaction involving that record. So if
you earned or created the CC activity record in round 1000 and you
spent it in round 1010 then you pay 10 rounds of holding fees.

Please note that the reward from usage fees are very small in comparison to the reward for being a featured application.  To simplify the tokenomics and implementation, the reward form usage fees may be dropped in the future.

It would be good to add a disclaimer to the documentation somewhere calling out the the usage fees are small and might be removed, compared to the gains from being a featured app.

Please note that direct calls to ``AmuletRules_Transfer``
tightly couples the upgrading cycles of one's app to Splice upgrades, which means every Splice Daml model
upgrade also requires an upgrade of the application's Daml code.
