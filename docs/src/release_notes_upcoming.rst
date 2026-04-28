..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

- Validator app

    - the configuration ``domain-migration-dump-path`` was removed

.. .. release-notes:: Upcoming

    - Token Standard V2 (CIP-112)

      - Add preview of the V2 token standard APIs and implement them for Amulet

      - Add support for creating Allocations V2 of Amulet in the Splice Amulet Wallet UI.
        This is meant for users that create the allocations for an allocation request
        using the registry specific UIs for each asset. The Amulet Wallet UI
        therefore does not archive the V2 AllocationRequest when creating the
        Amulet Allocation for it, so that the allocation request is visible in the other
        registry UIs as well.

        For creating all allocations in a single transaction `as documented in CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md#423-traders-accept-allocation-requests-and-create-allocations>`__, we recommend using
        a token standard v2 wallet UI that uniformly supports all V1 and V2 assets.

      .. TODO(#4707): add callouts for wallets, explorers, SVs, validator operators, app operators as needed
      .. TODO(#4707): add Daml versions of token standard to release notes
