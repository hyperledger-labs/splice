..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator-backups:

Backups
-------

While all components in the system are designed to be crash-fault-tolerant,
there is always a chance of failures from which the system cannot immediately
recover, e.g. due to misconfiguration or bugs. In such cases, you will need to
restore a component or a full validator node from
backups or from dumps of the components that are still operational.

Node operators should take **both** Node Identity backups and Postgres Instance
Backups to protect their node from the widest range of disaster scenarios.

.. _validator-identities-backup:

Backup of Node Identities
+++++++++++++++++++++++++

Once your validator node is up and onboarded, please make sure to backup the node identities of your node. **Note that this information
is highly sensitive, and contains the private keys of your participant**
(unless you configured your validator to :ref:`use an external KMS <validator-kms>`),
so make sure to store it in
a secure location, such as a Secret Manager. On the other hand, it is crucial for maintaining your identity (and thus, e.g.
access to your Canton Coin holdings), so must be backed up outside of the cluster.

Your identites may be fetched from your node through the following endpoint:

.. code-block:: bash

    curl "https://wallet.validator.YOUR_HOSTNAME/api/validator/v0/admin/participant/identities" -H "authorization: Bearer <token>"

where `<token>` is an OAuth2 Bearer Token with enough claims to access the Validator app,
as obtained from your OAuth provider. For context, see the Authentication section :ref:`here <app-auth>`.

If you are using a docker-compose deployment, replace `https://wallet.validator.YOUR_HOSTNAME` with `http://wallet.localhost`.
If you are running the docker-compose deployment with no auth, you can use the utility Python script ``get-token.py``
to generate a token for the ``curl`` command by running ``python get-token.py administrator`` (requires `pyjwt <https://pypi.org/project/PyJWT/>`_).

.. _validator-database-backup:

Backups of postgres instances
+++++++++++++++++++++++++++++

To backup a validator node, we recommend to back up all Postgres instances
at least every 4 hours. Note that there is a strict order requirement
between the backups: **the backup of the validator app's postgres instance must be taken at
a point in time strictly earlier than that of the participant**.
Please make sure the app's instance backup is completed before starting the participant one.

If you are running your own Postgres instances in the cluster, backups can be
taken either using tools like ``pg_dump``, or through snapshots of the underlying
Persistent Volume. Similarly, if you are using Cloud-hosted Postgres, you can
either use tools like ``pg_dump`` or backup tools provided by the Cloud provider.

If you are running a docker-compose deployment, you can run the following commands to backup
the Postgres databases. This will create two dump files, one for the participant and one for
the validator app.

.. code-block:: bash

  docker exec -i splice-validator-postgres-splice-1 pg_dump -U cnadmin validator > "${backup_dir}"/validator-"$(date -u +"%Y-%m-%dT%H:%M:%S%:z")".dump
  active_participant_db=$(docker exec splice-validator-participant-1 bash -c 'echo $CANTON_PARTICIPANT_POSTGRES_DB')
  docker exec splice-validator-postgres-splice-1 pg_dump -U cnadmin "${active_participant_db}" > "${backup_dir}"/"${active_participant_db}"-"$(date -u +"%Y-%m-%dT%H:%M:%S%:z")".dump

Historical backups
^^^^^^^^^^^^^^^^^^

If you have enabled participant :ref:`pruning <validator_participant_pruning>`, but wish to preserve participant data for longer, e.g. for future auditability, then you should preserve historical backups such that they are apart by no more than the size of your pruning window

This means that backups must be preserved with a time difference between two historical backups smaller than the `retention` set for the participant.

Furthermore, backups should be retained for previous :ref:`major upgrades <validator-upgrades>`, both the historical backups for the participant and all the backups for the other apps.
