..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

- Scan

   - Added a new ``GET /v2/updates/hash/{hash}`` endpoint that returns the update associated with a given external transaction hash of a prepared transaction.
     This endpoint is not always BFT safe: for transactions committed before a scan instance started indexing hashes, the instance will return a 404 error.
     For transactions committed around the time different scans started indexing hashes, some scan instances might return a 404 while others return the matching update.
     This is in contrast to the ``GET /v2/updates`` and ``GET /v2/updates/{update_id}`` endpoints, which are guaranteed to be always BFT safe.

.. .. release-notes:: Upcoming
