.. _sv-upgrades:

Synchronizer Upgrades with Downtime
===================================

For supporting non-backwards-compatible major version upgrades of the Canton software the Canton Network will implement a procedure for synchronizer (i.e., domain) upgrades with downtime.

Overview
--------

1. Canton releases containing breaking changes become available and Canton Network Node releases compatible with these Canton releases become available.
2. SVC members agree and eventually confirm via an on-ledger vote (cast via the SV UI) on which specific date and time the network downtime necessary for the upgrade will start. It is the responsibility of the SVC to make sure that validator operators are informed about planned upgrades and upgrade timelines.
3. At the start of the downtime window, the SV app of SVC members automatically pauses all traffic on the operating version of the global synchronizer.
4. Shortly after pausing traffic on the global synchronizer (there is a short delay to ensure that all components have synced up to the final state of the existing synchronizer), the node software of SVs and validators automatically exports so-called migration dumps to attached Kubernetes volumes. See :ref:`sv-upgrades-dumps`.
5. All SVs and validators previously using the now-paused global synchronizer create full backups of their nodes. (Both for disaster recovery and for supporting audit requirements). See :ref:`sv_backups`.
6. All SVs upgrade by:

  a) Deploying new Kubernetes pods containing the new versions of the Canton components of their Super Validator node (participant, sequencer, and mediator) alongside the old versioned ones which are not yet deleted, as well as a new CometBFT node. See :ref:`sv-upgrades-deploying-domain`.
  b) Upgrading their CN apps pods. See :ref:`sv-upgrades-deploying-apps`.

7. Upon (re-)initialization, the SV node backend automatically consumes the migration dump and initializes all components based on the contents of this dump. The process is analogous for validators, where the validator app is tasked with importing the dump and initializing components. Operationally, this process is quite similar to the weekly upgrade process that we have at the moment, with the big difference being that databases are preserved.
8. Once a BFT majority (i.e., >⅔) of SVs have initialized their upgraded nodes, the new version of the synchronizer (with an incremented migration ID and, during a real upgrade, a new version of the core Canton components) becomes operational automatically. The end of the downtime window is therefore determined by the speed at which a sufficient number of SVs complete the necessary deployment and initialization steps.
9. The remaining (Canton) nodes of the old synchronizer can be retired now. We recommend to only do so after the migration was confirmed to be successful, because in the event of a failure while setting up the new synchronizer these nodes can be used for reviving the original synchronizer.

Technical Details
-----------------

The following section assumes that the SV node on the original synchronizer was deployed using the instructions in :ref:`sv-helm`.

.. _sv-upgrades-dumps:

Migration Dumps
+++++++++++++++

Migration dumps contain identity and transaction data from all of an SV's Canton components
(participant, sequencer, and mediator) connected to the global synchronizer that is being upgraded.
When using the official Helm charts and following the :ref:`Helm-based deployment documentation <sv-helm>`,
the migration dump is automatically created once a scheduled synchronizer upgrade begins and the existing synchronizer has been paused.
As part of the Helm-based deployment of the SV app (``cn-sv-node``),
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

* Please make sure to pick the correct (incremented) ``MIGRATION_ID`` when following the steps.
* Please don't uninstall any helm charts installed as part of the original deployment run (with the smaller migration ID).
  We deliberately keep core synchronizer components running longer so that validators get a chance to sync up to the latest state from before the pause,
  which they need to do for successfully completing their part of the migration.
* Note that repeating the process with the incremented migration ID will result in the deployment of new pods with new Canton components and a new CometBFT node.
  Among other things, these deployments differ in k8s-internal host names, which are formed based on the migration ID.
* Please ensure that your ingress rules are extended accordingly to accommodate the new pods.
  Revisit :ref:`helm-sv-ingress` with the updated migration ID in mind.
* Note that the exposed CometBFT port and CometBFT ``externalAddress`` are changed due to a limitation in CometBFT.
  There is no fundamental need to use the port numbers suggested in the runbook, the only requirement is that either the (external) host IP of the CometBFT pod must be different for each migration ID or its (external) port number.
* Note that these instructions do not yet support an actual upgrade of the Canton software during a synchronizer migration. We will follow-up with instructions on configuring different Canton versions in a future iteration.

.. _sv-upgrades-deploying-apps:

Updating Apps
+++++++++++++

Repeat the steps described in :ref:`helm-sv-install` for installing the ``sv``, ``scan`` and ``validator`` components,
substituting the migration ID (``MIGRATION_ID``) with the target migration ID after the upgrade (typically the existing synchronizer's migration ID + 1).

While doing so, please note the following:

* Please make sure to pick the correct (incremented) ``MIGRATION_ID`` when following the steps.
* Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-values.yaml`` so that ``migration.migrating`` is set to ``true``.
  This will ensure that the SV app will consume the migration dump and initialize new components based on the contents of this dump.
* Use ``helm upgrade`` in place of ``helm install`` for the ``sv``, ``scan`` and ``validator`` charts
  and do not uninstall any helm charts installed as part of the original deployment run (with the smaller migration ID).
* No ingress rules need to be updated as part of this step.
  Once the redeployment is complete the existing ingress rules will apply to the updated pods.

Coordination calls
------------------

For keeping upgrade downtimes short and assisting with the quick resolution of individual issues, we propose to perform upgrades in a synchronized manner, with coordination over a group call (Zoom meeting). All SV operators will hereby be invited to join a call that starts shortly before the beginning of the downtime window (step 3 above) and ends once the upgrade and synchronizer migration has concluded (step 8 above). Our aim is that the core of the upgrade procedure (steps 3-8) can in principle be completed within one hour, realizing that the first coordinated tests will likely take longer.

Each SV must be represented in these calls by at least one person that is capable of performing the steps described in :ref:`sv-upgrades-deploying-domain` and :ref:`sv-upgrades-deploying-apps`.
The operators representing an SV must be:

- Familiar with the technical details around the SV’s deployment.
- Capable (in terms of both skills and permissions) to interact with the SV’s node deployment via Helm and (for debugging with support from the CN team) Kubernetes (kubectl) commands.
- Capable (in terms of both skills and permissions) to register new ingress rules for internal synchronizer components (sequencer, mediator and CometBFT) and SV participants. Resulting addresses will only be relevant to technical personnel and will follow a schema such as component-migrationId.hostname. Note that for fully supporting the migration flow for CometBFT nodes, it will also be necessary to allow communication over additional ports.

Testing
-------

The success of synchronizer upgrades and the duration of downtime both depend on the effectiveness of all SVC members in performing the necessary upgrading steps. We therefore recommend that SVs implement a  testing regime before a specific upgrade step is attempted on MainNet. The testing regime includes:

- Individual tests that can be performed by each SV partner in isolation and at arbitrary times. In order to perform such tests, partners should ensure that they are able to deploy the Super Validator and/or Validator node software to a cluster that is isolated from production clusters (and the wider Internet).
- Coordinated tests performed on DevNet and TestNet. These will tightly mirror the organizational and technical processes for upgrading MainNet. Especially in the beginning, we propose to coordinate these tests via a group call with representatives of all SVs (see “Coordination”).

We will follow up with instructions and recommendations for both types of tests.
