..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

   - Deployment

    - postgres-exporter: disabled exporting the settings table, to workaround `an issue of postgres-exporter <https://github.com/prometheus-community/postgres_exporter/issues/1240>`__.

   - SV app

    - Add a new trigger, ``ExpiredDevelopmentFundCouponTrigger`` for expiring development fund coupons.
