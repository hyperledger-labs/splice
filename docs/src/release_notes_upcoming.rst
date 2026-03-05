..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. .. release-notes:: Upcoming

  - Wallet UI

    - Introduced a new ``/development-fund`` panel providing a complete UI for managing Development Fund allocations.

    - The panel includes:

      - Display of total available Development Fund balance
      - Allocation form for Development Fund coupons (Development Fund Manager only)
      - Unclaimed allocations table with withdrawal support
      - Coupon history with lifecycle event tracking (claimed, withdrawn, rejected, expired)

    - Role-based behavior is enforced:

      - Simple Users: read-only access to fund total
      - Current Development Fund Manager: full allocation and withdrawal capabilities
      - Former Development Fund Manager: can manage and view allocations created under their tenure, but cannot create new ones
