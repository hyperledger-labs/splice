..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv_backups:

Backups
-------

Backup of Node Identities
+++++++++++++++++++++++++

Once your SV node is up and onboarded, please make sure to backup the node identities of your SV node. **Note that this information
is highly sensitive, and contains the private keys of your participant, sequencer and mediator,** so make sure to store it in
a secure location, such as a Secret Manager. On the other hand, it is crucial for maintaining your identity (and thus, e.g.
access to your Canton Coin holdings), so must be backed up outside of the cluster.

Your identites may be fetched from your node through the following endpoint:

.. code-block:: bash

    curl "https://sv.sv.YOUR_HOSTNAME/api/sv/v0/admin/domain/identities-dump" -H "authorization: Bearer <token>"

where `<token>` is an OAuth2 Bearer Token obtained from your OAuth provider. For context, see the Authentication section :ref:`here <app-auth>`.

Backup of Postgres Instances
++++++++++++++++++++++++++++

Taking backups
^^^^^^^^^^^^^^

While most backups can be taken independently, there is one strict ordering requirement between them:
The backup of the apps postgres instance must be taken at a point in time strictly earlier than that of the participant.
Please make sure the apps instance backup is completed before starting the participant one.

If you are running your own Postgres instances in the cluster, backups can be taken either using tools like `pgdump`, or through snapshots of the underlying Persistent Volume.
Similarly, if you are using Cloud-hosted Postgres, you can either use tools like `pgdump` or backup tools provided by the Cloud provider.

Please make sure your Postgres instances are backed up at least every 4 hours.

Historical backups
^^^^^^^^^^^^^^^^^^

We need historical backups in order to preserve a gap-less history from genesis, which can be uses during audits and more generally prove the correctness of the current synchronizer state to outside observers.

For the sequencer, when :ref:`pruning <sv-pruning-sequencer>` is enabled, historical backups must be kept for each pruning window.
This means that backups must be preserved with a time difference between two historical backups smaller than the `retentionPeriod` set for the sequencer.

For SVs, by default, the participant, mediator and the splice apps have no pruning enabled.

Furthermore, backups must be retained for previous :ref:`major upgrades <sv-upgrades>`. This includes all the historical sequencer backups and the backups of the other apps.

Backup of CometBFT
++++++++++++++++++

In addition to the Postgres instances, the storage used by CometBFT should also be backed up every 4 hours.
CometBFT does not use Postgres.
We recommend backing up its storage by creating snapshots of the underlying Persistent Volume.

Historical backups
^^^^^^^^^^^^^^^^^^

We need historical backups in order to preserve a gap-less history from genesis, which can be uses during audits and more generally prove the correctness of the current synchronizer state to outside observers.

CometBFT has :ref:`pruning <sv-pruning-cometbft>` enabled by default. The pruning window is defined by the number of blocks
to retain.
We target to set the number of blocks to retain to a value that keeps at least 30 days of data.
The CometBFT historical backups must be kept in such a way that the difference between the block height when two backups are taken is smaller than the configured number of blocks to retain.
We recommend that backups are taken and preserved in a more aggressive fashion, every 2 weeks.

Furthermore, backups must be retained for previous :ref:`major upgrades <sv-upgrades>`.
