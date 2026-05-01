..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan app

        - The ``app_activity_record_store`` table has been modified to avoid unexpected DB performance issues.
          This required clearing the existing data in this table which has been ingested since the ``0.5.18`` release.
          This impacts the data being served via the experimental field ``app_activity_records`` on the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints.
          Specifically the ``app_activity_records`` field will not contain the
          data which has been provided for the events which happened between the ``0.5.18`` and this release.
          Note that the ``app_activity_records`` data already provided for events during this period is correct
          and the network explorers who have ingested this data should keep a copy of it.
