..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE:
   We keep notes for upcoming releases in `release_notes_upcoming.rst`
   to avoid merges accidentally merging new changes into an existing release.

    - add your upcoming release notes into `release_notes_upcoming.rst`
    - upon release add the content of `release_notes_upcoming.rst` into a
      new `..  release-notes:: x.y.z` section with the actual version number;
      and comment out the `Upcoming` section in `release_notes_upcoming.rst`.

.. include:: release_notes_upcoming.rst

.. _release_notes:

.. release-notes:: 0.5.10

  - Deployment

    - postgres-exporter: disabled exporting the settings table, to workaround `an issue of postgres-exporter <https://github.com/prometheus-community/postgres_exporter/issues/1240>`__.

    - Splice apps and Canton components deployed via Docker compose now log at ``INFO`` level by default instead of ``DEBUG``.
      In case you do want to change this, export the ``LOG_LEVEL`` environment variable before running ``./start.sh``. e.g., ``export LOG_LEVEL="DEBUG"; ./start.sh``.

  - SV app

    - Add a new trigger, ``ExpiredDevelopmentFundCouponTrigger`` for expiring development fund coupons.

  - Wallet UI

    - Remove the provider field from transaction history.

  - Scan UI

    - Remove the provider field from transaction history. The updates API continues to expose it.

  - Daml

    - Add a ``splice-util-batched-markers`` package that provides support for creating
      a batch of ``FeaturedAppActivityMarkers`` in a transaction with a single view,
      which is more efficient to process. Note that this package is not yet uploaded
      automatically to validator (or super validator) nodes.
      See the :ref:`package docs <package-batched-markers>` for more details.


.. release-notes:: 0.5.9

  - Scan

    - `canton.scan-apps.scan-app.acs-store-descriptor-user-version` and `canton.scan-apps.scan-app.tx-log-store-descriptor-user-version`
      configuration settings
      have been added to set a `user-version`, respectively for the ACS and TxLog store.
      Modifying the `user-version` wipes the respective store and triggers re-ingestion.
      See the :ref:`SV Operations docs <sv-reingest-scan-stores>` for more details.

    - Added a new external endpoint ``GET /v0/unclaimed-development-fund-coupons`` to retrieve all active unclaimed development fund coupon contracts.

  - Daml

    - Implement minting delegation described as part of `CIP-0073 - Weighted Validator Liveness Rewards for SV-Chosen Parties <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0073/cip-0073.md>`__. See the :ref:`minting delegations <minting-delegations>` section for details.

      - New templates have been added in the ``splice-wallet`` package:

        - **MintingDelegationProposal**: Represents a proposal from beneficiary to the delegate to create a ``MintingDelegation``.

        - **MintingDelegation**: Represents an active delegation granting the delegate the authority to mint rewards on behalf of the beneficiary.

      Enabling this feature on all validators requires the SVs to vote on the following Daml versions:

      ================== =======
      name               version
      ================== =======
      wallet             0.1.16
      ================== =======

  - Wallet

    - Added a new internal endpoint ``POST /v0/wallet/development-fund-coupons/allocate`` to allocate a development fund coupon for a given beneficiary,
      amount, expiration time, and reason.

    - Added a new internal endpoint ``GET /v0/wallet/development-fund-coupons`` to retrieve all active DevelopmentFundCoupon contracts,
      sorted by expiration date, where the wallet user party is either the development fund manager or the beneficiary.

    - Added a new internal endpoint ``POST /v0/wallet/development-fund-coupons/{contract_id}/withdraw`` to withdraw a development fund coupon
      when the wallet user party is the development fund manager.

    - Support for managing minting delegation has been added to the wallet UI. See the :ref:`minting delegations <minting-delegations>` section for details.

    - Enhanced the rewards collection automation to support collecting development fund coupons.

  - Validator

    - Automation has been added to perform minting for hosted external-parties if they have an active ``MintingDelegation`` contract with an internal party.


.. release-notes:: 0.5.8

  Note: 0.5.7 introduced a significant performance regression related to the processing of topology transactions on participants, mediators, and sequencers.
  Please skip 0.5.7 and upgrade directly to 0.5.8.

  - Canton

    - Fix performance regression related to the processing of topology transactions.
    - Improve performance of some queries that are used in participant pruning. The fix includes
      a database migration which can take up to 2min but should be faster on most participants.

  - Scan

    - deprecated ``/v0/total-amulet-balance`` and ``/v0/wallet-balance`` endpoints have been removed in favor of using `/registry/metadata/v1/instruments/{instrumentId} <app_dev/token_standard/openapi/token_metadata.html#get--registry-metadata-v1-instruments-instrumentId>`_
      and `/v0/holdings/summary <app_dev/scan_api/scan_openapi.html#post--v0-holdings-summary>`_, respectively.

  - Deployments

    - The default logger has been switched to use an asynchronous appender, for all the nodes, for better performance.
      The behavior can be switched back to synchronous logging by setting the environment variable `LOG_IMMEDIATE_FLUSH=true`.
      This now includes helm deployments as well, in 0.5.7 the default was changed only for docker-compose deployments.

    - This version breaks backwards compatibility with migration dumps taken on 0.4.x versions.
      Please make sure that you are deploying with ``migrating: false`` (helm) / without ``-M`` (docker-compose).
      For helm-based validator deployments:

      In some cases, helm might not properly update the state after you removed the `migrating` flag.
      You can check before the upgrade if it got properly applied through ``kubectl describe deployment -n validator validator-app`` and look for this env var:

      .. code-block:: yaml

          - name: ADDITIONAL_CONFIG_VALIDATOR_MIGRATION_RESTORE
            value: |
              canton.validator-apps.validator_backend.restore-from-migration-dump = "/domain-upgrade-dump/domain_migration_dump.json"

      If you see it, the deployment still has ``migrating: true`` activated.
      You can clear that flag by, for example, uninstalling and reinstalling the validator helm release (but not participant and postgres).
      You can either directly reinstall the new version or first do the reinstall on the old version and then upgrade.

.. release-notes:: 0.5.7

  .. important::

      **Action recommended from app devs:**

      **App devs whose app's Daml code statically depends on** ``splice-amulet < 0.1.15`` should recompile their Daml code
      to link against ``splice-amulet >= 0.1.15`` in order to be ready to consume the two new fields introduced in `AmuletConfig`
      (`optDevelopmentFundManager`) and `IssuanceConfig` (`optDevelopmentFundPercentage`) once either of them is set.

      This is required because once the new fields are set, downgrades of `AmuletRules` will fail.
      At the moment, this recompilation is not strictly required, as setting these fields is not planned immediately.

      No change is required for apps that build against the :ref:`token_standard`
      or :ref:`featured_app_activity_markers_api`.

  - Sequencer connections

    - Fixed an issue in the sequencer connection logic in splice 0.5.6 that resulted in participants randomly crashing and restarting.

  - Daml

    - Implement Daml changes for `CIP-0082 - Establish a 5% Development Fund (Foundation-Governed) <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0082/cip-0082.md>`__:

      - New templates:

        - **UnclaimedDevelopmentFundCoupon**: Represents unallocated Development Fund entitlements created per issuance round.
          Coupons are owned by the DSO, have no expiry, and serve as accounting instruments.
          ACS size is managed through merging rather than expiration.

        - **DevelopmentFundCoupon**: Represents an allocated portion of the Development Fund for a specific beneficiary.
          Coupons can be withdrawn by the Development Fund Manager or expired by the DSO, in both cases restoring the
          amount to an unclaimed coupon.

      - Configuration extensions:

        - ``IssuanceConfig`` is extended with an optional ``optDevelopmentFundPercentage``, defining the fraction of each
          mint allocated to the Development Fund (validated to be within ``[0.0, 1.0]``).

        - ``AmuletConfig`` is extended with an optional ``optDevelopmentFundManager``, designating the party authorized
          to allocate Development Fund entitlements.

      - AmuletRules updates:

        - Modify ``AmuletRules_MiningRound_StartIssuing``: Issuance logic now deducts the Development Fund share
          before distributing rewards. When a nonzero allocation is configured, a new
          ``UnclaimedDevelopmentFundCoupon`` is created per round. If ``optDevelopmentFundPercentage`` is ``None``,
          a default value of **0.05** is applied.
          The accrual of ``UnclaimedDevelopmentFundCoupon`` contracts thus starts
          as soon as the new Daml models are voted in.

        - A new choice ``AmuletRules_MergeUnclaimedDevelopmentFundCoupons``: Adds a batch merge operation to combine
          multiple ``UnclaimedDevelopmentFundCoupon`` contracts into a single one for ACS size control.

        - A new choice ``AmuletRules_AllocateDevelopmentFundCoupon``: Allows the Development Fund Manager to allocate
          unclaimed entitlements to beneficiaries, creating ``DevelopmentFundCoupon`` contracts and returning any
          remaining unclaimed amount.

        - Modify ``AmuletRules_Transfer``: Transfers now accept ``DevelopmentFundCoupon`` as a valid input when
          the sender matches the beneficiary and report the total Development Fund amount consumed.

      - DsoRules updates:

        - A a new choice ``DsoRules_MergeUnclaimedDevelopmentFundCoupons``: Enables the DSO to trigger unclaimed coupon
          merges via governance.

        - Add a new ``DsoRules_ExpireDevelopmentFundCoupon``: Allows the DSO to expire an allocated
          ``DevelopmentFundCoupon``, restoring its amount to an ``UnclaimedDevelopmentFundCoupon``.

      Note that the UI changes in the Wallet app required to allocate funds are not yet implemented and will be delivered in a later release. Please refer to
      this issue: `Tracking - CIP-0082 - 5% Development Fund <https://github.com/hyperledger-labs/splice/issues/3218>`.

      These Daml changes require an upgrade to the following Daml versions **before**
      voting to set the transfer fees to zero:

      ================== =======
      name               version
      ================== =======
      amulet             0.1.15
      amuletNameService  0.1.16
      dsoGovernance      0.1.21
      splitwell          0.1.15
      validatorLifecycle 0.1.6
      wallet             0.1.15
      walletPayments     0.1.15
      ================== =======

  - SV app

    - Add a new trigger, `MergeUnclaimedDevelopmentFundCouponsTrigger`` that automatically monitors ``UnclaimedDevelopmentFundCoupon`` and,
      once their number reaches at least twice the configured threshold, merges the smallest coupons into a single one.
      This approach keeps contract-ids of larger coupons stable to minimize contention with externally prepared transactions which reference these ids.

    - Add a new config field to ``SvOnboardingConfig`` named ``unclaimedDevelopmentFundCouponsThreshold`` defining the
      threshold above which ``UnclaimedDevelopmentFundCoupon`` s are merged. The default value is set to 10.

  - Deployments

    - The default logger has been switched to use an asynchronous appender, for all the nodes, for better performance.
      The behavior can be switched back to synchronous logging by setting the environment variable `LOG_IMMEDIATE_FLUSH=true`.

  - Validator

    - Expose ``/v0/holdings/summary`` endpoint from scan proxy.

.. release-notes:: 0.5.6

  - Sequencer

    - Includes a number of performance improvements that should improve the stability of the sequencer under higher load.

.. release-notes:: 0.5.5

  - API security

    - Tightened authorization checks for all non-public API endpoints.

      All non-public endpoints now properly respect the current user rights
      defined in the participant user management service.
      Revoking user rights on the participant will revoke access to the corresponding API endpoints.

      In general, endpoints that required authentication before will now check that the authenticated user
      is not deactivated on the participant and has ``actAs`` rights for the relevant party
      (wallet party for the wallet app API, SV operator party for the SV app API, etc).

    - Administrative SV app endpoints now require participant admin rights.

      The following SV app endpoints now require the user to have participant admin rights in
      the participant user management service.

        - ``/v0/admin/domain/pause``
        - ``/v0/admin/domain/unpause``
        - ``/v0/admin/domain/migration-dump``
        - ``/v0/admin/domain/identities-dump``
        - ``/v0/admin/domain/data-snapshot``

      This allows for finer grained access control
      where users with ``actAs`` rights for the SV operator party but without participant admin
      rights may use the SV or wallet UIs, but may not perform administrative actions like
      hard synchronizer migrations.

      Note that only the service users of the SV and validator apps should automatically have participant admin rights.
      If you are using other users to access the above endpoints, check their rights.

    - Some endpoints will have changed authorization rules in an upcoming release.

        - SV app ``/v0/dso`` is currently public, but will require authorization as SV operator,
          similar to most other SV app endpoints.
          Use the public ``/v0/dso`` endpoint in the scan app if you need to fetch DSO info.

  - Validator

    - Added support for picking a custom name for new parties created when onboarding users via the `/v0/admin/users` API. See :ref:`docs <validator-users>`.

    - Added an optional ``excludeDebugFields``` boolean to the request body of allocation and transfer endpoints for the Token Standard component
      (``splice-api-token-allocation-v1`` and ``splice-api-token-transfer-instruction-v1``).
      Clients can now set this to true to omit debug information from the response in order to save on bandwidth.

.. release-notes:: 0.5.4

  - Participant

    - Fix a bug introduced in 0.5.0/0.5.1 that could cause participant pruning to prune active data.
      The bug only manifests in a rare edge case involving a manual ACS import on a participant that was already running for some time.

    - Fix a performance regression in participants that causes the processing of events to pause for multiple minutes at random times,
      due to a bad database query plan on the critical part of the indexer pipeline.

  - Scan

    - Removed the non-existing `command_id` field from the OpenAPI spec of all
      scan endpoints that return transactions.
      The field was included in the "required" section without being a property
      of the returned transaction object. This is only a bugfix in the OpenAPI spec
      and has no impact on the actual API behavior.

.. release-notes:: 0.5.3

  Note: 0.5.2 mistakingly introduced default pruning for Canton participants and should be skipped in favor of 0.5.3.
  Participants **do not** prune any data by default.
  Pruning can be enabled explicitly by any validator operator.
  For more information please check the :ref:`docs <validator_participant_pruning>`.

  - Sequencer connections

    - Improve retries for sending sequencer submissions when a sequencer rejects the request with an overloaded error code by retrying immediately on another node.
    - The network timeout for the connection was lowered to 15 seconds to detect failures faster.

  - Validator

    - Fix bug that caused validators to fail on restoring participant users without rights during a synchronizer migration.

  - Scan

    - The round-based aggregates for balance values (changes to holding fees and initial amounts since round zero)
      have diverged between scans because of the way amulet expiration is counted in rounds.
      The balance values recorded in the round aggregates are effectively not depended upon anymore by scan APIs,
      and are now set to zero to avoid consensus problems when an SV reads aggregates
      from the rest of the network when first joining.

    - Please note that ``/v0/total-amulet-balance`` and ``/v0/wallet-balance`` endpoints are marked for removal, and will be removed in an upcoming release.
      See the Scan OpenAPI documentation for details: `/v0/total-amulet-balance <app_dev/scan_api/scan_openapi.html#get--v0-total-amulet-balance>`_
      and `/v0/wallet-balance <app_dev/scan_api/scan_openapi.html#get--v0-wallet-balance>`_.

  - Daml

    - Fixed a bug in ``WalletUserProxy_TransferInstruction_Withdraw``, where the controller was
      required to be the ``receiver`` instead of the ``sender`` of the transfer instruction. Upgrade
      to ``splice-util-featured-app-proxies`` version ``1.2.1`` or newer to get the fix.

  - SV app

    - The SV app will no longer store the update history and such, will not be able to answer historical queries.
      All updates involving the DSO party will still be stored and returned by Scan.

    - Deployment

      - The helm values under ``scan``, that is ``publicUrl`` and ``internalUrl`` are now mandatory.
        All SVs already deploy scan on DevNet, TestNet and MainNet so this should have no impact.

  - Docs

    - Improvements to validator docs on :ref:`Synchronizer Upgrades with Downtime <validator-upgrades>`.

.. release-notes:: 0.5.1

  - Canton Participant

    - Fix an issue where after a restart the participant could fail to
      come up as a query exceeded the 65353 query parameter limit. This
      should only an issue for SVs or participants with very high
      traffic.


.. release-notes:: 0.5.0

  .. important::

      Upgrade to Canton 3.4: This upgrade requires a Synchronizer Migration with Downtime and cannot be applied through a regular upgrade.
      For details refer to the approved `CIP <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0089/cip-0089.md>`_
      as well as the respective documentation pages for :ref:`validators <validator-upgrades>` and :ref:`SVs <sv-upgrades>`.

  - Deployment

      - **Breaking**: Docker-compose based deployments of LocalNet, validator, and SV expose only to 127.0.0.1 by default. If you want to expose externally, use ``-E`` in validator and superValidator ``start.sh``. For LocalNet, set ``export HOST_BIND_IP=0.0.0.0`` manually.

  - Validator

      - ``/v0/admin/users/offboard``:
        Offboarding a user now also deletes the ledger API user in the participant node.
      - If you need to use an HTTP proxy in your environment, you can now use `https.proxyHost` and `https.proxyPort` Java system properties.
        Please see :ref:`HTTP Proxy configuration <validator-http-proxy-helm>` for Kubernetes-Based deployment and :ref:`HTTP Proxy configuration <validator-http-proxy-compose>` for Docker Compose-Based deployment.

  - Scan

    - Added a ``record_time_match`` property to ``/v0/state/acs``, ``/v0/holdings/state`` and ``/v0/holdings/summary`` API requests.
      Finds a snapshot that exactly matches the specified ``record_time`` if set to ``exact`` (default),
      or finds the first snapshot at or before the specified ``record_time`` if set to ``at-or-before```.

  - Docs

    - Document additional approach for resuming a :ref:`validator disaster recovery <validator_dr>` process that has failed at the step of importing the :term:`ACS`.
    - Added a section on :ref:`configuring traffic <compose_validator_topup>` topups for Docker-compose deployments
    - Add a section on :ref:`wallet_how_to_earn_featured_app_rewards`

  - Mediator

    - Mediators now prune data to only retain the last 30 days matching the 30 day pruning interval of sequencers.

.. release-notes:: 0.4.25

  Note: 0.4.24 was published incorrectly and should be skipped in favor of 0.4.25.

  - Canton Participant

    - Fix an issue where after a restart the participant could fail to
      come up as a query exceeded the 65353 query parameter limit. This
      should only an issue for SVs or participants with very high
      traffic.


.. release-notes:: 0.4.23

  - Daml

    - Added the ``splice-util-token-standard-wallet.dar``
      :ref:`package <package-splice-util-token-standard-wallet>` that provides support for
      implementing auto-merging of holdings and airdrop campaigns, as
      explained in :ref:`holding_utxo_management`.
      The package is optional and not uploaded by default to a validator node.
    - Extended the ``splice-util-featured-app-proxies.dar``
      :ref:`package <package-featured-app-proxies>` to
      support executing :ref:`batch/bulk transfers <type-splice-util-featuredapp-walletuserproxy-walletuserproxybatchtransfer-93002>` of Canton Network token standard tokens,
      both for featured and unfeatured apps.


  - Performance improvements

    - Scan

        - Cache open rounds with a default TTL of 30s. This should reduce load when rounds change and lots of clients try to read the open rounds. `View PR 2860. <https://github.com/hyperledger-labs/splice/pull/2860>`_

        - Reduce database load when the connection to the mediator verdict ingestion is restarted. `View PR 2861. <https://github.com/hyperledger-labs/splice/pull/2861>`_

  - Deployment

    - Increased the resource allocation for most apps, double check any changes if you override the default resources. `View PR 2972. <https://github.com/hyperledger-labs/splice/pull/2972>`_

.. release-notes:: 0.4.22

  - SV

    - Improve throughput of ``FeaturedAppActivityMarkerTrigger``, which converts ``FeaturedAppActivityMarker`` contracts
      to ``AppRewardCoupon`` contracts as described in `CIP-0047 Featured App Activity Markers <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`__.
      The new implementation uses larger batches (100 markers by default, instead of 5) and
      parallelizes their execution (by default up to 4x).
      The work is split between different SVs in a way that completely avoids contention when there are not too
      many (by default 10k) markers, and that minimizes contention using random sampling of batches when the automation
      is in catchup mode because there are too many markers.
      Catchup mode only triggers when one or more of the SVs failed to convert the markers assigned to them for too long.


.. release-notes:: 0.4.21

  - Deployment

    - Validator deployments on k8s now support no-auth mode. Please note that this is not recommended for production deployments
      and relies purely on network-level access control for securing the validator, i.e. anyone with access to your node
      can act on your behalf.

    - The ``chown`` init containers in the validator and SV helm charts have been replaced by setting the ``fsGroup`` in the security context of the pods.
      This overcomes certain security policies that disallow init containers from having ``chown`` capabilities, and in most environments should
      achieve the same effect. In certain environments, the ``fsGroup`` directive might be ignored. In that case, you can add
      an init container using the ``extraInitContainers`` helm value to achieve the same effect as before, as documented in
      :ref:`this section <helm-validator-volume-ownership>`.

  - Reward collection

    - Changed the behavior of automation around rewards and coupons to run for the first time in the interval of ``round open time`` -> ``round open time + tick duration``. This might increase the observed duration between rewards and coupons being issued and until they are collected. Once the first tick elapses, retries will happen more aggressively.

  - Scan

    - Add the ``v0/events`` API. For private transactions, this API returns events that contain only mediator verdicts. For transactions visible to the DSO (like Amulet transfers), the API combines mediator verdicts and associated updates by ``update_id``.
      Events can be retrieved by ``update_id`` by using ``/v0/events/{update_id}``.
      Please see the new section about :ref:`Events <scan_events_api>` in the Scan Bulk Data API for more details.

  - SV

    - Published conversion rates are now clamped to the configured range, and the clamped value is published instead of
      only logging a warning and not publishing an updated value for out-of-range values.

    - UI usability improvements.

  - Monitoring

    - The SV App now exposes metrics for SV-voted coin prices and the coin price in latest open mining round.


.. release-notes:: 0.4.20

  - Deployment

    - Fix a bug where the setting the affinity for the ``splice-cometbft`` and ``splice-global-domain`` helm charts would remove the anti affinity for the ``cometbft`` and the ``sequencer`` deployment. This ensures that if multiple SVs are run on the same nodes, not more than one ``cometbft`` pod can be deployed on the same node and that no more than one ``sequencer`` pod can be deployed to the same node (a ``cometbft`` pod can still share a node with a ``sequencer`` pod). This can be disabled by setting the ``enableAntiAffinity`` helm value to ``false`` (default ``true``).

    - Replace ``-Dscala.concurrent.context.minThreads=8`` with ``-Dscala.concurrent.context.numThreads=8`` and set ``-XX:ActiveProcessorCount=8``  in the ``defaultJvmOptions`` for all the helm charts that deploy scala apps. This should ensure that the internal execution contexts spawn 8 threads to handle processing and that the JVM is configured for 8 CPUs as well. The previous behavior would spawn up to number of available processors, which can be up to the number of CPUs on the actual node if no CPU limit is set. This should avoid overloading the nodes during heavy processing.

  - SV

    - UI

      - Add the ability to specify a validator party hint when generating onboarding secrets.

      - The UI now provides a formatted message for easily sharing onboarding details with validator operators.


0.4.19
------

  - Sequencer

    - Fix a regression introduced in 0.4.18 that made topology transactions significantly more expensive to process.

  - Docker images

    - All app & UI images now use a non-root user.

  - Validator

     - Add a trigger to export these party metrics:

        - ``validator_synchronizer_topology_num_parties``:
          Counts the number of parties allocated on the Global Synchronizer
        - ``validator_synchronizer_topology_num_parties_per_participant``:
          Uses the label ``participant_id`` and
          counts the number of parties hosted on the Global Synchronizer per participant.
          Note that multi-hosted parties are counted for each participant they are hosted on.

       The trigger does not run by default. See :ref:`enable_extra_metric_triggers`
       for instructions on how to enable it.

  - SV

    - Deployment

      - Remove CPU limits from the helm charts for ``scan``, ``mediator`` and ``sequencer`` apps.
        This should avoid issues with cpu scheduling that might lead to performance degradations.

    - UI

      - When updating the ``AmuletRules`` config, the UI will omit any transfer fee steps with value zero from the ``AmuletRules`` config stored on-ledger.
        Thereby making the ``AmuletRules`` contract smaller and saving traffic for transactions using it.
        This is motivated by `CIP-0078 CC Fee Removal <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`__ .

  - Canton and SDK:

     - Introduction of 2 new alpha primitives in ``DA.Crypto.Text`` Module
       in SDK version ``3.3.0-snapshot.20250930.0``. Note: To make use
       of the functionality added here, you must compile against SDK
       version ``3.3.0-snapshot.20250930.0`` and newer and you must
       first upgrade Canton to the version in Splice ``0.4.19`` before you can
       upload those dars to your validator.

       - sha256 : BytesHex -> BytesHex: Computes the SHA-256 hash of
         the given hexadecimal bytes.

       - secp256k1WithEcdsaOnly : SignatureHex -> BytesHex ->
         PublicKeyHex -> Bool: Verifies an ECDSA signature on the
         secp256k1 curve, checking if the signature matches the
         message and public key.

0.4.18
------

  - Daml

    - release ``splice-util-featured-app-proxies-1.1.0`` with
      support for a ``WalletUserProxy``, which simplifies
      the creation of featured app activity markers for wallet app providers
      when their users engage in token standard workflows.
    - Implement Daml changes for `CIP-0079 - Demonstrate Third-Party Price Feed Integration for CC Listing <https://github.com/global-synchronizer-foundation/cips/pull/101/files>`__:

       These Daml changes require an upgrade to the following Daml versions:

       ================== =======
       name               version
       ================== =======
       amulet             0.1.14
       amuletNameService  0.1.15
       dsoGovernance      0.1.20
       validatorLifecycle 0.1.5
       wallet             0.1.14
       walletPayments     0.1.14
       ================== =======

  - Scan

    - Performance bugfix for the ``/v0/wallet-balance`` endpoint, especially when requesting a balance for a party that does not exist, which previously would timeout.

  - UIs

    - Implement changes from CIP-78 CC Fee Removal.

0.4.17
------

.. important::

    **Action required from app devs:**

    1. **App devs whose app's Daml code statically depends on** ``splice-amulet < 0.1.14`` must recompile their Daml code
       to link against ``splice-amulet >= 0.1.14``.

       The reason being that earlier versions of the ``AmuletRules`` template
       do not support setting the transfer fees to zero. Attempting to downgrade to them will raise a
       ``PRECONDITION_FAILED`` error stating that the ``ensure`` clause evaluated to ``false``.

       No change is required for apps that build against the :ref:`token_standard`
       or :ref:`featured_app_activity_markers_api`.

    2. **App devs whose app predicts holding fees on transfers** must adjust their code to
       no longer expect any holding fees once this Daml change gets voted in.

       The simplest option is to make your code independent of whether the change was voted in
       by removing the prediction of holding fees. You can instead
       extract the actual holding fees charged from the transfer transaction itself;
       i.e., using the :ref:`"holdingFees" <type-splice-amuletrules-transfersummary-17366>` field
       of the ``TransferSummary`` in the :ref:`"summary" field <type-splice-amuletrules-transferresult-93164>`
       of the ``TransferResult``.

- Daml

  - Implement Daml changes for `CIP-0078 - CC Fee Removal <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0078/cip-0078.md>`__:

     - Change all Amulet transfers to not charge holding fees on inputs.
     - Fix a bug in the ``ensure`` clause of ``AmuletRules`` that prevented
       setting the Amulet transfer fees to zero.
     - Fix a bug in the featured app rewards issuance for ``AmuletRules_Transfer``
       that prevented featured app rewards to be issued when the Amulet transfer fees are set zero.

     These Daml changes require an upgrade to the following Daml versions **before**
     voting to set the transfer fees to zero:

     ================== =======
     name               version
     ================== =======
     amulet             0.1.14
     amuletNameService  0.1.14
     dsoGovernance      0.1.19
     validatorLifecycle 0.1.5
     wallet             0.1.14
     walletPayments     0.1.14
     ================== =======

- Canton

  - Add ``CanExecuteAs`` and ``CanExecuteAsAnyParty`` user rights that can be used for the
    ``InteractiveSubmissionService/ExecuteSubmission`` endpoint. ``CanActAs`` permissions imply
    ``CanExecuteAs`` so this is backwards compatible.

- Validator

  - Expose ``/dso`` endpoint from scan proxy

- Wallet

  - Do not deduct holding fees from available balance if ``splice-amulet >= 0.1.14``
    is configured in the ``AmuletConfig`` of the network.

- Deployment

  - Participant

     - Remove CPU limits in the ``splice-participant`` helm chart, to avoid throttling because of the way K8s handles CPU limits

  - Validator

    - Allow disabling the deployment of ``ans-web-ui`` and ``wallet-web-ui`` in the ``splice-validator`` helm chart by setting
      ``.ansWebUi.enabled`` and ``validatorWebUi.enabled`` to ``false``.
      Thanks to Marcin Kocur for contributing this change in https://github.com/hyperledger-labs/splice/pull/2171

- LocalNet

  - Add the environment variable ``LATEST_PACKAGES_ONLY`` (default: true). This modifies the previous default behavior — if set to true, only the latest version of each package is uploaded instead of all versions. This reduces resource usage but might cause issues if you try to use localnet to test an app that is compiled against an older version. In that case, set the environment variable to false to restore the prior behavior.

- Community docs

  - Add :ref:`Keycloak Configuration Guide for Validators <keycloak_canton_validator_config_guide>`.
    Thanks to mikeProDev for contributing this change in https://github.com/hyperledger-labs/splice/pull/2247

0.4.16
------

- Daml

  - Add the ``splice-util-featured-app-proxies``
    :ref:`package <featured_app_activity_markers_api>` to simplify
    the creation of featured app activity markers for token standard actions.
    This is a utility package that is not uploaded by default to a validator node.
    An example use-case for this package is an exchange that wants to
    `earn app rewards on deposits and withdrawals <https://docs.digitalasset.com/integrate/devnet/exchange-integration/extensions.html>`__
    of CN token standard tokens.

- Docs

  - SV

    - Document process for :ref:`ignoring party IDs for reward expiry automation <sv_ops_ignored_rewards_party_ids>`
      that is currently recommended after each Daml upgrade,
      to reduce the impact of validators that are unable to complete
      the Daml upgrade due to being on an outdated version of Splice.

    - Make the filter for ignoring party ids for reward expiry automation also ignore beneficiaries for SV reward coupons so
      that it is not required to ignore the SV if only one beneficiary has problems.

0.4.15
------

- Canton

    - SV
        - Increase default events buffer sizes to a maximum of 200MiB for the sequencer. This should improve performance for the sequencer when serving events to nodes have subscriptions that are slightly lagging behind. This will slightly increase memory usage for the sequencer.

    - Ledger API

        - Add ``maxRecordTime`` to ``PrepareSubmissionRequest`` to limit the record time until which
          a prepared transaction can be used.
        - Add an alpha version of ``com.daml.ledger.api.v2.admin.PartyManagementService/GenerateExternalPartyTopology`` and
          ``com.daml.ledger.api.v2.admin.PartyManagementService/AllocateExternalParty``. These endpoints can be used instead of
          the validator endpoints ``/v0/admin/external-party/topology/generate`` and ``/v0/admin/external-party/topology/submit``
          and will eventually supersede them.

- Docs

  - Various improvements to the docs on :ref:`recovering a validator from an identities backup <validator_reonboard>`,
    including adding a section on :ref:`obtaining an identities backup from a database backup <validator_manual_dump>`.
  - Add documentation about :ref:`Wasted traffic <traffic_wasted>`.

- Deployment

  - Cometbft

     - Increase resource requests from 1 CPU and 1Gi to 2 CPUs and 2Gi, to better fit observed resource usage.
     - Remove CPU limits to avoid throttling because of the way K8s handles CPU limits

0.4.14
------

- SV app

   - Add the option to ignore certain parties when running expiry on reward contracts. This can added to the app configuration. Example: ``canton.sv-apps.sv.automation.ignored-expired-rewards-party-ids = [ "test-party::1220b3eeb21b02e14945e419c5d9e986ce8102171c50e1444010ab054e11eba262c9" ]``


0.4.13
------

- Deployment

  - SV
    - Increase the CPU limits assigned to the sequencer from 4 CPUs to 8 CPUs. This should avoid any throttling during periods of high load and during catch-up after downtime.

  - Cometbft

    - State sync is disabled by default.
      State sync introduces a dependency on the sponsoring node for fetching the state snapshot on
      startup and therefore a single point of failure. It should only be enabled when joining a
      new node to a chain that has already been running for a while. In all other cases, including
      for a new node after it has completed initialization and after network resets, state sync
      should be disabled.

  - Observability

    - Global Synchronizer Utilization dashboard now includes an average over an hour of the transaction rate.
    - Canton/Sequencer Messages dashboard now includes hourly totals, and a pie chart of the
      distribution of message types over the last 24 hours.

- Validator Compose Deployment

  - Expose Canton ledger API by default. Reference the  :ref:`docs <compose_canton_apis>` for details.

- Daml

  - Fix a bug where activity record expiration had a reference to the ``AmuletRules`` contract which resulted in transactions
    failing when trying to expire an activity record for a party that has not upgraded to the latest version of the
    Daml models. This caused an issue on DevNet where transactions submitted by the SV app
    failed repeatedly which resulted in the circuit breaker getting triggered and blocking
    all submissions.

     These Daml changes requires an upgrade to the following Daml versions:

     ================== =======
     name               version
     ================== =======
     amulet             0.1.13
     amuletNameService  0.1.13
     dsoGovernance      0.1.18
     validatorLifecycle 0.1.5
     wallet             0.1.13
     walletPayments     0.1.13
     ================== =======

0.4.12
------

- Docs

  - Clarifications around the :ref:`validator disaster recovery <validator_dr>` process.
  - Add how-to docs for :ref:`Token Standard usage <token_standard>`.

- Cometbft

  - Doubled the default mempool size and deduplication cache size as they get exceeded on prod networks occasionally.

- Splice Development

  - Vagrant (new)

    - Add Vagrantfile as a convenient way to spin up a local development
      environment for Splice. See `README.vagrant.md
      <https://github.com/hyperledger-labs/splice/blob/0.4.12/README.vagrant.md>`_
      and `Vagrantfile
      <https://github.com/hyperledger-labs/splice/blob/0.4.12/Vagrantfile>`_ for
      details.

  - A subset of the tests now run on PRs from forks without approval from a maintainer
    (see `TESTING.md <https://github.com/hyperledger-labs/splice/blob/0.4.12/TESTING.md>` for details)

- Performance improvements

  - Improve sequencer performance when processing events from CometBFT, this should allow the sequencer to catch-up after downtime much faster.

0.4.11
------

- SV and Validator apps

  - Add a randomized delay to broadcasting of package vetting changes used on Daml upgrades. This ensures that
    there is no load spike when all validators try to do so at the same time. This has no impact on behavior as
    Daml upgrades are announced ahead of time and the broadcasting still happens before the switchover.

  - The CometBFT PVC is now annotated with ``helm.sh/resource-policy: keep``, so that in the event of a (potentially accidental)
    ``helm uninstall`` the CometBFT data is not deleted and the node can more easily be recovered.

- Docs

  - Mark the workflows in the ``splice-wallet-payments`` :ref:`package <reference_docs_splice_wallet_payments>` as **deprecated**, and recommend using the Canton Network Token Standard APIs instead.
  - Mark the :ref:`Splice Wallet transfer offers <validator-api-user-wallet-transfer-offers>` as **deprecated**, and recommend using the Canton Network Token Standard APIs instead.

0.4.10
------

- SV Application

  - Fully remove the automation and logic around DSO delegate elections.
  - UI enhancements.

- Daml

  - Deprecate Daml choices related to DSO delegate elections.
  - Implements `CIP-0068 - Bootstrap network from non-zero round <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0068/cip-0068.md>`_
    Now the first SV can specify a non-zero initial round that can be used on network initialization or resets.

     These Daml changes requires an upgrade to the following Daml versions:

     ================== =======
     name               version
     ================== =======
     amulet             0.1.13
     amuletNameService  0.1.13
     dsoGovernance      0.1.17
     validatorLifecycle 0.1.5
     wallet             0.1.13
     walletPayments     0.1.13
     ================== =======

- Helm

  - The `splice-istio-gateway` Helm chart has been deprecated, and will be removed in a future release.
    It has been replaced with explicit instructions in the :ref:`validator docs <helm-validator-ingress>`
    and :ref:`SV docs <helm-sv-ingress>` on how to set up Istio ingress for the validator and SV nodes.

- Docs

  - Add section on :ref:`disabling BFT sequencer connections for SV participants <helm-sv-bft-sequencer-connections>`.

- Stability improvements

  - Add circuit breaker functionality for ledger API command submissions in all splice apps;
    causes splice apps to pause attempting new command submissions if the synchronizer is overloaded.
  - Add rate-limiting to scan ``/acs/{party}`` endpoint.

0.4.9
-----

- SV Application

  - Status reports are now submitted every 2min rather than every
    1min. This has no impact other than on monitoring infrastructure
    so you may need to adjust some alerts to be slightly less
    aggressive.

- Canton

  - Fix an issue where topology transaction signatures where
    duplicated based on the actual signature as opposed to the public
    key of the signature. This caused transactions with thousands of
    signatures on DevNet due to an SV with KMS enabled using a non-deterministic
    signature scheme which slowed down onboarding of new nodes to an unusable level.

- Documentation

  - Clarified that the Daml API ``splice-token-burn-mint-v1`` is not part of the token standard, see :ref:`app_dev_daml_api`.

- Scan

  - Added basic rate limits to the HTTP APIs. There are configured by default to allow up to 200 req/s per endpoint. The values can be adjusted under the keys `canton.scan-apps.scan-app.parameters.rate-limiting`.


0.4.8
-----

- Deployment

  - Good-to-know but no changes needed: Added new helm values ``persistence.enablePgInitContainer`` and
    ``extraInitContainers`` allowing configuration around deployment init containers. So far this is implemented only
    for the validator and participant helm charts. The default values for these won't change your current deployment,
    so if uninterested you can safely ignore.

- SV Application

  - Add the ability to configure a different topology change delay for the synchronizer parameters and change the default to ``250ms``.
    This should have a slight impact on improving the performance of the sequencer.
    Until a majority of nodes upgrade to ``0.4.8`` the ``ReconcileDynamicSynchronizerParametersTrigger`` might produce warnings.

- Dashboards

  - Moved the acknowledgements section from the catchup dashboard to a dedicated dashboard in the ``canton`` folder.

- Istio Reference Ingress

  - Include in the ``splice-cluster-ingress-runbook`` helm chart an Istio local rate limit filter that adds basic rate limits to a subset of endpoints in Scan.
    This will be enabled by default if using the helm charts provided for Istio and the Scan ingress is enabled.
    If not using Istio, the included EnvoyFilter can be used as an inspiration to add rate limits.
    These rate limits will be expanded in the future to more endpoints.

- Canton

  Reduced the acknowledgement interval for participants, mediators and
  sequencers to 10 minutes. This has no impact other than on the
  acknowlegdement metrics exposed by the sequencer.

0.4.7
-----

Note: 0.4.6 had a bug and should be skipped in favor of 0.4.7 which
fixed a bug where the ``skipSynchronizerInitialization`` option could
still result in the SV app crashing if its mediator was unreachable
which can happen in certain cases when the sequencer is down.

- Info (new)

  - *important* This release contains a new helm chart "splice-info" which is supposed to be installed on all SV nodes and made publicly accessible.
    The new `info` endpoint provides:

    - Static information about network, sv, synchronizers, config digests of ip ranges and identities under ``https://info.sv.<YOUR_HOSTNAME>``.
    - Regularly updated (every minute) copy of DSO information under ``https://info.sv.<YOUR_HOSTNAME>/runtime/dso.json``.

    The relevant documentation is updated at :ref:`sv-helm`.

- Scan

  - Fix `bug #1252 <https://github.com/hyperledger-labs/splice/issues/1252>`_:
    populate the token metadata total supply using the aggregates used for closed rounds.
    The data used corresponds to the data served by the ``/v0/total-amulet-balance``
    endpoint in :ref:`app_dev_scan_api` for the latest closed round.
  - Fix `bug #1280 <https://github.com/hyperledger-labs/splice/pull/1280>`_:
    ``record_time`` in Scan API ``/updates`` is now right-padded to 6 digits (microseconds).

- Validator

  - Fix a bug where sweeps through transfer preapprovals failed with a
    ``CONTRACT_NOT_FOUND`` error if the transfer preapproval provider
    party (usually the validator operator) of the receiver is featured.

- Splice

  - Building the Splice repo, and running the vast majority of integration tests locally, no longer requires
    JFrog access.

- SV

  - Added a ``domain.skipInitialization`` helm value that can be set for nodes that have already been onboarded and allows the SV app
    to start without the sequencer being up. This is useful for long-running sequencer database migrations.

  - Retired deprecated code for old Daml choices ``AmuletRules_AddFutureAmuletConfigSchedule``, ``AmuletRules_RemoveFutureAmuletConfigSchedule`` and ``AmuletRules_UpdateFutureAmuletConfigSchedule``

- Sequencer

  - Fix a sequential scan in a pruning query. This requires a
    long-running sequencer database migration (expected around an hour
    on mainnet). Make sure to set ``domain.skipInitialization`` on the
    SV app so the rest of your SV node can continue functioning. The
    liveness probe of the sequencer will fail during the migration so
    make sure to temporarily bump ``livenessProbeInitialDelaySeconds``
    and reduce it back to the default after the migration is
    complete. Otherwise the liveness probe will kill the sequencer and
    the migration will never complete.

- Participant

  - Fix an issue in sequencer BFT connections where the node got
    completely disconnected on certain failures even if only one
    sequencer reported those failures.

- Daml

  - Deprecated Daml choices ``AmuletRules_AddFutureAmuletConfigSchedule``, ``AmuletRules_RemoveFutureAmuletConfigSchedule`` and ``AmuletRules_UpdateFutureAmuletConfigSchedule``

    * This requires a Daml upgrade to versions

          ================== =======
          name               version
          ================== =======
          amulet             0.1.12
          amuletNameService  0.1.12
          dsoGovernance      0.1.16
          validatorLifecycle 0.1.5
          wallet             0.1.12
          walletPayments     0.1.12
          ================== =======

0.4.5
-----

- SV

  - *breaking* SV participants now enable sequencer BFT connections
    for the SV participant by default.  You must remove the
    ``useSequencerConnectionsFromScan: false`` config and the
    ``decentralizedSynchronizerUrl`` config from your SV helm values.
    If needed, the previous behavior can be restored by setting those two variables again
    as well as the following configs (through ``ADDITIONAL_CONFIG_*`` environment variables for validator app and SV app respectively:
    ``canton.validator-apps.validator_backend.disable-sv-validator-bft-sequencer-connection = true``
    ``canton.sv-apps.sv.bft-sequencer-connection = false``

  - The extra beneficiaries weight config has been fixed to accept integer values.
    The string values for weight have been deprecated and will be removed in future releases.
    It is recommended to fix the config as per this example, the previous config::

        extraBeneficiaries:
          - beneficiary: "BENEFICIARY_1_PARTY_ID"
            weight: "1000"

    changes to::

        extraBeneficiaries:
          - beneficiary: "BENEFICIARY_1_PARTY_ID"
            weight: 1000

    Thanks to Divam Narula for contributing this change
    in https://github.com/hyperledger-labs/splice/pull/1371

- Daml

  - security: change ``AmuletRules_Transfer`` and ``AmuletRules_ComputeFees`` to take an explicit argument
    ``expectedDso : Optional Party`` and check that against the ``dso`` party value in ``AmuletRules``.
    This value must be provided, and thus protects people that delegate calls to these choices from
    unintentionally allowing calls to ``AmuletRules`` contracts with a different ``dso`` party.

    This addresses suggestion S-8 reported by Quantstamp in their security review.

    Application developers that call these choices directly must adjust their call-sites to set the
    the ``expectedDso`` value. All calls to these choices from within the splice codebase have been
    adapted.

  - security: apply the spirit of suggestion S-8 to all non-DevNet choices on ``AmuletRules`` and ``ExternalAmuletRules``
    granted to users. Concretely, we added the ``expectedDso`` party as a required argument to
    ``AmuletRules_BuyMemberTraffic``,
    ``AmuletRules_CreateExternalPartySetupProposal``,
    ``AmuletRules_CreateTransferPreapproval``, and
    ``ExternalPartyAmuletRules_CreateTransferCommand``.

    Ledger API clients calling these choices should set that value to the ``dso`` party-id of
    the network they are operating on. They can retrieve that with BFT by calling ``GET /v0/scan-proxy/dso-party-id``
    on their validator's :ref:`validator-api-scan-proxy`.

    Third-party Daml code calling these choices should set it based on the ``dso`` party that the third-party
    workflow was started with. All calls to these choices from within the splice codebase have been
    adapted.

  - security: add a missing check that the actor is a current SV party to ``DsoRules_ExpireSubscription``

  - prudent engineering: enforce on calls to ``ExternalPartyAmuletRules_CreateTransferCommand`` that ``expiresAt``
    is in the future

  - prudent engineering: change all splice Daml code to fetch all reference data
    using checked fetches where the caller specifies the expected ``dso`` party

  These Daml changes require an upgrade to the following Daml versions:

   ================== =======
   name               version
   ================== =======
   amulet             0.1.11
   amuletNameService  0.1.11
   dsoGovernance      0.1.15
   wallet             0.1.11
   walletPayments     0.1.11
   ================== =======

0.4.4
-----

- Daml

  This release contains two sets of Daml changes that build upon each other:

  1. Implement `CIP-0064 - Delegateless Automation <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0064/cip-0064.md>`_

     These Daml changes requires an upgrade to the following Daml versions:

     ================== =======
     name               version
     ================== =======
     amulet             0.1.9
     amuletNameService  0.1.9
     dsoGovernance      0.1.13
     validatorLifecycle 0.1.3
     wallet             0.1.9
     walletPayments     0.1.9
     ================== =======

  2. Implement `CIP-0066 - Mint Canton Coin from Unminted/Unclaimed Pool <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0066/cip-0066.md>`_ and fix security issues
     and suggestions raised by Quantstamp as part of their `audit of the Splice codebase <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0057/cip-0057.md#abstract>`_.
     Note that the backend and frontend changes from CIP 66 are not yet implemented so we recommend holding off on upgrading to the new Daml models for now.

      - CC-1 (low severity): addressed by rate limiting every SV wrt casting votes on a ``VoteRequest`` and updating their ``AmuletPriceVote``
        to defend against them causing undue contention, which would block other SVs from
        voting, closing the vote, or advancing the mining rounds.

        This change introduces a new config value ``voteCooldownTime`` in
        the ``DsoRules`` configuration that defines the cooldown time between
        votes of the same SV. If not set, then the default value is 1 minute.

      - CC-2 (low severity): addressed by enabling delegateless automation from CIP-0064 by default

      - CC-4 (low severity): addressed by

        - checking that ``expiresAt`` is in the future in the choice body of
          ``DsoRules_ExecuteConfirmedAction``, ``DsoRules_AddConfirmedSv``, and ``ValidatorOnboarding_Match``.

      - CC-5 (low severity): addressed by

        - requiring steps of a valid ``SteppedRate`` to be strictly ascending
        - enforcing this validation on the ``transferFee`` in ``AmuletConfig``
        - failing ``chargeSteppedRate`` if a negative step is found

      - S-2 (auditor suggestion): addressed by

        - adding basic validation for all fields of ``AmuletConfig`` to reduce the risk of misconfigurations
        - restricting the choice ``AmuletRules_Mint`` to only be called in DevNet setups
        - properly handling the edge case of amulet that expired when checking whether a lock expires before an amulet
          in the ``doesLockExpireBeforeAmulet`` function
        - checking that ``createdAt`` and ``ratePerRound`` of an ``ExpiringAmount`` are positive;
          and enforcing that check in the ``expiringAmount`` smart constructor
        - checking that the ``validatorRewardPercentage`` and the ``appRewardPercentage`` in a valid
          ``IssuanceConfig`` are non-negative and do not exceed 100%
        - changing the ``ensure`` clause of ``MemberTraffic`` to enforce non-empty ``memberId`` and ``synchronizerId`` fields
        - enforcing a length limit of 280 characters on the ``trackingId`` of ``TransferOffer``
          as a prudent engineering measure

      - S-3 (auditor suggestion): addressed by

        - calling ``FeaturedAppRight_Withdraw`` in the implementation of ``DsoRules_RevokeFeaturedAppRight``
        - calling ``Confirmation_Expire`` in the implementation of ``DsoRules_ExpireStaleConfirmation``

      - S-7 (auditor suggestion): addressed by checking the ``dso`` party whenever
        executing a confirmed action.

      - S-8 (auditor suggestion): addressed by

        - checking the expected ``dso`` party on all calls to the helper methods
          ``exerciseAppTransfer``, ``exercisePaymentTransfer``, and ``exerciseComputeFees``
          to safe-guard against a delegee providing an unexpected ``AmuletRules`` contract from an ``AmuletRules`` contract
          with a ``dso`` party under their control
        - adding deprecation markers to the

           - ``ValidatorFaucetCoupon`` template
           - ``AmuletRules_AddFutureAmuletConfigSchedule``, ``AmuletRules_RemoveFutureAmuletConfigSchedule``, ``AmuletRules_UpdateFutureAmuletConfigSchedule`` choices
             that are deprecated in favor using a ``CRARC_SetConfig`` governance vote with effective dating
           - ``DsoRules_RequestElection``, ``DsoRules_ElectDsoDelegate``, and ``DsoRules_ArchiveOutdatedElectionRequest`` choices
             that are deprecated in favor of delegateless automation

        - clarifying that the ``amuletRulesCid`` parameter of ``DsoRules_AddConfirmedSv`` is a historical artifact


        These Daml changes requires an upgrade to the following Daml versions:

        ================== =======
        name               version
        ================== =======
        amulet             0.1.10
        amuletNameService  0.1.10
        dsoGovernance      0.1.14
        validatorLifecycle 0.1.4
        wallet             0.1.10
        walletPayments     0.1.10
        ================== =======

- SV

  - The actual delegate-based triggers inheriting from SvTaskBasedTrigger are modified so that they implement
    the changes described in the delegateless automation CIP once the new dsoGovernance DAR is vetted.
  - The Delegate Election page in the SV UI is removed automatically once the new dsoGovernance DAR implementing the delegateless automation CIP is vetted.

- Scan

  - Fix a `bug (#1254) <https://github.com/hyperledger-labs/splice/issues/1254>`_ where the token metadata name and acronym for Amulet were not populated
    based on the ``splice-instance-names`` config.

- Validator

  - **Breaking**: The validator app now enforces that the traffic
    topup interval is >= the automation polling interval (30s by
    default). Previously it implicitly rounded up if the topup
    interval was smaller which caused confusion on how much traffic is
    purchased each time. If your topup interval was >= 30s you are not
    affected. If you are affected, set the topup interval to the
    polling interval (30s unless changed) to recover the prior
    behavior.

- Docs

  - Improve the :ref:`application development documentation <app_dev_overview>` to better explain the available APIs and how to use them.
  - Add relevant links to the new application developer documentation pages published by Digital Asset at
    https://docs.digitalasset.com/build/3.4/.
  - Fixed docker-compose docs around migrating from a non-authenticated validator to
    an authenticated validator. A complete wipe of the validator database is not required, as
    opposed to what the docs previously stated. See the relevant section on :ref:`authenticated
    docker-compose validators <compose_validator_auth>`.





0.4.3
-----

- Validator

  - Fix a `bug (#1216) <https://github.com/hyperledger-labs/splice/issues/1216>`_ where sends through transfer preapprovals failed with a ``CONTRACT_NOT_FOUND`` ERROR
    if the receiver's provider party was featured.
  - Fix a bug where uploading dars would not immediately vet the dependencies that had a vetting entry effective in the future.
  - Fix a `bug (#1215)  <https://github.com/hyperledger-labs/splice/issues/1215>`_ where wallet transaction could get stuck when creating transfer offers from the wallet UI.

- Synchronizer Migrations

  - Fix a rare bug where a crash of the validator or SV while trying
    to restore the data after a migration could result in an
    inconsistent state being restore.

0.4.2
-----

- SV

  - Add official support for :ref:`operating an SV participant with keys managed by an external Key Management Service (KMS) <sv-kms>`.

- Deployment

  - Fix a typo in the `splice-participant` Helm chart that caused the participant container to be named `participant-1` instead of `participant`.
  - Java 21 replaces Java 17 in all Docker images and as the base JDK for building Splice apps.

- Scan

  - Fix a bug where the ``/v0/holdings/summary`` endpoint would return incomplete results when the requested parties had more than 1000 holdings.
    Additionally, that endpoint and ``/v0/holdings/state`` will now fail if an empty list of parties is provided.
  - ``/v2/updates`` endpoints are now available on the Scan app, ``/v1/updates`` endpoints are deprecated.
    The ``/v2/updates`` endpoints no longer return the ``offset`` field in responses,
    and ``events_by_id`` are now lexicographically ordered by ID for conveniently viewing JSON results.

- Mediator

  - Fix an issue where the mediator sometimes got stuck after initialization and required a restart to recover.

- Validator

  - docker-compose, breaking: Restoration from identities dump requires to
    specify path to `identities.json` and not directory containing it. This is
    consistent with the :ref:`documented
    <validator_disaster_recovery-docker-compose-deployment>` behavior.  See
    `#387 <https://github.com/hyperledger-labs/splice/pull/387>`_

- Auth

  - Added an option to override the default connection and read timeouts for the JWKS URL when using ``auth.algorithm="rs-256"``.

0.4.1
-----

- Validator

  - Expose token-standard endpoints on the validator scan-proxy. The paths are the normal token standard path with a ``/api/validator/v0/scan-proxy`` prefix.
  - Fix a bug where transfers using transfer pre-approvals (both through the wallet UI and automatic via sweeps) were broken until the DARs released in 0.4.0 are effective.
  - Fix a bug that requires the latest dars to be uploaded when `re-onboarding a validator and recovering the balances of all the users <https://dev.global.canton.network.digitalasset.com/validator_operator/validator_disaster_recovery.html#re-onboard-a-validator-and-recover-balances-of-all-users-it-hosts>`_

- Sequencer

  - Improve sequencer startup time by fixing a slow query.

- Define `standard k8s labels <https://helm.sh/docs/chart_best_practices/labels/#standard-labels>`_
  for most k8s resources deployed through Splice Helm charts.
  Thanks to Stephane Loeuillet for contributing an initial proposal for this change
  in https://github.com/hyperledger-labs/splice/pull/296.

- Scan

  - Backfilling of all Scan data is now enabled by default.

0.4.0
-----

.. important::

    - Upgrade to Canton 3.3: This upgrade requires a Hard Synchronizer migration and cannot be applied
      through a regular helm upgrade. For details refer to the `CIP draft <https://github.com/global-synchronizer-foundation/cips/pull/66>`_.

- Daml

  - Implement `CIP 47 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0047/cip-0047.md>`_ and
    `CIP 56 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md>`_.

    This requires an upgrade to the following Daml versions:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.9
    amuletNameService  0.1.9
    dsoGovernance      0.1.12
    validatorLifecycle 0.1.3
    wallet             0.1.9
    walletPayments     0.1.9
    ================== =======

- Deployment

  - This release includes a change to the database schema that will trigger a short database migration.
    On DevNet and MainNet, the migration is expected to take 2min for scan applications,
    and much less for validator and sv applications.

  - Add jemalloc into the docker images. This is not enabled by
    default but allows for easier testing. Thanks to Stanislav
    German-Evtushenko for contributing this in
    https://github.com/hyperledger-labs/splice/pull/318

- Validator

  - Fix an issue where the automation for completing ``TransferCommand`` failed
    if the provider had a featured app right.

  - Fixes and stability improvements for the :ref:`validator re-onboarding <validator_reonboard>` flow.
    Among other things:

    - Recovery of standard local parties is now automatically possible even if the validator is hosting external parties.
    - It is now possible to force the recovery attempt for a party that was skipped during the fully automated recovery flow.

  - Improve the error message when trying to use the wallet outside of
    localhost or https. Thanks to Stephane Loeuillet for contributing
    this in https://github.com/hyperledger-labs/splice/pull/322.

- Scan

  - Scan now implements some Token Standard endpoints that are under the ``/registry`` path.
    ``https://scan.sv.<YOUR_HOSTNAME>/registry`` should be routed to ``/registry`` at port 5012 in service ``scan-app`` in the ``sv`` namespace,
    the same way that ``/api/scan`` already is.

0.3.21
------

.. important::

    * This release includes a change to the database schema that will trigger a long database migration
      of the scan and validator app databases, resulting in increased downtime of SV nodes,
      and to a much lesser extent the validator nodes.

      The migration will be triggered the first time an application is started after the version upgrade,
      and will leave the application in an unavailable state until the migration is finished.
      It is expected to take up to 1:30h for SV nodes and less than 10min for validator nodes on MainNet.
      The migration is expected to take significantly less time on DevNet and TestNet due to the recent resets of these networks.
      Note that even after the database migration completed,
      you might observe an additional (shorter) period of downtime for scan (and only scan) due to Postgres autovacuuming.

      The following points are essential for a successful migration:

      * Make sure to upgrade all apps in parallel (i.e., the scan app, validator app, and sv app for SV nodes)
      * Make sure you have at least 50% free disk space on the database volume, or set it to expand automatically
        (the migration will consume a significant amount of temporary disk space).
      * Make sure you the `temp_file_limit <https://www.postgresql.org/docs/current/runtime-config-resource.html#GUC-TEMP-FILE-LIMIT>`_
        Postgres parameter is set to a sufficiently high number.
        The actual usage is hard to predict, so we recommend setting it to the maximum value for the duration of the migration.

      Additionally, consider the following actions to reduce your downtime due to the migration:

      * For the duration of the migration, pause any non-essential services accessing the database
        (e.g., a postgres exporter pushing database metrics to grafana).
      * For the duration of the migration, increase the hardware configuration
        (upgrading from 2 CPUs / 8GB RAM to 8 CPUs / 32 GB RAM lowered the duration by ~20%).
      * The first Postgres autovacuum after migration is expected to be significantly slower than usual
        vacuum runs. In case autovacuum doesn't trigger shortly after the migration, you might want
        to trigger a vacuum on your app databases manually to have better control over the
        additional potential downtime for scan.

- Deployments

  - Validator, app and scan support specifying a scope when requesting the token from the participant.
    This enables use of IAMs that make the scope parameter mandatory.

- Frontends

  - The Wallet and Scan UIs now show the Update ID of every transaction. These IDs are consistent with those
    used in the `updates` endpoints of the Scan API.
  - Wallet UI: Add a logout button to the "Loading" and "Logged in but not onboarded" states to enable recovering
    from all types of login failures.

0.3.20
------

- Performance

  - Improved the performance of ACS snapshot generation

- Frontends

  - Relax config validation on audience to not require that it is a URL as this causes issues with some IAMs.

- API

  - interdependencies in the Open API specs are now inlined in every yaml file,
    so that the files can be used independently of each other (and no longer incorrectly reference the common.yaml file in the bundle).

- Deployment

  - The ``splice-util-lib``` helm chart is no longer published.
    The library has always been packaged with every helm chart that uses it,
    there is no need to pull it separately from the ghcr.io container registry.

- Implement `Canton Improvement Proposal cip-0051 <https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0051/cip-0051.md>`_

    * Added the optional `targetEffectiveAt` field to the `VoteRequest` template, which allows specifying an effective date and time for the vote request.
      Additionally, the `DsoRules_CloseVoteRequest` now enforces the new semantics for vote requests that include an effective date and time.

    * These changes will take full effect once all SVs upgrade to the new DAML package version and corresponding frontend and backend updates.

    * New Vote Request Semantics:

        * Vote Requests with an Effective Date-Time (``targetEffectiveAt != None``):
            * **Voting Period Starts (now < voteBefore):**
                * Early closing occurs if a super-majority of SVs rejects the vote request.
            * **Voting Period Ends (now ≥ voteBefore and now < targetEffectiveAt):**
                * If a super-majority has voted, the vote request remains open and SVs can still change their votes.
                * If not, the vote request is marked as expired.
                * Early closing occurs upon a super-majority of rejections after expiration.
            * **Effective Date-Time Reached (now ≥ targetEffectiveAt):**
                * If a super-majority accepts the vote request, the change takes effect.
                * Otherwise, the vote request is registered as rejected.
        * Vote Requests Without an Effective Date-Time (``targetEffectiveAt = None``):
            * **Voting Period Starts (now < voteBefore):**
                * Early closing occurs when a super-majority rejects.
                * Early acceptance occurs when a super-majority agrees.
            * **Voting Period Ends (now ≥ voteBefore):**
                * The vote request is marked as expired.

    * The Daml changes in this release require a governance vote to upgrade the package configs to:

    * Introducing `CRARC_SetConfig` choice in favor of `CRARC_AddFutureAmuletConfigSchedule`, `CRARC_AddUpdateAmuletConfigSchedule` and `CRARC_AddRemoveAmuletConfigSchedule`

    * The new action `CRARC_SetConfig` allows the SV to set the configuration of AmuletRules configuration in the same way of `SRARC_SetConfig`. This action is only available when the new dars below are vetted.

    * Handling parallel proposals

        * Before: concurrent editing proposals (`CRARC_SetConfig`  and `SRARC_SetConfig`) risked overwriting new changes with outdated values because the entire new configuration replaced the old one, regardless of the specific changes.

        * Now: concurrent editing proposals (`CRARC_SetConfig`  and `SRARC_SetConfig`) apply only to the fields that were intented to be changed. A copy of the current configuration is passed along the modified configuration at the creation of a proposal.

    * Governance: new dars

          ================== =======
          name               version
          ================== =======
          amulet             0.1.8
          amuletNameService  0.1.8
          dsoGovernance      0.1.11
          validatorLifecycle 0.1.2
          wallet             0.1.8
          walletPayments     0.1.8
          ================== =======
- CometBFT

   - Updated CometBFT to `0.37.15 <https://github.com/cometbft/cometbft/blob/v0.37.15/CHANGELOG.md?rgh-link-date=2025-04-03T08%3A37%3A21.000Z#v03715>`_

0.3.19
------

* Stability improvements

0.3.18
------

* Scan

  * ``scan_txlog.py`` will safely save its cache specified with ``--cache-file-path``.
    A failed run will always revert to the prior cache, such as if the disk ran out of space while cache was being written.

* Docs

  * SV and validator ingress: Clarify that all traffic not explicitly allowed as per the docs should be blocked for security reasons.
  * Clarify that the GCP and AWS KMS drivers are available only for licensed users of Canton Enterprise.

0.3.17
------

.. important::

    * This release fixes an issue where the Validator app would uploads dars before being vetted. This can result in ledger API command submissions that target those DARs directly (as opposed to a third-party DAR that depends on them) breaking.
       If you are upgrading from 0.3.15, please upgrade directly to 0.3.17. If you don't submit any commands directly against the ledger API (as opposed to the validator APIs) for the amulet DARs you are not affected.

* Docs

  * Update documentation on configuring SV egress.
  * Add note about ``.localhost`` addresses used by Docker Compose-based validator deployments.

0.3.16
------

* SV and validator apps

  * The SV and validator apps now preserve participant-local user state across synchronizer upgrades with downtime.
    More specifically, SVs and validators now preserve identity provider configs and users with all state attached to them (including, for example, rights and metadata annotations).

* Scan

  * The Scan API in scan-internal.yaml and scan-external.yaml have been merged into one scan.yaml file. Deprecated endpoints are marked with ``deprecated: true``.

* Deployment

  * Make synchronizer migration PVC names configurable through ``pvc.volumeName``. Thanks to Stéphane Loeuillet for contributing this in https://github.com/digital-asset/decentralized-canton-sync/pull/338

0.3.15
------

.. important::

    * This release fixes a Scan backfilling regression introduced in 0.3.14. Please skip 0.3.14 and upgrade directly to 0.3.15.

* Deployment

  * Change the port used by nginx in the UI docker images from 80 to 8080.

    The services defined by the helm charts still expose port 80 by default, but now all of them are configurable through the helm values, eg: the validator helm chart has new values configured through `service.wallet.port` & `service.ans.port`.

    The compose deployments contain an updated nginx.conf that now uses the new 8080 ports.

  * Move ``topup`` section from the ``validator-values.yaml`` example file to the ``standalone-validator.yaml`` example file
    to make it more clear that configuring topups is a reasonable option only for non-SV validators.
    See `hyperledger-labs/splice#255 <https://github.com/hyperledger-labs/splice/pull/255>`_

  * Added the ``initialAmuletPrice`` helm option to set the initial amulet price vote (i.e., the price for which your SV node will vote when onboarded).
    See the :ref:`configuration instructions <helm-configure-global-domain>`.
    Note that this only takes effect for new nodes. For already existing nodes, change the price vote through the SV UI.

* Validator

  * Added the option to specify multiple ``validatorWalletUsers`` in the validator helm charts. The existing ``validatorWalletUser`` option is
    still supported.

* Docs

  * Added documentation for managing network resets for validators and super validators.

0.3.13
------

* Docs

  * Add documentation about :ref:`traffic`.
  * Add documentation about :ref:`computing total burnt coin <total_burn>`.
  * Enable commenting on doc pages.

* Config changes

  * Increased the time before a participant retries a sequencer submission back to 10 seconds (from 5 seconds). This ensures we're not too aggressive in
    retrying, thus leading to traffic waste.

0.3.12
------

* Docs

  * Add :ref:`SV pruning <sv-pruning>` section.
  * Add historical :ref:`backups <sv_backups>` section to the SV docs.
  * Add historical :ref:`backups <validator-backups>` section to the Validator docs.

* Performance

  * Updated table definitions in Scan to improve performance of ``/transactions`` and ``/activities`` endpoints.
    This requires a SQL migration that will run on app startup for ~15m on devnet and ~2m on mainnet according to our tests.

* Deployment

  * Add OCI annotations to provide standardized information attached to a Docker image. Details provided are image name, image version,
    creation date, base image, repository, and commit hash.
  * Fix an issue in the SV helm chart where the resource section was omitted if ``attachPvc`` was set to ``false``.
    See https://github.com/digital-asset/decentralized-canton-sync/issues/299
  * Add a new ``serviceAccountName`` value to all Splice Helm charts to allow specifying a custom service account for deployed pods.
  * Increased the size of the caches and the mempool for CometBFT in an effort to try to improve it's performance under load

0.3.11
------

* Validator

  * Add an option to enable :ref:`participant pruning <validator_participant_pruning>`.

* Observability

  * Add a dashboard for sequencer client metrics.

* Docs

  * Extend :ref:`Scan API docs <app_dev_scan_api>` docs.
  * Various smaller documentation updates and improvements.

0.3.10
------

* Validator app

  Add support for :ref:`operating a validator participant with keys stored in an external Key Management Service (KMS) <validator-kms>`.

* Metrics

  Added ``splice_store_last_ingested_record_time_ms`` metric for the last ingested record time in each store and an
  associated dashboard. This can be used to track general activity of the node.

* Docs

  * Add :ref:`Troubleshooting <troubleshooting>` section.
  * Add overview docs for the :ref:`Validator Onboarding Process <validator_onboarding_process>`.
  * Add docs for :ref:`Getting console access to Canton nodes <console_access>`.
  * Add docs for :ref:`Configuring deployed apps <configuration>`.
  * Add docs for :ref:`Validator Ingress & Egress requirements <validator_network>`.
  * Add overview docs about :ref:`Metrics <metrics>`.
  * Add overview docs about :ref:`Application Development <app_dev_overview>`.
  * Improve API docs.
  * Various smaller documentation updates and improvements.

* SV UI

  Various improvements to the SV UI.

0.3.9
-----

* SV UI

  * Add better spacing between items and alerts/badges in navigation bar

* Docs

  * Added a section on hardware requirements to the validator docs.
  * Improved the docs around required network parameters for starting a new validator.
  * Added network diagrams of SVs and validators.
  * Added initial docs on how to access metrics for validators and SVs.

0.3.8
-----

* Fixes to documentation and scripts around using the publicly available images and Helm charts

0.3.7
-----

* Deployment

  * When recovering a validator from an identities dump
    ``nodeIdentifier`` must now match
    ``newParticipantIdentifier``. This was already a requirement when
    ``newParticipantIdentifier`` was removed again after the restore
    was complete so this just catches misconfigurations earlier.
  * In the docker-compose start script, the migration id is now a
    mandatory argument instead of defaulting to 0. This should not
    require any changes as no network is on migration id 0 at the
    moment so you must already have it set.
  * Release versions of docker images and helm charts are now publicly available respectively from
    Github Container Registry at
    ghcr.io/digital-asset/decentralized-canton-sync/docker and ghcr.io/digital-asset/decentralized-canton-sync/helm.
    No credentials are required to download these release artifacts. The default `imageRepo` value in helm charts has been updated to ghcr.io/digital-asset/decentralized-canton-sync/docker.

0.3.6
-----

* Validator app

    * The wallet sweep automation now supports sweeping to end user parties.
    * Fix a bug where the validator operator was unable to preapproval incoming transfers
      if a user on the same validator preapproved incoming transfers first.

* SV app

    * Onboarding secrets now encode the sponsoring SV party to provide
      better error messages in case a secret is used to onboard
      against an SV that did not issue it. Secrets are still just
      opaque strings so no change is required.

* Wallet UI

  * Added a confirmation dialog when enabling preapproval of incoming direct transfers.

* Deployment

  * The release bundle has been removed again from the docs image. The docs instead link to
    the release bundles publicly available on the OSS GitHub repo.

* CometBFT

  * The CometBFT version has been updated to 0.37.13. No change should be required from SV operators.

0.3.5
-----

* Scan

  * Added new metrics for the Scan app to monitor the ingestion of transactions and contract reassignments into the update history.

* Deployment

  * The setting ``spliceDomainNames.nameServiceDomain`` must now be supplied for the ``splice-cluster-ingress-runbook`` helm chart.
    See the ``sv-helm`` example.

  * Added a new Grafana dashboard for monitoring utilization of the Global Synchronizer, currently estimated by comparing the total number
    of transactions processed to those visible to the DSO party. The larger this delta is, the more likely it is that the Global Synchronizer is
    used for private transactions beyond those needed for operating the synchronizer itself.

  * The docs image expects a new environment variable ``SPLICE_CLUSTER``. In production, that would be one of ``dev``, ``test`` or ``main``.
    The cn-docs Helm chart takes this value from the ``networkName`` Helm value.

* Metrics

  * All metrics named starting with ``cn_`` now start with ``splice_`` instead.
    Example Grafana configuration has been updated to match, but any custom consumers of these metrics must be updated manually.

* Daml

  * Restructured the Daml code of AmuletRules_BuyMemberTraffic to
    avoid an intermediate transfer to the DSO party before the amulets
    were burned. There is no change in the amount that gets burned or
    the rewards are issued, just a slight change in the transaction
    structure to accomplish this.

    This requires an upgrade to the following Daml versions:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.7
    amuletNameService  0.1.7
    dsoGovernance      0.1.10
    validatorLifecycle 0.1.1
    wallet             0.1.7
    walletPayments     0.1.7
    ================== =======


0.3.4
-----

* SV UI

  * Switch to ``YYYY-MM-DD``-based date formatting and 24h-based time formatting.

* Deployment

  * The release bundle is now included in the docs image, for easier hosting by the GSF.
  * Add a new ``jsonApiServerPathPrefix`` value to the participant helm chart that allows setting a path prefix for JSON API endpoints,
    to simplify configuring ingress routing to the participant JSON API.

* Stability improvements

0.3.3
-----

* All UIs (except the experimental app manager and splitwell UIs)

  * Added the ``openid`` scope to their authorization requests to comply with the `OpenID Connect specification <https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest>`_.

* Scan

  * Scan instances will now run a background process that replicates the history of the network from before their SV node joined.
    This affects data returned by the ``/v1/updates`` endpoints, missing data for other API endpoints (such as ``/v0/transactions``)
    will be backfilled in a future update.
    This one-time process is expected to take up to a few days to complete, depending on the size of the missing history.
    During this time, scan instances will consume slightly more compute and networking resources than usual,
    and the ``/v1/updates`` endpoint will return an error until the replication has finished on that particular instance.
    Progress on your own scan instance can be monitored through the ``cn_history_backfilling_*`` metrics.
  * The `/v1/updates` endpoint now excludes updates resulting from ACS imports.
    This change was already mentioned in the 0.2.5 release notes, but due to a bug was not actually implemented until now.

0.3.2
-----

* Bugfixes

  * Fix JSON API bug that was causing UNAUTHENTICATED responses on calls to GetLedgerEnd


0.3.1
-----

.. important::

    * This release fixes an upgrading-related bug in 0.3.0.
      Please skip 0.3.0 and upgrade directly to 0.3.1 through the :ref:`Synchronizer Upgrade with Downtime <sv-upgrades>` procedure.

* Bugfixes

  * Fix Canton topology import issue that can cause synchronizer upgrades with downtime to fail on some networks.

* Deployment

  * Make the wallet sweep config value `use-transfer-preapproval` optional in the validator helm chart with a default of `false`.

0.3.0
-----

.. important::

    * **Daml recompilation may be required:** this release changes the definition of the ``AmuletRules`` template arguments,
      as it introduces a new optional config value called ``transferPreapprovalFee`` (see :ref:`daml_changes_0.3.0`).
      If your Daml code depends on ``splice-amulet`` < ``0.1.6``, then you **must
      recompile** and redeploy it after the network was upgraded to ``splice-amulet-0.1.6`` and
      before the SVs change this optional config value away from its default value.
    * This release must be applied through the :ref:`Synchronizer Upgrade with Downtime <sv-upgrades>` procedure.

* Canton

  This release upgrades from Canton 3.1 to Canton 3.2.
  In addition to stability improvements, the primary change is adding support for externally hosted parties, which enables supporting Amulet custody.

* Validator App, Scan App

  * Add support for Amulet custody.
  * Fixed a bug where BFT scan calls would fail even though enough remote scan connections are available. This would happen if roughly a third of the SV nodes are offline.

* Wallet UI

  * Support for non-external parties to exchange amulets with externally hosted ones via pre-approved transfers.

* SV UI

  * The SV UI now shows a confirmation dialog when creating a Vote Request or Voting.

* Deployment

  * An optional value ``uiPollInterval`` has been added to the Helm charts for ``splice-scan``, ``splice-sv-node``, and ``splice-validator``. This value allows you to configure the interval at which the deployed UIs poll the services for updates in milliseconds. If unspecified, the default value is 1000 (1 second).
  * The log field ``labels."k8s-pod/cn-component"`` has been renamed to ``labels."k8s-pod/splice-component"``.

* Security

  * Fix a Canton node initialization issue that caused newly initialized participants, mediators, and sequencers to reuse their root namespace signing key for all signing purposes. Upgrading to this release will also fix the key usage on all validators and SVs that were originally onboarded on an affected version (versions 0.2.4 to 0.2.8), generating fresh signing keys for affected Canton nodes.

* Docs

  * Added a new section to the Validator documentation on how to share the operator wallet with multiple users. See :ref:`validator-users`.

  * Added a new subsection to Supervalidator documentation documenting the URL conventions agreed upon by the SV operators.

.. _daml_changes_0.3.0:

Daml Changes in 0.3.0
~~~~~~~~~~~~~~~~~~~~~

The Daml changes introduce support for the external signing of the keys of a Daml party.
Signatures required from these external parties can be collected via a crypto custodian's system, and
can involve multiple human confirmers. Transactions submitted in the name of these parties can thus take
multiple hours from the creation of the transaction signing request to the final commit of the transaction on the network.
This increased latency required several changes in the Daml models underlying Amulet.
They can be reviewed in detail by diffing the ``daml`` directory in the https://github.com/hyperledger-labs/splice
repo.

The key changes are summarized below:

  * Changes the existing ``AmuletRules`` template:

    * Add a new config field ``transferPreapprovalFee`` in the ``AmuletConfig`` stored in ``AmuletRules``.

      **Important:** once this field is set to ``Some value``, you can no longer call choices on ``AmuletRules``
      using Daml code built against a version before ``splice-amulet-0.1.6``! Please recompile and redistribute
      your Daml code once the SVs have upgraded to ``splice-amulet-0.1.6`` on your target network.
    * Add the choices ``AmuletRules_CreateTransferPreapproval`` and ``AmuletRules_CreateExternalPartySetupProposal``
      explained below.

  * New workflows and templates:

    * Introduce the ability for a party to declare to the network that they are OK with receiving incoming Amulet transfers
      from any party by creating a ``TransferPreapproval``. This is used by externally hosted parties to receive funds
      without having to actively confirm that they are OK to receive the funds.
      It must also be used by parties that want to receive funds from externally hosted parties,
      as external party wallets currently do not use the transfer offer workflow.
    * The ``TransferPreapproval`` contracts are expected to be created by the party’s crypto custodian, which pays the
      yearly maintenance fee. That fee is configurable via DSO vote and initially set to $1 per year.
      The payment itself happens by burning the corresponding amount of Amulet on purchase. In return for paying that fee,
      the crypto custodian is recorded as the app provider and validator operator on all Amulet transfers executed via the
      ``TransferPreapproval`` maintained by them.
    * A helper workflow called an ``ExternalPartySetupProposal`` has been added for crypto custody providers to set up
      both the ``TransferPreapproval`` and the ``ValidatorRight`` for an external party. The latter is required for
      claiming validator activity records. That workflow is initiated by the crypto custody provider calling the
      ``AmuletRules_CreateExternalPartySetupProposal`` choice.
    * Parties can also directly purchase a ``TransferPreapproval`` using ``AmuletRules_CreateTransferPreapproval`` choice.
    * Furthermore, parties are given the ability to delegate executing a Amulet transfer to a party of their choosing using the
      ``ExternalPartyAmuletRules_CreateTransferCommand``. We introduced this feature because the normal Amulet transfer
      transactions refer to the ``OpenMiningRound`` contracts, which are valid for at most 30 minutes
      (10 minutes of pre-announcement time, and 2 * 10 minutes of active time). This time is too short to accommodate
      the human-in-the-loop confirmation workflows of crypto custody providers, which in turn would result in failed
      transactions due to referencing a stale round contract.
    * The typical choice for the delegate is a normal party on the crypto custodians node. That party is expected to be
      online and submit the actual transfer as soon as the ``TransferCommand`` is visible. The input amulets for the transfer
      are selected by the delegate; and they are expected to select inputs that cover the required amount provided they exist.
      In case there are not enough funds the ``TransferCommand`` gets archived and marked as failed.
    * External parties creating multiple ``TransferCommands`` are protected from executing the same transfer twice using an
      Ethereum style nonce tracked by the DSO, which must be sequentially increasing for a transfer command to be executed.
      We expect the wallet of these parties to select the right nonce using information available from Amulet scan.
      Having multiple transfer commands in-flight is supported.
    * All transactions involving ``TransferCommands`` and ``TransferPreapprovals`` have the ``dso`` party as a signatory
      and are thus always validated by ⅔ of the SV nodes.


  * The Daml changes in this release require a governance vote to upgrade the package configs to:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.6
    amuletNameService  0.1.6
    dsoGovernance      0.1.9
    validatorLifecycle 0.1.1
    wallet             0.1.6
    walletPayments     0.1.6
    ================== =======


0.2.8
-----

* SV App

  * The query to fetch the vote results has been fixed for postgres 15.

* Sequencer

  * Fix an inefficient query when querying the onboarding snapshot for a new SV that tries to onboard.

0.2.7
-----

* Scan

  * Added new endpoints `/v1/updates` and `/v1/updates/{update_id}`. The updates endpoint returns all Daml transactions
    and also all contract reassignments. Both Daml transactions and contract reassignments can be made up of multiple
    smaller components: A single Daml transaction may be the top node of a tree of sub-transactions, and a contract
    reassignment may actually be a batch of many reassignments.

    Each Super Validator node assigns a unique counter, called an event ID, to each of the sub-transactions in the Daml
    transaction tree. Because there's not just one way to assign a counter to the elements of a tree, each Super Validator
    node gives different event IDs to the same elements of the transaction tree.

    This means that applications that want to compare updates from more than one Super Validator can't match their event IDs.
    So for the v1 version of these endpoints, we've added a method for tree node numbering in Scan, which consistently produces
    the same event ids on each tree node, when given the same tree structure.

    Applications that rely on an existing set of event IDs drawn from a single Super Validator may continue to use /v0/updates
    and /v0/updates/{update_id}. This will return the single-Super Validator set of event IDs that they've used up to now.
    Applications that want to compare the details of updates, including transaction trees and sub-transactions, across Super
    Validators can use the v1 version of these endpoints.

0.2.6
-----

Note: 0.2.5 was skipped as it introduced a regression where the splice apps hardcoded the wrong log level.

* Docs

  * Updated docs to include a section on how to create a standalone k8s-based Canton Network. This can be useful to test deployment changes, in particular for SVs. See :ref:`scratchnet`.

* SV UI

  * Configuration changes for AmuletRules and DsoRules are diffed against the configuration it will replace and the in-flights proposals.
    This makes it easier to see what changes are being proposed and what the current configuration is.

  * When creating validator onboarding secrets through the SV UI, they will now have an expiration time of 48 hours.

* Scan

  * Added endpoint `/v0/validators/validator-faucets` to query the validator faucet by validator party Ids.

  * Modified the `/v0/updates` and `/v0/updates/{update_id}` Scan API endpoints to make sure they consistently returns the same history across SVs:

    * The `/v0/updates` endpoint now fails on scans that have not yet replicated history from before their SV node joined the network.
    * The `/v0/updates` endpoint now excludes updates resulting from ACS imports (those with workflow id starting with ``canton-network-acs-import``).
    * Fix an issue where the ordering of stakeholders (signatories and observers) would be inconsistent across SVs
      when calling the `/v0/updates` and `/v0/updates/{update_id}` endpoints on the Scan API.
    * Fix a bug in `/v0/domains/{domain_id}/members/{member_id}/traffic-status`
      that resulted in the returned total purchased traffic value being incorrect after a hard migration.

* Add a new index to Splice application databases. Scan and validator apps might take a while to start after the upgrade.

* Canton

  * Enabled slow future logging for all components to better debug stuck nodes.
  * Added a max time of 10 minutes for processing of a sequenced event before the node crashes to get restarted.
    This mitigates cases where nodes might get stuck due to a bug and a restart recovers them.

* Deployment

  * **Breaking** Every Helm chart with a name starting with ``cn-`` has been renamed, now
    starting with ``splice-`` instead, except for ``cn-docs``.
  * **Breaking** The script token.py was renamed to get-token.py to avoid conflicting with some
    imported modules.
  * ``imagePullPolicy`` is now unset by default corresponding to ``IfNotPresent``.
    You can overwrite it using the helm value ``imagePullPolicy`` if needed.
  * In ``paused-triggers`` settings, the trigger name prefix ``com.daml.network`` has been
    replaced by ``org.lfdecentralizedtrust.splice``. This also applies to stacktraces you may
    see in logs.
  * ``domain.sequencerAddress``, ``domain.mediatorAddress`` and
    ``participantAddress`` in the SV and Scan helm values are now
    mandatory. The defaults did not include the migration id so are
    almost always incorrect which means this likely has no impact as
    SVs should already have this set explicitly.

* Bugfixes

  * Fix an issue in the wallet app where the transactions from previous migration ids would not be listed when paginating.

0.2.4
-----

* Sequencer

  Fix a rare bug where a lagging participant trying to submit a
  topology transaction resulted in the sequencer deadlocking and not
  processing any new events.

0.2.3
-----

Note: 0.2.2 was skipped due to an error in the publishing process.

* SV UI
  * The route to view the amulet price has been renamed from ``/cc-price`` to ``/amulet-price``

* The docker-compose validator now supports recovering from a node identities dump in case of a complete disaster.

* Add new ``initialPackageConfigJson`` value to the SV helm chart to allow for setting the daml package version when bootstrapping a network.
  This is useful to ensure that the Daml versions do not change on a network reset. Only the first SV needs to set this.

* SV app

  * Fix a bug where sequencer pruning treated nodes that have not
    joined after a synchronizer migration with downtime as lagging
    even when the pruning interval has not yet passed and disabled
    them preventing them from connecting to the sequencer.

* Deployment

  * **Breaking**: The auth secrets ``splice-app-{sv,validator}-ledger-api-auth`` formerly had ``audience`` as an optional field. This is now required. The former implicit value was ``https://canton.network.global``. If you have not overridden this value before, you should add it now explicitly.
  * It used to be possible to override the ledger-api audience value through the helm value ``auth.ledgerApiAudience`` in the sv and validator charts. This has been removed -- use the secret mentioned in the previous point.
  * **Breaking** The chart value ``auth.audience`` was formerly optional, and is now required for the following charts. The previous implicit value was ``https://canton.network.global``. To continue using it, please provide it explicitly to your values. (See the sv-helm and validator-helm docs for more information on auth configuration.)
    * ``cn-sv-node``
    * ``cn-validator``
  * **Breaking** The chart value ``auth.jwksUrl`` was formerly optional, and is now required for the same charts above. This should already be overridden in your values file for your particular auth setup, so likely no further action is required.

* Bugfixes

    * Fix an issue where validators that were already deployed with an invalid ``validatorPartyHint`` were failing to start after a hard domain migration, as the already existing hint was rejected by the validator app.

* Sequencer

  * Fix an issue in sequencer traffic management that resulted in a
    deadlock after a synchronizer upgrade with downtime where lagging
    validators failed to submit a transaction due to lagging behind
    but also failed to catch up due to the submission failing.

* Added support for a docker-compose based deployment of a single-SV network, for app developers
  to test against without needing to connect to DevNet.

0.2.1
-----

* Added support for a docker-compose based validator deployment.

* Scan

  * Fix an issue in the holdings and holding summary endpoint where it failed to decode contracts when the
    splice-amulet version the contract was created in did not match the latest supported version by the Scan release.

* Sequencer

  * Fix a bug that prevented initialization during a hard domain migration if there was a proposal in the topology state
    on the old migration id.

0.2.0
-----

Note: This release must be applied through the `Synchronizer Upgrades with Downtime` procedure.

* Canton

  This release upgrades from Canton 3.0 to Canton 3.1. The primary change is a full redesign of the sequencer database
  to only store each sequenced messages once instead of duplicating it for each recipient.

* Daml

  * Add a choice that allows merging duplicated validator licenses. On DevNet it is easy to get duplicates as secrets can be automatically generated
  * by querying the `/api/sv/v0/devnet/onboard/validator/prepare` endpoint. This is not an issue on Test/MainNet where secrets are explicitly provisioned by SV operators and are one-time use.
  * It is up to the SV operators to ensure that they only hand out one secret to each validator
  * Add a new template `ValidatorLivenessActivityRecord`.
    It is a copy of the `ValidatorFaucetCoupon` template with the only difference being that the validator is an observer instead of signatory.
    This is to allow to expire the coupon without the validator's involvement.

  * The Daml changes in this release require a governance vote to upgrade the package configs to:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.5
    amuletNameService  0.1.5
    dsoGovernance      0.1.8
    validatorLifecycle 0.1.1
    wallet             0.1.5
    walletPayments     0.1.5
    ================== =======

* SV and validator apps

  * Add a note about avoiding installing third-party Daml apps on SV nodes in the SV operations documentation,
    as that may compromise the :ref:`security of the SV node <sv_security_notice>`.

  * Remove support for deprecated ``bootstrapTXs`` field on node identity dumps. Node identity dumps taken on a 0.1.2 snapshot or earlier version are no longer supported.

* Metrics: All the histograms default to using `native histograms <https://opentelemetry.io/docs/specs/otel/compatibility/prometheus_and_openmetrics/#exponential-histograms>`_.

   * Dashboards were also adjusted to use the PromQL functions for native histograms in all the queries

   * You can turn off this behavior for each component by adding the following env variable in the `additionalEnvVars` helm values: `ADDITIONAL_CONFIG_DISABLE_NATIVE_HISTOGRAMS="canton.monitoring.metrics.histograms=[]"`

* Dashboards

  * Added a new "Synchronizer Fees (Validator View)" dashboard for validators to monitor their traffic purchases and consumption.

* Wallet API

  * The ``list`` API in ``wallet-internal.yaml`` now exposes contracts
    as ``ContractWithState`` instead of just as a ``Contract``.

* Deployment

  * Removed the ``disableAutoInit`` value from the helm charts of Canton nodes. All Canton nodes will now always start
    with initialization disabled. SV and validator apps will take care of initializing the nodes as needed, using
    use the new ``nodeIdentifier`` helm chart value for the Canton node identifiers.
    The installing instructions for :ref:`validators <k8s_validator>` and :ref:`SVs <sv-helm>` have been updated accordingly.
  * `spliceInstanceNames` values are now mandatory for all Helm charts that deploy a frontend (``cn-scan``, ``cn-validator``, ``cn-sv-node``, and ``cn-splitwell-web-ui``).
    The correct values for them are published in the docs for :ref:`validators <k8s_validator>` and :ref:`SVs <sv-helm>`.
  * The configuration variable `clusterUrl` was removed from all Helm charts except `splitwell-web-ui`.
  * Default Postgres PVC size for validators is configured as 50GiB in the new `postgres-values-validator-participant.yaml` examples file.
    Note also the change in the :ref:`validator installation docs <validator-helm-charts-install>` to use this file while installing the Postgres chart.
  * For the Docker images, these input environment variables have been renamed,
    replacing ``CN`` with ``SPLICE``:

      * ``CN_APP_UI_HTTP_URL``
      * ``CN_APP_UI_UNSAFE_SECRET``
      * ``CN_APP_UI_UNSAFE``
      * ``CN_APP_WALLET_REDIRECT``
  * The Kubernetes secrets below have been renamed, replacing ``cn-`` with
    ``splice-``:

      * ``cn-app-*-ledger-api-auth``
      * ``cn-app-cns-ui-auth``
      * ``cn-app-sv-key``
      * ``cn-app-sv-ui-auth``
      * ``cn-app-validator-onboarding-validator``
      * ``cn-app-wallet-ui-auth``

* Documentation

  * Updated recommendations for checking synchronizer health after a :ref:`Synchronizer Upgrade with Downtime <sv-upgrades>` to focus exclusively on monitoring signals.
  * Simplified ``jq``-based data dump post-processing examples in disaster recovery documentation for :ref:`SVs <sv_restore>` and :ref:`validators <validator-backups>`.

* Metrics

  * Added ``cn_wallet_unlocked_amulet_balance`` and ``cn_wallet_locked_amulet_balance`` metrics to expose the effective per party balance of locked and unlocked
    amulets.

0.1.19
--------


* Fix the Docker image digest which was used for the ``ans-web-ui``
  and accidentally was empty (thereby not pinning the image) in
  0.1.18 due to a rename.

* ``validatorPartyHint`` is now mandatory for non-SV validators. For an existing validator, it must be set to the current party hint
  (otherwise, the app will fail to start).
  For new validators, it must be of format ``<organization>-<function>-<enumerator>``, where ``organization`` and ``function``
  are alphanumeric, and ``enumerator`` is a number starting from 1.

* Fix an issue in the scan ACS snapshot functionality added in 0.1.18 for network bootstrapped just before 0:00.

* Fix an issue in the ACS snapshot functionality added in 0.1.18 around hard domain migrations. This only affects a hard domain migration *to* 0.1.18 but not *from* 0.1.18.

0.1.18
--------

* SV apps

  * Fix a rare race condition where the SV app uses the wrong
    timestamp to export the topology state on a hard domain migration
    resulting in the sequencer failing to initialize after the
    migration. We recommend upgrading before the next hard domain migration.

  * Enable SV to retain pre-migration sequencer URLs in ``SvNodeState``. This is done through a new `migration.legacyId` configuration in the SV values.
    If set, the SV will keep exposing its sequencer URL for that migration id.
    Once you undeploy the old sequencer node, remove this option as well to stop Scan from advertising your old sequencer.
    This allows validators that have been lagging behind to catchup easier.

* Dashboards

  * Added a new CometBFT Network Status dashboard that displays how much data is being exchanged with each peer on the CometBFT P2P network.
    This should should make it easier to diagnose connectivity problems between network peers.

* Scan API

  * Added the ``getUpdateById`` API in ``scan-internal.yaml``.
    The ``getUpdateById`` API can be used to retrieve an update by its update ID.

  * Added the ``getAcsSnapshotAt``, ``getHoldingsStateAt`` and ``getHoldingsSummaryAt`` APIs in ``scan-internal.yaml``.
    A snapshot of the active contract set (ACS) is now computed and stored periodically to serve these endpoints.

  * Modified ``listDsoSequencers`` Scan API to also expose pre migration sequencer urls, allowing pre-migration validators to catch up.

* UI

  * Gzip compression has been enabled for the Scan, Wallet, SV and CNS UIs.

* Deployment

  * Updated the Cometbft Helm chart to not accept integer values for the `chainIdSuffix`.
  * The ``disableAutoInit`` Helm value now defaults to ``true`` wherever it is used and must be explicitly set to ``false`` when onboarding fresh validators or SVs. The installing instructions for :ref:`validators <k8s_validator>` and :ref:`SVs <sv-helm>` have been updated accordingly.
  * Added ``helm.sh/resource-policy: keep`` to validator and SV app domain migration PVCs
    to ensure they don't accidentally get deleted by a ``helm uninstall``. You can
    still fully delete them with a ``kubectl delete pvc``.
  * `validatorPartyHint` is now mandatory for non-SV validators. For an existing validator, it should be set to the current party hint
    (otherwise, the value will be ignored, and a warning will be printed to log).
    For new validators, it should be of format `<organization>-<function>-<enumerator>`.
  * In ``cometbft-values.yaml``, the top-level label ``founder`` is now ``sv1``.  The
    example has been updated to match, and this change must be made to your own copy.
  * The download link for the release bundle has changed to a new URL format: `<version>_splice-node.tar.gz`.
    Its content has been renamed accordingly as well.

* Documentation

  * Simplified ``jq``-based data dump post-processing examples in disaster recovery documentation for :ref:`SVs <sv_restore>` and :ref:`validators <validator-backups>`.

0.1.17
--------

* Wallet automation

  * Fix an issue in the wallet sweep automation where it created
    additional transfer offers even if there were already sufficient
    transfer offers to cover the sweep.

* Deployment

  * Image versions in Helm charts are now pinned to digests for extra security

0.1.16
------

* CometBft

  * The default cometbft persistent volume size was bumped from 1250Gi to 2500Gi.

* SV app

  * Add automation to automatically call the Daml choice that prunes ``futureValue`` added in 0.1.15

* Release

  * HTML docs are now included in the release bundle, under `docs/html`.

* Documentation

  * Added notes about configuring traffic top-ups for validators to `validator-values.yaml`

* Daml

  * Fixed a bug in ``AmuletRules_ComputeFees`` where the fee computation for locks was too high
    as it did not do the same deduplication of lock-holders as is done by ``AmuletRules_Transfer``.

  * Fixed ANS entry expiration so that it's robust to stakeholder participants being unavailable.

  * All Dars have been rebuilt from source files that include the same copyright prefix
    as in the Splice repository. This bumps dar versions in all packages.
    Incorporating that will require a governance vote to upgrade the package configs to:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.4
    amuletNameService  0.1.4
    dsoGovernance      0.1.6
    validatorLifecycle 0.1.1
    wallet             0.1.4
    walletPayments     0.1.4
    ================== =======

* Deployment

  * Added an ``livenessProbeInitialDelaySeconds`` parameter to all helm charts.

  * Helm charts that deploy a frontend (``cn-scan``, ``cn-validator``, ``cn-sv-node``, and ``cn-splitwell-web-ui``) now accept a new parameter, ``spliceInstanceNames``, to configure network-specific terminology. The correct values should be consumed from the `cn-svc-configs ui-config-values.yaml <https://github.com/DACH-NY/cn-svc-configs/blob/main/configs/ui-config-values.yaml>`_

  * Docker environment variables of the form ``CN_APP_*_UI_*`` have been renamed to ``CN_APP_UI_*``, dropping the app name prefix. For users of the Helm charts, no further action is needed.

* Sequencer

  * Improve performance of sequencer startup and querying the
    sequencer onboarding snapshot when onboarding new SVs. This adds a
    new index to the sequencer database so can take a while depending
    on the size of the DB.

    Note: If you encounter issues with the migration taking too long and k8s killing your pod,
    bump the ``livenessProbeInitialDelaySeconds`` parameter in the sequencer helm
    chart.

    We have also seen some issues with istio cancelling the database connection before the migration can finish (on much larger scale clusters than what we expect to have on dev/test/mainnet).
    In that case, consider disabling the istio proxy through ``annotations: traffic.sidecar.istio.io/excludeOutboundPorts: "YOURDATABASEPORT"`` on the sequencer deployment.

* All helm charts now allow configuring the database port through ``persistence.port``. Note that for the ``cn-global-domain`` chart, this is nested under
  ``sequencer.persistence`` and ``mediator.persistence``.

0.1.15
------

Note: 0.1.14 was skipped as it contained an issue related to logging. Upgrade directly from 0.1.13 to 0.1.15.

* SV app

  * Added a governance option to update the SV reward weight of a member SV.
    This is available in the Governance tab by selecting the action "Update SV Reward Weight".
  * Added ``consensus_state`` to the list of CometBFT RPC endpoints exposed via the SV app at ``/v0/admin/domain/cometbft/json-rpc``.

* Deployment

  * Fix an issue in the validator and SV helm charts where setting ``contactPoint`` to an empty string produced an error.

* Daml

  * Add a choice that allows pruning configs from the AmuletRules ``futureValues`` after the time has been reached to reduce the size of the config
    and reduce differences between the config schedule on different networks.

  * The Daml changes in this release require a governance vote to upgrade the package configs to:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.3
    amuletNameService  0.1.3
    dsoGovernance      0.1.5
    validatorLifecycle 0.1.0
    wallet             0.1.3
    walletPayments     0.1.3
    ================== =======

0.1.13
------

* Docker

  * Switch to using ``eclipse-temurin:17-jdk-jammy`` as the base image as the ``openjdk:17-jdk-slim`` is no longer maintained.

* Deployment

  * UI containers in the Helm charts now request only 0.1 CPU and 240Mi memory by default.

  * Default participant CPU requests have been lowered from 2 to 1 CPU based on the observed usage under load tests.

  * Validator and SV helm charts have a new required ``contactPoint``
    field that must be set in ``validator-values.yaml`` and
    ``sv-values.yaml``. This should point to a Slack username or email
    address that can be used by other node operators to contact you in
    case there are issues with your node. If you do not want to share
    this, set it to an empty string.

  * Added support for k8s tolerations to all Helm charts.

* SV app

  * ``/v0/admin/domain/data-snapshot`` now includes ``created_at`` and ``migration_id`` in
    the response payload, so these no longer need to be added manually when restoring an
    SV app from backup.  ``migration_id`` is also an optional argument to set the latter,
    defaulting to 1 + the cluster's current migration ID.

  * The extra beneficiary config has been changed to specify weights in an ordered list instead of percentages.
    The weights are distributed in the order of the list until there is no weight remaining. Any remainder
    still goes to the SV operator party.
    This fixes two problems with the percentage-based beneficiary specification:

        1. it does not suffer from rounding errors
        2. it allows changing the config ahead of time to account for a planned weight changes by adding
           additional entries at the end.

    This is a breaking config change, which requires you to adapt the SV app config
    as per this example: assuming a total weight of 10000 basis points, the previous config::

        extraBeneficiaries:
          - partyId: "BENEFICIARY_1_PARTY_ID"
            percentage: 10.0
          - partyId: "BENEFICIARY_2_PARTY_ID"
            percentage: 33.33

    changes to::

        extraBeneficiaries:
          - beneficiary: "BENEFICIARY_1_PARTY_ID"
            weight: 1000
          - beneficiary: "BENEFICIARY_2_PARTY_ID"
            weight: 3333


* Validator app

  * ``/v0/admin/domain/data-snapshot`` now accepts ``migration_id`` as an argument,
    overriding ``migrationId`` in the response payload.  The default ``migrationId`` is
    now 1 + the cluster's current migration ID, rather than only the current migration ID.

  * The migration dump format has changed; the JSON keys ``acsSnapshot``,
    ``acsTimestamp``, ``migrationId``, ``domainId``, and ``createdAt`` have changed to
    ``acs_snapshot``, ``acs_timestamp``, ``migration_id``, ``domain_id``, and
    ``created_at``, respectively. The format of ``/v0/admin/domain/data-snapshot`` has
    been fixed where it mismatched the migration dump import format so that backups do not
    need to be patched to be restored. Previous dumps can still be imported using the old format.

* Scan app

  * Improved performance of the per-party ACS endpoint that is used
    when reonboarding a validator from the identity backup.

* Daml

  * Extended the Daml models to report the version number and a
    periodic heartbeat of each validator to provide a better overview
    of the network state and detect potential issues from upgrades
    earlier.
  * The frequency of ACS commitments can now be modified via a
    "Set DsoRules configuration" governance by changing the newly added ``acsCommitmentReconciliationInterval`` configuration
    parameter in the DsoRules (set by default to 30 minutes).
  * Removed a special case for ``SRARC_OffboardSv`` in the ``DsoRules_CloseVoteRequest`` choice in ``splice-dso-governance.dar``,
    so that offboarding an SV before the vote request expires is now only possible if **all** current SVs agree,
    **including** the SV that is being offboarded.
    Prior to this change, the offboarding would become effective before the set expiration time once all SVs except the SV to be offboarded had voted.
    This complicated the coordination around giving SVs sufficient time to address the offboarding reason and prevent the offboarding.

  * The Daml changes in this release require a governance vote to upgrade the package configs to:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.3
    amuletNameService  0.1.3
    dsoGovernance      0.1.4
    validatorLifecycle 0.1.0
    wallet             0.1.3
    walletPayments     0.1.3
    ================== =======

* Dashboards

  Added a new Validator License dashboard that displays the version and contact point of all validators. This can be useful
  to judge the impact of an upgrade.

0.1.12
------

Note: 0.1.11 was skipped as it contained some issues. Upgrade directly from 0.1.10 to 0.1.12.

* SV and Validator app

  * Added a ``disableIngestUpdateHistoryFromParticipantBegin`` flag to the helm values of the SV and validator app.
    This was added to account for a change in 0.1.11 that stores more history as backfilling the history on the existing test/devnet clusters
    is too expensive. This should **only** be enabled on existing Dev/TestNet clusters to avoid issues when upgrading to 0.1.12.
    It **must not** be enabled on any new cluster or if a node is fully reset.

* Scan

  * Fix a bug where the new update history API in scan was unable to serve data from before
    the upgrade.

* Include Grafana dashboards and a README on network health in the release bundle.

* Configuration

  * Add support in the Validator app Helm chart for configuring sweeps and auto-accepts of transfer offers.

  * The ``wallet-sweep`` and ``auto-accept`` configuration values for a validator app
    were changed to map party-ids to configurations instead of mapping participant user-names to configurations.

* Daml

  * The ``WalletAppInstall_ExecuteBatch`` choice in ``splice-wallet.dar`` was changed to also record the wallet user party when executing
    batches of operations on a user's coin holdings to improve disambuiguation of log entries
    in the wallet transaction log.

  * Fix an issue in the computation of transfer fees where the values of the steps
    were interpreted as the difference between steps as opposed to an absolute value so e.g.
    the fees were computed as ``transferFee(2000) = 0.1 * 100 + 1000 * 0.01 + 900 * 0.001``
    instead of ``transferFee(2000) = 0.1 * 100 + 900 * 0.01 + 1000 * 0.001`` for the default config.

    This requires a governance vote to upgrade the package configs to:

    ================== =======
    name               version
    ================== =======
    amulet             0.1.2
    amuletNameService  0.1.2
    dsoGovernance      0.1.3
    validatorLifecycle 0.1.0
    wallet             0.1.2
    walletPayments     0.1.2
    ================== =======

* Validator admin API

  Simplified creating users that share the same party-id and wallet. For that purpose
  ``POST /v0/admin/users`` accepts an optional ``party_id`` field in its JSON body,
  which can be set to an already allocated party.

* Bugfixes

  * The wallet automation for collecting rewards is started only once per Daml party instead of
    once per onboarded wallet user. This enables setups where multiple wallet users have access to
    the same coin holdings for the same Daml party.

  * Fixed a bug where a user wallet wrongly attempted to use the featured app right of the validator
    admin party if that existed, which resulted in failed transactions.

* The `approved-sv-id-values-*.yaml` files have been removed from the release bundle. The approved SV identities for
  each network instance can now exclusively be obtained from the `cn-svc-configs repo <https://github.com/DACH-NY/cn-svc-configs>`_ .

* CC Scan

  Fix a bug in the balance API and UI where balances did not get tracked
  properly if the balance change for a given party was negative in one
  round, e.g., because it transferred away a large amount.

0.1.10
------

* SV App

  The default transfer config set by the founding node has been changed from
  ``"0.0000192901`` to ``0.0000190259`` corresponding to changing the computation to be
  performaned in fixed point decimals and 365 days. This matches the change already applied to
  devnet through a governance vote.

* Daml

  Fixed a bug that resulted in duplicate ``SvRewardState`` contracts when an SV got reonboarded
  which allowed them to receive rewards corresponding to a multiple of their actual weight. This
  requires upgrading ``dso-governance`` to ``0.1.2`` through a governance vote on ``AmuletConfig``.

* SV UI

  Fixed a bug in pretty printing of the JSON object in ``DSO Info``
  that printed maps differently from the API response and some other
  parts of the UI.

0.1.9
-----

* Configuration

    * Default ``actionConfirmationTimeout`` parameter in CoinRules was increased from 5 minutes to 1 hour.
      This increases robustness if some nodes are temporarily unavailable or slow.
      Note that this requires a governance vote to change the ``DsoConfig`` on existing clusters.

    * Default PVC sizes updated: 2800Gi for Postgres.

* App Dev

  * DARs can no longer be uploaded through the Ledger API. Instead use
    the Canton admin API. This change was made as the ledger API
    upload breaks under hard domain migrations.

* Documentation

  * Add notes about (Helm chart) version upgrades to the Synchronizer Upgrades with Downtime documentation sections
    for :ref:`SVs <sv-upgrades>` and :ref:`validators <validator-upgrades>`.

  * Updated ``Preparing for Validator Onboarding`` sections to describe the steps a validator operator needs to take
    to onboard a new node.

  * Removed Self-Hosted Validator documentation in favor of the Helm docs for validator deployments.

  * Removed Splitwell-related documentation as Splitwell is not actively maintained as a production-ready app.

* Deployment

  * The values ``nodeId``, ``publicKey`` and ``keyAddress`` in the ``founder`` section of the cometbft helm chart are not set
    in the chart defaults but must be explicitly provided. See the comments in the example ``cometbft-values.yaml``
    for the values to use for DevNet, TestNet or MainNet.

* Daml

  Fixed a bug that prevented a round from moving to the issuing state if there are no unclaimed rewards for that round.
  This requires upgrading ``splice-amulet``, ``splice-amulet-name-service``, ``splice-dso-governance`` and ``splice-wallet``
  to version ``0.1.1`` through a governance vote on AmuletConfig.

0.1.8
-----

* Deployment

  * The URL for the Digital-Asset-2 node is now compliant with the agreed upon URL formats: `*.sv-2.<dev|test>.global.canton.network.digitalasset.com`

  * All Digital-Asset-Eng-X nodes also change URLs with this release, from `*.sv-x.<hostname>` to `*.sv-x-eng.<dev|test>.global.canton.network.digitalasset.com`.

* Bugfixes

  * Reduced the frequency of ACS commitments to every 30min to avoid issues with validators running out of traffic.

* Performance

  * Sequencers now batch some of their writes which should improve performance.

0.1.7
-----

* Deployment

  * Note change in urls in the Digital-Asset-2 node (which is used in several example and default configurations in the docs), from `*.sv-1.svc.<hostname>` to `*.sv-1.<hostname>`, as a step towards making that node compliant with the agreed upon URL formats.
    Note that further changes to Digital Asset node URLs might become effective before the next release becomes available.

* Updated validator runbooks with instructions for re-onboarding a validator.
* Renamed `traffic-reserved-for-topups` in the validator app and SV app config to `reserved-traffic` to better reflect the fact that
  the "reserved" traffic amount is used for more than just traffic top-ups. No change is needed unless you explicitly set a value for this instead
  of just relying on the default.

* APIs

  * The ``admin/domain/data-snapshot`` endpoints on the SV and validator app now require specifying the timestamp as a query parameter instead of in the payload body. This was changed since ``GET`` requests must not have request bodies.

0.1.6
-----

Note: 0.1.5 resulted in the issue mentioned below so both SVs and validators should directly upgrade from 0.1.4 to 0.1.6.

* Security

  * Fixed an issue where secrets in config files were logged on startup. This effects Auth0 secrets, SV onboarding and validator onboarding secrets.
    Please rotate all those secrets as soon as possible to reduce the impact.

* Bugfixes

  * Fix a bug (triggered by some changes in 0.1.5) where automation could submit too many commands in parallel overloading the synchronizer.

0.1.5
-----

* Fixed the SV UI to show node status information in the DSO info tab and display AmuletConfigChange vote requests that were executed.
* Removed PVC size overrides in example `postgres-values-participant.yaml` and `postgres-values-sequencer.yaml` files. The Postgres instances used by the participant and sequencer should use the default size instead (1300Gi).
* Updated the scan UI to show recent activity in a way that is more consistent and matches the actual activity on the ledger.
  Note that all transfers recorded in the past will show as having no sv rewards.
  This limitation can be removed with a future update.
* Fix a bug where the namespace triggers did not get started on SV’s
  with ``migrating: true`` which prevented new SVs from being
  onboarded after domain migrations.
* Updated SV and validator runbooks with network-wide disaster recovery instructions.
* Introduced a `vpns` section in the IP whitelists json file, replacing the `infra.vpn` one.

0.1.4
-----

* Default PVC sizes updated: 640Gi for CometBFT and 1300Gi for Postgres.
* Bugfix in Total balance and Total rewards in USD in Scan UI.
* New value for ``cometbft-values.yaml``:  ``genesis.chainIdSuffix``. Please explicitly set this to ``"0"`` as per the updated example.
  Note that this deprecates ``genesis.chainIdVersion``, which can be removed for deployments that use this and later releases.
* By default, CometBFT deployments now use the ``premium-rwo`` storage class for increased performance. Please override ``db.volumeStorageClass`` in your ``cometbft-values.yaml`` if this storage class is not supported by your Kubernetes cluster provider. Please use an SSD storage class for the CometBFT PVC.
* Updated SV runbook for Re-onboarding an SV.

0.1.3
-----

* The Scan frontend shows information about currently open mining rounds
  in the current configuration box.
* Minor documentation improvements related to synchronizer upgrades with downtime.
* Fixed the initial validator rewards tranche to be 5% of the total issuance (it was wrongly set to 50%). Note that this only has an effect
  on newly bootstrapped clusters. Existing clusters need to be changed through a voting process.
* Set the ``validatorFaucetCap`` explicitly to 2.85 instead of leaving it unset to make
  reviewing the config easier. This has no  effect since unset defaults to 2.85.
  Existing clusters need to be changed through a voting process.
* The resource requests for sequencers have been increased to match our target scale.
  If needed, they can be reduced using the ``sequencer.resources`` value of the
  ``cn-global-domain`` but please try to get them to a comparable value in time for mainnet.
* Fix a sequencer bug that resulted in it failing to process any further messages after a message
  with high traffic costs.
* If a tap fails in the wallet frontend, the error message includes extra technical details that
  may be useful for diagnosis.

0.1.2
-----

* Fixed a bug where coins with very large values broke ingestion in the SV and validator app due to an overflow.

* Updated SV runbook for correct recommendation on pruning intervals.

2024-04-01
----------

* Renamed the following terms in our underlying Daml models and the apps' APIs to prepare for open-sourcing
  their code in a form that does not use the term "Canton" or "collective":

  * Coin -> Amulet
  * CNS -> ANS (Amulet Name Service)
  * SVC -> DSO (Decentralized Synchronizer Operations)
  * Domain -> Synchronizer
  * Global Domain (whenever it refers to the more generic concept) -> Decentralized Synchronizer
  * Note that for technical reasons the URLs for networks still include the term "svc" for now;
    e.g., ``https://wallet.sv.svc.YOUR_HOSTNAME``.

* Added an option to disable the Validator apps' wallet. This can be done by setting ``enableWallet`` to ``false`` in the ``validator-values.yaml`` file.

* Added ANS name resolution (formally known as CNS) for ``dso.ans`` to the DSO party and ``<sv-name>.sv.ans`` to all SV members parties.

* CometBFT pruning duration has been increased to 30 days. No configuration changes are required.

* Sequencer pruning period has been adjusted to 30 days and pruning interval has been reduced to 1 hour.
  Adjust ``sequencerPruningConfig.pruningInterval`` and ``sequencerPruningConfig.retentionPeriod`` in your ``sv-values.yaml`` to match the example ``sv-values.yaml``.

* The sequencer URL of the Digital Asset 2 node ``https://sequencer.sv-1.svc.CLUSTER.network.canton.global`` is no longer exposed. Instead use
  ``https://sequencer-MIGRATION_ID.sv-1.svc.CLUSTER.network.canton.global`` where ``MIGRATION_ID`` is the current migration id of the cluster.

* Round 0 now has a duration of 26h. The two extra hours are to allow for internal validation before the release is announced while still providing 24h for anyone else to validate the config.

* DevNet and TestNet are deployed on Monday instead of Sunday each week.


2024-03-25
----------

* Round 0 now has a duration of 24h. This removes the advantage of
  early joiners and allows for more time to validate that the
  configuration upon joining is the one an SV expected.

* Initial coin price is now $0.005/CC.

* Subsequent round duration is now 10min.

* The initial holding fee is now $0.0000192901/round (about 4× its prior
  value) to preserve an approximate fee of $1/360 days given the round
  duration change.

* New ``initial-holding-fee`` setting for ``"found-collective"`` sv
  onboarding.

* Sequencer pruning is now enabled by default. This requires configuring a pruning interval and retention period in the SV app's configuration.

* Fix performance bottleneck while initializing new synchronizer after a hard synchronizer migration.

* Fix scan so that it functions as expected after a hard synchronizer migration.


2024-03-18
----------

* Deployment

  * ``participant-values.yaml`` and ``global-domain-values.yaml`` now require specifying your SV name as ``nodeIdentifier: YOUR_SV_NAME``.
    This is used to provide better names to Canton nodes.
  * Multiple changes to the way (non-SV) validator nodes are deployed,
    to prepare for supporting :ref:`Synchronizer Upgrades with Downtime <validator-upgrades>`.
    Please revisit the section on :ref:`Helm-based validator deployment <k8s_validator>`,
    paying attention to the new ``MIGRATION_ID`` variable (should be set to ``0`` until further notice).

* Documentation

  * Added detailed instructions for (non-SV) validator node operators on participating in a synchronizer upgrade.
    Please see the new validator operations section on :ref:`Synchronizer Upgrades with Downtime <validator-upgrades>`,
    as well as the updates in :ref:`k8s_validator`.
  * :ref:`SV Synchronizer Upgrades <sv-upgrades>`: Added more detailed instructions on :ref:`testing <sv-upgrades-testing>`, as well as various clarifications.
  * Removed now-obsolete documentation about "Transitioning Across Network Resets" and "Restoring from an existing Particiant Identities Backup".
  * Added :ref:`backup and restore documentation for (non-SV) validator nodes <validator-backups>`.

* Configuration

  * SV node renames:

    * Digital-Asset is preparing to run two nodes, Digital-Asset-1 and Digital-Asset-2
    * Digital Asset engineering team's extra nodes on DevNet were renamed to Digital-Asset-Eng-X

  * SV weights: The SV weights on DevNet have been updated

2024-03-11
----------

* Deployment

  * Multiple changes to the way SV nodes are deployed, to prepare for supporting :ref:`Synchronizer Upgrades with Downtime <sv-upgrades>`.
    Please revisit the section on :ref:`Helm-based SV deployment <sv-helm>`,
    paying attention to the new ``MIGRATION_ID`` variable (should be set to ``0`` until further notice).
  * ``sv-values.yaml`` now also requires you to specify an ``internalUrl`` for your scan instance that the SV app
    can use to query its status.

  * In preparation for the mainnet deployment and testing real
    upgrades, testnet no longer preserves coin balances and validator licenses. No
    configuration changes are required for this. However, any validator secrets created
    through the UI or API now need to be regenerated on each reset. Validator secrets
    configured in ``expected-validator-onboardings`` will automatically be recreated.
    Note that this affects only testnet so will only take effect on March 18th.

* Documentation

  * Added more detailed instructions for SV node operators on participating in a synchronizer upgrade.
    Please see the updated section on :ref:`Synchronizer Upgrades with Downtime <sv-upgrades>`,
    as well as the updates in :ref:`sv-helm`.

  * Added a section on how to configure the `extraBeneficiaries` to the SV rewards so that the SV can distribute its SV rewards to other parties.
    Please see the new section in :ref:`sv-helm`.

* The SV rewards are now issued in accordance to CIP-0001.

2024-03-04
----------

* Deployment

  * It is no longer necessary to specify anything related to `globalDomain` in `participant-values.yaml`.

* Documentation

  * Added section on :ref:`Synchronizer Upgrades with Downtime <sv-upgrades>`.
    This section only contains a high-level overview for now and will be expanded in the upcoming weeks.
  * Preliminary documentation of :ref:`restoring from backups <sv_restore>`.
    Note that for now, only the case of restoring a full SV node from a backup is fully covered.

* SVs do not pay domain fees anymore for their nodes. Therefore, traffic top-ups do not need to be configured for SV validators.
  In keeping with this, `topup.enabled` in `sv-validator-values.yaml` is set to `false`.

* The `RemoveMember` action has been renamed to `OffboardMember` in the SV UI and SvcRules Daml model, including `SRARC_OffboardMember` and `SvcRules_OffboardMember`.

2024-02-26
----------

* Deployment

  * Removed option to configure Kubernetes node affinity for PVCs due to a faulty implementation.
    For controlling the provisioning of PVCs, you can define custom storage classes and configure them via the respective `db.volumeStorageClass` Helm chart field.
  * Fix the `affinity` and `nodeSelector` field on the `cn-postgres` Helm chart so they are applied as expected.
  * The ``scanAddress`` in ``validator-values.yaml`` should be an address to a trusted Scan instance that is reachable by your Validator.

* Documentation

  * Added instructions for fetching and backing up node identities from an SV node in the :ref:`Backups section <sv_backups>`.

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
    * We suggest deleting the existing ones using `helm repo remove` and `kubectl delete secret`

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
    * The SV Helm runbook has been extended with a section that explains how to restore from a participant identities backup.
    * The instructions for self hosted validators have been extended with a section that explains how to restore from a participant identities backup.
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
  This is covered in a new section of the runbook.
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
    Section Using the Canton Coin Directory UI explains the UI.
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
  (see the Self-Hosted Validator runbook for how that secret is then used by the validator operator).

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
