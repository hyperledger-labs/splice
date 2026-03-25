..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

- Validator app

    - the configuration ``domain-migration-dump-path`` was removed

.. .. release-notes:: Upcoming

    - Token Standard V2 (CIP-112)

      - Notable callouts for Amulet changes:
          - add a ``meta : Optional Metadata`` field to the ``AmuletRules.Transfer`` type and the ``TransferPreapproval_SendV2`` choice
          - properly classify the burn of ANS in the V2 token standard transaction history

      - Add preview of the V2 token standard APIs and implement them for Amulet

      .. TODO(#4707): add callouts for wallets, explorers, SVs, validator operators, app operators as needed
      .. TODO(#4707): add Daml versions of token standard to release notes
