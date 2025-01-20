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

Please make sure your Postgres instances are backed up at least every 4 hours. We will provide guidelines on retention of older backups
at a later point in time.

While most backups can be taken independently, there is one strict ordering requirement between them:
The backup of the apps postgres instance must be taken at a point in time strictly earlier than that of the participant.
Please make sure the apps instance backup is completed before starting the participant one.

If you are running your own Postgres instances in the cluster, backups can be taken either using tools like `pgdump`, or through snapshots of the underlying Persistent Volume.
Similarly, if you are using Cloud-hosted Postgres, you can either use tools like `pgdump` or backup tools provided by the Cloud provider.

Backup of CometBFT
++++++++++++++++++

In addition to the Postgres instances, the storage used by CometBFT should also be backed up every 4 hours.
CometBFT does not use Postgres.
We recommend backing up its storage by creating snapshots of the underlying Persistent Volume.
