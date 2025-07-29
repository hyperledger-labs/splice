..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _cc_xfer_splice_wallet_nomics:

A CC Transfer using the Splice Wallet UI
========================================

A manual CC transfer using the UI of the Splice wallet (the wallet UI
built into every validator) is an example of a :ref:`cc_xfer_nomics`.
There are three types of manual transfers possible with the Splice
wallet UI:

      -  *Amulet legacy transfer* offer where the receiver has to accept the transfer offer and then automation on the sender’s side completes the transfer. The ``provider`` party on the
         `AppRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-apprewardcoupon-57229>`__
         is the ``sender``. The ``sender`` is assigned to the `ValidatorRewardCoupon <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-Amulet.html#type-splice-amulet-validatorrewardcoupon-76808>`__
         user field. These transfers are never featured.

      -  A two-step, *CN token standard based*
         `Transfer <https://docs.dev.sync.global/app_dev/api/splice-api-token-transfer-instruction-v1/Splice-Api-Token-TransferInstructionV1.html#type-splice-api-token-transferinstructionv1-transfer-51973>`__
         offer first locks the desired CC amount; on acceptance, it is
         unlocked and the transfer is completed. This does not rely on
         automation and works well for external parties. Note that there
         the two steps show up in the wallet, with each step having fees
         that are rewarded:

            -  The first step locks the CC as part of creating the transfer
               offer. Some additional CC is locked, when compared with the
               ``Amulet`` legacy transfer offer, to account for possible holding
               feeds if the request is not accepted quickly. The locking fee
               creates both an ``AppRewardCoupon`` and ``ValidatorRewardCoupon`` which
               accrue to the ``sender``. These transfers are not featured.

            -  When the ``receiver`` accepts the transfer, the CC is unlocked, the CC
               amount is transferred to the ``receiver``, and any overage change
               is returned to the ``sender``. The fees for this step generate both
               an ``AppRewardCoupon`` and ``ValidatorRewardCoupon`` which accrue to
               the ``sender``. These transfers are not featured.

      -  *One-step transfer* to a ``receiver`` that has preapproved incoming CC
         transfers set up via a
         `TransferPreapproval <https://docs.dev.sync.global/app_dev/api/splice-amulet/Splice-AmuletRules.html#type-splice-amuletrules-transferpreapproval-36220>`__
         contract. The validator operator of the ``receiver`` is the ``provider``
         party on the
         ``TransferPreapproval``
         of the ``receiver``. The
         ``TransferPreapproval``
         contract is only visible to: the ``receiver``, the receiver's
         validator operator party, and SuperValidators. These transfers can be featured
         if the receiver's validator operator's party is a featured party,
         and the ``provider`` party listed on the ``TransferPreapproval`` receives
         the reward for this transfer. The legacy or token standard based
         one-step transfers work the same.

When comparing the ``Amulet`` legacy and token standard two-step transfers,
a difference is that the token standard transfer has additional coupons
for the CC locking fees.
