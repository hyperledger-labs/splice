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
