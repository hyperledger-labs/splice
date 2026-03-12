..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

   - Validator App

     - Remove the ``new-sequencer-connection-pool`` flag as it didn't
       do what it was supposed to do. If you did set it, you can
       safely remove it regardless of whether you disabled the new sequencer connection
       pools in the participant or not.

       Fix a bug that caused the Validator App to fail during restarts when the Scan Apps defined
       in ``scanClient.seedUrls`` were unavailable. This fix ensures the Validator App uses its
       persisted scan connections from previous runs, removing the dependency on seedUrls
       scan availability for successful reboots.

   - Scan

     - **Experimental**: Added an optional ``traffic_summary`` field to the response of ``GET /v0/events/{update-id}`` and ``POST /v0/events`` endpoints.
       When enabled by SV configuration, traffic summaries are included alongside verdicts in event history items.
       This is part of the CIP-104 preview and is subject to change.

       Traffic summaries will be enabled step-by-step on Dev/Test/MainNet,
       once the SVs have successfully concluded their performance testing.

  - Wallet UI

    - Introduced a new ``/development-fund`` panel providing a complete UI for managing Development Fund allocations (see `CIP-0082 <https://github.com/canton-foundation/cips/blob/main/cip-0082/cip-0082.md>`_ and `CIP-0100 <https://github.com/canton-foundation/cips/blob/main/cip-0100/cip-0100.md>`_ for context).

    - The panel includes:

      - Display of total available Development Fund balance
      - Allocation form for Development Fund coupons (Development Fund Manager only)
      - Unclaimed allocations table with withdrawal support
      - Coupon history with lifecycle event tracking (claimed, withdrawn, rejected, expired)

    - Role-based behavior is enforced:

      - Simple Users: read-only access to fund total
      - Current Development Fund Manager: full allocation and withdrawal capabilities
      - Former Development Fund Manager: can manage and view allocations created under their tenure, but cannot create new ones
