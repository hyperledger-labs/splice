..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv-pruning:

Pruning
=======

The sequencer, participant and CometBFT have pruning options that can be used to ensure storage use is kept within reasonable bounds.

Pruning can also be configured for the participant, but we don't currently recommend enabling it for the SVs.

.. _sv-pruning-sequencer:

Sequencer pruning
-----------------

Can be enabled in helm through the following config:

.. literalinclude:: ../../../apps/app/src/pack/examples/sv-helm/sv-values.yaml
    :language: yaml
    :start-after: DOCS_PRUNING_START
    :end-before: DOCS_PRUNING_END

.. note::

    It is recommended that sequencer pruning is enabled and the ``pruningInterval`` is set to ``1 hour`` and the ``retentionPeriod`` to ``30 days``.

.. _sv-pruning-cometbft:

CometBFT pruning
----------------

It is enabled by default.
Pruning is defined as the number of blocks to keep. Older blocks are pruned.

The number of blocks to keep can be configured under the `node` helm values key.

.. literalinclude:: ../../../cluster/helm/splice-cometbft/values-template.yaml
    :language: yaml
    :start-after: DOCS_COMETBFT_PRUNING_START
    :end-before: DOCS_COMETBFT_PRUNING_END

.. _sv_participant_pruning:

Participant pruning
-------------------

Participant pruning is also supported and recommend. To enable it, set the following helm value on your validator chart:

.. literalinclude:: ../../../apps/app/src/pack/examples/sv-helm/sv-validator-values.yaml
    :language: yaml
    :start-after: SV_PARTICIPANT_PRUNING_SCHEDULE_START
    :end-before: SV_PARTICIPANT_PRUNING_SCHEDULE_END

You also need to tell the participant to continue pruning even if it has not received an ACS commitment from one of its counter participant
in the last 30 days. Without this pruning will essentially never run on mainnet as validators get shut down relatively frequently:
To do so, set the following through the ``additionalEnvVars`` on your participant:

.. code-block:: yaml

    additionalEnvVars:
        - name: ADDITIONAL_CONFIG_PRUNING_ACS_COMITMENTS
          value: |
            canton.participants.participant.parameters.stores.safe-to-prune-commitment-state = "all"
