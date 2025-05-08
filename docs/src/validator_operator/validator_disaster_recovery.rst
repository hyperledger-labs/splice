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

#. If a full backup is unavailable but an identity backup has been
   created, the balance of the validator can be :ref:`recovered <validator_reonboard>` on a new
   validator.

#. Lastly, for network-wide failures, a more complex :ref:`Disaster recovery procedure <validator_network_dr>` is required.


.. _validator_backup_restore:

Restoring a validator from backups
++++++++++++++++++++++++++++++++++

Assuming backups have been taken, the entire node can be restored from backups.
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


Re-onboard a validator and recover balances of all users it hosts
+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

In the case of a catastrophic failure of the validator node, some data owned by the validator and users
it hosts can be recovered from the SVs. This data includes Canton Coin balance and CNS entries. This is achieved
by deploying a new validator node with control over the original validator's participant keys.

In order to be able to recover the data, you must have a backup of the identities of the
validator, as created in the :ref:`Backup of Node Identities <validator-backups>` section.

To recover from the identities backup, we deploy a new validator with
some special configuration described below. Refer to either the
docker-compose deployment instructions or the kubernetes instructions
depending on which setup you chose.

Once the new validator is up and running, you should be able to login as the administrator
and see its balance. Other users hosted on the validator would need to re-onboard, but their
coin balance and CNS entries should be recovered.

.. todo:: explain that the recovery only automatically works for users whose parties were allocated in the participant namespace

Kubernetes Deployment
^^^^^^^^^^^^^^^^^^^^^

To re-onboard a validator in a Docker-compose deployment and recover the balances of all users it hosts,
repeat the steps described in :ref:`helm-validator-install` for installing the validator app and participant.
While doing so, please note the following:

* Create a Kubernetes secret with the content of the identities dump file.
  Assuming you set the environment variable ``PARTICIPANT_BOOTSTRAP_DUMP_FILE`` to a dump file path, you can create the secret with the following command:

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

Docker-Compose Deployment
^^^^^^^^^^^^^^^^^^^^^^^^^

To re-onboard a validator in a Docker-compose deployment and recover the balances of all users it hosts, type:

.. code-block:: bash

    ./start.sh -s "<SPONSOR_SV_URL>" -o "" -p <party_hint> -m "<MIGRATION_ID>" -i "<node_identities_dump_file>" -P "<new_participant_id>" -w

where ``<node_identities_dump_file>`` is the path to the file containing the node identities dump, and
``<new_participant_id>`` is a new identifier to be used for the new participant. It must be one never used before.
Note that in subsequent restarts of the validator, you should keep providing ``-P`` with the same ``<new_participant_id>``.



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
