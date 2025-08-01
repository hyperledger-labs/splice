..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _cc_xfer_nomics:

Canton Coin Transfer
====================

A CC ``Amulet`` `Transfer <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-AmuletRules.html#type-splice-amuletrules-transfer-72721>`__
or `TwoStepTransfer <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet-TwoStepTransfer.html#splice-amulet-twosteptransfer>`__
is submitted as part of an application's business logic and it includes
a ``sender`` and a ``receiver`` or ``provider`` party. Typically, the application that
facilitated that transfer will put its own application party ID as the
``sender``.

Consider a single, simple CC ``TwoStepTransfer`` with: a single ``sender``
party, a single ``receiver`` party, one application provider, and no
beneficiaries:

      1. A `ValidatorRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-validatorrewardcoupon-76808>`__
         is created. The operator party of the validator that hosts the
         sender can mint the
         ``ValidatorRewardCoupon``
         in the minting step. This hosting relationship is tracked in the
         `ValidatorRight <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-validatorright-15964>`__\ ``(sender,
         operator)`` contract.

      2. An `AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__
         is created. By default, the ``provider`` can mint CC in the minting
         step. However, if a ``beneficiary`` is specified then they will
         receive the minted CC instead of the ``provider``.
         The
         `AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__
         is created with the DSO as the ``signatory`` and the ``provider`` field
         as an observer. The application provider can set the ``provider`` to
         its application provider ID to receive the
         `AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__.
         Setting the ``featured`` field to ``true`` indicates eligibility to
         receive featured application rewards. It is set to ``true`` by
         passing in the ``ContractId`` `FeaturedAppRight <http://127.0.0.1:8000/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-featuredappright-765>`__
         to the
         `TransferContext <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-AmuletRules.html#type-splice-amuletrules-transfercontext-68991>`__
         for the ``provider`` that is set on an
         `AmuletRules_Transfer <https://github.com/hyperledger-labs/splice/blob/ace244c252cb6412714fd645999b45deeabcdbcb/daml/splice-amulet/daml/Splice/AmuletRules.daml#L114>`__.

A Daml transaction tree can contain multiple CC transfers where each
transfer generates activity records.

It is possible to share the CC for the ``AppRewardCoupon`` across parties.
The
`executeTwoStepTransfer <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet-TwoStepTransfer.html#function-splice-amulet-twosteptransfer-executetwosteptransfer-18767>`__
choice has a ``MetaData`` field that can take an optional list of
beneficiaries and a corresponding list of weights (see
``beneficiariesFromMetadata`` in ``TokenAPiUtils.daml`` for details).

During the ``TwoStepTransfer`` the CC usage fees that are eligible for
rewards are:

-  The *percentage transfer fee* which is proportional to the total amount of CC burnt in the transaction.

-  A *base transfer fee* that is a fixed cost for each new CC contract created for the receivers or the sender.

-  A coin *locking fee* for when CC is locked during a transfer.

See the CC whitepaper for the details. Relevant fee values for a round
can be obtained from the `Scan State
API <https://docs.sync.global/app_dev/scan_api/scan_current_state_api.html>`__.

The following CC usage fees are not eligible for rewards:

-  The sender's base transfer fee. If CC contracts are merged, that merge has a sender base transfer fee.

-  The *holding fee* which is a periodic, fixed fee per separate
   `Amulet <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-amulet-63582>`__
   contract to pay for incurred network storage costs. The holding
   fee is tallied per round since the the activity record has existed
   and is paid on the next transaction involving that record. So if
   you earned or created the CC activity record in round 1000 and you
   spent it in round 1010 then you pay 10 rounds of holding fees.

