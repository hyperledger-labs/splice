.. _sv-upgrades:

Synchronizer Upgrades with Downtime
===================================

For supporting non-backwards-compatible major version upgrades of the Canton software the Canton Network will implement a procedure for synchronizer (i.e., domain) upgrades with downtime.

High-level overview of the procedure
------------------------------------

1. Canton releases containing breaking changes become available and Canton Network Node releases compatible with these Canton releases become available.
2. SVC members agree and eventually confirm via an on-ledger vote (cast via the SV UI) on which specific date and time the network downtime necessary for the upgrade will start. It is the responsibility of the SVC to make sure that validator operators are informed about planned upgrades and upgrade timelines.
3. At the start of the downtime window, the SV app of SVC members automatically pauses all traffic on the operating version of the global synchronizer.
4. Shortly after pausing traffic on the global synchronizer (there is a short delay to ensure that all components have synced up to the final state of the existing synchronizer), the node software of SVs and validators automatically exports so-called migration dumps to previously configured (via Helm chart values) Kubernetes volumes.
5. All SVs and validators previously using the now-paused global synchronizer create full backups of their nodes. (Both for disaster recovery and for supporting audit requirements.)
6. All SVs upgrade by:

  a) Deploying new Kubernetes pods containing the new versions of the Canton components of their Super Validator node (participant, sequencer, and mediator) alongside the old versioned ones which are not yet deleted, as well as a new CometBFT node.
  b) Upgrading their CN apps pods.

7. Upon (re-)initialization, the SV node backend automatically consumes the migration dump and initializes all components based on the contents of this dump. The process is analogous for validators, where the validator app is tasked with importing the dump and initializing components. Operationally, this process is quite similar to the weekly upgrade process that we have at the moment, with the big difference being that databases are preserved.
8. Once a BFT majority (i.e., >⅔) of SVs have initialized their upgraded nodes, the new version of the synchronizer (with an incremented migration ID and, during a real upgrade, a new version of the core Canton components) becomes operational automatically. The end of the downtime window is therefore determined by the speed at which a sufficient number of SVs complete the necessary deployment and initialization steps.
9. The remaining (Canton) nodes of the old synchronizer can be retired now. We recommend to only do so after the migration was confirmed to be successful, because in the event of a failure while setting up the new synchronizer these nodes can be used for reviving the original synchronizer.

We are actively working on narrowing down the specific sequence of deployment steps required from SV operators during the downtime part of this procedure (mainly step 6 above), and are tuning the overall DevOps experience. In a nutshell, we expect that the required steps will boil down to:

- Editing Helm values configuration files.
- Installing and uninstalling Helm charts on your SV cluster.
- Extending ingress rules (for supporting parallel operation of old and new synchronizer components)

Coordination calls
------------------

For keeping upgrade downtimes short and assisting with the quick resolution of individual issues, we propose to perform upgrades in a synchronized manner, with coordination over a group call (Zoom meeting). All SV operators will hereby be invited to join a call that starts shortly before the beginning of the downtime window (step 3 above) and ends once the upgrade and synchronizer migration has concluded (step 8 above). Our aim is that the core of the upgrade procedure (steps 3-8) can in principle be completed within one hour, realizing that the first coordinated tests will likely take longer.

Due to the nature of the steps that SVs will be required to perform, each SV must be represented in these calls by at least one person that is:

- Familiar with the technical details around the SV’s deployment.
- Capable (in terms of both skills and permissions) to interact with the SV’s node deployment via Helm and (for debugging with support from the CN team) Kubernetes (kubectl) commands.
- Capable (in terms of both skills and permissions) to register new ingress rules for internal synchronizer components (sequencer, mediator and CometBFT) and SV participants. Resulting addresses will only be relevant to technical personnel and will follow a schema such as component-migrationId.hostname. Note that for fully supporting the migration flow for CometBFT nodes, it will also be necessary to allow communication over additional ports.

Testing
-------

The success of synchronizer upgrades and the duration of downtime both depend on the effectiveness of all SVC members in performing the necessary upgrading steps. We therefore recommend that SVs implement a  testing regime before a specific upgrade step is attempted on MainNet. The testing regime includes:

- Individual tests that can be performed by each SV partner in isolation and at arbitrary times. In order to perform such tests, partners should ensure that they are able to deploy the Super Validator and/or Validator node software to a cluster that is isolated from production clusters (and the wider Internet).
- Coordinated tests performed on DevNet and TestNet. These will tightly mirror the organizational and technical processes for upgrading MainNet. Especially in the beginning, we propose to coordinate these tests via a group call with representatives of all SVs (see “Coordination”).

We will follow up with instructions and recommendations for both types of tests.
