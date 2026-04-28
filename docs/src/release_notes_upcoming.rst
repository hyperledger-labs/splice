..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

- Validator app

    - the configuration ``domain-migration-dump-path`` was removed

- Docs

    - SV only: Introduced the concept of **serial ID** alongside the existing **migration ID** for synchronizer deployment.
      The migration ID is now frozen at its value after the last major upgrade and is only used for the ``migration.id`` field in helm chart values.
      The serial ID is incremented by 1 for each :ref:`logical synchronizer upgrade <sv-logical-synchronizer-upgrades>` and replaces the migration ID
      in helm release names, DNS entries, database names, CometBFT chain IDs, and all other deployment naming conventions.
      All example YAML files and documentation have been updated to use ``SERIAL_ID`` for addressing and ``MIGRATION_ID`` for migration-specific configuration.
      The initial value of ``SERIAL_ID`` must be the same as the current value of ``MIGRATION_ID``.
    - Removed references to major (hard) upgrades for both SV and validator operators.
      Logical synchronizer upgrades are now the default mechanism for protocol upgrades.
    - Updated the documentation to clarify that the ``MIGRATION_ID`` will not change in the future and that all validators should keep the current value for the foreseeable future.

.. .. release-notes:: Upcoming
