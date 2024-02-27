.. _sv_restore:

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

Scale down all components in the SV node to 0 replicas:

.. code-block:: bash

    kubectl scale deployment --replicas=0 -n sv \
      cometbft \
      lobal-domain-mediator \
      global-domain-sequencer \
      participant \
      scan-app \
      sv-app \
      validator-app

.. TODO(#9818)
  Update deployment names with migration ID suffixes once they're in the runbook.

Restore the storage and DBs of all components from the backups. The exact process for this
depends on the storage and DBs used by the components, and is not documented here.

Once all storage has been restored, scale up all components in the SV node back to 1 replica:

.. code-block:: bash

    kubectl scale deployment --replicas=1 -n sv \
      cometbft \
      lobal-domain-mediator \
      global-domain-sequencer \
      participant \
      scan-app \
      sv-app \
      validator-app

.. TODO(#9818)
  Update deployment names with migration ID suffixes once they're in the runbook.

Once all components are healthy again, they should start catching up their state from peer
SVs, and eventually become functional again.


Disaster recovery from loss of CometBFT layer
---------------------------------------------

In case of a complete disaster, where the complete CometBFT layer of the network is lost beyond repair, we will
document a process for recovering the network from data dumps taken from participants of
SV nodes, quite similar to the migration dumps used for :ref:`sv-upgrades`.
This process is not yet documented here.
