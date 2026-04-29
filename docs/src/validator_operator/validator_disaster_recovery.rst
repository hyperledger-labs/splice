..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_dr:

=================
Disaster Recovery
=================

There are three ways to recover from disasters:

#. In simple cases where only a single node is affected but the overall
   network is still healthy, a :ref:`Restore from backup <validator_backup_restore>` is
   usually sufficient.

#. If a full backup is unavailable but an identities backup has been
   created, the balance of the validator can be :ref:`recovered <validator_reonboard>` on a new
   validator.

#. If the global synchronizer breaks, the super validators will
   initiate a roll-forward Logical Synchronizer Upgrade to roll
   forward to a new physical synchronizer. Validators will need to
   initiate the :ref:`procedure <validator_roll_forward_lsu>` on their
   node based on the information communicated by the SVs.

.. note :: A recovery of assets is **only** possible if at least **one** of the following holds:

  - A recent :ref:`database backup <validator-database-backup>` is available, or:
  - An up-to-date :ref:`identities backup <validator-identities-backup>` is available, or:
  - The validator participant was using an :ref:`external KMS <validator-kms>` to manage its keys and the KMS still retains those keys.
    (Note that recovering the validator from only KMS keys
    - i.e., without an identities backup or database backup -
    is an involved process that is not explicitly documented here.)

  If neither of the above holds, it is not possible to recover the relevant participant secret keys to prove asset ownership.

.. _validator_backup_restore:

Restoring a validator from backups
++++++++++++++++++++++++++++++++++

The entire node can be restored from backups as long as **all** of the following hold:

- A :ref:`database backup <validator-database-backup>` is available.
- The database backup is less than 30 days old.
  Due to sequencer pruning, a participant that is more than 30 days behind will be unable to catch up on the synchronizer
  to become fully operational again.
- If the backup was taken before the synchronizer underwent a :ref:`logical synchronizer upgrade <sv-logical-synchronizer-upgrades>`,
  then restoring the node from the backup will only be possible if synchronizer nodes on the old physical synchronizer are still available.
  If this is true, you must restore the node on the old physical synchronizer first so it can catch up and become fully operational on the new physical synchronizer.

If one of the above does not hold, it might still be possible to recover the node using the
:ref:`re-onboarding <validator_reonboard>` procedure discussed below.

The following steps can be taken to restore a node from backups:


#. Scale down all components in the validator node to 0 replicas.

#. Restore the storage and DBs of all components from the backups. The exact
   process for this depends on the storage and DBs used by the components, and
   is not documented here.
#. Once all storage has been restored, scale up all components in the validator node
   back to 1 replica.

**NOTE:** Currently, you have to manually re-onboard any users that were
onboarded after the backup was taken.

If you are running a docker-compose deployment, you can restore the Postgres databases as follows:

#. Stop the validator and participant using ``./stop.sh``.

#. Wipe out the existing database volume: ``docker volume rm compose_postgres-splice``.

#. Start only the postgres container: ``docker compose up -d postgres-splice``

#. Check whether postgres is ready with: ``docker exec splice-validator-postgres-splice-1 pg_isready`` (rerun this command until it succeeds)

#. Restore the validator database (assuming `validator_dump_file` contains the filename of the dump from which you wish to restore): ``docker exec -i splice-validator-postgres-splice-1 psql -U cnadmin validator < $validator_dump_file``

#. Restore the participant database (assuming `participant_dump_file` contains the filename of the dump from which you wish to restore, and `migration_id` contains the latest migration ID): ``docker exec -i splice-validator-postgres-splice-1 psql -U cnadmin participant-$migration_id < $participant_dump_file``

#. Stop the postgres instance: ``docker compose down``

#. Start your validator as usual

.. _validator_reonboard:

Recovery from an identities backup: Re-onboard a validator and recover balances of all users it hosts
+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

In the case of a catastrophic failure of the validator node, some data owned by the validator and users
it hosts can be recovered from the SVs. This data includes Canton Coin balance and CNS entries. This is achieved
by deploying a new validator node with control over the original validator's namespace key.
The namespace key must be provided via an identities backup file.
It is used by the new validator for migrating the parties hosted on the original validator to the new validator.
SVs assist this process by providing information about all contracts known to them that the migrated parties are stakeholders of.

The following steps assume that you have a backup of the identities of the
validator, as created in the :ref:`Backup of Node Identities <validator-identities-backup>` section.
In case you do not have such a backup but instead have a backup of the validator participant's database,
you can :ref:`assemble an identities backup manually <validator_manual_dump>`.

To recover from the identities backup, we deploy a new validator with
some special configuration described below. Refer to either the
:ref:`docker-compose deployment instructions <validator_disaster_recovery-docker-compose-deployment>`
or the
:ref:`kubernetes instructions <validator_reonboard_k8s>`
depending on which setup you chose.

Once the new validator is up and running, you should be able to login as the administrator
and see its balance. Other users hosted on the validator will need to re-onboard, but their
coin balance and CNS entries should be recovered and will accessible to users that have re-onboarded.

In case of issues, please consult the :ref:`troubleshooting <validator_disaster_recovery_troubleshooting>` section below.

.. warning:: This process preserves all party IDs and all contracts shared
             with the DSO party.  This means that you *must* keep
             the same validator party hint and you do not need a new
             onboarding secret. If you get any errors about needing a
             new onboarding secret, double check your configuration
             instead of requesting a new secret.

.. _validator_reonboard_k8s:

Kubernetes Deployment
^^^^^^^^^^^^^^^^^^^^^

To re-onboard a validator in a Kubernetes deployment and recover the balances of all users it hosts,
repeat the steps described in :ref:`helm-validator-install` for installing the validator app and participant.
While doing so, please note the following:

* Create a Kubernetes secret with the content of the identities backup file.
  Assuming you set the environment variable ``PARTICIPANT_BOOTSTRAP_DUMP_FILE`` to a backup file path, you can create the secret with the following command:

.. code-block:: bash

    kubectl create secret generic participant-bootstrap-dump \
        --from-file=content=${PARTICIPANT_BOOTSTRAP_DUMP_FILE} \
        -n validator

* Uncomment the following lines in the ``standalone-validator-values.yaml`` file.
  This will specify a new participant ID for the validator. Replace ``put-some-new-string-never-used-before`` with a string that was never used before.
  Make sure to also adjust ``nodeIdentifier`` to match the same value.

.. literalinclude:: ../../../apps/app/src/pack/examples/sv-helm/standalone-validator-values.yaml
    :language: yaml
    :start-after: PARTICIPANT_BOOTSTRAP_MIGRATE_TO_NEW_PARTICIPANT_START
    :end-before: PARTICIPANT_BOOTSTRAP_MIGRATE_TO_NEW_PARTICIPANT_END

.. _validator_disaster_recovery-docker-compose-deployment:

Docker-Compose Deployment
^^^^^^^^^^^^^^^^^^^^^^^^^

To re-onboard a validator in a Docker-compose deployment and recover the balances of all users it hosts, type:

.. code-block:: bash

    ./start.sh -s "<SPONSOR_SV_URL>" -o "" -p <party_hint> -m "<MIGRATION_ID>" -i "<node_identities_dump_file>" -P "<new_participant_id>" -w

where ``<node_identities_dump_file>`` is the path to the file containing the node identities backup, and
``<new_participant_id>`` is a new identifier to be used for the new participant. It must be one never used before.
Note that in subsequent restarts of the validator, you should keep providing ``-P`` with the same ``<new_participant_id>``.

.. _validator_manual_dump:

Obtaining an Identities Backup from a Participant Database Backup
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In case you do not have a usable identities backup but instead have a backup of the validator participant's database,
you can assemble an identities backup manually.
Here is one possible way to do so:

#. Restore the database backup into a temporary postgres instance and deploy a temporary participant against that instance.

   * See the section on :ref:`restoring a validator from backups <validator_backup_restore>` for pointers that match your deployment model.
   * You only need to restore and scale up the participant, i.e., you can ignore the validator app and its database.
   * In case the restored participant shuts down immediately due to failures, add the following :ref:`additional configuration <configuration_ad_hoc>`:

    .. code-block:: yaml

        additionalEnvVars:
            - name: ADDITIONAL_CONFIG_EXIT_ON_FATAL_FAILURES
              value: canton.parameters.exit-on-fatal-failures = false

#. Open a :ref:`Canton console <console_access>` to the temporary participant.
#. Run below commands in the opened console. This will store the backup into a *local* file
   (relative to the local directory from which you opened the console) called ``identities-dump.json``.

    .. literalinclude:: ../../../apps/app/src/pack/examples/recovery/manual-identities-dump.sc

   Note that above commands need to be adapted if your participant is configured to store keys in an :ref:`external KMS <validator-kms>`.

.. _validator_disaster_recovery_troubleshooting:

Limitations and Troubleshooting
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In some non-standard cases, the automated re-onboarding from key backup might not succeed in migrating (i.e., recovering) a party.
Please check the logs of the validator for warnings or error entries that may give clues.

Parties not migrated automatically
""""""""""""""""""""""""""""""""""

The following types of parties will not be migrated by default:

* Parties that are hosted on multiple participants.
  These may get unhosted from the original (failed) participant, but will remain hosted on any other participants.
* External parties that are hosted on the validator.
  These may get unhosted from the original (failed) participant.
  Please refer to :ref:`validator_recover_external_party` for instructions on how to recover external parties.

In some cases you might want to force the migration attempt for a set of parties that were not migrated automatically.
To do so, you can set the ``parties-to-migrate`` :ref:`configuration option <configuration>` on your validator app.
A migration will be attempted for each party that you pass to this option.
The initialization of the validator app will be interrupted on the first failed migration attempt.

Troubleshooting failed ACS imports
"""""""""""""""""""""""""""""""""""

If you still observe issues, in particular you observe
``ACS_COMMITMENT_MISMATCH`` warnings in your participant logs,
something has likely gone wrong while importing the active contracts
of at least one of the parties hosted on your node.
Another common symptom (in case the validator party is affected) is that your your validator initialization fails with a ``Unknown secret`` error and your validator logs contain a ``ValidatorLicense not found`` message.
To address a failed :term:`ACS` import, you can usually:

1. First make sure all parties are hosted on the same node. The most
   common case is that either the parties are still on the old node
   with the old participant ID or they have been migrated to the new
   node. You can check by opening a :ref:`Canton console
   <console_access>` to any participant on the network (i.e., you can also ask another validator or SV operator for this information) and running the
   following query where <namespace> is the part after the ``::`` in, for example, your validator party ID.

   .. code::

      val syncId = participant.synchronizers.list_connected().head.synchronizerId
      participant.topology.party_to_participant_mappings.list(syncId, filterParticipant = <namespace>)

   If all parties are on the same node, proceed to the next step. If some are on the old node and some are on the new node, migrate the ones on the old node to the new node by opening a console to the new node and running the following command
   (adjust the parameters as required for your parties):

   .. code::

      val participantId = participant.id // ID of the new participant
      participant.topology.party_to_participant_mappings.propose(<party-id>, Seq((participantId, <participant-permission>)), store = syncId)

2. If all parties are on the new node already, you can attempt to (re-)import the ACS for those parties manually.
   The following steps concern your new validator node:

   a. Stop your validator app.
   b. Open a :ref:`participant console <console_access>` to that new validator and keep it open for the next steps.
   c. From the Canton console, run:

      .. code::

         participant.synchronizers.disconnect_all()

   d. For each ``PARTY_ID`` you want to migrate / re-import the ACS for:

      Run from a regular shell (same working directory like the one you started your Canton console from):

      .. parsed-literal::

         curl -sSL --fail-with-body '|gsf_scan_url|/api/scan/v0/acs/YOUR_PARTY_ID' -H 'Content-Type: application/json' | jq -r .acs_snapshot | base64 -d > acs_snapshot

      From the Canton console:

      .. code::

          participant.repair.import_acs("acs_snapshot")

   e. From the Canton console, run ``participant.synchronizers.reconnect_all()``.
   f. Start your validator app again.

3. If the previous step failed or you chose not to attempt it, you can retry the migration procedure with a fresh participant.
   If your parties are still on the original node that you took identities backup from, you can use your existing backup.
   If your parties have been migrated to the new node already, take a new identities dump from the new node.
   If the new node is in a state where you cannot take a fresh dump, use the old dump but edit the ``id`` field to the participant ID of the new node.
   You can obtain the ``id`` in the correct format by, for example, running ``participant.id.toProtoPrimitive`` in a Canton console to the participant.
   You can now take down the node to which you originally tried to restore and try the restore procedure again with your adjusted dump on a fresh node with a different participant ID prefix
   (i.e., a different ``newParticipantIdentifier`` / ``<new_participant_id>`` depending on your deployment model).

Troubleshooting rejected topology snapshots
""""""""""""""""""""""""""""""""""""""""""""

In rare cases, the re-onboarding process may fail at the ``ImportTopologySnapshot`` step
because an ``OwnerToKeyMapping`` for the old participant ID has an insufficient number of
signatures in the topology snapshot. This only affects validators that were originally
onboarded on Splice 0.4.1 or earlier, which used a Canton version that did not require
the mapped keys to co-sign ``OwnerToKeyMapping`` transactions.
You can identify this issue by looking for the following messages in your participant logs:

.. code::

   Missing authorizers: ReferencedAuthorizations(extraKeys = <key-id>...)
   Rejected transaction ... OwnerToKeyMapping(...) ... due to Not authorized

To work around this, follow these steps:

1. Start only the new participant (without the validator app). Do not wipe its state from
   the previous (failed) re-onboarding attempt.

2. Open a :ref:`Canton console <console_access>` to the new participant and run the following
   commands to propose the corrected ``OwnerToKeyMapping``. Replace the key ID prefixes with
   those from the rejected ``OwnerToKeyMapping`` in your participant logs, and replace the
   old participant ID with your actual old participant ID:

   .. code::

      val keys = Seq("<signing-key-id-prefix>", "<encryption-key-id-prefix>").map(prefix =>
        participant.keys.public.list().filter(_.publicKey.id.toProtoPrimitive.startsWith(prefix)).head.publicKey)

      val oldParticipantId = ParticipantId.fromProtoPrimitive("<old-participant-id>", "participant").toOption.get
      val otk = OwnerToKeyMapping(member = oldParticipantId, keys = NonEmpty.from(keys).get)
      participant.topology.owner_to_key_mappings.propose(otk, force = ForceFlag.AlienMember)

3. Start the validator app using your original identities dump configuration.

.. _validator_recover_external_party:

Recover the Coin balance of an external party
+++++++++++++++++++++++++++++++++++++++++++++

For a party relying on external signing, a similar procedure can be
used to recover its coin balance in case the validator originally
hosting it becomes unusable for whatever reason.

.. warning:: The target validator that you use to host the party after
             recovery **must** be a **completely new validator**. An existing validator
             may brick completely due to some limitations around party
             migrations and there is no way to recover from that at
             this point. Recovering a validator from an identities backup does not classify
             as a completely new validator here. You must setup it with a completely new identity
             and a completely clean database.
             This limitation is expected to be lifted in
             the future.

First, setup a new validator following the standard :ref:`standard validator deployment docs <validator_operator>`.

Next, connect a :ref:`Canton console <console_access>` to that new validator.

We now need to sign and submit the topology transaction to host the
external party on the new node and import the ACS for that party.

To do so, first generate the topology transaction. Note that the instructions here
assume that the party is only hosted on a single participant node. If you want to host
it on multiple nodes, you will need to adjust this.

.. code::

   // replace YOUR_PARTY_ID by the ID of your external party
   val partyId = PartyId.tryFromProtoPrimitive("YOUR_PARTY_ID")
   val participantId = participant.id
   val synchronizerId = participant.synchronizers.id_of("global")

   // generate topology transaction
   val partyToParticipant = PartyToParticipant.tryCreate(
       partyId = partyId,
       threshold = PositiveInt.one,
       participants = Seq(
         HostingParticipant(
           participantId,
           ParticipantPermission.Confirmation,
         )
       ),
     )

   import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Write.GenerateTransactions
   val topologyTransaction = participant.topology.transactions.generate(
     Seq(
       GenerateTransactions.Proposal(
         partyToParticipant,
         TopologyStoreId.Synchronizer(synchronizerId),
       )
     )
   ).head

   // Print out the hash that needs to be signed. Note that you need to sign
   // the actual bytes the hex string represents not the hex string
   topologyTransaction.hash.hash.toHexString

We'll need the topology transaction and the definitions defined here later again. Either keep your Canton console open or save them.

The topology transaction hash needs to be signed externally following the
`documentation for external signing <https://docs.digitalasset.com/build/3.4/tutorials/app-dev/external_signing_onboarding.html#external-party-onboarding-transactions>`_.

After you signed it externally, you need to construct the signed
topology transaction, sign it additionally through the participant and
then submit it through the synchronizer.

.. code::

   // Replace HASH_SIGNATURE_HEXSTRING with the signed topology transaction hash
   val signature = Signature.fromExternalSigning(SignatureFormat.Raw, HexString.parseToByteString("HASH_SIGNATURE_HEXSTRING").get, partyId.namespace.fingerprint, SigningAlgorithmSpec.Ed25519)
   val topologyTxSignedByParty = SignedTopologyTransaction.create(
     topologyTransaction,
     NonEmpty(Set, SingleTransactionSignature(topologyTransaction.hash, signature): TopologyTransactionSignature),
     isProposal = false,
     ProtocolVersion.v34,
   )
   val topologyTxSignedByBoth = participant.topology.transactions.sign(
     topologyTxSignedByParty,
     TopologyStoreId.Synchronizer(synchronizerId),
     signedBy = Seq(participantId.namespace.fingerprint)
   )
   participant.topology.transactions.load(
     topologyTxSignedByBoth,
     TopologyStoreId.Synchronizer(synchronizerId),
   )

We can now check that the topology transaction got correctly applied and get the ``validFrom`` time:

.. code::

    // The detailed output will slightly vary. Make sure that you see the new participant ID though.
    participant.topology.party_to_participant_mappings.list(synchronizerId, filterParty = partyId.filterString)
      res36: Seq[topology.ListPartyToParticipantResult] = Vector(
        ListPartyToParticipantResult(
          context = BaseResult(
            storeId = Synchronizer(id = global-domain::122025296c61...),
            validFrom = 2025-05-14T10:19:33.534074Z,
            validUntil = None,
            sequenced = 2025-05-14T10:19:33.534074Z,
            operation = Replace,
            transactionHash = <ByteString@2d53bfcc size=34 contents="\022 \320\215d\276\352m)\316 \231\345 \360\252WQB\331\3668\216\362\022\342S\310k\vF\267\347\374">,
            serial = PositiveNumeric(value = 1),
            signedBy = Vector(1220b529c1d9...)
          ),
          item = PartyToParticipant(YOUR_PARTY_ID, PositiveNumeric(1), Vector(HostingParticipant(YOUR_PARTICIPANT_ID..., Submission)))
        )
      )

In this example, the validFrom time is ``2025-05-14T10:19:33.534074Z``.

We can now query CC Scan to get the active contract set (ACS) for a party and write it to the file ``acs_snapshot``:

.. parsed-literal::

    // Make sure to adjust YOUR_VALID_FROM to the time you got from the previous query and YOUR_PARY_ID
    curl -sSL --fail-with-body '|gsf_scan_url|/api/scan/v0/acs/YOUR_PARTY_ID?record_time=YOUR_VALID_FROM' -H 'Content-Type: application/json' | jq -r .acs_snapshot | base64 -d > acs_snapshot


Lastly, we can import the ACS:

.. code::

     participant.synchronizers.disconnect_all()
     participant.repair.import_acs("acs_snapshot")
     participant.synchronizers.reconnect_all()

The party is now hosted on the node and can participat in
transactions. The last step is to setup the necessary contracts to
allow the validator automation to renew transfer preapprovals and
complete transfer commands. To do so, go through the same flow used
for initial onboarding of the party, i.e.,
``/v0/admin/external-party/setup-proposal``,
``/v0/admin/external-party/setup-proposal/prepare-accept`` and
``/v0/admin/external-party/setup-proposal/submit-accept``. For details
refer to the :ref:`docs for the validator external signing API <validator-api-external-signing>`.

.. _validator_roll_forward_lsu:

Roll Forward Logical Synchronizer Upgrade
+++++++++++++++++++++++++++++++++++++++++

In case the SVs communicate that they recover from a loss of the
physical synchronizer, they will communicate the
``newPhysicalSynchronizerId`` and the ``sequencerSuccessors``.

Validators then need to:

1. Wait for their node to finish catching up to the latest transaction
   on the existing synchronizer. A good indicator for that is that you
   don't see any new logs containing ``Processing event at`` in your
   participant INFO logs.

2. Initiate the roll forward LSU through a :ref:`Canton console <console_access>`:

.. code::

    val existingPhysicalSynchronizerId = participant.synchronizers.list_connected().find(_.synchronizerAlias == "global").head.physicalSynchronizerId
    participant.synchronizers.perform_manual_lsu(
      existingPhysicalSynchronizerId,
      newPhysicalSynchronizerId,
      upgradeTime = None,
      sequencerSuccessors,
    )

.. _validator_acs_mismatches:

Resolving ACS mismatches
^^^^^^^^^^^^^^^^^^^^^^^^

Note that depending on how exactly the old synchronizer failed,
validators may desynchronize if some validators have observed a
transaction before the failure while others have not.  In that case,
the participant will produce ACS mismatches that should be resolved
using the `standard ACS mismatch resolution process
<https://docs.digitalasset.com/operate/3.4/howtos/troubleshoot/commitments.html>`_
after migrating to the new physical synchronizer.
