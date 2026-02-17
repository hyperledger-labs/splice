..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Wallet UI

      - The wallet UI transaction history now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenance overhead.

    - Wallet backend

      - Fix a bug (`#3970 <https://github.com/hyperledger-labs/splice/issues/3970>`__) that caused transaction history
        for entries created by Splice versions prior to 0.5.11 to fail to decode in the backend and thus not show in the
        wallet UI.
        These entries now shown again in the wallet UI.

    - Scan UI

      - The scan UI transaction history now uses the current amulet conversion rate to convert amounts instead of the historic one to
        reduce maintenance overhead.

    - Validator

      - Add support for custom fault-tolerance configurations for **scan** and **sequencer** connections.
        Please see the updated :ref:`documentation for Helm-based deployments <helm-validator-install>`.
        This introduces the new configuration keys ``scanClient`` and ``synchronizer`` as the new recommended way to configure **scan** and **sequencer** connections.
        Existing configuration options ``scanAddress``, ``nonSvValidatorTrustSingleScan``, ``decentralizedSynchronizerUrl``, ``useSequencerConnectionsFromScan`` are still supported, but will be deprecated in a future release.
        We recommend to migrate to the new ``scanClient`` and ``synchronizer`` configuration key options as soon as possible.
        Docker Compose-based deployments do not currently support the new custom configuration options.
