..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

- Validator app

    - the configuration ``domain-migration-dump-path`` was removed

- Scan

    - Added ``/v1/holdings/summary`` endpoint that drops the ``accumulated_holding_fees_unlocked``,
      ``accumulated_holding_fees_locked``, ``accumulated_holding_fees_total``, and
      ``total_available_coin`` response fields and the ``as_of_round`` request parameter, as those
      values are not meaningful aggregates. The same endpoint is also exposed on the validator
      scan-proxy. The ``/v0/holdings/summary`` endpoint is now deprecated but remains available.

.. .. release-notes:: Upcoming
