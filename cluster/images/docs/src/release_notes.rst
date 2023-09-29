.. _release_notes:

Release Notes
=============

2023-10-02
----------

* Deployment updates:

    * The SV name for the node operated by Digital Asset on DevNet has been updated.
      Note the updated SV name in ``approved-sv-id-values-dev.yaml``: `Digital-Asset`.

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

    * Added a section :ref:`Renaming an SV <helm-rename-sv>` in the page "Kubernetes-Based Deployment of a Super Validator node" for step-by-step guide of renaming an SV.

2023-07-16
----------

* Deployment
    * Ensured that both the `cn-cometbft` and `cn-postgres` charts support the `db.volumeSize` and `db.volumeStorageClass` values for configuring persistent storage.
    * The three secrets, `cn-app-scan-ledger-api-auth`, `cn-app-directory-ledger-api-auth`, `cn-app-svc-ledger-api-auth` that were required before with dummy values, are no longer required.
    * The `cn-postgres` and `cn-participant`charts now require a non-empty `postgresPassword` value to be set. The value templates includes a default value that you can modify to something more secure.
    * The SV Helm runbook has been extended with a section that explains :ref:`how to restore from a participant identities backup <sv-participant-identities-restore>`.
    * The instructions for self hosted validators have been extended with a section that explains :ref:`how to restore from a participant identities backup <validator-participant-identities-restore>`.
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
  This is covered in a :ref:`new section of the runbook <validator_continuity>`.
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
    Section :ref:`Using the Canton Coin Directory UI <helm-directory-web-ui>` explains the UI.
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
