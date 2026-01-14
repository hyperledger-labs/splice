..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv_restore:

=================
Disaster Recovery
=================

There are three ways to recover from disasters:

#. In simple cases where only a single node is affected but the overall
   network is still healthy, a :ref:`Restore from backup <sv_backup_restore>` is
   usually sufficient.

#. If a full backup is unavailable but an identities backup has been
   created, the balance of the SV can be :ref:`recovered <sv_reonboard>` on a dedicated
   validator but the SV must be onboarded as a separate node.

#. Lastly, For network-wide failures, a more complex :ref:`Disaster recovery procedure <sv_network_dr>` is required.


.. _sv_backup_restore:

Restoring From Data Corruption
==============================

While all components in the system are designed to be crash-fault-tolerant, there is always
a chance of failures from which the system cannot immediately recover, e.g. due to misconfiguration or bugs.
In such cases, we will need to restore a component, a full SV node, or even the whole network,
from backups or from dumps from components that are still operational.

Restoring a single component from backups
-----------------------------------------

If a single component in an SV node is corrupted, it should be recoverable from
backups without restoring the whole node. This scenario is not yet tested enough to be
advisable for production. Moreover, there are certain dependencies between the components
that are not yet documented here.

Restoring a full SV node from backups
-------------------------------------

Assuming backups have been taken for the storage and DBs of all components per the
instructions in :ref:`sv_backups`, the entire node can be restored from backups. Recall that
in order for a set of backup snapshots to be consistent, the backup taken from the apps
database instance must be taken at a time strictly earlier than that of the participant.

Assuming such a consistent set of backups is available, the following steps can be taken
to restore the node:

Scale down all components in the SV node to 0 replicas
(replace ``-0`` with the correct migration ID in case a :ref:`migration <sv-upgrades>` has already been performed):

.. code-block:: bash

    kubectl scale deployment --replicas=0 -n sv \
      global-domain-0-cometbft \
      global-domain-0-mediator \
      global-domain-0-sequencer \
      participant-0 \
      scan-app \
      sv-app \
      validator-app

Restore the storage and DBs of all components from the backups. The exact process for this
depends on the storage and DBs used by the components, and is not documented here.

Once all storage has been restored, scale up all components in the SV node back to 1 replica
(replace ``-0`` with the correct migration ID in case a :ref:`migration <sv-upgrades>` has already been performed):

.. code-block:: bash

    kubectl scale deployment --replicas=1 -n sv \
      global-domain-0-cometbft \
      global-domain-0-mediator \
      global-domain-0-sequencer \
      participant-0 \
      scan-app \
      sv-app \
      validator-app

Once all components are healthy again, they should start catching up their state from peer
SVs, and eventually become functional again.

.. _sv_reonboard:

Re-onboard an SV and recover Amulets with a validator
=====================================================

In the case of a catastrophic failure of the SV node, the amulets owned by the SV can be recovered via deploying a standalone validator node with control over the SV's participant keys.
The SV node can be re-onboarded with a new identity and the amulets can be transferred from the validator to the new SV node.

In order to be able to recover the amulet, the backup of the identities of your SV node is required.
The details of fetching the identities are provided in the :ref:`Backup of Node Identities <sv_backups>` section.

From the backup of Node Identities, copy the content of the field ``identities.participant`` and save it as a separate JSON file.
This file will be used as identities bootstrap dump for the validator runbook.

.. code-block:: bash

    jq '.identities.participant' backup.json > dump.json


Once the failed SV node is offboarded by a majority of SVs (via a governance vote on a ``OffboardMember`` action), we can deploy a standalone validator node for recovering the SV's amulets.

Repeat the steps described in :ref:`helm-validator-install` for installing the validator app and participant,

While doing so, please note the following:

* Modify the file ``splice-node/examples/sv-helm/standalone-validator-values.yaml`` so that ``validatorPartyHint`` is set to the name you chose when creating the SV identity.
* Follow the notes in :ref:`Restoring from a Participant Identities Dump <validator_reonboard>` to restore the validator with the identities from the backup.
  Use the separate JSON file prepared previously.

Once the validator is up and running, login to the wallet of the validator ``https://wallet.validator.YOUR_HOSTNAME`` with the validator user account setup in :ref:`helm-validator-auth0`.
Confirm that the wallet balance is as expected. It should be the same as the amount that the original SV owned.

You can now deploy and onboard a fresh SV node (reusing your SV identity but otherwise starting with a clean slate) by following the steps in :ref:`helm-sv-install`.

Login to the wallet of the new SV node, copy the new party ID.
Switch to the wallet of the validator, create a new transfer offer sending the amulets to the new SV node with the copied party ID.

Switch to the wallet of the new SV node, accept the transfer offer from the wallet of the new SV node, and verify that the amulets have arrived as expected.

.. _sv_network_dr:

Disaster recovery from loss of CometBFT layer
=============================================

In case of a complete disaster, where the complete CometBFT layer of the network is lost beyond repair, we will
follow a process somewhat similar to the migration dumps used for :ref:`sv-upgrades` to recover the network to
a consistent state from before the disaster. The main difference from that process is that the downtime is not
scheduled, and the existing synchronizer is assumed to be unusable for any practical purpose. Morever, the
timestamp from which the network will be recovering will most probably be earlier than the time of the incident,
and data loss is expected to occur.

The steps at the high level are:

1. All SVs agree on the timestamp from which they will be recovering.
2. Each SV operator gets a data dump from their SV app for that timestamp.
3. Each SV operator creates a migration dump file, which combines the data dump from their SV app with the identities from their backups.
4. SV operators deploy a new synchronizer.
5. Each SV operator copies the migration dump file to their SV app's PVC and migrates the data by restarting the app.

Technical Details
-----------------

We recommend first familiarizing yourself with the :ref:`migration <sv-upgrades>` process, as the disaster recovery process is similar.

Finding a Consistent Timestamp to Recover the Synchronizer From
+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

Unlike the :ref:`migration <sv-upgrades>` process, the synchronizer in case of disaster has not been
paused in an orderly manner, therefore we cannot assume that all SVs are caught up to the same point.
The SV operators therefore need to coordinate to agree on a timestamp that many of the SV nodes have
reached, and from which they will be recovering.

Currently, the recommended method for doing so is by inspecting the log files of the SV node's participant,
specifically searching for log lines from the ACS commitment process. ACS commitment in Canton
is the periodic process in which pairs of participants confirm to each other that they agree on the
the relevant subset of the Active Contract Set (ACS) on ledger at that point in time.
By searching the log files of your participant for "CommitmentPeriod" you can see the periods for which
your participant has committed to the ACS, and with whom. You should look for "Commitment correct for sender" messages, such as:
``Commitment correct for sender PAR::Digital-Asset-Eng-4::12205f1149bc... and period CommitmentPeriod(fromExclusive = 2024-04-21T23:24:00Z, toInclusive = 2024-04-21T23:25:00Z)``,
indicating that your participant agreed with that of another node, `Digital-Asset-Eng-4` in this example,
at the `toInclusive` time, `2024-04-21T23:25:00Z` in this example.

The SVs should find a period for which most of them have mutually committed to. Note that all transactions
committed after the chosen timestamp will be lost. On the other hand, any SV not committed to the chosen
timestamp will have to either completely re-onboarded, or copy over a data dump from another SV that is
caught up to the chosen timestamp. This a tradeoff to be discussed among the SVs at the time of recovery.

It is beneficial to err on the side of going a bit further back in time than the last agreed upon ACS commitment.
Going back 15 minutes further is probably a good rule of thumb, as most validators have at least two transactions every round, which means that
validator participants will have recorded transactions after the selected timestamp, enabling them to get a clean dump with the selected timestamp.

Creating a Data Dump from the SV App
++++++++++++++++++++++++++++++++++++

The migration dump data consist of two parts: private identities, and transaction data.
The private identities are assumed to have been backed up by the SV operator.
See the :ref:`Backup of Node Identities <sv_backups>` section.

The data dump can be fetched from the SV app by running the following command:

.. code-block:: bash

    data=$(curl -sSLf "https://sv.sv.YOUR_HOSTNAME/api/sv/v0/admin/domain/data-snapshot?timestamp=<timestamp>" -H "authorization: Bearer <token>" -X GET -H "Content-Type: application/json")

where `<token>` is an OAuth2 Bearer Token with enough claims to access the SV app, as obtained from your OAuth provider, and `<timestamp>` is the agreed upon timestamp,
in the format `"2024-04-17T19:12:02Z"`.

Please note that both the participant and the sequencer components must still be running
and reachable for this call to succeed.

.. TODO(DACH-NY/canton-network-node#11099): Update this once the sequencer is no longer required

If the `curl` command fails with a 400 error, that typically means that your participant has been pruned beyond the chosen timestamp,
and your node cannot generate the requested dump. Discuss with other SVs whether a later timestamp can be chosen.
If it fails with a 429, that means the timestamp is too late for your participant to create a
dump for. Discuss with other SVs whether an earlier timestamp can be chosen.

Assuming your identities data is in a file `identities.json`, copy it into a bash variable `id`:

.. code-block:: bash

    id=$(cat identities.json)

We will now merge the two parts of the data dump into the format expected by the SV app.

Create the migration dump file by merging the json structures from the identities dump and the data dump.
You can use the `jq` tool for that as follows:

.. code-block:: bash

    echo "$id" "$data" | jq -s add > dump.json

This file can now be copied to the SV app's PVC:

.. code-block:: bash

    kubectl cp dump.json sv/<sv_app_pod_name>:/domain-upgrade-dump/domain_migration_dump.json

where `<sv_app_pod_name>` is the full name of the pod running the SV app.



Migrating the Data
++++++++++++++++++

Please follow the instructions in the :ref:`Updating Apps <sv-upgrades-deploying-apps>` section
to update the configuration of the SV app to consume the migration dump file, and seed the new synchronizer.
