..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Deployment

        - We've added support for the `Stakater Reloader <https://github.com/stakater/Reloader>`_ annotation,
          which performs a rolling restart of pods when their referenced Secrets or ConfigMaps change.
          The annotation is included by default in all Splice Helm charts.
          You can disable it by setting ``enableReloader`` to ``false`` in your Helm values file.
          Reloader must be installed separately; if it is not present, the annotation is harmless and will be ignored.
          See also the new deployment tips in the :ref:`Validator <helm-validator-install>` and :ref:`SV <helm-sv-install>` Helm guides.

   - Scan UI

     - The following tabs and features have been removed from Scan UI.
       Their corresponding API endpoints are still available, yet deprecated, and will be removed soon.
       Users are strongly advised to migrate to non-deprecated API endpoints as soon as possible.

      - Canton Coin Activity
         - Recent activity list, and all leaderboards
         - Total app & validator rewards
         - The round as-of which the content has been computed (no round-based data is listed any more)
         - The tab has been renamed "Canton Coin Configuration"
      - Governance
         - Completely removed
      - Validators
         - Completely removed

   - Scan

        - Improve CPU usage of update and event history.
