..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _cc_transfer_splice_wallet_tokenomics:

A CC Transfer using the Splice Wallet UI
****************************************

A manual CC transfer using the UI of the Splice wallet (the wallet UI
built into every validator) is an example of a :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>`.
There are three types of manual transfers possible with the Splice
wallet UI:

      -  *Amulet legacy transfer* offer where the receiver has to accept the transfer offer and then automation on the sender’s side completes the transfer. The ``provider`` party on the
         :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`
         is the party of the sender's validator node's operator. The ``sender`` is assigned to the :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`
         user field. These transfers are never featured.

      -  A two-step, *CN token standard based*
         :ref:`Transfer <type-splice-api-token-transferinstructionv1-transfer-51973>`
         offer first locks the desired CC amount; on acceptance, it is
         unlocked and the transfer is completed. This does not rely on
         automation and works well for external parties. Note that there
         the two steps show up in the wallet, with each step having fees
         that are rewarded:

            -  The first step locks the CC as part of creating the transfer
               offer. Some additional CC is locked, when compared with the
               ``Amulet`` legacy transfer offer, to account for possible holding
               fees if the request is not accepted quickly. The locking fee
               creates both an ``AppRewardCoupon`` and ``ValidatorRewardCoupon`` which
               accrue to the ``sender``. These transfers are not featured.

            -  When the ``receiver`` accepts the transfer, the CC is unlocked, the CC
               amount is transferred to the ``receiver``, and any overage change
               is returned to the ``sender``. The fees for this step generate both
               an ``AppRewardCoupon`` and ``ValidatorRewardCoupon`` which accrue to
               the ``sender``. These transfers are not featured.

         In both cases, the ``AppRewardCoupon`` reward is small because it results from usage fees.

      -  *One-step transfer* to a ``receiver`` that has preapproved incoming CC
         transfers set up via a
         :ref:`TransferPreapproval <type-splice-wallet-transferpreapproval-transferpreapprovalproposal-39002>`
         contract. The validator operator of the ``receiver`` is the ``provider``
         party on the
         ``TransferPreapproval``. The
         ``TransferPreapproval``
         contract is only visible to: the ``receiver``, the receiver's
         validator operator party, and SuperValidators.
         The transfer is featured if the ``provider`` party is a featured application provider.
         The reason for this being a featured transfer, is that this ``provider`` party enabled the receipt
         of the CC for the external party by creating the ``TransferPreapproval`` and taking care of its regular renewal.
         The legacy or token standard based
         one-step transfers work the same.

When comparing the ``Amulet`` legacy and token standard two-step transfers,
a difference is that the token standard transfer has additional coupons
for the CC locking fees.


