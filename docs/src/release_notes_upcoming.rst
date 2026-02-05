..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

   - Deployment

    - postgres-exporter: disabled exporting the settings table, to workaround `an issue of postgres-exporter <https://github.com/prometheus-community/postgres_exporter/issues/1240>`__.

    - Splice apps and Canton components deployed via Docker compose now log at ``INFO`` level by default instead of ``DEBUG``.
      In case you do want to change this, export the ``LOG_LEVEL`` environment variable before running ``./start.sh``. e.g., ``export LOG_LEVEL="DEBUG"; ./start.sh``.

   - SV app

    - Add a new trigger, ``ExpiredDevelopmentFundCouponTrigger`` for expiring development fund coupons.

   - Wallet UI

     - Remove the provider field from transaction history.

   - Scan UI

     - Remove the provider field from transaction history. The update API continues to expose it.
