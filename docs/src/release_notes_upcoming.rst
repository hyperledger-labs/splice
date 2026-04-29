..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

- Validator app

    - the configuration ``domain-migration-dump-path`` was removed

- Docs

    - SV only: Introduced the concept of **serial ID** alongside the existing **migration ID** for synchronizer deployment.
      The migration ID is now frozen at its current value and is only used for the ``migration.id`` field in helm chart values.
      The serial ID is incremented by 1 for each :ref:`logical synchronizer upgrade <sv-logical-synchronizer-upgrades>` and replaces the migration ID
      in synchronizer (sequencer/mediator/CometBFT) release names, DNS entries, DB names, chain IDs and port numbers. Participant naming and the participant DB name continue to use MIGRATION_ID, which is now frozen.
      All example YAML files and documentation have been updated to use ``SERIAL_ID`` for addressing and ``MIGRATION_ID`` for migration-specific configuration.
      For existing networks, ``SERIAL_ID`` must initially be set to the current value of ``MIGRATION_ID``.
      Newly initialized networks start with ``SERIAL_ID=0``.
      This also changed the name of the participant helm installation in the documentation, along with the ``participantAddress`` in ``sv-values.yaml``. You can either reinstall the helm chart with the new name or ensure the ``participantAddress`` reflects the name of your helm chart installation.
    - Removed references to major (hard) upgrades for both SV and validator operators.
      Logical synchronizer upgrades are now the default mechanism for protocol upgrades.
    - Updated the documentation to clarify that the ``MIGRATION_ID`` will not change in the future and that all validators should keep the current value for the foreseeable future.

.. .. release-notes:: Upcoming
