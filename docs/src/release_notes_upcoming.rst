..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV App

       - A new optional configuration map `additionalPackagesToUnvet` to unvet additional supported packages was added in the SV app configuration.
         This is only aimed as a security measure to add the ability to downgrade to previous versions in case of major issues or to prevent
         corrupted and unsecured packages from being used. More information about this new configuration can be found in the :ref:`Unvet unsecure package versions <sv-unvet_unsercure_package_versions>` guide.

    - Deployment

        - We've added support for the `Stakater Reloader <https://github.com/stakater/Reloader>`_ annotation,
          which performs a rolling restart of pods when their referenced Secrets or ConfigMaps change.
          The annotation is included by default in all Splice Helm charts.
          You can disable it by setting ``enableReloader`` to ``false`` in your Helm values file.
          Reloader must be installed separately; if it is not present, the annotation is harmless and will be ignored.
          See also the new deployment tips in the :ref:`Validator <helm-validator-install>` and :ref:`SV <helm-sv-install>` Helm guides.

        - SV apps now support a ``copyVotesFrom`` setting that automatically mirrors governance votes
          from another named SV, which can help operators keep votes in sync when they run multiple SV nodes.

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

    - Scan

        - Scan now ingests and serves app activity records for traffic-based rewards,
          which delivers Increments 2 and 3 from the
          `CIP-104 incremental roll-out plan <https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md#incremental-roll-out>`__.

          The responses from the ``/v0/events`` and ``/v0/events/{update_id}``
          `endpoints <https://github.com/hyperledger-labs/splice/blob/004f19622e4a145840f18d3fda9d71c9a751a282/apps/scan/src/main/openapi/scan.yaml#L1579-L1639>`__
          now include the
          ``traffic_summary`` (`schema <https://github.com/hyperledger-labs/splice/blob/004f19622e4a145840f18d3fda9d71c9a751a282/apps/scan/src/main/openapi/scan.yaml#L4007-L4026>`__) and
          ``app_activity_records`` (`schema <https://github.com/hyperledger-labs/splice/blob/004f19622e4a145840f18d3fda9d71c9a751a282/apps/scan/src/main/openapi/scan.yaml#L4027-L4063>`__)
          fields.

          .. note::

              These new fields enable the Canton Network community to start
              validating the traffic-based rewards model and to prepare for the full
              roll-out of CIP-104 in the future.

              **Network explorer operators**: consider ingesting traffic summaries and
              app activity records in your network explorer apps together with the mediator verdicts
              and use them to provide both per-transaction and per-round previews of the expected
              traffic-based app rewards when CIP-104 goes live.

              **App developers**: review the app activity records for your app to
              understand the impact of traffic-based app rewards on your app.
              Keep in mind that the rewards depend on the exact transaction structure of your app,
              which might change when you stop creating ``FeaturedAppActivityMarker`` contracts in your transactions.
              Traffic-based app rewards also depend on the featured app status of counter-parties in your transactions.
              Expected rewards can therefore differ between DevNet, TestNet and MainNet
              because different apps are featured.

          The new fields are marked as experimental in the API specification, as the validation might
          show that changes are required. Most likely that will though not be the case.
