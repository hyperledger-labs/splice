..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv-pruning:

Pruning
=======

The sequencer and CometBFT have pruning options that can be used to ensure storage use is kept within reasonable bounds.

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
