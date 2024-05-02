.. _validator-backups:

Backup and Restore
-------------------

While all components in the system are designed to be crash-fault-tolerant,
there is always a chance of failures from which the system cannot immediately
recover, e.g. due to misconfiguration or bugs. In such cases, we will need to
restore a component, a full validator node, or even the whole network, from
backups or from dumps from components that are still operational.

Backup of Node Identities
+++++++++++++++++++++++++

Once your validator node is up and onboarded, please make sure to backup the node identities of your node. **Note that this information
is highly sensitive, and contains the private keys of your participant,** so make sure to store it in
a secure location, such as a Secret Manager. On the other hand, it is crucial for maintaining your identity (and thus, e.g.
access to your Canton Coin holdings), so must be backed up outside of the cluster.

Your identites may be fetched from your node through the following endpoint:

.. code-block:: bash

    curl 'https://wallet.validator.YOUR_HOSTNAME/api/validator/v0/admin/participant/identities' -H 'authorization: Bearer <token>'

where `<token>` is an OAuth2 Bearer Token with enough claims to access the Validator app,
as obtained from your OAuth provider. For context, see the Authentication section :ref:`here <app-auth>`.


Backups of postgres instances
+++++++++++++++++++++++++++++

To backup a validator node, please make sure that all Postgres instances are
backed up at least every 4 hours. Note that there is a strict order requirement
between the backups: **the backup of the apps postgres instance must be taken at
a point in time strictly earlier than that of the participant**.
Please make sure the apps instance backup is completed before starting the participant one.
We will provide guidelines on retention of older backups at a later point in time.

If you are running your own Postgres instances in the cluster, backups can be
taken either using tools like pgdump, or through snapshots of the underlying
Persistent Volume. Similarly, if you are using Cloud-hosted Postgres, you can
either use tools like pgdump or backup tools provided by the Cloud provider.


Restoring a validator from backups
++++++++++++++++++++++++++++++++++

Assuming backups have been taken, the entire node can be restored from backups.
The following steps can be taken to restore a node from backups:


#. Scale down all components in the validator node to 0 replicas.

#. Restore the storage and DBs of all components from the backups. The exact
   process for this depends on the storage and DBs used by the components, and
   is not documented here.
#. Once all storage has been restored, scale up all components in the validator node
   back to 1 replica

**NOTE:** Currently, you have to manually re-onboard any users that were
onboarded after the backup was taken.

Disaster recovery from loss of the CometBFT storage layer of the global synchronizer
++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

In case of a complete disaster, where the complete CometBFT layer of the network is lost beyond repair, the SVs will
follow a process somewhat similar to the migration dumps used for :ref:`sv-upgrades` to recover the network to
a consistent state from before the disaster. Correspondingly, the validators will need to follow a process
similar to the one described in :ref:`validator-upgrades`.
The main difference from that process from a validator's perspective is that the existing synchronizer is assumed to be
unusable for any practical purpose, so validators cannot catchup if they are behind.
Moreover, the timestamp from which the network will be recovering will most probably be earlier than the time of the incident,
and data loss is expected to occur.

The steps at the high level are:

1. All SVs agree on the timestamp from which they will be recovering, and follow the disaster recovery process for SVs.
2. Validator operators wait until the SVs have signaled that the restore procedure has been successful, and to which timestamp they have restored.
3. Validator operators create a dump file through their validator app.
4. Validator operators copy the dump file to their validator app's PVC and restart the app to restore the data.

Technical Details
^^^^^^^^^^^^^^^^^

We recommend first familiarizing yourself with the :ref:`migration <validator-upgrades>` process, as the disaster recovery process is similar.
In case of disaster, the SVs will inform you of the need to recover, and indicate the timestamp from which the network will be recovering.

The following steps will produce a data dump through the validator app, consisting of your node's private identities as well as the
Active Contract Set (ACS) as of the required timestamp. That data dump will then be stored on the validator app's PVC,
and the validator app and participant will be configured to consume it and restore the data from it.

The data dump can be fetched from the validator app by running the following command:

.. code-block:: bash

    curl -sSLf "https://wallet.validator.YOUR_HOSTNAME/api/validator/v0/admin/domain/data-snapshot?timestamp=<timestamp>" -H "authorization: Bearer <token>" -X GET -H "Content-Type: application/json" > dump.json

where `<token>` is an OAuth2 Bearer Token with enough claims to access the Validator app, as obtained from your OAuth provider, and `<timestamp>` is the timestamp provided by the SVs,
in the format `"2024-04-17T19:12:02.000000000Z"`.

If the `curl` command fails with a 400 error, that typically means that your participant has been pruned beyond the chosen timestamp,
and your node cannot generate the requested dump.
If it fails with a 429, that means the timestamp is too late for your participant to create a
dump for, i.e. your participant has not caught up to a late enough point before the disaster.
Either way, you will need to go through a process of recreating your validator and recovering your balance,
which will be documented soon.

.. TODO(#11472): refer here to validator re-onboarding docs once they exist.

This file can now be copied to the Validator app's PVC:

.. code-block:: bash

    kubectl cp dump.json validator/<validator_pod_name>:/domain-upgrade-dump/domain_migration_dump.json

where `<validator_pod_name>` is the full name of the pod running the validator app.

Migrating the Data:

Please follow the instructions in the :ref:`Deploying the Validator App and Participant <validator-upgrades-deploying>` section
to update the configuration of the validator app and participant to consume the migration dump file.
