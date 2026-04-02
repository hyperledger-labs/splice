..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Deployment

        - We've added support for `reloader annotation <https://github.com/stakater/reloader>`, which performs a rolling
          restart of our apps on secret/configmap change. The integration is enabled by
          default. You can disable it by setting enableReloader to false in your values.yaml file.
          Please note that reloader needs to be installed separately for the integration to work.
          If you don't have reloader installed this annotation will be ignored.

   - Scan UI

     - The following tabs and features have been removed from Scan UI.
       Their corresponding API endpoints are still available, yet deprecated, and will be removed soon.
       Users are strongly advised to migrate to non-deprecated API endpoints as soon as possible.

      - Canton Coin Activity
         - Recent activity list, and all leaderboards
         - Total app & validator rewards
         - The round as-of which the content has been computed (no round-based data is listed any more)
         - The tab has been renamed "Canton Coin Configuration"
      - Governance
         - Completely removed
      - Validators
         - Completely removed

   - Scan

        - Improve CPU usage of update and event history.

    - Token Standard V2 (CIP-112)

      - Add preview of the V2 token standard APIs and implement them for Amulet

      .. TODO(#4707): add callouts for wallets, explorers, SVs, validator operators, app operators as needed
      .. TODO(#4707): add Daml versions of token standard to release notes
