..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator-upgrades:

Synchronizer Upgrades with Downtime
===================================

To consume non-backwards-compatible major version upgrades of the Canton software the Global Synchronizer
uses, you need to follow the procedure for synchronizer (i.e., domain) upgrades with downtime described below.

.. _validator-upgrades-overview:

Overview
--------

This overview is trimmed to what is most relevant for the operators of regular (non-super) validators.
For a more comprehensive overview, please refer to the :ref:`documentation for SV operators <sv-upgrades-overview>`.

1. Canton releases containing breaking changes become available and Splice releases compatible with these Canton releases become available.
2. SVs agree and eventually confirm via an on-ledger vote on which specific date and time the network downtime necessary for the upgrade will start. Information about the downtime window is communicated to validators.
3. At the start of the downtime window, the SVs automatically pause all traffic on the operating version of the global synchronizer.
4. Shortly after traffic on the global synchronizer has been paused (there is a short delay to ensure that all components have synced up to the final state of the existing synchronizer), the validator node software automatically exports so-called migration dumps.
   In a Kubernetes deployment, this dump is saved to attached Kubernetes volumes.
   In Docker-compose deployments, the dump is saved to a docker volume.
   See :ref:`validator-upgrades-dumps`.
5. Validator operators verify that their nodes have caught up to the now-paused global synchronizer. See :ref:`validator-upgrades-catching-up`.
6. All SVs and validators previously using the now-paused global synchronizer create full backups of their nodes. (Both for disaster recovery and for supporting audit requirements). See :ref:`validator-backups`.
7. Validators wait until the SVs signal that the migration has been successful.
8. All validators upgrade theirs deployments. See :ref:`validator-upgrades-deploying`.
9. Upon (re-)initialization, the validator backend automatically consumes the migration dump and initializes the new validator participant based on the contents of this dump. App databases are :ref:`preserved <validator-upgrades-state>`.

.. note::
  This process creates a new synchronizer instance. Because
  :ref:`synchronizer traffic balances <traffic>` are tracked per synchronizer instance this implies
  that all validator traffic balances start at zero on this new instance. The
  remaining traffic on the old synchronizer instance cannot be used anymore once
  that instance is shut down, effectively resulting in a loss of that balance.

  **Validator operators are thus strongly encouraged to purchase traffic on a
  pay-as-you-go basis** in small enough increments such that the cost of the
  remaining traffic balance lost due to a synchronizer upgrade with downtime is
  well acceptable and easily amortized across the activity of the validator node
  on the old synchronizer instance.

Technical Details
-----------------

The following section assumes that the validator node connected to the original synchronizer has already been deployed.

.. _validator-upgrades-state:

State and Migration IDs
+++++++++++++++++++++++

Synchronizer upgrades with downtime effectively clone the state of the existing synchronizer, with some finer points:

- All :ref:`identities <sv-identities-overview>` are reused, which includes all party IDs and participant identities.
  This is realized through exporting and importing a :ref:`migration dump <validator-upgrades-dumps>`.
- Active ledger state is preserved.
  This is realized through exporting and importing a :ref:`migration dump <validator-upgrades-dumps>`.
- Historical app state in the validator app (such as transaction history) is preserved. Note however, that the transaction history exposed by the participant is not preserved and the participant will only serve history going forward.
  This is realized through persisting and reusing the (PostgreSQL) database of the validator app.

For avoiding conflicts across migrations, we use the concept of a migration ID:

- The migration ID is 0 during the initial bootstrapping of a network and incremented after each synchronizer upgrade with downtime.
- The validator app is aware of the migration ID and uses it for ensuring the consistency of its internal stores and avoiding connections to nodes on the "wrong" synchronizer.
- The validator Canton participant is **not** directly aware of the migration ID.
  As part of :ref:`validator-upgrades-deploying`, the validator app will initialize a fresh participant
  (i.e., a newly deployed participant starting out with an empty database)
  based on the migration ID configured in the validator app.
  A fresh participant needs to be deployed in order to upgrade across non-backwards-compatible changes to the Canton software.

Implications for Apps and Integrations
++++++++++++++++++++++++++++++++++++++

This guide focuses on the steps necessary for upgrading the validator node itself.
Additional considerations may apply for ensuring that custom applications and integrations remain functional and consistent across major upgrades.
As a consequence of :ref:`validator-upgrades-state`, additional considerations may include the following:

- A major upgrade only preserves the active contracts but not the update history inside the participant.
  In particular, you will not be able to get transactions from before the major upgrade on the update service on the Ledger API of the newly deployed validator node.
- Offsets on the upgraded validator node start from ``0`` again.
- The update history will include special import transactions for the contracts imported from the old synchronizer. They all have record time ``0001-01-01T00:00:00.000000Z``, and represent the creation of the imported contracts.

For a representative example runbook covering the migration of a specific integration use-case,
see the `Rolling out Major Splice Upgrades <https://docs.digitalasset.com/integrate/devnet/exchange-integration/node-operations.html#rolling-out-major-splice-upgrades>`_ section of the Digital Asset `Exchange Integration Guide <https://docs.digitalasset.com/integrate/devnet/exchange-integration/>`_.

.. _validator-upgrades-dumps:

Migration Dumps
+++++++++++++++

Migration dumps contain identity and transaction data from the validator participant.
The migration dump is automatically created once a scheduled synchronizer upgrade begins and the existing synchronizer has been paused.
When redeploying the validator app as part of the migration process (see :ref:`validator-upgrades-deploying`),
the validator app will automatically consume the migration dump and initialize the participant based on the contents of this dump.

For Kubernetes deployments deployed using the official Helm charts and following
the :ref:`Helm-based deployment documentation <k8s_validator>`,
a persistent Kubernetes volume is attached to the ``validator-app`` pod and configured
as the target storage location for migration dumps.

Similarly, for Docker-compose deployments, a docker volume is created, mounted to the
``validator-app`` container, and is configured
as the target storage location for migration dumps.

.. _validator-upgrades-catching-up:

Catching Up Before the Migration
++++++++++++++++++++++++++++++++

In order for the migration to the new synchronizer to be safe and successful, it is important that the validator node is fully caught up on the existing synchronizer before proceeding to :ref:`validator-upgrades-deploying`.

* To ensure that the validator participant has caught up and the :ref:`migration dump <validator-upgrades-dumps>` has been created as expected, operators can check the logs of the ``validator-app`` pod for ``Wrote domain migration dump`` messages.
* To ensure that the validator app has caught up, operators can check the logs of the ``validator-app`` pod for the message ``Ingested transaction``.
  If the latest such message is 10 or more minutes old, the validator app has very likely (with a large safety margin) caught up to the state on the participant, and hence to the state of the existing (paused) synchronizer.
* Note that the sequencers of the existing (old) synchronizer will be kept available by SVs for a limited
  time after the migration to the new synchronizer has been completed. Once they are shut down, the validator
  will not be able to catch up anymore. You should therefore ensure that your node is caught up and migrated
  to the new synchronizer in a timely manner after the migration.

.. _validator-upgrades-deploying:

Deploying the Validator App and Participant
+++++++++++++++++++++++++++++++++++++++++++

Deploying the Validator App and Participant (Kubernetes)
""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This section refers to validators that have been deployed in Kubernetes using the instructions in :ref:`k8s_validator`.

Once you confirmed that your validator is caught up, as explained above, confirm that a migration dump has been created by
searching the logs of the ``validator-app`` pod for ``Wrote domain migration dump`` messages.

Repeat the steps described in :ref:`helm-validator-install` for installing the validator app and participant,
substituting the migration ID (``MIGRATION_ID``) with the target migration ID after the upgrade (typically the existing synchronizer's migration ID + 1).

While doing so, please note the following:

* Please make sure to pick the correct (incremented) ``MIGRATION_ID`` when following the steps.
  Notably, by consistently following through on updating the ``MIGRATION_ID``,
  you should, in the end, deploy a *new* participant with a *fresh* (empty) database.
  (In case your database setup requires you to create databases manually,
  for example because you want to limit the permissions of the database user used by the participant deployment,
  please ensure that you have created the new database as per the updated ``.persistence.databaseName`` value on the participant chart.)
* Please modify the file ``splice-node/examples/sv-helm/standalone-validator-values.yaml`` so that ``migration.migrating`` is set to ``true``.
  This will ensure that the validator app will consume the migration dump and initialize the participant based on the contents of this dump.
* You do not need to redeploy the ``postgres`` release.
  The updated Canton participant will use a new database on the PostgreSQL instance,
  whereas the validator app will reuse the existing state (see :ref:`validator-upgrades-state`).
* Use ``helm upgrade`` in place of ``helm install`` for the ``validator`` chart.
* Please make sure that Helm chart deployments are upgraded to the expected Helm chart version; during an actual upgrade this version will be different from the one on your existing deployment.

See :ref:`validator_health` for pointers on determining the status of your validator after the migration.
In case of issues, check your logs for warnings and errors and consult :ref:`validator-migration-troubleshooting` below.

Once you have confirmed that the migration has been successful:

* It is recommended to change the ``migration.migrating`` value on the ``validator`` chart back to ``false``
  (**keep** the incremented ``MIGRATION_ID``!) and perform another ``helm upgrade``.
* The old participant deployment (old ``MIGRATION_ID``) is no longer used and can be decommissioned.
  We recommend retaining its database (or a current backup thereof) for at least another week after the migration,
  in case the synchronizer migration needs to be rolled back due to an unexpected major issue.

Deploying the validator App and Participant (Docker-Compose)
""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

This section refers to validators that have been deployed in Docker-Compose using the instructions in :ref:`compose_validator`.

Once you confirmed that your validator is caught up, as explained above, confirm that a migration dump has been created using:

.. code-block:: bash

  docker compose logs validator | grep "Wrote domain migration dump"

(For general reading about docker compose log retention and rotation, see these `Docker docs <https://docs.docker.com/engine/logging/configure/>`_).

If the migration dump has been created, proceed with the following steps:

* Stop the validator, using ``./stop.sh``.
* In case of an actual version upgrade (not just a test migration), upgrade your validator to the target version by updating the bundle and adjusting the ``IMAGE_TAG`` as you would during a :ref:`minor upgrade <validator-upgrades>`.
* Restart the validator, while updating the migration ID in the ``-m <migration ID>`` argument,
  and also including ``-M`` to instruct the validator to perform the actual migration
  to the new migration ID. Note that ``-M`` is required only in the first startup after the migration,
  to instruct the validator to perform the actual migration. Follow-up restarts should keep the
  ``-m <migration ID>``, but omit the ``-M``.
* See :ref:`validator_health` for pointers on determining the status of your validator after the migration.
  In case of issues, check your logs for warnings and errors and consult :ref:`validator-migration-troubleshooting` below.

.. _validator-migration-troubleshooting:

Troubleshooting
---------------

Common errors
+++++++++++++

If any of the steps above fail, double check the following:

- The expected versions were deployed, both *before* the migration and *after* the migration.
- In case that you don't see the ``Wrote domain migration dump`` message in the logs of the validator app despite confirming that your are on the expected version before the migration,
  your validator might already have taken a dump at an earlier time.
  You can inspect the contents of the ``domain-migration-validator-pvc`` PVC (Helm) or ``domain-upgrade-dump`` volume (compose).
  In case a ``domain_migration_dump.json`` exists there and you are unsure about the circumstances of its creation,
  it is recommended to remove it and restart the validator app (on the older version and migration ID) to trigger the creation of a fresh dump.
- You have deployed a fresh Canton participant as part of the upgrade and that participant was configured to use a fresh (empty) database.
  By correctly setting the migration ID while following the deployment steps above, this should be the case.
- The correct (incremented) ``MIGRATION_ID`` has been set *after* the upgrade.
- If you get an error like ``Migration ID was incremented (to 1) but no migration dump for restoring from was specified.`` you are missing the ``migrating: true`` flag (for Helm) or ``-M`` argument (for Docker compose).

.. _validator-upgrade-failure-cleanup:

Cleaning up the validator app database in the event of a failed upgrade
+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

In rare occasions, where the upgrade is not successful but the validator app manages to start ingesting from the new migration id,
the app's database might contain data of the failed migration id that should be removed.
To check whether any such data has been stored, you can query your validator app's database
with the following query:

.. code-block:: sql

    select *
    from update_history_last_ingested_offsets
    where history_id = (select distinct history_id from update_history_last_ingested_offsets)
      and migration_id = ?;

Replace the migration_id parameter with the migration_id for which the upgrade procedure just failed.

If no rows are returned by the query, that means that nothing was ingested and thus
the app's database does not contain any invalid data.

If a row is returned, that means that data was ingested that should be purged.
The easiest way is to restore the backup that was taken as part of the upgrade process (as per :ref:`validator-backups`)
for the validator app and drop the database of the failed migration id for the participant.
