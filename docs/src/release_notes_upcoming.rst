..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Scan

    - `canton.scan-apps.scan-app.acs-store-descriptor-user-version` and `canton.scan-apps.scan-app.tx-log-store-descriptor-user-version`
      configuration settings
      have been added to set a `user-version`, respectively for the ACS and TxLog store.
      Modifying the `user-version` wipes the respective store and triggers re-ingestion.
      See the :ref:`SV Operations docs <sv-reingest-scan-stores>` for more details.

    - Added a new external endpoint ``GET /v0/unclaimed-development-fund-coupons`` to retrieve all active unclaimed development fund coupon contracts.

  - Daml

    - Implement minting delegation described as part of `CIP-0073 - Weighted Validator Liveness Rewards for SV-Chosen Parties <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0073/cip-0073.md>`__. See the :ref:`minting delegations <minting-delegations>` section for details.

      - New templates have been added in the ``splice-wallet`` package:

        - **MintingDelegationProposal**: Represents a proposal from beneficiary to the delegate to create a ``MintingDelegation``.

        - **MintingDelegation**: Represents an active delegation granting the delegate the authority to mint rewards on behalf of the beneficiary.

      Enabling this feature on all validators requires the SVs to vote on the following Daml versions:

      ================== =======
      name               version
      ================== =======
      wallet             0.1.16
      ================== =======

  - Wallet

    - Added a new internal endpoint ``POST /v0/wallet/development-fund-coupons/allocate`` to allocate a development fund coupon for a given beneficiary,
      amount, expiration time, and reason.

    - Added a new internal endpoint ``GET /v0/wallet/development-fund-coupons`` to retrieve all active DevelopmentFundCoupon contracts,
      sorted by expiration date, where the wallet user party is either the development fund manager or the beneficiary.

    - Added a new internal endpoint ``POST /v0/wallet/development-fund-coupons/{contract_id}/withdraw`` to withdraw a development fund coupon
      when the wallet user party is the development fund manager.

    - Support for managing minting delegation has been added to the wallet UI. See the :ref:`minting delegations <minting-delegations>` section for details.

  - Validator

    - Automation has been added to perform minting for hosted external-parties if they have an active ``MintingDelegation`` contract with an internal party.
