..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Scan

    - Added a new external endpoint ``GET /v0/unclaimed-development-fund-coupons`` to retrieve all active unclaimed development fund coupon contracts.

  - Wallet

    - Added a new internal endpoint ``POST /v0/wallet/development-fund-coupons/allocate`` to allocate a development fund coupon for a given beneficiary,
      amount, expiration time, and reason.

    - Added a new internal endpoint ``GET /v0/wallet/development-fund-coupons`` to retrieve all active DevelopmentFundCoupon contracts,
      sorted by expiration date, where the wallet user party is either the development fund manager or the beneficiary.

    - Added a new internal endpoint ``POST /v0/wallet/development-fund-coupons/{contract_id}/withdraw`` to withdraw a development fund coupon
      when the wallet user party is the development fund manager.

