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

#. Lastly, for network-wide failures, a more complex :ref:`Disaster recovery procedure <validator_network_dr>` is required.

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
- If the backup was taken before the synchronizer underwent a :ref:`major upgrade <validator-upgrades>`,
  then restoring the node from the backup will only be possible if synchronizer nodes on the old migration ID are still available.
  If this is true, you must restore the node on the old migration ID first and can then move it through
  the regular :ref:`migration process <validator-upgrades>` so it becomes fully operational on the new migration ID.

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
Among other things, the following types of parties will not be migrated by default:

* Parties that are hosted on multiple participants.
  These may get unhosted from the original (failed) participant, but will remain hosted on any other participants.
* External parties that are hosted on the validator.
  These may get unhosted from the original (failed) participant.
  Please refer to :ref:`validator_recover_external_party` for instructions on how to recover external parties.

In some cases you might want to force the migration attempt for a set of parties that were not migrated automatically.
To do so, you can set the ``parties-to-migrate`` :ref:`configuration option <configuration>` on your validator app.
A migration will be attempted for each party that you pass to this option.
The initialization of the validator app will be interrupted on the first failed migration attempt.

If you still observe issues, in particular you observe
``ACS_COMMITMENT_MISMATCH`` warnings in your participant logs,
something has likely gone wrong while importing the active contracts
of at least one of the parties hosted on your node. To address this, you can usually:

1. First make sure all parties are hosted on the same node. The most
   common case is that either the parties are still on the old node
   with the old participant ID or they have been migrated to the new
   node. You can check by opening a :ref:`Canton console
   <console_access>` to any participant on the network (i.e., you can also ask another validator or SV operator for this information) and running the
   following query where <namespace> is the part after the ``::`` in, for example, your validator party ID.

   .. code::

      val syncId = participant.synchronizers.list_connected().head.synchronizerId
      participant.topology.party_to_participant_mappings.list(syncId, filterNamespace = <namespace>)

   If all parties are on the same node, proceed to the next step. If some are on the old node and some are on the new node, migrate the ones on the old node to the new node by opening a console to the new node and running the following command
   (adjust the parameters as required for your parties):

   .. code::

      val participantId = participant.id // ID of the new participant
      participant.topology.party_to_participant_mappings.propose(<party-id>, Seq((participantId, <participant-permission>)), store = syncId)

2. If your parties are still on the original node that you took identities backup from, you can use your existing backup.
   If your parties have been migrated to the new node already, take a new identities dump from the new node.
   If the new node is in a state where you cannot take a fresh dump, use the old dump but edit the ``id`` field to the participant ID of the new node.
   You can obtain the ``id`` in the correct format by, for example, running ``participant.id.toProtoPrimitive`` in a Canton console to the participant.
   You can now take down the node to which you originally tried to restore and try the restore procedure again with your adjusted dump on a fresh node with a different participant ID prefix
   (i.e., a different ``newParticipantIdentifier`` / ``<new_participant_id>`` depending on your deployment model).

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
`documentation for external signing <https://docs.digitalasset.com/build/3.3/tutorials/app-dev/external_signing_onboarding.html#external-party-onboarding-transactions>`_.

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

.. _validator_network_dr:

Disaster recovery from loss of the CometBFT storage layer of the global synchronizer
++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

In highly unlikely case of a complete disaster, where the complete CometBFT layer of the network is lost beyond repair, the SVs will
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
4. Validator operators copy the dump file to their validator app's migration dump volume and restart the app to restore the data.

We recommend first familiarizing yourself with the :ref:`migration <validator-upgrades>` process, as the disaster recovery process is similar.
In case of disaster, the SVs will inform you of the need to recover, and indicate the timestamp from which the network will be recovering.

The following steps will produce a data dump through the validator app, consisting of your node's private identities as well as the
Active Contract Set (ACS) as of the required timestamp. That data dump will then be stored on the validator app's volume,
and the validator app and participant will be configured to consume it and restore the data from it.

Please make sure before you fetch a data dump from the validator app that your participant was healthy around the timestamp that the SVs have provided.

Technical Details (Kubernetes)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The data dump can be fetched from the validator app by running the following command:

.. code-block:: bash

    curl -sSLf "https://wallet.validator.YOUR_HOSTNAME/api/validator/v0/admin/domain/data-snapshot?timestamp=<timestamp>&force=true" -H "authorization: Bearer <token>" -X GET -H "Content-Type: application/json" > dump_response.json
    cat dump_response.json | jq '.data_snapshot' > dump.json

where `<token>` is an OAuth2 Bearer Token with enough claims to access the Validator app, as obtained from your OAuth provider, and `<timestamp>` is the timestamp provided by the SVs,
in the format `"2024-04-17T19:12:02Z"`.

If the `curl` command fails with a 400 error, that typically means that your participant has been pruned beyond the chosen timestamp,
and your node cannot generate the requested dump.
If it fails with a 429, that means the timestamp is too late for your participant to create a
dump for, i.e. your participant has not caught up to a late enough point before the disaster.
Either way, you will need to go through a process of recreating your validator and recovering your balance.
See :ref:`validator_reonboard` for more information.

This file can now be copied to the Validator app's PVC:

.. code-block:: bash

    kubectl cp dump.json validator/<validator_pod_name>:/domain-upgrade-dump/domain_migration_dump.json

where `<validator_pod_name>` is the full name of the pod running the validator app.

Migrating the Data
""""""""""""""""""

Please follow the instructions in the :ref:`validator-upgrades-deploying` section
to update the configuration of the validator app and participant to consume the migration dump file.

Technical Details (Docker-Compose)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The data dump can be fetched from the validator app by running the following command:

.. code-block:: bash

    curl -sSLf "https://wallet.localhost/api/validator/v0/admin/domain/data-snapshot?timestamp=<timestamp>&force=true" -H "authorization: Bearer <token>" -X GET -H "Content-Type: application/json" > dump_response.json
    cat dump_response.json | jq '.data_snapshot' > dump.json

where `<token>` is an OAuth2 Bearer Token with enough claims to access the Validator app, as obtained from your OAuth provider, and `<timestamp>` is the timestamp provided by the SVs,
in the format `"2024-04-17T19:12:02Z"`.
If you are running your validator without auth, you can use the utility Python script `get-token.py`
to generate a token for the `curl` command by running ``python get-token.py administrator`` (requires `pyjwt <https://pypi.org/project/PyJWT/>`_).

If the `curl` command fails with a 400 error, that typically means that your participant has been pruned beyond the chosen timestamp,
and your node cannot generate the requested dump.
If it fails with a 429, that means the timestamp is too late for your participant to create a
dump for, i.e. your participant has not caught up to a late enough point before the disaster.
Either way, you will need to go through a process of recreating your validator and recovering your balance.
See :ref:`validator_reonboard` for more information.

This file can now be copied to the Validator app's Docker volume using the following command:

.. code-block:: bash

    docker run --rm -v "domain-upgrade-dump:/volume" -v "$(pwd):/backup" alpine sh -c "cp /backup/dump.json /volume/domain_migration_dump.json"

Migrating the Data
""""""""""""""""""

Please follow the instructions in the :ref:`validator-upgrades-deploying` section
to update the configuration of the validator app and participant to consume the migration dump file.
