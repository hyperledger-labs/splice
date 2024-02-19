.. _release_notes:

Release Notes
=============

2024-02-26
----------

* Deployment

  * Removed option to configure Kubernetes node affinity for PVCs due to a faulty implementation.
    For controlling the provisioning of PVCs, you can define custom storage classes and configure them via the respective `db.volumeStorageClass` Helm chart field.
  * Fix the `affinity` and `nodeSelector` field on the `cn-postgres` Helm chart so they are applied as expected.

2024-02-19
----------

* The scan app is now initialized with last computed aggregates from other scans in the SVC.

* Deployment

  * You can now configure Kubernetes affinity and node selection rules for pods deployed as part of CN helm charts.
    This is done by setting the `affinity` and `nodeSelector` fields in the Helm values files, respectively.
    For helm charts that deploy persistent volumes, you can additionally configure Kubernetes node affinity for those volumes.
    This is done by setting the `db.volumeNodeAffinity` field in the respective Helm values files.
    For all of these fields, the standard Kubernetes configuration syntax applies.
    See also the examples given (as commented-out lines) in `cometbft-values.yaml`.

2024-02-12
----------

* The JSON encoding of jsonb columns in the database has been changed.
  Please make sure to clean all the database before upgrading.
  Data with the old JSON encoding cannot be read by the new version of the software.


2024-02-05
----------

* The wallet of non-SV validators now execute all reads through a Scan proxy in the validator,
  thus executing them in a BFT fashion.
* You might see some ``ACS_COMMITMENT_MISMATCH`` warning logs in the participant. These can be ignored.
* Add `enableHealthProbes` to the global domain helm chart, providing the ability to disable gRPC readiness and liveness probes for the sequencer and mediator.
* Containers now use tini as the entrypoint to ensure proper signal handling.
* Fix for wallet balances incorrectly reporting as zero for rounds that have not been aggregated yet. An error will be returned instead.

* Deployment

  * The postgres instance has been split into four different instances: sequencer-pg, mediator-pg, participant-pg, apps-pg.
    Please see the new section on installing and configuring Postgres: :ref:`Installing Postgres instances <helm-sv-postgres>`
  * postgres PVC size are new set to 480GB for the sequencer, and 48GB for each of the other three instances.
  * The default database names for the different components have been changed to `cantonnet_<componentName>`.
    They are all created automatically in init containers attached to the respective app pods.


2024-01-29
----------

* The ``/v0/wallet-balance`` endpoint to query a party's CC balance is exposed through the Scan app.

* Non-SV validators now connect to all registered Scans in the domain and read from them in a BFT fashion (safe as long as less than 1/3 of them are faulty).
  Scans are registered as part of the SV onboarding, using the public URL that was configured in the 2024-01-15 deployment (``scan.publicUrl`` in SV-app's helm chart).
  SV validators still trust the Scan app of the SV.

* Clean up our OpenAPI spec to use oneOf.
  This involves minor changes to the some of our API endpoints including:

  * getHealthStatus for all nodes
  * getBuyTrafficRequestStatus and getTransferOfferStatus in the wallet API
  * getCometBftNodeStatus and getCometBftNodeDebugDump in the SV app

  In almost all cases, the changes should only involve some fields that were
  optional now becoming required.

2024-01-22
----------

* Adjust traffic purchase rewards structure to CIP-2:

  * Validator rewards are issued over the full amount of CC spent.
  * No app rewards are issued for traffic purchases.
  * Set the minimum traffic purchase amount to 1 USD to ensure coverage of execution cost.
  * Issue an extra app reward over $1 for CC transfers facilitated by featured apps.

* Deployment:

  * Enabled pruning in sequencers of canton foundation

2024-01-15
----------

* Deployment:

  * The SV-app helm chart now expects `scan.publicUrl` to be set to the URL of the Scan app.

  * The syntax of the Helm charts configuring persistence has been standardized through the different images.
    The provided snippet highlights the optional fields and its syntax.
    To find the expected values for each image, refer to the _required.yaml_ Helm files.::

        sequencer:
          driver:
            type:
            port:
            host:
          persistence:
            databaseName:
            host:
            port:
            user:
            secretName:
        mediator:
          persistence:
            databaseName:
            host:
            port:
            user:
            secretName:

* Documentation:

    * Removed the section `Renaming an SV` in the page "Kubernetes-Based Deployment of a Super Validator node". Not longer possible through the JSON API.


2024-01-08
----------

* Deployment:

  * The global domain helm chart now supports separate Postgres instances for the sequencer and mediator. They can be configured in the `sequencerPostgres` and `mediatorPostgres` values.
    The single `postgres` value has been deprecated and is no longer supported.
    (It is still possible to use a shared postgres instance, by configuring it under both `sequencerPostgres` and `mediatorPostgres`.)
  * The CometBFT egress ports have changed from `26656, 26666, 26676, 26686, 26696` to `26016, 26026, 26036, 26046, 26096`
  * The CometBFT founder port was updated in the `cometbft-values.yaml` file to `26016` (from `26656`). This is found under the `founder.externalAddress` field.

* Bugfixes:

  * CometBFT state sync has been fixed after the recent issues and can once again be used for fast CometBFT node bootstrapping.
    (It should not be necessary anymore to override `stateSync.enable` to `false` in `cometbft-values.yaml`.)


2023-12-18
----------

* Minor SV UI tweaks. Among other things, it is now mandatory to supply a textual proposal summary when submitting a vote request.

* Renamed ``directory`` to ``CNS`` across the system

  * Renamed ingress rule from ``https://directory.sv.svc.<YOUR_HOSTNAME>*`` to ``https://cns.sv.svc.<YOUR_HOSTNAME>*``.  Please note that this is now a requirement for this UI to continue working properly.

2023-12-11
----------

* Deployment:

  * The `https://directory.sv.svc.<YOUR_HOSTNAME>/api/json-api/*` ingress rule is no longer required for validators and super-validators

  * The helm charts now allow configuring the secret in which the postgres password is stored. Default is ``postgres-secrets``.

* Documentation:

  * Added an ingress rule for `https://directory.sv.svc.<YOUR_HOSTNAME>/api/validator`, which was accidentally omitted from the instructions.


2023-12-04
----------

* The party representing the supervalidator collective is renamed from ``svc::...`` to ``SVC::...``, for consistency with SV party names.

* The password for the PostgreSQL database is now set in Kubernetes secrets as opposed to Helm values files.
  Please refer to the updated documentation on ``Configuring PostgreSQL authentication`` for SV operators and :ref:`Validator operators <validator-postgres-auth>`.

* Documentation:

  * Clarify that using custom auth audiences is recommended.

  * Updated SV runbook to explain also ip white-listing.

  * Updated SV runbook with a list of outbound traffic.

* The `/v0/activities` and `/v0/transactions` Scan APIs now include normalized balance changes per party.
  Please see the Scan OpenAPI specification.

2023-11-27
----------

* The `domain.sequencerPublicUrl` configuration in `cn-sv-node` helm chart is now mandatory. All SVs must specify the URL at which their sequencer can be reached.

* The Canton Name Service (CNS) application is now decentralized. Rather than the founder SV operating a backend service for CNS, the application is now a decentralized one, operated by the SVC with BFT guarantees. No deployment or configuration changes are required for this change.

* CNS entry name limit is changed from 40 to 60 (including suffix).

* The SV, Scan, directory and validator apps now require a PostgreSQL database to run.
  This can be configured in ``sv-values.yaml``, ``validator-values.yaml``, ``scan-values.yaml``.

2023-11-20
----------

* The CometBft RPC endpoints required for state sync are now exposed through the SV App. Accordingly, the list of `rpcServers` in `cometbft-values.yaml` has been updated to reflect the new URL path.

* Documentation fixes

2023-11-13
----------

* CometBft pruning is now enabled, with approximately 7 days of history retained by default.

* Revised the SVC governance formula, so that we now need `ceil( (n + f + 1) / 2.0 )` SVs to form a quorum, where `f := floor ( (n - 1) / 3.0 )` is the maximum supported number of faulty SVs for safe operation.

* Removing the adjustment of SVC governance thresholds for DevNet, aligning it with the one used in TestNet.

* Deployment updates:

  * The URL of the global domain sequencer hosted by the Canton Foundation has changed to `https://sequencer.sv-1.svc.<TARGET_CLUSTER>.network.canton.global`. This change is reflected in the values specified in `participant-values.yaml`, `validator-values.yaml` and `sv-values.yaml`.
  * The requirement for URL rewriting in the rules for Scan and SV apps has been removed:
    ``https://scan.sv.svc.<YOUR_HOSTNAME>/api/scan`` and ``https://sv.sv.svc.<YOUR_HOSTNAME>/api/sv`` no longer requires rewriting
    (and also has been modified from `/api/v0/scan` to `/api/scan` and from `/api/v0/sv` to `/api/sv`).
    For example, ``https://scan.sv.svc.<YOUR_HOSTNAME>/api/scan/foobar`` should be forwarded to
    ``http://validator-app:5012/api/scan/foobar``.
    Note that URL rewriting is now required only in the ingress rule of the JSON API used by the directory frontend. This rule will be completely removed in the future.
  * Note that the readiness and liveness endpoints of Validator, Scan and SV apps have all been moved to
    `/api/<app>/readyz` and `/api/<app>/livez`, with `<app>` being `validator`, `scan` or `sv`, respectively.
    The corresponding Helm charts have been updated to reflect this change.

  * The URL configuration for the foundation's Scan app in `validator-values.yaml` has been updated to be
    ``https://scan.sv-1.svc.TARGET_CLUSTER.network.canton.global``. Similarly, in the config files in the self-hosted validator section.
  * The `isDevNet` flag has been removed from the `cn-cometbft` helm chart in order to eliminate its potential for accidental misconfiguration.
    Instead, the chart now relies on the value of `genesis.chainId` in `cometbft-values.yaml` to determine whether it is a TestNet or DevNet deployment.
  * The CNS UI now uses the validator API to manage user entries, instead of the JSON Ledger API. In order to authenticate to the validator-app correctly, the chart now uses the `auth.audience` value to specify JWT audience, instead of `auth.ledgerApiAudience`

* Documentation:

  * Add documentation for configuring the SV node to publish the URL of its sequencer, so that other validators can subscribe to it.
  * Add documentation for a new required ingress rule to expose ``global-domain-sequencer`` in :ref:`Configuring the Cluster Ingress <helm-sv-ingress>`.


2023-11-06
----------

* Deployment updates:

  * CometBFT state sync i.e. snapshot based syncing of CometBFT nodes is now enabled by default. This allows CometBFT nodes to catchup much quicker during initialization.
    `cometbft-values.yaml` is configured by default to fetch the snapshots from ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global:443/cometbft-rpc/`` which is the URL
    for the CometBFT RPC API of the Canton-Foundation SV. In order to disable state sync, set `stateSync.enable` to `false` in `cometbft-values.yaml`.
    Further details can be found in :ref:`Configuring CometBft state sync <helm-cometbft-state-sync>`.
  * Added ``useSequencerConnectionsFromScan``.
    For validator Helm charts, ``useSequencerConnectionsFromScan`` should be set to ``false`` for SV nodes.
    This value is in the ``sv-validator-values.yaml`` file.

* Documentation:

  * Add brief overview about important :ref:`Identities used by SV nodes on different layers <sv-identities-overview>`.

* The Scan activity and transaction history API's now return the round for which transactions were registered. Please see the Scan OpenAPI specification.
  The ``/coin-config-for-round`` API can be used to lookup the holding fees for a specific round.

2023-10-30
----------

* Add a hidden page to the SV UI (``/leader``) that allows SVs to manually trigger a reelection of the SvcRules leader, as an additional safety mechanism and measure of last resort.

* Deployment updates:

  * Add documentation for :ref:`Kubernetes-Based Deployment of a Validator node <k8s_validator>`
  * The requirement for url rewriting in one of the rules has been removed:
    ``https://wallet.sv.svc.<YOUR_HOSTNAME>/api/validator`` no longer requires rewriting
    (and also has been modified from `/api/v0/validator` to `/api/validator`).
    For example, ``https://wallet.sv.svc.<YOUR_HOSTNAME>/api/validator/foobar`` should be forwarded to
    ``http://validator-app:5003/api/validator/foobar``. In the future, the other rewrite requirements
    will also be removed.
  * Renamed ``SV_WALLET_USER_ID`` placeholder in ``validator-values.yaml`` to ``OPERATOR_WALLET_USER_ID`` to better reflect that this value is the operator's user in the deployment for both SVs and standalone validator nodes

* Bugfixes:

  * Fixed an issue with fees in Scan recent activity and transaction history API where some sender and receiver fees were not reported.
    If a party transfers to themself, it will now be included in the Transfer receivers property in API responses (this was previously filtered out).

2023-10-23
----------

* Improved BFT guarantees on SVC ledger actions and mediator verdicts. Both of these are now safe as long as less than 1/3 of SVs are faulty.

* Non-SV validators now connect to all reachable sequencers in the domain and read from them in a BFT fashion (safe as long as less than 1/3 of them are faulty). Note that for now, only the sequencers operated by the Canton Foundation are reachable and considered for that quorum. We will soon provide instructions for making the sequencers of all SV nodes reachable as well.

* Deployment updates

  * Replaced ``disableAllocateLedgerApiUserParty`` with ``svValidator``.
    For validator Helm charts, ``svValidator`` should be set to ``true`` for SV nodes.
    This value is in the ``sv-validator-values.yaml`` file.
  * Default volume sizes for `cn-cometbft` and `cn-postgres` increased to `240Gi` each (from, respectively, `80Gi` and `160Gi`), as a conservative precaution.
  * The global domain now uses CometBFT instead of a Postgres-backed domain on TestNet.
  * Each global domain node now deploys both a sequencer and mediator on both DevNet and TestNet.
    The `domain.enable` flag in ``sv-values.yaml`` no longer needs to be explicitly set for DevNet (it is `true` by default).
  * Added `scan-values.yaml`, please use that when deploying the `cn-scan` Helm chart. The `clusterUrl` value is used for looking up directory entries in the scan UI.

* Domain fees (and traffic top-ups) are now enabled on DevNet as well. This implies that explicitly setting `topup.enabled` to `false` in `validator-values.yaml` for DevNet is no longer required
  (it is always set to `true`).

2023-10-16
----------

* Frontend updates

  * The recent activity tab in Scan now looks up CNS entries for party IDs.
    Party IDs are shown as-is if they are not found in CNS.

* Removed the unused "users" field from participant identities dumps.
* A transaction history API has been added to Scan. Please see the Scan OpenAPI specification.

2023-10-09
----------

* Deployment updates

  * The URL of the global domain has changed to `http://sequencer.sv-1.svc.<TARGET_CLUSTER>.network.canton.global:5008`. This change is reflected in the values specified in `participant-values.yaml`, `validator-values.yaml` and `sv-values.yaml`.

* Frontend updates

  * The "SVC Configuration" and "Canton Coin Configuration" tabs in the SV UI were renamed to,
    respectively, "SVC Info" and "Canton Coin Info",
    to reflect the fact that they also show non-configuration information.

* Bugfixes:

  * Fixed broken Recent Activity tab link on the scan UI.

2023-10-02
----------

* Deployment updates:

    * The SV name for the node operated by Digital Asset on DevNet has been updated.
      Note the updated SV name in ``approved-sv-id-values-dev.yaml``: `Digital-Asset`.
    * The bucket configuration structure for the SV app and validator
      is now flattened. `projectId` and `bucketName` are now specified
      at the top level rather than in a `config` structure.

* Frontend updates

  * The option to vote on enabled choices has been removed.
    This will be superseded by support for full Daml model upgrades.

2023-09-25
----------

* Frontend updates:

    * New history view of past SVC governance votes and actions, listing separately "Action Needed", "In Progress", "Planned", "Executed" and "Rejected" votes.
        * Click on rows to review and vote on vote requests.
        * Filter vote requests by action name, requester and dates.

* Bugfixes:

    * When configured to operate a local domain node, the SV app now waits for the local CometBFT node to fully catch up before onboarding the local sequencer and mediator nodes.
      This leads to clearer log messages while the SV app is initializing and improves domain performance by avoiding spam caused by local sequencer nodes that are lagging behind during initialization.

2023-09-11
----------

* Deployment updates:

    * Please note an SV rename from `sbi` to `SBI-Holdings`.
    * Please note an increase in database volume size from `80Gi` to `160Gi`.

* Bugfixes:

    * Fixed an issue where the UIs would incorrectly include an `undefined` scope to requests towards an OIDC provider.

2023-09-04
----------

* Deployment updates:

    * Each SV node now participates in operating the global domain (``devnet`` only)
        * A new helm chart was introduced, ``global-domain`` that will deploy a global domain node. This helm chart is currently required only on the ``devnet`` deployment.
          Follow the :ref:`configuration instructions <helm-configure-global-domain>` and the :ref:`deployment instruction <helm-install>` to also deploy the global-domain helm chart.
          Please note that the order of deployment of the helm charts has been updated and the helm charts must be applied in the new order.
        * Added a new value, ``domain.enable`` to the ``sv-values.yaml``. This must be set to `true` only for the ``devnet`` deployment. The default value is `false`.
          Setting this to true will enable the usage of the components deployed as part of the global domain node and will make this SV node participate in
          operating the global domain.

* Documentation:

    * Configuring the SV node to participate in operating the global domain (``devnet`` only)
        * Added new entry to :ref:`Configuring the Helm Charts <helm-configure-global-domain>` that details the changes required for the ``global-domain-values.yaml``
        * Included the new helm chart ``global-domain`` in the :ref:`Installing the Helm Charts <helm-install>` section and updated the helm deploy ordering
        * Added new entry to :ref:`Logging into the SV UI <sv-ui-global-domain>` that  explains how to check if the components of the ``global-domain`` helm chart were installed correctly.

2023-08-28
----------

* Frontend updates:

    * The expiration of a vote request (VoteRequestTimeout) can be specified on the request level fulfilling the condition that
      the effective date of the coin configuration schedule change must be after the expiration.
    * Better vote requests concurrency management by preventing SVs to define coin configuration schedule effective at the same time.

* Deployment updates:

    * Introduced a new value, ``isDevNet``, for the CometBFT Helm charts,
      which should be set to ``true`` only for the ``devnet`` deployment.
      This value is required to generate the proper CometBFT genesis file.
      The value has been added to the ``cometbft-values.yaml`` file, default to false.
    * The ``foundingSvApiUrl`` has been removed from ``sv-values.yaml`` and ``validator-values.yaml``.

* Jfrog Artifact API Keys will be deprecated during the second half of 2023. You will need to to update the passwords of both the helm repository
  and the k8s secret from an API key to an Identity Token.

    * More information: `Introducing JFrog Access and Identity Tokens <https://jfrog.com/help/r/platform-api-key-deprecation-and-the-new-reference-tokens>`_
    * HowTo: :ref:`add your Artifactory password<identity-token>` (we suggest deleting the existing ones using `helm repo remove` and `kubectl delete secret`)

* Frontend updates:

    * Retired the action to entirely replace the coin config schedule `SetConfigSchedule`.
      The governance actions to add/remove/modify individual scheduled coin config changes should be used instead.
    * Added the current CometBFT validator set to the CometBFT debug tab in the SV app

* Documentation:

    * Clarified under which conditions it is possible to recover from a participant identities backup,
      and that coin balances are only preserved across TestNet resets.

2023-08-14
----------

* SV and validator apps now fail earlier and with a more clear error message if the version of their node software mismatches the version on the cluster they are connecting to.

* Deployment updates:

    * The SV name for the node operated by Fiutur been updated.
      Note the updated SV name in ``approved-sv-id-values-dev.yaml``: `Fiutur`.

* Documentation:

    * The list of approved SV members has been moved from the ``sv-values.yaml`` file into
      a separate config file. Two versions thereof exist now - one for TestNet-approved
      identities and the other for DevNet-approved ones. The instructions for deploying the
      ``cn-sv-node`` Helm chart have been updated to use this separate values file.

* The validator service now automatically uploads the directory service Daml DAR package to its participant. End-users and bootstrap scripts no longer need to upload the directory app models explicitly.

2023-08-07
----------

* Frontend updates:

    * Introduced new governance actions to add/remove/modify individual scheduled coin config changes.
      The existing UI for replacing the entire schedule will be deprecated in a coming release.

* Deployment updates:

    * Introduced a new value, ``disableAllocateLedgerApiUserParty``, for
      validator Helm charts, which should be set to ``true`` for SV nodes. This
      prevents clashes between the Validator and SV apps. The runbook includes
      this value in a newly-introduced ``sv-validator-values.yaml`` file.
    * The SV name for the node operated by IEU on behalf of LCV has been updated.
      Note the updated SV name in ``sv-values.yaml``: `Liberty-City-Ventures`.
    * Cumberland's name and public key have also been added in ``sv-values.yaml``,
      to be included in the sv-app's configuration as another identity that should be
      approved to join the SVC on TestNet.
    * Removed the SV `cometbft.automationEnabled` option from ``sv-values.yaml``. The automation is enabled by default and
      the CometBFT network is updated to reflect the current SV network.

* Documentation:

  * Removed obsolete section on manually "Onboarding a SV".
    The subsections on generating SV and CometBFT identities have been moved into the
    :ref:`Helm-based deployment section <sv-helm>`.

* Bugfixes:

    * Fix an issue where after a token refresh, ledger ingestion would not get restarted.

2023-07-31
----------

* Frontend updates:

    * Enhanced the SetEnabledChoices Vote Request to modify the CoinRules Choices, providing increased configurability and control over the Coin.
    * Various Frontend improvements.

* Bugfixes:

    * Fixed issue where Scan UI showed future coin config changes as if they are current.
    * Fixed issue where Scan app did not aggregate the total coin balance correctly when balance was migrated across network upgrades.
    * Fixed issue where coin migrations were not appearing in the wallet transaction log.
    * Fixed issue where automation in SV app gets into a busy loop and the app becomes irresponsive.
    * Fixed issue where existing CometBFT nodes were breaking the creation of a new network. Nodes running an old version will not be able to join new networks until they are upgraded.
      Note that the CometBFT Helm chart now includes the version in the genesis chain ID in order to support that.

* SV and validator apps will now exit with an error if the version of their node software mismatches the version on the cluster they are connecting to.

* Documentation:

    * Added a section `Renaming an SV` in the page "Kubernetes-Based Deployment of a Super Validator node" for step-by-step guide of renaming an SV.

2023-07-16
----------

* Deployment
    * Ensured that both the `cn-cometbft` and `cn-postgres` charts support the `db.volumeSize` and `db.volumeStorageClass` values for configuring persistent storage.
    * The three secrets, `cn-app-scan-ledger-api-auth`, `cn-app-directory-ledger-api-auth`, `cn-app-svc-ledger-api-auth` that were required before with dummy values, are no longer required.
    * The `cn-postgres` and `cn-participant`charts now require a non-empty `postgresPassword` value to be set. The value templates includes a default value that you can modify to something more secure.
    * The SV Helm runbook has been extended with a section that explains :ref:`how to restore from a participant identities backup <sv-participant-identities-restore>`.
    * The instructions for self hosted validators have been extended with a section that explains :ref:`how to restore from a participant identities backup <old-validator-participant-identities-restore>`.
    * The secrets ``cn-app-sv1-validator-ledger-api-auth`` and ``cn-app-sv1-ledger-api-auth`` are no longer required.
    * The participant now requests 4 CPUs to improve behavior under load.

* Frontend updates:

  * Total coin balance in Scan UI now shows real data from the backend, and also reflects data from network inception and not only from the point the specific backend was created.
    Note that this holds only for total coin balance for now, not for all data in the Scan UI.
  * Various SV UI improvements.

2023-07-02
----------

* The URL to scan in ``validator-values.yaml`` has changed to
  ``https://scan.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/scan``. The
  ``scanPort`` field has been removed.
* The ``svSponsorAddress`` from ``validator-values.yaml`` has been removed. The validator associated
  with an SV node is onboarded automatically through its SV node.
* The request to the IAM to acquire a token is now made using content
  type ``application/x-www-form-urlencoded`` instead of
  ``application/json``. This matches the OAuth standard and is
  compatible with a wider range of IAMs. No change is required if your
  IAM configuration was working previously.
* The public keys of other super validators now need to be specified in
  the ``approvedSvIdentities`` section in ``sv-values.yaml``. For TestNet launch these are::

    approvedSvIdentities:
      - name: sbi
        publicKey: MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETM+CeyHvphl9RiPDKL3vVX7F+Qo4fIhJopmgU5B7IzkwSdFic20hFB6tnAuCTU+UBjqZgh8N/h9r+CTrXMPsRg==
      - name: intellecteu-canton-da-test
        publicKey: MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr/iPpyuFu2U914tHyNUDuECT4/AYz9J+nLQRTC8m+95yQ6Y4Oah+Y3u3o5MK4a9D+qkoNGoG6ng0HcjA6TGKmw==
      - name: Digital-Asset
        publicKey: MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsRRntNkOLF2Wh7JxV0rBQPgT+SendIjFLXKUXCrLbVHqomkypHQiZP8OgFMSlByOnr81fqiUt3G36LUpg/fmgA==
* The SV node now supports domain fee top-ups in a network where domain fees are enabled.
  The ``validator-values.yaml`` file contains initial recommended values for those.
* SV and validator operators are now asked to perform backups of participant identities data, to enable continuity across network resets.
  This is covered in a :ref:`new section of the runbook <old-validator-continuity>`.
* The environment variable ``CANTON_PARTICIPANT_USERS`` defining the admin users to be allocated as part of participant
  bootstrapping is now a simple JSON array of strings, where each element is the name of another environment variable
  storing the name of the user to be allocated.

* Frontend updates:

  * Coin configuration in Scan UI now shows real data from the backend.
  * Domain Fees leaderboard in Scan UI now shows real data from the backend.

2023-06-25
----------

* Frontend updates:

   * The SV web UI allows operators to create vote requests to propose future coin configurations.
     Others can vote on these. Once the majority is reached the new configurations will be applied at due times.
   * The SV web UI allows operators to create vote requests to propose a new SV Collective configuration.
     Others can vote on these. Once the majority is reached the new configuration is applied.
   * The SV web UI has a new tab to display the status of your sequencer and mediator. Note that the mediator and sequencer are not yet deployed
     as part of the runbook so you will see an error on the status page.

* Deployment updates:

   * The container for scan now accepts the address to the participant through the ``CN_APP_SCAN_PARTICIPANT_ADDRESS``
     environment variable matching the SV app and the validator app. The helm charts set the right default so
     if you are using them, there is no need to configure anything.
   * The `audience` which defines the intended consumer of the token is now configurable in Participant, Validator app, Validator web UI, SV app, SV web UI and Directory web UI.

   * Validator apps and SV apps now configure the address of the founding SV app to allow for synchronization across nodes until all aspects of the BFT domain are robust under concurrent operations.

* All CN apps now have ``/readyz`` and ``/livez`` endpoints which can be used for Kubernetes readiness and liveness probes.

* Bugfixes

  * Fix a bug where the Directory UI does not connect correctly to the directory backend.

2023-06-18
----------

* Frontend updates:

  * Super validators can now feature and unfeature an application provider in the governance tab.

* Deployment updates:

  * The Canton Coin Scan app is now being deployed as part of the SV node in our runbook.
    See instructions for deploying the ``cn-scan`` Helm chart in :ref:`Installing the Software <helm-sv-install>`,
    and two new required ingress rules in :ref:`Configuring the Cluster Ingress <helm-sv-ingress>`.
    Section :ref:`Using the Canton Coin Scan UI <helm-scan-web-ui>` explains the UI.
    Note that not all fields in the Scan UI are hooked up to fetch data in the backend yet.
    Ones that should work at this point are the as-of round in the top-right corner, and the Validator and App leaderboards.

  * A CometBft node is now being deployed as part of the SV node in our runbook.

    * See instructions for generating a node identity in :ref:`Generating the CometBft node identity <cometbft-identity>`.
    * See instructions for configuring the required secrets with the node identity in :ref:`Configuring your CometBft node keys <helm-cometbft-secrets-config>`
    * See instructions for deploying the ``cn-cometbft`` Helm chart in :ref:`Installing the Software <helm-sv-install>`,
      and the new required ingress rule in :ref:`Configuring the Cluster Ingress <helm-sv-ingress>`.
    * See instructions for verifying that your node is connected in :ref:`Logging into the SV UI <local-sv-web-ui>`.
    * NOTE: you now need to configure your ingress to accept connections from the other SVs, talk to your contact at DA for the current list of IPs to whitelist.

  * The startup order for SV nodes has changed slightly: The SV app needs to be started before the validator app now.

  * The Canton Name Service Directory UI is now being deployed as part of the SV node in our runbook.
    See instructions for deploying the ``cn-validator`` Helm chart in :ref:`Installing the Software <helm-sv-install>`,
    and two new required ingress rules in :ref:`Configuring the Cluster Ingress <helm-sv-ingress>`.
    Section :ref:`Using the Canton Coin Directory UI <helm-cns-web-ui>` explains the UI.
    By searching for a name in the UI, an SV operator can register the name on Canton name service if it is not yet
    registered.

* Removed the ``svc-client`` config parameter from the SV app. The SVC app is no longer used for SV onboarding and initialization.

* Auth0 tokens used in Ledger connections are renewed 2 minutes before they expire.

* The domain connection is now initiated by the validator and SV app
  instead of the participant bootstrap script which requires
  specifying it in the ``globalDomainUrl`` field in ``sv-values.yaml``
  and ``validator-values.yaml``.

* Bugfixes

  * Fix a bug where the SV and validator app sometimes would stop
    observing ledger updates if the participant was down for a longer
    period of time and only recovered after a restart.

2023-06-11
----------

* Documentation:

  * Fixed missing ``service`` level indentation in sample istio-gateway helm chart values in the ingress installation instructions

  * Added `enableHealthProbes` to the participant Helm chart to allow operation on versions of Kubernetes <1.24 that do not support gRPC readiness and liveness probes.

2023-06-04
----------

* Deployment updates:

  * Docker images now use the same versioning scheme as helm charts:
    ``<major>.<minor>.<patch>-snapshot.<commit_date>.<number_of_commits>.0.v<commit_sha_8>``

* Frontend updates:

  * Reorganized the information tab in SV UI and included rules governing canton coin (e.g. fees) in SV UI.
  * Added support for displaying details of governance vote requests, casting a vote, and updating a casted vote.

* Bugfixes

  * Fixed the SV onboarding URL in the Helm runbook. It must be ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``
    rather than ``https://sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``.
  * Fixed an issue in last week’s release where the public/private SV keys were required in both the K8s secret and in ``sv-values.yaml``.
    Now they only need to be specified through the secret.
  * Fixed how the first round for which a new SV is eligible to receive SV rewards is determined.
    With the fix, SVs start receiving SV rewards starting from the next round that opens after that SV has joined, i.e.,
    an SVs will not receive SV rewards for any of the rounds that have opened before the time it has joined.

* Deployment updates:

  * The downloaded bundle now includes sample values files for the helm charts, and the
    instructions have been modified to list the required user-specific configuration changes
    required before using them.
  * `auth.jwksEndpoint` value in the Helm values of the participant has been renamed to `auth.jwksUrl` to
    align with the other Helm charts, and the instructions for setting them have also been made more consistent.


2023-05-28
----------

* Deployment updates:

  * ``joinWithKeyOnboarding.keyName`` in ``sv-values.yaml`` has been renamed to ``onboardingName``.
  * ``svSponsorPort`` in ``validator-values.yaml`` has been removed. The port is now included in ``svSponsorAddress``. The default sponsor address has
    been changed to ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``.
  * ``sponsorApiPort`` in ``sv-values.yaml`` has been removed. The port is now included in ``sponsorApiUrl``. The default sponsor address has
    been changed to ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``.
  * The SV private and public key are now stored in k8s secrets.
  * Kubernetes `liveness <https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-a-grpc-liveness-probe>`_ and `readiness <https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-readiness-probes>`_ probes are configured to probe the `GRPC Health Checking Protocol <https://github.com/grpc/grpc/blob/master/doc/health-checking.md>`_ of the participant node.
  * The instructions for generating your SV keys now also work on MacOS.
  * Ingress Helm charts and instructions have been rewritten to be simpler and are now based on Istio instead of Nginx. See :ref:`Configuring the Cluster Ingress <helm-sv-ingress>`

* Add new ``initial-coin-price-vote`` config option to SV app

  * for configuring an SV node to vote for a given coin price during initialization, if no coin price has been voted for by this SV node yet
  * useful for persisting coin price vote preferences across cluster (re-)deployments

* SV UI:

  * Added support for proposing a vote on an action, currently only on removing an SV member.

* Bugfixes

  * DA's internal automated tests are now resilient to coin price changes allowing us to change coin price votes on DA’s SVs so votes from other SVs have an observable effect.
  * Fix SV reward collection for cases in which an SV has been offline for an extended period of time. Previously, the collection of new rewards was blocked for a potentially very long time after restarting.


2023-05-21
----------

* Make the Kubernetes namespace for the SV node configurable in the Helm charts (now defaulting to `sv` in the runbook), see :ref:`deployment using Helm <helm-sv-wallet-ui>`.

* Features introduced to the SV UI:

  * Set your desired coin price (price per round determined using median of all coin price votes by SVs)

  * View currently open mining rounds, along with their coin prices

* Documentation improvements

  * Extend and improve documentation for :ref:`setting up authentication for SV nodes <helm-sv-auth>`
  * Add documentation for :ref:`validator onboarding through SV UI <generate_onboarding_secret>`

* Bugfixes

  * Fix an issue where the validator and SV app were unable to pass
    the well known response from IAMs other than Auth0.


2023-05-14
----------

* Introduce a UI for the Super Validator operator, see :ref:`SV Helm-Based Runbook <sv-helm>`.

  This UI currently allows the SV operator to see information about their SV party, and the rest of the SV collective.
  It allows allows the SV operator to onboard a validator by generating a validator onboarding secret
  (see the :ref:`Self-Hosted Validator runbook <self_hosted_validator>` for how that secret is then used by the validator operator).

* Fix a bug where ``cn-node`` sometimes failed to start with a ``ClassNotFoundException``.

2023-05-07
----------

* Add wallet UI for SV user to SV runbook. Instructions exist for
  :ref:`deployment using Helm <helm-sv-wallet-ui>` and local
  deployment (deprecated). This allows the SV operator to
  login to their wallet and e.g. observe SV rewards accumulating.

* Various simplifications and extensions of :ref:`SV Helm-based runbook <sv-helm>`:

  * Added :ref:`instructions <helm-sv-auth0>` for setting up Auth0, and creating the corresponding k8s secrets.
  * Consolidate namespaces. Everything other than docs now resides in the sv-1 namespace.
  * Simplify the ingress setup.

2023-04-30
----------

* Helm chart deployment of a node connected to either ``TestNet`` or ``DevNet``. Instructions :ref:`here <sv-helm>`
* Helm chart deployment documentation online :ref:`here <sv-helm>`.

2023-04-23
----------

* Initial Helm chart deployment of a standalone SV node. Instructions :ref:`here <sv-helm>`
