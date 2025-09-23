..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _cc_transfer_splice_wallet_tokenomics:

A CC Transfer using the Splice Wallet UI
****************************************

A manual CC transfer using the UI of the Splice wallet (the wallet UI
built into every validator) is an example of an :ref:`AmuletRules_Transfer <type-splice-amuletrules-amuletrulestransfer-23235>`.
There are three types of manual transfers possible with the Splice
Wallet UI:

      -  *Amulet legacy transfer* offer where the receiver has to accept the transfer offer and then automation on
         the sender’s side completes the transfer.
         The ``sender`` is assigned to the :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`
         user field.

         These transfers are never featured.
         Since `CIP-0078 CC Fee Removal <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`__
         was implemented, no fees are charged and thus no activity records are created.

      -  A two-step, *CN token standard based*
         :ref:`Transfer <type-splice-api-token-transferinstructionv1-transfer-51973>`
         offer first locks the desired CC amount; on acceptance, it is
         unlocked and the transfer is completed. This does not rely on
         automation and works well for external parties. Note that there
         the two steps show up in the wallet:

            #. The first step locks the CC as part of creating the transfer
               offer.
            #. When the ``receiver`` accepts the transfer, the CC is unlocked and the CC
               amount is transferred to the ``receiver``.

         Since `CIP-0078 CC Fee Removal <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`__
         was implemented, these steps neither charge any fees nor do they generate any activity records.

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
         The legacy or token standard based one-step transfers work the same.

         As for the other two transfers, no fees are charged. If the transfer is featured,
         then an ``AppRewardCoupon`` is created for the ``provider`` with the amount set
         to ``AmuletConfig.transferConfig.extraFeaturedAppRewardAmount / conversionRate`` where
         the ``USD / CC`` ``conversionRate`` is read from the ``OpenMiningRound`` contract referenced
         in the transfer.

When comparing the ``Amulet`` legacy and token standard two-step transfers,
a difference is that the token standard transfer has additional coupons
for the CC locking fees.


