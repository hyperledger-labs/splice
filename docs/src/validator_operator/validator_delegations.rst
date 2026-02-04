..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _minting-delegations:

Minting Delegations
-------------------

Minting delegations allow a delegate to instruct their validator node to automate the minting
of rewards on behalf of an external party (the beneficiary) hosted on the same validator node.
The delegate can be any party onboarded to the validator node's wallet (e.g., the validator
operator party, but other internal parties are also possible). This is useful to automate the
reward collection for external parties.

Overview
++++++++

A **minting delegation** grants a delegate party the authority to:

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
     - The external party on whose behalf minting is performed
   * - Delegate
     - The internal party authorized to perform minting operations
   * - Expiration
     - The time after which the delegation is no longer valid
   * - Amulet merge limit
     - The number of amulets to keep after auto-merging


Automation
++++++++++

When a minting delegation is active, the validator node runs automation
(``MintingDelegationCollectRewardsTrigger``) for the beneficiary party that periodically:

1. Checks for reward coupons owned by the beneficiary that are eligible for minting
2. Collects and mints these rewards on behalf of the beneficiary
3. Merges the beneficiary's amulets when the count exceeds twice the configured merge limit

For the automation to run successfully, the following conditions must be met:

- The delegate must be a party onboarded to the validator node's wallet (e.g., the validator
  operator party, or another internal party with its own user account)
- The delegation must not be expired
- The beneficiary must be hosted on the validator node in at least observer mode
- The beneficiary should not be onboarded to the wallet app, as otherwise the delegated automation
  contends with the built-in automation of the wallet app
- The beneficiary must have reward coupons or amulets that need processing

Note that minting delegations count towards the :ref:`max 200 Splice wallet parties limit <party_scaling>`.

The automation submits transactions as the delegate party. Transaction costs are paid from the
validator node's traffic balance.

.. note::
   The amulet merge limit controls automatic consolidation of the beneficiary's amulets: when
   the number of amulets exceeds twice the limit, the smallest amulets are merged to maintain
   exactly the configured number.


Managing Minting Delegations
++++++++++++++++++++++++++++

The minting delegation workflow consists of the following steps:

1. **Proposal Creation**: The beneficiary creates a ``MintingDelegationProposal`` specifying the
   delegate, expiration date, and amulet merge limit. This is typically done via the Ledger API.

2. **Proposal Acceptance**: The delegate reviews and accepts (or rejects) the proposal through
   the wallet UI. Upon acceptance, an active ``MintingDelegation`` contract is created.

3. **Withdrawal**: When the delegation is no longer needed, the delegate can withdraw it through
   the wallet UI. This terminates the delegation and stops the automation from minting rewards
   for the beneficiary.

Using the Delegations Tab
^^^^^^^^^^^^^^^^^^^^^^^^^

Delegates can manage minting delegations through the **Delegations** tab in the wallet UI.
This tab displays two sections:

Proposed Delegations
""""""""""""""""""""

The **Proposed** section shows all pending ``MintingDelegationProposal`` contracts where
the current user is the designated delegate.

For each proposal, the delegate can:

- **Accept**: Approve the delegation request. This creates an active minting delegation.
  If a delegation already exists for the same beneficiary, accepting a new proposal will
  replace the existing delegation.
- **Reject**: Decline the delegation request. This archives the proposal.

.. note::
   The Accept button is disabled if the beneficiary is not yet hosted on the validator node.
   The beneficiary must be hosted before a delegation can be accepted.

Active Delegations
""""""""""""""""""

The **Active** section shows all current ``MintingDelegation`` contracts where the current
user is the delegate.

For each active delegation, the delegate can:

- **Withdraw**: Terminate the delegation. This archives the delegation contract and stops
  the automation from minting rewards for the beneficiary.

Replacing Delegations
^^^^^^^^^^^^^^^^^^^^^

When a delegate accepts a proposal for a beneficiary that already has an active delegation,
a confirmation dialog will appear showing:

- The current delegation's Merge Threshold and Expiration values
- The new proposal's Merge Threshold and Expiration values

Accepting the proposal will automatically replace the existing delegation with the new one.
This allows beneficiaries to update their delegation parameters (such as extending the
expiration date) without the delegate having to manually withdraw the old delegation first.


Creating Proposals via Ledger API
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

External parties (beneficiaries) who wish to delegate minting to a validator operator
must create a ``MintingDelegationProposal`` contract. Since beneficiaries typically do not
have access to the wallet UI, they can create proposals programmatically using the
Canton Ledger API.

Prerequisites
"""""""""""""

Before creating a proposal, the beneficiary must have:

1. **Hosting on the validator node**: The beneficiary party must be hosted on the validator
   node where they want to delegate minting.

2. **Ledger API access**: Authenticated access to the validator's Ledger API endpoint,
   including:

   - The Ledger API URL (e.g., ``https://<validator-host>:<port>``)
   - Valid authentication credentials (OAuth2 token or other configured auth method)
   - The beneficiary's party ID

3. **The delegate's party ID**: The delegate is typically the validator operator party,
   but could be another internal party on the validator node.

4. **The DSO party ID**: The DSO party ID for the network. This can be obtained from
   the Scan API.

Example: Creating a Proposal
""""""""""""""""""""""""""""

The ``MintingDelegationProposal`` contract contains a ``delegation`` field with the same
properties as described in the Overview section above (beneficiary, delegate, expiration,
and amulet merge limit), plus the DSO party ID.

All interaction works via the JSON Ledger API (see its
`OpenAPI definition <https://github.com/digital-asset/canton/blob/main/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml>`_).
Check out the `Authentication docs <https://docs.digitalasset.com/operate/3.4/howtos/secure/apis/jwt.html>`_
for more information on how to authenticate the requests.

To create the proposal, submit a ``create`` command via the Ledger API
`command submission endpoint <https://github.com/digital-asset/canton/blob/main/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L173>`_
with the following payload structure:

.. code-block:: json

   {
     "commands": [
       {
         "CreateCommand": {
           "templateId": "#splice-wallet:Splice.Wallet.MintingDelegation:MintingDelegationProposal",
           "createArguments": {
             "delegation": {
               "beneficiary": "beneficiary::1220abcd...",
               "delegate": "validator_operator::1220efgh...",
               "dso": "DSO::1220ijkl...",
               "expiresAt": "2025-12-31T23:59:59Z",
               "amuletMergeLimit": 40
             }
           }
         }
       }
     ]
   }

See the `MintingDelegationProposal template source code
<https://github.com/hyperledger-labs/splice/blob/main/daml/splice-wallet/daml/Splice/Wallet/MintingDelegation.daml>`_
for the complete Daml definition.

Monitoring Proposal Status
""""""""""""""""""""""""""

The beneficiary can monitor their proposal status by querying for active
``MintingDelegationProposal`` contracts via the Ledger API's
`active contracts endpoint <https://github.com/digital-asset/canton/blob/main/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L620>`_.
Once accepted, they can query for their ``MintingDelegation`` contract to confirm the
delegation is active.

Withdrawing a Proposal
""""""""""""""""""""""

If the beneficiary wants to withdraw their proposal before it is accepted or rejected,
they can exercise the ``MintingDelegationProposal_Withdraw`` choice on their proposal
contract via the Ledger API's
`exercise endpoint <https://github.com/digital-asset/canton/blob/main/community/ledger/ledger-json-api/src/test/resources/json-api-docs/openapi.yaml#L173>`_.


Security Considerations
^^^^^^^^^^^^^^^^^^^^^^^

When managing minting delegations, delegates should consider:

1. **Verify the beneficiary**: Before accepting a delegation, ensure you recognize and trust
   the beneficiary party. The Party ID should match the expected party.

2. **Traffic costs**: Verify that the validator operator is willing to pay the cost of minting
   transactions from the validator node's traffic balance.

3. **Hosting status**: Only accept delegations from hosted parties. The UI enforces
   this by disabling the Accept button for non-hosted beneficiaries.

4. **Monitor active delegations**: Periodically review active delegations and withdraw any
   that are no longer needed or authorized.
