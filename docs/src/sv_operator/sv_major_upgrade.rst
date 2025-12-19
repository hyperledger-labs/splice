..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv-upgrades:

Synchronizer Upgrades with Downtime
===================================

For supporting non-backwards-compatible major version upgrades of the Canton software the Global Synchronizer uses,
the SV operatores follow the procedure for synchronizer (i.e., domain) upgrades with downtime described below.

.. _sv-upgrades-overview:

Overview
--------

1. Canton releases containing breaking changes become available and Splice releases compatible with these Canton releases become available.
2. SVs agree and eventually confirm via an on-ledger vote (cast via the SV UI) on which specific date and time the network downtime necessary for the upgrade will start. It is the responsibility of the DSO to make sure that validator operators are informed about planned upgrades and upgrade timelines.
3. Once the downtime has been scheduled, all SVs deploy new Kubernetes pods containing the new versions of the Canton components of their Super Validator node (participant, sequencer, and mediator) as well as a new CometBFT node, alongside the old versioned ones which are not yet deleted. See :ref:`sv-upgrades-deploying-domain`.
4. At the start of the downtime window, the SV app of SVs automatically pauses all traffic on the operating version of the global synchronizer.
5. Shortly after pausing traffic on the global synchronizer (there is a short delay to ensure that all components have synced up to the final state of the existing synchronizer), the node software of SVs and validators automatically exports so-called migration dumps to attached Kubernetes volumes. See :ref:`sv-upgrades-dumps`.
6. SV operators verify that their nodes have caught up to the now-paused global synchronizer. See :ref:`sv-upgrades-catching-up`.
7. All SVs and validators previously using the now-paused global synchronizer create full backups of their nodes. (Both for disaster recovery and for supporting audit requirements). See :ref:`sv_backups`.
8. All SVs upgrade theirs CN apps pods. See :ref:`sv-upgrades-deploying-apps`.
9. Upon (re-)initialization, the SV node backend automatically consumes the migration dump and initializes all components based on the contents of this dump. The process is analogous for validators, where the validator app is tasked with importing the dump and initializing components. App databases are :ref:`preserved <sv-upgrades-state>`.
10. Once a BFT majority (i.e., >⅔) of SVs have initialized their upgraded nodes, the new version of the synchronizer (with an incremented migration ID and, typically, a new version of the core Canton components) becomes operational automatically. The end of the downtime window is therefore determined by the speed at which a sufficient number of SVs complete the necessary deployment and initialization steps.
11. The remaining (Canton and CometBFT) components of the old synchronizer can be retired now. We recommend to only do so after (non-super) validators have had sufficient time to :ref:`catch up <validator-upgrades>` to the latest state from before the pause.

Technical Details
-----------------

The following section assumes that the SV node on the original synchronizer was deployed using the instructions in :ref:`sv-helm`.

.. _sv-upgrades-state:

State and Migration IDs
+++++++++++++++++++++++

Synchronizer upgrades with downtime effectively clone the state of the existing synchronizer, with some finer points:

- All :ref:`identities <sv-identities-overview>` are reused, which includes all party IDs, all participant, sequencer and mediator (node) identities, as well as CometBFT node identities.
  This is realized through exporting and importing a :ref:`migration dump <sv-upgrades-dumps>`.
- Active ledger state is preserved.
  This is also realized through exporting and importing a :ref:`migration dump <sv-upgrades-dumps>`.
- Historical app state in SV, validator and scan (such as transaction history) is preserved. Note however, that the transaction history exposed by the participant is not preserved and the participant will only serve history going forward.
  This is realized through persisting and reusing the (PostgreSQL) databases of the SV node apps (``sv``, ``scan``, ``validator``).
- The CometBFT blockchain is **not** preserved.
  The upgraded synchronizer starts with a fresh CometBFT chain.
  CometBFT node state from the existing synchronizer is discarded.

For avoiding conflicts across migrations, we use the concept of a migration ID:

- The migration ID is 0 during the initial bootstrapping of a network and incremented after each synchronizer upgrade with downtime.
- The SV node apps (``sv``, ``scan``, ``validator``) are aware of the migration ID and use it for ensuring the consistency of their internal stores and avoiding connections to nodes on the "wrong" synchronizer.
- The SV node Canton components (participant, sequencer, mediator) are **not** directly aware of the migration ID.
  To keep Canton components apart across migrations
  (new Canton components are needed to upgrade across non-backwards-compatible changes to the Canton software),
  we deploy them with migration ID-specific names and ingress rules (see :ref:`sv-upgrades-deploying-domain`).
  We furthermore configure Canton components to create and use migration ID-specific (PostgreSQL) database names.
  As part of :ref:`sv-upgrades-deploying-apps`, the SV app will initialize Canton components based on its configured migration ID.
- The SV node CometBFT component is aware of the migration ID - it is used for forming the CometBFT chain ID.

.. _sv-upgrades-dumps:

Migration Dumps
+++++++++++++++

Migration dumps contain identity and transaction data from all of an SV's Canton components
(participant, sequencer, and mediator) connected to the global synchronizer that is being upgraded.
When using the official Helm charts and following the :ref:`Helm-based deployment documentation <sv-helm>`,
the migration dump is automatically created once a scheduled synchronizer upgrade begins and the existing synchronizer has been paused.
As part of the Helm-based deployment of the SV app (``splice-sv-node``),
a persistent Kubernetes volume is attached to the ``sv-app`` pod and configured as the target storage location for migration dumps.
When redeploying the SV app as part of the migration process (see :ref:`sv-upgrades-deploying-apps`),
the SV app will automatically consume the migration dump and initialize new components (see :ref:`sv-upgrades-deploying-domain`)
based on the contents of this dump.

.. _sv-upgrades-deploying-domain:

Deploying new Canton Components and CometBFT Node
+++++++++++++++++++++++++++++++++++++++++++++++++

Repeat the steps described in :ref:`helm-sv-install` for installing the participant, sequencer, mediator and CometBFT components,
substituting the migration ID (``MIGRATION_ID``) with the target migration ID after the upgrade (typically the existing synchronizer's migration ID + 1).

While doing so, please note the following:

* You don't need to wait for the existing synchronizer to have paused before performing these steps.
  Performing them well in advance of the scheduled downtime can reduce the downtime (s.a. :ref:`sv-upgrades-testing-preparation`).
* Please make sure to pick the correct (incremented) ``MIGRATION_ID`` when following the steps.
* Please make sure that all new Helm charts you install as part of this step have the expected Helm chart version; during an actual upgrade this version will be different from the one on your existing deployment.
* Please don't uninstall any Helm charts installed as part of the original deployment run (with the smaller migration ID).
  We deliberately keep SV participants and core synchronizer components running longer so that validators get a chance to sync up to the latest state from before the pause,
  which they need to do for successfully completing their part of the migration.
* Note that repeating the process with the incremented migration ID will result in the deployment of new pods with new Canton components and a new CometBFT node.
  Among other things, these deployments differ in k8s-internal host names, which are formed based on the migration ID.
* We recommend disabling CometBFT :ref:`state sync <helm-cometbft-state-sync>` while going through a synchronizer migration, to reduce dependencies on the readiness of other SVs.
  To do so, please modify the file ``splice-node/examples/sv-helm/cometbft-values.yaml`` so that ``stateSync.enable`` is set to ``false``.
* Please ensure that your ingress rules are extended accordingly to accommodate the new pods.
  Revisit :ref:`helm-sv-ingress` with the updated migration ID in mind.
* Note that the exposed CometBFT port and CometBFT ``externalAddress`` are changed due to a limitation in CometBFT.
  There is no fundamental need to use the port numbers suggested in the runbook, the only requirement is that either the (external) host IP of the CometBFT pod must be different for each migration ID or its (external) port number.

.. _sv-upgrades-catching-up:

Catching Up Before the Migration
++++++++++++++++++++++++++++++++

In order for the migration to the new synchronizer to be safe and successful, it is important that the SV node is fully caught up on the existing synchronizer before proceeding to :ref:`sv-upgrades-deploying-apps`.

* For making sure that the SV participant has caught up and the :ref:`migration dump <sv-upgrades-dumps>` has been created as expected, operators can check the logs of the ``sv-app`` pod for the message ``Wrote domain migration dump``.
* For making sure that alls apps - ``sv-app``, ``scan-app``, and ``validator-app`` - have caught up, operators can check the logs of the respective pods for ``Ingested transaction`` messages.
  If the latest such message for a given app is 10 or more minutes old, that app has very likely (with a large safety margin) caught up to the state on the participant, and hence to the state of the existing (paused) synchronizer.

.. _sv-upgrades-deploying-apps:

Updating Apps
+++++++++++++

Repeat the steps described in :ref:`helm-sv-install` for installing the ``sv``, ``scan`` and ``validator`` components,
substituting the migration ID (``MIGRATION_ID``) with the target migration ID after the upgrade (typically the existing synchronizer's migration ID + 1).

While doing so, please note the following:

* Please make sure to pick the correct (incremented) ``MIGRATION_ID`` when following the steps.
* Please modify the file ``splice-node/examples/sv-helm/sv-values.yaml`` so that ``migration.migrating`` is set to ``true``.
  This will ensure that the SV app will consume the migration dump and initialize new components based on the contents of this dump.
  Also set ``migration.legacyId`` to the value of migration ID before incremented. (``MIGRATION_ID`` - 1).
* Use ``helm upgrade`` in place of ``helm install`` for the ``sv``, ``scan`` and ``validator`` charts
  and do not uninstall any Helm charts installed as part of the original deployment run (with the smaller migration ID).
* Please make sure that all Helm chart deployments you upgrade as part of this step are upgraded to the expected Helm chart version; during an actual upgrade this version will be different from the one on your existing deployment.
* No ingress rules need to be updated as part of this step.
  Once the redeployment is complete the existing ingress rules will apply to the updated pods.

Once you have confirmed that the migration has been successful:

* Please change the ``migration.migrating`` value on the ``sv`` chart back to ``false``.

Recovering from a failed upgrade
++++++++++++++++++++++++++++++++

In the event of a failed upgrade, Each SV must submit a topology transaction to resume the old synchronizer. This can be triggered using the following command:

.. code-block:: bash

    curl -X POST "https://sv.sv.YOUR_HOSTNAME/api/sv/v0/admin/domain/unpause" -H "authorization: Bearer <token>"

where `<token>` is an OAuth2 Bearer Token obtained from your OAuth provider. For context, see the Authentication section :ref:`here <app-auth>`.

The command will complete successfully once a sufficient number of SVs have executed it. The old synchronizer will then be un-paused.

.. _sv-upgrades-organization:

Organizational Details
----------------------

.. _sv-upgrades-coordination:

Coordination calls
++++++++++++++++++

For keeping upgrade downtimes short and assisting with the quick resolution of individual issues, we propose to perform upgrades and :ref:`upgrade tests <sv-upgrades-testing>` in a synchronized manner, with coordination over a group call (Zoom meeting). All SV operators will hereby be invited to join a call that starts shortly before the beginning of the downtime window (step 4 above) and ends once the upgrade and synchronizer migration has concluded (step 10 above). Our aim is that the core of the upgrade procedure (steps 4-10) can in principle be completed within one hour, realizing that the first coordinated tests will likely take longer.

Each SV must be represented in these calls by at least one person that is capable of performing the steps described in :ref:`sv-upgrades-deploying-domain` and :ref:`sv-upgrades-deploying-apps`.
The operators representing an SV must be:

- Familiar with the technical details around the SV’s deployment.
- Capable (in terms of both skills and permissions) to interact with the SV’s node deployment via Helm and (for debugging with support from the CN team) Kubernetes (kubectl) commands.
- For being able to amend potential errors during :ref:`preparation <sv-upgrades-testing-preparation>`: Capable (in terms of both skills and permissions) to register new ingress rules for internal synchronizer components (sequencer, mediator and CometBFT) and SV participants. Resulting addresses will only be relevant to technical personnel and will follow a schema such as ``component-migrationId.hostname``. Note that for fully supporting the migration flow for CometBFT nodes, it will also be necessary to allow communication over additional ports.

.. todo:: update this with references to the GSF processes, which are now being used to run these calls

.. _sv-upgrades-testing:

Testing
-------

The success of synchronizer upgrades and the duration of downtime both depend on the effectiveness of all SVs in performing the necessary upgrading steps. We therefore recommend that the DSO performs :ref:`coordinated tests <sv-upgrades-testing-coordinated>`
before attempting an upgrade on MainNet.
Mirroring the recommended process for an actual upgrade,
we also recommend that SVs perform :ref:`preparation <sv-upgrades-testing-preparation>` steps in advance of every coordinated test.

.. CN-team internal steps documented in: cluster/README.md#new-domain-readiness-checks

.. _sv-upgrades-testing-preparation:

Individual Preparation
++++++++++++++++++++++

The following steps can be performed before the downtime window has started
and even before a synchronizer upgrade has been formally scheduled:

1. Ensure that the :ref:`backup and restore <sv_backups>` process for the SV works as expected.
2. Deploy a new version of the CometBFT component as per :ref:`sv-upgrades-deploying-domain`
   (``MIGRATION_ID += 1`` compared to the currently active synchronizer),
   including setting up the ingress for this component.
   Ensure that the pod reports as healthy.
   For checking that the ingress rules are set up correctly, ensure that TCP connections to the new CometBFT port are possible,
   for example by using the ``nc`` command: ``nc -vz <cometbft host> <cometbft port>``.
3. Deploy new versions of the Canton components (participant, sequencer, mediator) as per :ref:`sv-upgrades-deploying-domain`
   (``MIGRATION_ID += 1`` compared to the currently active synchronizer),
   including setting up the ingress for the new sequencer.
   Note that these components are only initialized by the SV app once the synchronizer migration has started.
   Ensure that all pods report as healthy.
   Note that when using ``grpcurl`` to check that the ingress rule for the new sequencer is set up correctly
   (as described in :ref:`helm-sv-ingress`), you will get a ``rpc error: code = Unavailable`` error as the sequencer is not yet initialized
   (and its public gRPC endpoint is therefore not yet available).
   If the rpc error details include a ``delayed connect error: 111`` message,
   this is an indicator that the ingress rule is set up correctly.

We recommend that all SVs perform these steps before a :ref:`coordinated test <sv-upgrades-testing-coordinated>` starts and signal their readiness once they have done so.

.. _sv-upgrades-testing-coordinated:

Coordinated Tests
+++++++++++++++++

Coordinated tests mirror the steps that will later be used for upgrading MainNet (see :ref:`above <sv-upgrades-overview>`).
Specifically, coordinated tests involve:

- Scheduling a :ref:`group call <sv-upgrades-coordination>` covering the planned synchronizer downtime.
- Pausing the global synchronizer via a governance vote.
- Each SV making sure that all of its node's components have caught up to the final state of the existing synchronizer - see :ref:`sv-upgrades-catching-up`.
- Creating full backups of SV nodes after they have fully caught up and the synchronizer has been paused.
- Performing all remaining deployment steps necessary for a successful migration to the new synchronizer.
  If :ref:`sv-upgrades-testing-preparation` was followed, this involves only the steps outlined in :ref:`sv-upgrades-deploying-apps`.
  It is fully supported that SVs perform these deployment steps concurrently after the synchronizer has been paused.
- Each SV verifying that its local state is consistent with the state of the global synchronizer before the pause.
  For example, verifying that their own coin balance is as expected.
- Once all SVs (or a governance threshold of SVs) have completed the migration:
  Collectively verifying that the new synchronizer is healthy and operational.
  Synchronizer health can be inferred from monitoring signals such as timely ``SV Status Reports`` from all SVs.
- Agreeing on a timeline for decommissioning the Canton and CometBFT nodes connected to the original (pre-migration) synchronizer.
- Before we decommission the Canton and CometBFT nodes connected to the original (pre-migration) synchronizer, we should remove ``migration.legacyId`` we have set previously in the file ``splice-node/examples/sv-helm/sv-values.yaml``.

.. CN-team internal checklist in: cluster/README.md#participating-in-a-hard-domain-migration

We recommend to perform at least one coordinated test on TestNet, with the exact same configuration and versions that will be used for the upgrade on MainNet,
before scheduling a MainNet upgrade with downtime.

.. _sv-upgrade-failure-cleanup:

Cleanup in the event of failure
-------------------------------

In rare occasions, where the upgrade is not successful but the apps manage to start ingesting from the new migration id,
the apps' databases might contain data of the failed migration id that should be removed.
To check whether any such data has been stored, you can query all of the apps' databases
(that is: validator, scan and sv apps) with the following query:

.. code-block:: sql

    select *
    from update_history_last_ingested_offsets
    where history_id = (select distinct history_id from update_history_last_ingested_offsets)
      and migration_id = ?;

Replace the migration_id parameter with the migration_id for which the upgrade procedure just failed.

If no rows are returned by the query in any of the apps' databases, that means that nothing was ingested and thus
the app databases do not contain any invalid data.

If a row is returned, that means that data was ingested that should be purged.
The easiest way is to restore the backup that was taken as part of the upgrade process (as per :ref:`sv_backups`)
for the validator, scan and SV apps and drop the databases of the failed migration id for sequencer, mediator and participant.
