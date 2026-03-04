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

   - Participant

     - Increase default retention parameters of the `session encryption keys cache <https://docs.digitalasset.com/operate/3.4/howtos/optimize/session_keys.html#configure-session-keys>`__.
       Most notably, increase the lifetime of session encryption keys from 10 minutes to 1 hour,
       to improve performance and reduce (KMS) costs throughout the network.
       This change has no practical security implication for participants that are not using an external KMS:
       in this case the main (asymmetric) encryption keys are usually already available in memory (in addition to being stored inside the participant database).
       We encourage operators of KMS-enabled participants to review the updated sections on KMS usage for :ref:`validator participants <validator-kms-config>` and :ref:`SV participants <sv-kms-participant>` for more pointers about the security impact of session key caching and ways to tweak the relevant parameters to individual needs.

   - Scan

     - **Experimental**: Added an optional ``traffic_summary`` field to the response of ``GET /v0/events/{update-id}`` and ``POST /v0/events`` endpoints.
       When enabled by SV configuration, traffic summaries are included alongside verdicts in event history items.
       This is part of the CIP-104 preview and is subject to change.
