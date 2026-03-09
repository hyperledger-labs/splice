..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _development_fund:

Development Fund
----------------

Overview
++++++++

The Development Fund is a protocol-level mechanism introduced by CIP-0082.
From its activation onward, 5% of all future mint emissions in each issuance round are allocated to the Development Fund.

For every issuance round:

- The Development Fund share (5%) is computed as part of the round's CC available to mint.
- The corresponding amount is recorded as an unclaimed entitlement under the contract
  ``Splice.Amulet.UnclaimedDevelopmentFundCoupon``.
- The remaining issuance streams are reduced proportionally.

Over time, multiple ``Splice.Amulet.UnclaimedDevelopmentFundCoupon`` contracts may accumulate.
An automation in the SV app periodically merges small unclaimed coupons to keep the number
of active contracts bounded.

Merging is triggered when the number of unclaimed coupons reaches at least
``2 × unclaimedDevelopmentFundCouponsThreshold`` (as defined in the SV configuration).
When this condition is met:

- The ``threshold`` smallest coupons (by amount) are selected.
- The selected coupons are archived.
- A new ``Splice.Amulet.UnclaimedDevelopmentFundCoupon`` is created for the sum of their amounts.

Larger coupons are intentionally left untouched to reduce contention with externally
prepared transactions that may reference their contract IDs.

The Development Fund entitlement is not minted directly to a final recipient during the issuance round.
Instead, the designated on-chain party, the **Development Fund Manager**, can allocate portions
of the accumulated entitlement to specific beneficiaries.
An allocation results in the creation of a ``Splice.Amulet.DevelopmentFundCoupon`` contract for the beneficiary.

Once allocated:

- The beneficiary (local or external party) may collect the allocation via the wallet app automation.
  This exercises the minting right embedded in the ``Splice.Amulet.DevelopmentFundCoupon``
  and mints the corresponding amount of Canton Coin to the beneficiary.
- The Development Fund Manager may withdraw an allocation before it is collected.
  This archives the corresponding ``Splice.Amulet.DevelopmentFundCoupon`` and creates a new
  ``Splice.Amulet.UnclaimedDevelopmentFundCoupon`` returning the amount to the Development Fund entitlement.
- If the beneficiary does not collect the allocation within its validity period, it may expire automatically via SV app automation.
  Expiration archives the ``Splice.Amulet.DevelopmentFundCoupon`` and creates a new
  ``Splice.Amulet.UnclaimedDevelopmentFundCoupon`` for the same amount.

The Development Fund Manager is responsible for managing allocations in accordance with governance decisions defined in CIP-0082.

Managing Development Fund Allocations
++++++++++++++++++++++++++

Development Fund allocations are managed through the **Development Fund** tab
in the wallet UI.

This tab provides visibility into the current Development Fund balance and
allows the Development Fund Manager to allocate and manage coupons.

.. important::

   The **Development Fund** tab is primarily intended for the current
   Development Fund Manager designated by the CF Foundation.

   If your party is not the designated Development Fund Manager, you
   will not be able to create allocations.

   If your party was formerly the Development Fund Manager, you may use
   this page to manage your active Development Fund allocations and
   review the history of your past allocations.

   Otherwise, this page can be safely ignored.

Development Fund Total
^^^^^^^^^^^^^^^^^^^^^^

The **Development Fund Total** indicator displays the total amount currently
available in the Development Fund.

This value corresponds to the sum of all active
``Splice.Amulet.UnclaimedDevelopmentFundCoupon`` contracts.

This information is visible to all users.

Development Fund Allocation
^^^^^^^^^^^^^^^^^^^^^^^^^^^

The **Development Fund Allocation** section allows the current
Development Fund Manager to allocate funds to a beneficiary.

This section is only enabled for the current manager.

To create an allocation, the manager must provide:

- **Amount**
- **Beneficiary**
- **Expires At**
- **Reason**

Submitting the allocation creates a
``Splice.Amulet.DevelopmentFundCoupon`` contract for the specified beneficiary.

Unclaimed Development Fund Allocations
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The **Unclaimed Development Fund Allocations** section lists all active
``Splice.Amulet.DevelopmentFundCoupon`` contracts that:

- Have been allocated by the current or a former manager, and
- Have not been collected, expired, withdrawn, or rejected.

A beneficiary does not see their coupon in this list.
Only users who are or have been Development Fund Manager can view these entries.

From this list, the Development Fund Manager (current or former) may withdraw
an allocation by providing a withdrawal reason.

Withdrawing an allocation:

- Archives the corresponding ``Splice.Amulet.DevelopmentFundCoupon``.
- Creates a new ``Splice.Amulet.UnclaimedDevelopmentFundCoupon`` returning the amount
  to the Development Fund entitlement.

Coupon History
^^^^^^^^^^^^^^

The **Coupon History** section allows the current or former
Development Fund Manager to view historical allocations.

This includes ``Splice.Amulet.DevelopmentFundCoupon`` contracts that have been
archived due to:

- Collection (claimed by the beneficiary)
- Expiration (expired by SV automation)
- Withdrawal (withdrawn by the Development Fund Manager)
- Rejection (rejected by the beneficiary via the Ledger API)

For each entry, the UI displays the event that caused the coupon to be archived.

Rejecting an Allocation
^^^^^^^^^^^^^^^^^^^^^^^

If a beneficiary wishes to reject an allocation, this must be done via the
Ledger API.

The wallet UI does not provide a reject action for beneficiaries.


Changing the Development Fund Configuration
++++++++++++++++++++++++++++++++++++++++++++

The Development Fund configuration is defined in the ``AmuletConfig`` and
``IssuanceConfig`` types.

Two optional fields control the behavior of the Development Fund:

- ``IssuanceConfig.optDevelopmentFundPercentage``
  Defines the percentage of each issuance round allocated to the Development Fund.

  If this field is unset (``null``), the Daml issuance logic defaults to **5%**.

- ``AmuletConfig.optDevelopmentFundManager``
  Designates the party authorized to allocate Development Fund entitlements.

Governance Process
^^^^^^^^^^^^^^^^^^

Changes to either the Development Fund percentage or the Development Fund Manager
must be performed through the standard governance process.

Specifically, these parameters are updated via the
**"Set Amulet Rules Configuration"** vote.

This vote is submitted and approved by the SVs in accordance with the
normal governance rules of the network.

Once the vote is approved and the updated configuration becomes effective:

- The updated Development Fund percentage applies to subsequent issuance rounds.
- The designated Development Fund Manager gains authority to allocate
  Development Fund entitlements.