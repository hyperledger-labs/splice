..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

   - Validator App

     - Remove the ``new-sequencer-connection-pool`` flag as it didn't
       do what it was supposed to do. If you did set it, you can
       safely remove it regardless of whether you disabled the new sequencer connection
       pools in the participant or not.

   - Scan

     - **Experimental**: Added an optional ``traffic_summary`` field to the response of ``GET /v0/events/{update-id}`` and ``POST /v0/events`` endpoints.
       When enabled by SV configuration, traffic summaries are included alongside verdicts in event history items.
       This is part of the CIP-104 preview and is subject to change.

       Traffic summaries will be enabled step-by-step on Dev/Test/MainNet,
       once the SVs have successfully concluded their performance testing.
