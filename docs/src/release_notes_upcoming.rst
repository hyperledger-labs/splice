..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Deployment

        - SV apps now support a ``copyVotesFrom`` setting that automatically mirrors governance votes
          from another named SV, which can help operators keep votes in sync when they run multiple SV nodes.
   - Scan
        - Added a new ``GET /v2/updates/hash/{hash}`` endpoint that returns the update associated with a given external transaction hash of a prepared transaction.
          This endpoint is not always BFT safe, see the Scan OpenAPI documentation for details.
