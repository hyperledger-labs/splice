..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Participant

    - Increase default retention parameters of the `session encryption keys cache <https://docs.digitalasset.com/operate/3.4/howtos/optimize/session_keys.html#configure-session-keys>`__.
      Most notably, increase the lifetime of session encryption keys from 10 minutes to 1 hour,
      to improve performance and reduce (KMS) costs throughout the network.
      This change has no practical security implication for participants that are not using an external KMS:
      in this case the main (asymmetric) encryption keys are usually already available in memory (in addition to being stored inside the participant database).
      We encourage operators of KMS-enabled participants to review the updated sections on KMS usage for :ref:`validator participants <validator-kms-config>` and :ref:`SV participants <sv-kms-participant>` for more pointers about the security impact of session key caching and ways to tweak the relevant parameters to individual needs.

    - APIs:

      - *BREAKING* The
      
          - ``/v2/updates`` HTTP POST and websocket GET endpoints
          - ``/v2/updates/flats`` HTTP POST and websocket GET endpoints

        were incorrectly retuning LedgerEffects events (i.e., ``CreatedEvent`` and ``ExercisedEvent``). They are now corrected to return
        AcsDelta (flat) events (i.e., ``CreatedEvent`` and ``ArchivedEvent``).


  - Sequencer

    - Fix issue where sequencer startup could take more than 10 minutes.


  - SV app

    - Improve the automation for converting featured app activity
      markers to handle batches of markers for nodes that have not
      vetted the same version of the amulet package.

  - Validator app

    - Prevent secrets from being logged at DEBUG level when the environment variable ``SPLICE_APP_DARS`` or ``.appDars`` in Helm were set.
