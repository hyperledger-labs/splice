..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Wallet UI

      - The wallet UI transaction hisory now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenace overhead.

    - Scan UI

      - The scan UI transaction hisory now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenace overhead.

    - Validator

      - Add support for custom fault-tolerance configurations for **scan** and **sequencer** connections.
        Please see the updated :ref:`documentation for Helm-based deployments <helm-validator-install>`.
        This introduces the new configuration keys ``scanClient`` and ``synchronizer`` as the new recommended way to configure **scan** and **sequencer** connections.
        Existing configuration options ``scanAddress``, ``nonSvValidatorTrustSingleScan``, ``decentralizedSynchronizerUrl``, ``useSequencerConnectionsFromScan`` are still supported, but will be deprecated in a future release.
        We recommend to migrate to the new ``scanClient`` and ``synchronizer`` configuration options as soon as possible.
        Docker Compose-based deployments do not currently support the new custom configuration options.
