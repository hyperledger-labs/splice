..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV Participant

      - Participant pruning for super validators is now supported and
        recommended. Follow the :ref:`documentation
        <sv_participant_pruning>` for instructions on how to enable
        it.

    - Canton

      - JSON Ledger API OpenAPI/AsyncAPI Specification Updates
         We've corrected the OpenAPI and AsyncAPI specification files to properly reflect field requirements as defined in the Ledger API ``.proto`` files. Additionally, specification files now include the Canton version in their filenames (e.g., ``openapi-3.4.11.yaml``).

          - Impact and Migration
            If you regenerate client code from these updated specifications, your code may require changes due to corrected field optionality. You have two options:

            - **Keep using the old specification** - The JSON API server maintains backward compatibility with previous specification versions.
            - **Upgrade to the new specification** - Update your client code to handle the corrected optional/required fields:

              - **Java (OpenAPI Generator)**: Code compiles without changes, but static analysis tools may flag nullability differences.
              - **TypeScript**: Handle optional fields using ``!`` or ``??`` operators as needed.

            The JSON API server remains compatible with specification files from all 3.4.x versions (e.g., 3.4.9).

      - Minor Improvements
        - New connection pool:

          - The connections gRPC channels are now correctly using the defined client keep-alive configuration. **If you had disabled
            the new connection pools because of issues before, please reenable them and report any issues.**
          - The connections gRPC channels are now configured with a ``maxInboundMessageSize`` set to ``MaxInt`` instead of the default 4MB (this will be improved in the future to use the dynamic synchronizer parameter ``maxRequestSize``).

      - Bugfixes

        - Switched the gRPC service ``SequencerService.subscribe`` and ``SequencerService.downloadTopologyStateForInit`` to manual
          control flow, so that the sequencer doesn't crash with an ``OutOfMemoryError`` when responding to slow clients.

    - Scan txlog script

      - scan_txlog.py script has been deprecated. It will not be maintained moving forward, and will be removed completely in a future release.
