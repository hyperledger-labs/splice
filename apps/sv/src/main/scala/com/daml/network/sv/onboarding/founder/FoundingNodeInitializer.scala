// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.onboarding.founder

import cats.implicits.{
  catsSyntaxTuple2Semigroupal,
  catsSyntaxTuple3Semigroupal,
  catsSyntaxTuple4Semigroupal,
}
import cats.syntax.functorFilter.*
import cats.syntax.traverse.*
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.splice
import com.daml.network.config.UpgradesConfig
import com.daml.network.environment.*
import com.daml.network.http.HttpClient
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.{
  AppStoreWithIngestion,
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.daml.network.store.MultiDomainAcsStore.*
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.onboarding.{
  DsoPartyHosting,
  NodeInitializerUtil,
  SetupUtil,
  SynchronizerNodeReconciler,
}
import com.daml.network.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState
import com.daml.network.sv.onboarding.founder.FoundingNodeInitializer.bootstrapTransactionOrdering
import com.daml.network.sv.store.{SvDsoStore, SvStore, SvSvStore}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{AssignedContract, TemplateJsonDecoder, UploadablePackage}
import com.daml.network.util.SpliceUtil.{defaultAmuletConfig, defaultAnsConfig}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnections,
  TrafficControlParameters,
}
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration, PositiveSeconds}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  StoredTopologyTransactions,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.transaction.{
  DecentralizedNamespaceDefinition,
  SignedTopologyTransaction,
  TopologyChangeOp,
  TopologyMapping,
}
import com.digitalasset.canton.topology.transaction.TopologyMapping.Code
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize the founding SV node. */
class FoundingNodeInitializer(
    localSynchronizerNode: LocalSynchronizerNode,
    foundingConfig: SvOnboardingConfig.FoundDso,
    requiredDars: Seq[UploadablePackage],
    participantId: ParticipantId,
    override protected val config: SvAppBackendConfig,
    upgradesConfig: UpgradesConfig,
    override protected val cometBftNode: Option[CometBftNode],
    override protected val ledgerClient: SpliceLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val domainTimeSync: DomainTimeSynchronization,
    override protected val domainUnpausedSync: DomainUnpausedSynchronization,
    override protected val storage: Storage,
    override protected val retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tracer: Tracer,
) extends NodeInitializerUtil {

  def bootstrapDso()(implicit
      tc: TraceContext
  ): Future[
    (
        DomainId,
        DsoPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvDsoStore,
        SvDsoAutomationService,
    )
  ] = {
    for {
      _ <- rotateGenesisGovernanceKeyForFounder(cometBftNode, foundingConfig.name)
      initConnection = ledgerClient.readOnlyConnection(
        this.getClass.getSimpleName,
        loggerFactory,
      )
      (namespace, domainId) <- bootstrapDomain(localSynchronizerNode)
      _ = logger.info("Domain is bootstrapped, connecting founding participant to domain")
      _ <- participantAdminConnection.ensureDomainRegisteredAndConnected(
        DomainConnectionConfig(
          config.domains.global.alias,
          sequencerConnections = SequencerConnections.single(
            GrpcSequencerConnection.tryCreate(config.domains.global.url)
          ),
          manualConnect = false,
          domainId = None,
        ),
        RetryFor.WaitingOnInitDependency,
      )
      _ = logger.info("Participant connected to domain")
      (dsoParty, svParty, _) <- (
        setupDsoParty(domainId, initConnection, namespace),
        SetupUtil.setupSvParty(
          initConnection,
          config,
          participantAdminConnection,
        ),
        retryProvider.ensureThatB(
          RetryFor.WaitingOnInitDependency,
          "founder_initial_package_upload",
          "Founder has uploaded the initial set of packages",
          initConnection
            .lookupUserMetadata(
              config.ledgerApiUser,
              BaseLedgerConnection.FOUNDER_INITIAL_PACKAGE_UPLOAD_METADATA_KEY,
            )
            .map(_.nonEmpty),
          participantAdminConnection
            .uploadDarFiles(
              requiredDars,
              RetryFor.WaitingOnInitDependency,
            )
            .flatMap { _ =>
              initConnection.ensureUserMetadataAnnotation(
                config.ledgerApiUser,
                BaseLedgerConnection.FOUNDER_INITIAL_PACKAGE_UPLOAD_METADATA_KEY,
                "true",
                RetryFor.WaitingOnInitDependency,
              )
            },
          logger,
        ),
      ).tupled
      storeKey = SvStore.Key(svParty, dsoParty)
      migrationInfo =
        DomainMigrationInfo(
          currentMigrationId =
            config.domainMigrationId, // Note: not guaranteed to be 0 for the founding node
          acsRecordTime = None, // No previous migration, we're starting the network
        )
      svStore = newSvStore(storeKey, migrationInfo, participantId)
      dsoStore = newDsoStore(svStore.key, migrationInfo, participantId)
      svAutomation = newSvSvAutomationService(
        svStore,
        dsoStore,
        ledgerClient,
      )
      (_, decentralizedSynchronizer) <- (
        SetupUtil.ensureDsoPartyMetadataAnnotation(svAutomation.connection, config, dsoParty),
        svStore.domains.waitForDomainConnection(config.domains.global.alias),
      ).tupled
      _ <- DomainMigrationInfo.saveToUserMetadata(
        svAutomation.connection,
        config.ledgerApiUser,
        migrationInfo,
      )
      dsoPartyHosting = newDsoPartyHosting(storeKey.dsoParty)
      // NOTE: we assume that DSO party, cometBft node, sequencer, and mediator nodes are initialized as
      // part of deployment and the running of bootstrap scripts. Here we just check that the DSO party
      // is allocated, as a stand-in for all of these actions.
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "dso_party_allocation",
        show"DSO party $dsoParty is allocated on participant $participantId and domain $decentralizedSynchronizer",
        for {
          dsoPartyIsAuthorized <- dsoPartyHosting.isDsoPartyAuthorizedOn(
            decentralizedSynchronizer,
            participantId,
          )
        } yield {
          if (dsoPartyIsAuthorized) ()
          else
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"DSO party is allocated on participant $participantId and domain $decentralizedSynchronizer"
              )
              .asRuntimeException()
        },
        logger,
      )

      dsoAutomation = newSvDsoAutomationService(
        svStore,
        dsoStore,
        Some(localSynchronizerNode),
        upgradesConfig,
      )
      _ <- dsoStore.domains.waitForDomainConnection(config.domains.global.alias)
      withDsoStore = new WithDsoStore(dsoAutomation, decentralizedSynchronizer)
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "bootstrap_dso_rules",
        show"the DsoRules and AmuletRules are bootstrapped",
        dsoStore.lookupDsoRules().map(_.isDefined), {
          withDsoStore.foundDso()
        },
        logger,
      )
      _ <- ensureCometBftGovernanceKeysAreSet(
        cometBftNode,
        svParty,
        dsoStore,
        dsoAutomation,
      )
      // Only start the triggers once DsoRules and AmuletRules have been bootstrapped
      _ = dsoAutomation.registerPostOnboardingTriggers()
      _ = dsoAutomation.registerPostSequencerInitTriggers()
      _ <- checkIsOnboardedAndStartSvNamespaceMembershipTrigger(dsoAutomation, dsoStore, domainId)
      // The previous foundDso step will set the domain node config if DsoRules is not yet bootstrapped.
      // This is for the case that DsoRules is already bootstrapped but setting the domain node config is required,
      // for example if the founding SV node restarted after bootstrapping the DsoRules.
      // We only set the domain sequencer config if the existing one is different here.
      _ <- withDsoStore.reconcileSequencerConfigIfRequired(
        Some(localSynchronizerNode),
        config.domainMigrationId,
      )
    } yield (
      decentralizedSynchronizer,
      dsoPartyHosting,
      svStore,
      svAutomation,
      dsoStore,
      dsoAutomation,
    )
  }

  private def checkIsOnboardedAndStartSvNamespaceMembershipTrigger(
      dsoAutomation: SvDsoAutomationService,
      dsoStore: SvDsoStore,
      domainId: DomainId,
  )(implicit traceContext: TraceContext) =
    retryProvider
      .ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "dso_onboard",
        show"the DsoRules list the SV party ${dsoStore.key.svParty}",
        isOnboardedInDsoRules(dsoStore),
        Future.successful({}),
        logger,
      )
      .flatMap { _ =>
        checkIsInDecentralizedNamespaceAndStartTrigger(
          dsoAutomation,
          dsoStore,
          domainId,
        )
      }

  private def setupDsoParty(
      domain: DomainId,
      connection: BaseLedgerConnection,
      namespace: Namespace,
  )(implicit
      tc: TraceContext
  ): Future[PartyId] =
    for {
      dso <- connection.ensurePartyAllocated(
        TopologyStoreId.DomainStore(domain),
        foundingConfig.dsoPartyHint,
        Some(namespace),
        participantAdminConnection,
      )
      // this is idempotent
      _ <- connection.grantUserRights(
        config.ledgerApiUser,
        Seq(dso),
        Seq.empty,
      )
    } yield dso

  private def initialTrafficControlParameters: TrafficControlParameters = {
    TrafficControlParameters(
      foundingConfig.initialSynchronizerFeesConfig.baseRateBurstAmount,
      foundingConfig.initialSynchronizerFeesConfig.readVsWriteScalingFactor,
      // have to convert canton.config.NonNegativeDuration to canton.time.NonNegativeDuration
      NonNegativeFiniteDuration.tryOfMillis(
        foundingConfig.initialSynchronizerFeesConfig.baseRateBurstWindow.duration.toMillis
      ),
    )
  }

  private def bootstrapDomain(synchronizerNode: LocalSynchronizerNode)(implicit
      tc: TraceContext
  ): Future[(Namespace, DomainId)] = {
    withSpan("bootstrapDomain") { implicit tc => _ =>
      logger.info("Bootstrapping the domain as the founding node")

      (
        participantAdminConnection.getParticipantId(),
        synchronizerNode.mediatorAdminConnection.getMediatorId,
        synchronizerNode.sequencerAdminConnection.getSequencerId,
      ).flatMapN { case (participantId, mediatorId, sequencerId) =>
        val namespace =
          DecentralizedNamespaceDefinition.computeNamespace(Set(participantId.uid.namespace))
        val domainId = DomainId(
          UniqueIdentifier.tryCreate(
            "global-domain",
            namespace,
          )
        )
        val initialValues = DynamicDomainParameters.initialValues(clock, ProtocolVersion.v31)
        val values = initialValues.tryUpdate(
          // TODO(#6055) Consider increasing topology change delay again
          topologyChangeDelay = NonNegativeFiniteDuration.tryOfMillis(0),
          trafficControlParameters = Some(initialTrafficControlParameters),
          reconciliationInterval =
            PositiveSeconds.fromConfig(SvUtil.defaultAcsCommitmentReconciliationInterval),
        )
        val svKeyFingerprint = participantId.uid.namespace.fingerprint
        for {
          _ <- retryProvider.ensureThatO(
            RetryFor.WaitingOnInitDependency,
            "init_sequencer",
            "sequencer is initialized",
            synchronizerNode.sequencerAdminConnection.getStatus
              .map(_.successOption.map(_.domainId)),
            for {
              // must be done before the other topology transaction as the decentralize namespace is used for authorization
              decentralizedNamespace <- participantAdminConnection
                .proposeInitialDecentralizedNamespaceDefinition(
                  namespace,
                  NonEmpty.mk(Set, participantId.uid.namespace),
                  threshold = PositiveInt.one,
                  signedBy = svKeyFingerprint,
                )
              (
                identityTransactions,
                domainParametersState,
                sequencerState,
                mediatorState,
              ) <- (
                List(
                  participantAdminConnection,
                  synchronizerNode.mediatorAdminConnection,
                  synchronizerNode.sequencerAdminConnection,
                ).traverse { con =>
                  con
                    .getId()
                    .flatMap(con.getIdentityTransactions(_, TopologyStoreId.AuthorizedStore))
                }.map(_.flatten),
                participantAdminConnection.proposeInitialDomainParameters(
                  domainId,
                  values,
                  signedBy = svKeyFingerprint,
                ),
                participantAdminConnection.proposeInitialSequencerDomainState(
                  domainId,
                  active = Seq(sequencerId),
                  observers = Seq.empty,
                  signedBy = svKeyFingerprint,
                ),
                participantAdminConnection.proposeInitialMediatorDomainState(
                  domainId,
                  group = NonNegativeInt.zero,
                  active = Seq(mediatorId),
                  observers = Seq.empty,
                  signedBy = svKeyFingerprint,
                ),
              ).tupled
              bootstrapTransactions =
                (Seq(
                  decentralizedNamespace,
                  domainParametersState,
                  sequencerState,
                  mediatorState,
                ) ++ identityTransactions).sorted
                  .mapFilter(_.selectOp[TopologyChangeOp.Replace])
                  .map(signed =>
                    StoredTopologyTransaction(
                      SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
                      EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
                      None,
                      signed.copy(isProposal = false),
                    )
                  )
              _ <- synchronizerNode.sequencerAdminConnection.initializeFromBeginning(
                StoredTopologyTransactions(bootstrapTransactions),
                synchronizerNode.staticDomainParameters,
              )
            } yield (),
            logger,
          )
          _ <- retryProvider.ensureThatB(
            RetryFor.WaitingOnInitDependency,
            "init_mediator",
            "mediator is initialized",
            synchronizerNode.mediatorAdminConnection.getStatus.map(_.successOption.isDefined),
            synchronizerNode.mediatorAdminConnection.initialize(
              domainId,
              synchronizerNode.sequencerConnection,
            ),
            logger,
          )
        } yield (namespace, domainId)
      }
    }
  }

  /** A private class to share the dsoStoreWithIngestion and the global domain-id
    * across setup methods.
    */
  private class WithDsoStore(
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      domainId: DomainId,
  ) {

    private val dsoStore = dsoStoreWithIngestion.store
    private val dsoParty = dsoStore.key.dsoParty
    private val svParty = dsoStore.key.svParty
    private val synchronizerNodeReconciler = new SynchronizerNodeReconciler(
      dsoStore,
      dsoStoreWithIngestion.connection,
      clock = clock,
      retryProvider = retryProvider,
      logger = logger,
    )

    /** The one and only entry-point: found a fresh DSO, given a properly
      * allocated DSO party
      */
    def foundDso()(implicit
        tc: TraceContext
    ): Future[Unit] = retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      "bootstrap_dso",
      "bootstrapping DSO",
      bootstrapDso(),
      logger,
    )

    def reconcileSequencerConfigIfRequired(
        localSynchronizerNode: Option[LocalSynchronizerNode],
        migrationId: Long,
    )(implicit
        tc: TraceContext
    ): Future[Unit] = {
      synchronizerNodeReconciler.reconcileSynchronizerNodeConfigIfRequired(
        localSynchronizerNode,
        domainId,
        SynchronizerNodeState.Onboarded,
        migrationId,
      )
    }

    // Create DsoRules and AmuletRules and open the first mining round
    private def bootstrapDso()(implicit
        tc: TraceContext
    ): Future[Unit] = {
      val dsoRulesConfig = SvUtil.defaultDsoRulesConfig(domainId)
      for {
        (participantId, trafficStateForAllMembers, amuletRules, dsoRules) <- (
          participantAdminConnection.getParticipantId(),
          localSynchronizerNode.sequencerAdminConnection.listSequencerTrafficControlState(),
          dsoStore.lookupAmuletRules(),
          dsoStore.lookupDsoRulesWithOffset(),
        ).tupled
        _ <- dsoRules match {
          case QueryResult(offset, None) =>
            amuletRules match {
              case Some(amuletRules) =>
                sys.error(
                  "A AmuletRules contract was found but no DsoRules contract exists. " +
                    show"This should never happen.\nAmuletRules: $amuletRules"
                )
              case None =>
                val amuletConfig = defaultAmuletConfig(
                  foundingConfig.initialTickDuration,
                  foundingConfig.initialMaxNumInputs,
                  domainId,
                  foundingConfig.initialSynchronizerFeesConfig.extraTrafficPrice.value,
                  foundingConfig.initialSynchronizerFeesConfig.minTopupAmount.value,
                  foundingConfig.initialSynchronizerFeesConfig.baseRateBurstAmount.value,
                  foundingConfig.initialSynchronizerFeesConfig.baseRateBurstWindow,
                  foundingConfig.initialSynchronizerFeesConfig.readVsWriteScalingFactor.value,
                  foundingConfig.initialHoldingFee,
                )
                for {
                  founderSynchronizerNodes <- SvUtil.getFounderSynchronizerNodeConfig(
                    cometBftNode,
                    localSynchronizerNode,
                    config.scan,
                    domainId,
                    clock,
                    config.domainMigrationId,
                  )
                  _ = logger
                    .info(
                      s"Bootstrapping DSO as $dsoParty and BFT nodes $founderSynchronizerNodes"
                    )
                  _ <- dsoStoreWithIngestion.connection
                    .submit(
                      actAs = Seq(dsoParty),
                      readAs = Seq.empty,
                      new splice.dsobootstrap.DsoBootstrap(
                        dsoParty.toProtoPrimitive,
                        svParty.toProtoPrimitive,
                        foundingConfig.name,
                        foundingConfig.founderSvRewardWeightBps,
                        participantId.toProtoPrimitive,
                        founderSynchronizerNodes,
                        new RelTime(
                          TimeUnit.NANOSECONDS.toMicros(
                            foundingConfig.roundZeroDuration
                              .getOrElse(foundingConfig.initialTickDuration)
                              .duration
                              .toNanos
                          )
                        ),
                        amuletConfig,
                        foundingConfig.initialAmuletPrice.bigDecimal,
                        defaultAnsConfig(
                          foundingConfig.initialAnsConfig.renewalDuration,
                          foundingConfig.initialAnsConfig.entryLifetime,
                          foundingConfig.initialAnsConfig.entryFee,
                        ),
                        dsoRulesConfig,
                        trafficStateForAllMembers
                          .map(m =>
                            m.member.toProtoPrimitive -> new splice.dsorules.TrafficState(
                              m.extraTrafficConsumed.value
                            )
                          )
                          .toMap
                          .asJava,
                        foundingConfig.isDevNet,
                      ).createAnd.exerciseDsoBootstrap_Bootstrap,
                    )
                    .withDedup(
                      commandId = SpliceLedgerConnection
                        .CommandId("com.daml.network.dso.executeDsoBootstrap", Seq()),
                      deduplicationOffset = offset,
                    )
                    .withDomainId(domainId)
                    .yieldUnit()
                } yield ()
            }
          case QueryResult(_, Some(AssignedContract(dsoRules, _))) =>
            amuletRules match {
              case Some(amuletRules) =>
                if (dsoRules.payload.svs.keySet.contains(svParty.toProtoPrimitive)) {
                  logger.info(
                    "AmuletRules and DsoRules already exist and founding party is an SV; doing nothing." +
                      show"\nAmuletRules: $amuletRules\nDsoRules: $dsoRules"
                  )
                  Future.successful(())
                } else {
                  sys.error(
                    "AmuletRules and DsoRules already exist but party tasked with founding the DSO isn't member." +
                      "Is more than one SV app configured to `found-dso`?" +
                      show"\nAmuletRules: $amuletRules\nDsoRules: $dsoRules"
                  )
                }
              case None =>
                sys.error(
                  "An DsoRules contract was found but no AmuletRules contract exists. " +
                    show"This should never happen.\nDsoRules: $dsoRules"
                )
            }
        }
      } yield ()
    }

  }

}

object FoundingNodeInitializer {

  /** Same ordering as https://github.com/DACH-NY/canton/blob/2fc1a37d815623cb68dcb4b75bc33a498065990e/enterprise/app-base/src/main/scala/com/digitalasset/canton/console/EnterpriseConsoleMacros.scala#L160
    */
  implicit val bootstrapTransactionOrdering
      : Ordering[SignedTopologyTransaction[TopologyChangeOp, TopologyMapping]] =
    (x, y) => {
      def toOrdinal(t: SignedTopologyTransaction[TopologyChangeOp, TopologyMapping]) = {
        t.transaction.mapping.code match {
          case Code.NamespaceDelegation => 1
          case Code.OwnerToKeyMapping => 2
          case Code.DecentralizedNamespaceDefinition => 3
          case _ => 4
        }
      }

      Ordering.Int.compare(
        toOrdinal(x),
        toOrdinal(y),
      )
    }

}
