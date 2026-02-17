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
