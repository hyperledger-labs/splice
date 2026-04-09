..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV App

        - Package versions newer than the version specified in the AmuletRules configuration are now automatically unvetted by the SV app after a successful downgrade vote.

    - Deployment

        - SV apps now support a ``copyVotesFrom`` setting that automatically mirrors governance votes
          from another named SV, which can help operators keep votes in sync when they run multiple SV nodes.

        - The SV helm chart now supports a new ``synchronizers`` value that replaces the previous ``domain`` value.
          The new structure allows configuring ``current``, ``successor``, and ``legacy`` synchronizer nodes, each with
          ``sequencerPublicUrl``, ``sequencerAddress``, ``mediatorAddress``, optional ``sequencerPruningConfig``,
          ``enableBftSequencer``, and inline ``cometBFT``.
          The ``synchronizers.skipInitialization`` field replaces ``domain.skipInitialization``.
          The previous ``domain`` value is still accepted for backwards compatibility but cannot be combined with ``synchronizers``.
          We strongly recommend updating your ``sv-values.yaml`` to use the new ``synchronizers`` structure, as
          the ``domain`` value will be removed in a future release.
          See :ref:`helm-sv-install` for the updated configuration instructions.

        - The Scan helm chart now supports a new ``synchronizers`` value that replaces the previous top-level
          ``sequencerAddress``, ``mediatorAddress``, and ``bftSequencers`` values.
          The new structure requires ``synchronizers.current.sequencer`` and ``synchronizers.current.mediator``, and
          optionally supports ``successor`` and ``legacy`` entries with the same fields, as well as per-synchronizer
          ``bftSequencerConfig.p2pUrl``.
          The previous ``sequencerAddress`` and ``mediatorAddress`` values are still accepted for backwards compatibility
          but cannot be combined with ``synchronizers``.
          We strongly recommend updating your ``scan-values.yaml`` to use the new ``synchronizers`` structure, as
          the previous values will be removed in a future release.
          See :ref:`helm-sv-install` for the updated configuration instructions.

    - Scan

        - Added a new ``GET /v2/updates/hash/{hash}`` endpoint that returns the update associated with a given external transaction hash of a prepared transaction.
          This endpoint is not always BFT safe, see the Scan OpenAPI documentation for details.
