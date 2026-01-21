..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator-delegations:

Minting Delegations
-------------------

Minting delegations allow a delegate to mint rewards on behalf of another party (the beneficiary).
This is useful for external parties who want someone else to manage their reward collection
without running their own wallet automation.

.. note::
   While this document focuses on validators as delegates, any user with the Delegations tab
   visible in their wallet can act as a delegate. Validator operators can also use a separate
   user account as the delegate rather than their operator account.

Overview
++++++++

A **minting delegation** grants a validator (the delegate) the authority to:

- Mint reward coupons on behalf of a beneficiary party
- Auto-merge amulets for the beneficiary (up to the configured limit)

The following reward coupon types may be minted through a delegation:

- :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`
- :ref:`UnclaimedActivityRecord <type-splice-amulet-unclaimedactivityrecord-97331>`
- :ref:`DevelopmentFundCoupon <type-splice-amulet-developmentfundcoupon-75673>`
- :ref:`AppRewardCoupon <type-splice-amulet-apprewardcoupon-57229>`
- :ref:`ValidatorLivenessActivityRecord <type-splice-validatorlicense-validatorlivenessactivityrecord-17293>`

.. note::
   To mint :ref:`ValidatorRewardCoupon <type-splice-amulet-validatorrewardcoupon-76808>`, the beneficiary must first create a :ref:`ValidatorRight <type-splice-amulet-validatorright-15964>`.

The delegation has the following key properties:

.. list-table::
   :widths: 20 80
   :header-rows: 1

   * - Property
     - Description
   * - Beneficiary
     - The party on whose behalf minting is performed
   * - Delegate
     - The validator party authorized to perform minting operations
   * - Expiration
     - The time after which the delegation is no longer valid
   * - Amulet merge limit
     - The number of amulets to keep after auto-merging

.. note::
   The amulet merge limit controls automatic consolidation of the beneficiary's amulets: when
   the number of amulets exceeds twice the limit, the smallest amulets are merged to maintain
   exactly the configured number.


Workflow
++++++++

The minting delegation workflow consists of two steps:

1. **Proposal Creation**: The beneficiary creates a ``MintingDelegationProposal`` specifying the
   delegate (validator), expiration date, and amulet merge limit. This is typically done via
   the Ledger API or through an application built on top of the wallet API.

2. **Proposal Acceptance**: The validator reviews and accepts (or rejects) the proposal through
   the wallet UI. Upon acceptance, an active ``MintingDelegation`` contract is created.

Using the Delegations Tab
+++++++++++++++++++++++++

Validators can manage minting delegations through the **Delegations** tab in the wallet UI.
This tab displays two sections:

Proposed Delegations
^^^^^^^^^^^^^^^^^^^^

The **Proposed** section shows all pending ``MintingDelegationProposal`` contracts where
the validator is the designated delegate.

For each proposal, the validator can:

- **Accept**: Approve the delegation request. This creates an active minting delegation.
  If a delegation already exists for the same beneficiary, accepting a new proposal will
  replace the existing delegation.
- **Reject**: Decline the delegation request. This archives the proposal.

.. note::
   The Accept button is disabled if the beneficiary is not yet hosted to the validator node.
   The beneficiary must be hosted before a delegation can be accepted.

Active Delegations
^^^^^^^^^^^^^^^^^^

The **Active** section shows all current ``MintingDelegation`` contracts where the validator
is the delegate.

For each active delegation, the validator can:

- **Withdraw**: Terminate the delegation. This archives the delegation contract and stops
  the validator from minting rewards for the beneficiary.

Replacing Delegations
+++++++++++++++++++++

When a validator accepts a proposal for a beneficiary that already has an active delegation,
a confirmation dialog will appear showing:

- The current delegation's Merge Thresholds and Expiration values
- The new proposal's Merge Thresholds and Expiration values

Accepting the proposal will automatically replace the existing delegation with the new one.
This allows beneficiaries to update their delegation parameters (such as extending the
expiration date) without the validator having to manually withdraw the old delegation first.

Limitations
+++++++++++

- Minting delegations count towards the :ref:`max 200 Splice wallet parties limit <party_scaling>`


Security Considerations
+++++++++++++++++++++++

When managing minting delegations, validators should consider:

1. **Verify the beneficiary**: Before accepting a delegation, ensure you recognize and trust
   the beneficiary party. The Party ID should match the expected party.

2. **Hosting status**: Only accept delegations from hosted parties. The UI enforces
   this by disabling the Accept button for non-hosted beneficiaries.

3. **Monitor active delegations**: Periodically review active delegations and withdraw any
   that are no longer needed or authorized.
