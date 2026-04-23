..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV App

        - Package versions newer than the version specified in the AmuletRules configuration are now automatically unvetted by the SV app after a successful downgrade vote.

    - Deployment

        - SV apps now support a ``copyVotesFrom`` setting that automatically mirrors governance votes
          from another named SV, which can help operators keep votes in sync when they run multiple SV nodes.

        - The SV helm chart now supports a new ``synchronizers`` value that replaces the previous ``domain`` value.
          The new structure allows configuring ``current``, ``successor``, and ``legacy`` synchronizer nodes, each with
          ``sequencerPublicUrl``, ``sequencerAddress``, ``mediatorAddress``, optional ``sequencerPruningConfig``,
          ``enableBftSequencer``, and inline ``cometBFT``.
          The ``synchronizers.skipInitialization`` field replaces ``domain.skipInitialization``.
          The previous ``domain`` value is still accepted for backwards compatibility but cannot be combined with ``synchronizers``.
          We strongly recommend updating your ``sv-values.yaml`` to use the new ``synchronizers`` structure, as
          the ``domain`` value will be removed in a future release.
          See :ref:`helm-sv-install` for the updated configuration instructions.

        - The Scan helm chart now supports a new ``synchronizers`` value that replaces the previous top-level
          ``sequencerAddress``, ``mediatorAddress``, and ``bftSequencers`` values.
          The new structure requires ``synchronizers.current.sequencer`` and ``synchronizers.current.mediator``, and
          optionally supports ``successor`` and ``legacy`` entries with the same fields, as well as per-synchronizer
          ``bftSequencerConfig.p2pUrl``.
          The previous ``sequencerAddress`` and ``mediatorAddress`` values are still accepted for backwards compatibility
          but cannot be combined with ``synchronizers``.
          We strongly recommend updating your ``scan-values.yaml`` to use the new ``synchronizers`` structure, as
          the previous values will be removed in a future release.
          See :ref:`helm-sv-install` for the updated configuration instructions.

    - Scan

        - Added a new ``GET /v2/updates/hash/{hash}`` endpoint that returns the update associated with a given external transaction hash of a prepared transaction.
          This endpoint is not always BFT safe, see the Scan OpenAPI documentation for details.

        - ``POST /v0/state/acs`` has been labeled as deprecated, and replaced by a newer ``POST /v1/state/acs``.
          The new `/v1` endpoint replaces the event ID in the response from `/v0` by an (optional) update ID. The update ID for
          each contract in the ACS refers to the update in which the contract has been created. This value is
          guaranteed to be consistent across all instances of Scan, therefore is suitable for BFT reads.
          The update ID will be omitted for contracts created in a prior migration ID, or potentially in the
          future in extreme cases of disaster recovery.

    - Validator

        - The HTTP client used by the CN apps (Validator, Scan, SV, Wallet) now honours the
          standard ``http.nonProxyHosts`` Java system property to bypass a configured HTTP
          forward proxy for specific hosts. This matches the JDK's own default ``ProxySelector``
          behaviour, so the same property also applies to other JVM egress components.
          See :ref:`validator-http-proxy-compose` and :ref:`validator-http-proxy-helm` for configuration examples.

    - LocalNet

        - Added support for configuring the protocol version used in LocalNet.

    - Participant

        - Set ``commitment-use-db-snapshot-for-participant-lookup = true`` by default. If you manually set this, you can
          remove your overwrite.

    - Remove use of rollback nodes to support protocol version 35

        - Daml

            - ``AmuletRules``. Replace ``InvalidTransfer`` exceptions with ``failWithStatus``:

                - ``ITR_InsufficientFunds`` → ``FailureStatus`` with error_id = ``splice.lfdecentralizedtrust.org/insufficient-funds``
                - ``ITR_UnknownSynchronizer`` → ``FailureStatus`` with error_id = ``splice.lfdecentralizedtrust.org/unknown-synchronizer``
                - ``ITR_InsufficientTopupAmount`` → `FailureStatus` with error_id = ``splice.lfdecentralizedtrust.org/insufficient-topup-amount``
                - ``ITR_Other ("More than the maximum number of inputs")`` → ``FailureStatus`` with error_id = ``splice.lfdecentralizedtrust.org/maximum-inputs-exceeded``
                - ``ITR_Other ("More than the maximum number of outputs")`` → ``FailureStatus`` with error_id = ``splice.lfdecentralizedtrust.org/maximum-outputs-exceeded``

            - ``WalletAppInstall_ExecuteBatch``. No longer catches exceptions and returns them as ``AmuletOperationOutcome`` / ``COO_Error``. Instead, the transaction is aborted.

            - ``TransferCommand_Send``. No longer catches exceptions and returns them as ``TransferCommandResultFailure``. Instead, the transaction is aborted without merging inputs.

            - ``DsoRules_CloseVoteRequest`` no longer catches exceptions. Previously, it would close the vote request with outcome ``VRO_AcceptedButActionFailed``.
              The transaction will now abort on failure.

        - Wallet app

            - ``batchSize`` in ``TreasuryConfig`` is set to 1.

    - Daml

        - The Daml SDK in Splice has been upgraded to 3.4.11. All Daml packages have been recompiled to new Dars, with versions:

            ================== =======
            name               version
            ================== =======
            amulet             0.1.18
            amuletNameService  0.1.19
            dsoGovernance      0.1.24
            validatorLifecycle 0.1.7
            wallet             0.1.19
            walletPayments     0.1.18
            ================== =======
