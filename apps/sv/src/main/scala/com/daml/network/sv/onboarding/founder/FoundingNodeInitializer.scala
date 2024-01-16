package com.daml.network.sv.onboarding.founder

import cats.implicits.{
  catsSyntaxTuple2Semigroupal,
  catsSyntaxTuple3Semigroupal,
  catsSyntaxTuple5Semigroupal,
}
import cats.syntax.functorFilter.*
import cats.syntax.traverse.*
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.*
import com.daml.network.http.v0.definitions as http
import com.daml.network.store.MultiDomainAcsStore.*
import com.daml.network.store.{AcsStoreDump, CNNodeAppStoreWithIngestion, PageLimit}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.automation.SvSvcAutomationService.LocalSequencerClientContext
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{
  SequencerPruningConfig,
  SvAppBackendConfig,
  SvBootstrapDumpConfig,
  SvOnboardingConfig,
}
import com.daml.network.sv.onboarding.DomainNodeReconciler.DomainNodeState
import com.daml.network.sv.onboarding.founder.FoundingNodeInitializer.bootstrapTransactionOrdering
import com.daml.network.sv.onboarding.{DomainNodeReconciler, SetupUtil, SvcPartyHosting}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.CNNodeUtil.{defaultCnsConfig, defaultCoinConfig}
import com.daml.network.util.{AssignedContract, GcpBucket, TemplateJsonDecoder, UploadablePackage}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnections,
  TrafficControlParameters,
}
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code
import com.digitalasset.canton.topology.transaction.{
  DecentralizedNamespaceDefinitionX,
  SignedTopologyTransactionX,
  TopologyChangeOpX,
  TopologyMappingX,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContextExecutor, Future, blocking}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize the founding SV node. */
class FoundingNodeInitializer(
    // TODO(#5364): cleanup the order and mass of these parameters
    config: SvAppBackendConfig,
    foundingConfig: SvOnboardingConfig.FoundCollective,
    cometBftNode: Option[CometBftNode],
    requiredDars: Seq[UploadablePackage],
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    participantId: ParticipantId,
    clock: Clock,
    storage: Storage,
    localDomainNode: LocalDomainNode,
)(implicit
    ec: ExecutionContextExecutor,
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
) extends NamedLogging {

  def bootstrapCollective(): Future[
    (
        DomainId,
        SvcPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvSvcStore,
        SvSvcAutomationService,
    )
  ] = {
    val initConnection = ledgerClient.readOnlyConnection(
      this.getClass.getSimpleName,
      loggerFactory,
    )

    for {
      (namespace, domainId) <- bootstrapDomain(localDomainNode)
      _ = logger.info("Domain is bootstrapped, connecting founding participant to domain")
      _ <- participantAdminConnection.ensureDomainRegistered(
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
      svcParty <- setupSvcParty(domainId, initConnection, namespace)
      // founder does not need to lock here
      svParty <- SetupUtil.setupSvParty(initConnection, config, participantAdminConnection)
      _ <- participantAdminConnection.uploadDarFiles(
        requiredDars,
        RetryFor.WaitingOnInitDependency,
      )
      storeKey = SvStore.Key(svParty, svcParty)
      svStore = newSvStore(storeKey)
      svcStore = newSvcStore(svStore.key)
      svAutomation = newSvSvAutomationService(
        svStore,
        svcStore,
        ledgerClient,
      )
      _ <- SetupUtil.ensureSvcPartyMetadataAnnotation(svAutomation.connection, config, svcParty)
      globalDomain <- svStore.domains.waitForDomainConnection(config.domains.global.alias)
      svcPartyHosting = newSvcPartyHosting(
        storeKey,
        participantAdminConnection,
      )
      // NOTE: we assume that SVC party, cometBft node, sequencer, and mediator nodes are initialized as
      // part of deployment and the running of bootstrap scripts. Here we just check that the SVC party
      // is allocated, as a stand-in for all of these actions.
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        show"SVC party $svcParty is allocated on participant $participantId and domain $globalDomain",
        for {
          svcPartyIsAuthorized <- svcPartyHosting.isSvcPartyAuthorizedOn(
            globalDomain,
            participantId,
          )
        } yield
          (
            if (svcPartyIsAuthorized) ()
            else
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  s"SVC party is allocated on participant $participantId and domain $globalDomain"
                )
                .asRuntimeException()
          ),
        logger,
      )

      svcAutomation = newSvSvcAutomationService(
        svStore,
        svcStore,
        ledgerClient,
        cometBftNode,
      )
      _ = svcAutomation.registerPostOnboardingTriggers()
      _ <- svcStore.domains.waitForDomainConnection(config.domains.global.alias)
      withSvcStore = new WithSvcStore(svcAutomation, globalDomain)
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        show"the SvcRules and CoinRules are bootstrapped",
        svcStore.lookupSvcRules().map(_.isDefined), {
          withSvcStore.foundCollective()
        },
        logger,
      )
      // The previous foundCollective step will set the domain node config if SvcRules is not yet bootstrapped.
      // This is for the case that SvcRules is already bootstrapped but setting the domain node config is required,
      // for example if the founding SV node restarted after bootstrapping the SvcRules.
      // We only set the domain sequencer config if the existing one is different here.
      _ <- withSvcStore.reconcileSequencerConfigIfRequired(Some(localDomainNode))
    } yield (
      globalDomain,
      svcPartyHosting,
      svStore,
      svAutomation,
      svcStore,
      svcAutomation,
    )
  }

  private def setupSvcParty(
      domain: DomainId,
      connection: BaseLedgerConnection,
      namespace: Namespace,
  ): Future[PartyId] =
    for {
      svc <- connection.ensurePartyAllocated(
        TopologyStoreId.DomainStore(domain),
        foundingConfig.svcPartyHint,
        Some(namespace),
        participantAdminConnection,
      )
      // this is idempotent
      _ <- connection.grantUserRights(
        config.ledgerApiUser,
        Seq(svc),
        Seq.empty,
      )
    } yield svc

  private def initialTrafficControlParameters: TrafficControlParameters = {
    TrafficControlParameters(
      foundingConfig.initialTrafficControlConfig.baseRateBurstAmount,
      foundingConfig.initialTrafficControlConfig.readVsWriteScalingFactor,
      // have to convert canton.config.NonNegativeDuration to canton.time.NonNegativeDuration
      NonNegativeFiniteDuration.tryOfMillis(
        foundingConfig.initialTrafficControlConfig.baseRateBurstWindow.duration.toMillis
      ),
    )
  }

  private def bootstrapDomain(domainNode: LocalDomainNode): Future[(Namespace, DomainId)] = {
    logger.info("Bootstrapping the domain as the founding node")

    (
      participantAdminConnection.getParticipantId(),
      domainNode.mediatorAdminConnection.getMediatorId,
      domainNode.sequencerAdminConnection.getSequencerId,
    ).flatMapN { case (participantId, mediatorId, sequencerId) =>
      val namespace =
        DecentralizedNamespaceDefinitionX.computeNamespace(Set(participantId.uid.namespace))
      val domainId = DomainId(
        UniqueIdentifier(
          Identifier.tryCreate("global-domain"),
          namespace,
        )
      )
      val initialValues = DynamicDomainParameters.initialValues(clock, ProtocolVersion.dev)
      val values = initialValues.copy(
        // TODO(#6055) Consider increasing topology change delay again
        topologyChangeDelay = NonNegativeFiniteDuration.tryOfMillis(0),
        trafficControlParameters = Some(initialTrafficControlParameters),
      )(initialValues.representativeProtocolVersion)
      for {
        _ <- retryProvider.ensureThatO(
          RetryFor.WaitingOnInitDependency,
          "sequencer is initialized",
          domainNode.sequencerAdminConnection.getStatus.map(_.successOption.map(_.domainId)),
          for {
            (
              identityTransactions,
              decentralizedNamespace,
              domainParametersState,
              sequencerState,
              mediatorState,
            ) <- (
              List(
                participantAdminConnection,
                domainNode.mediatorAdminConnection,
                domainNode.sequencerAdminConnection,
              ).traverse { con =>
                con.getId().flatMap(con.getIdentityTransactions(_, domainId = None))
              }.map(_.flatten),
              // Proposing the same state is idempotent so we don't bother wrapping all of these in a check if the transaction has already
              // been proposed.
              participantAdminConnection.proposeInitialDecentralizedNamespaceDefinition(
                namespace,
                NonEmpty.mk(Set, participantId.uid.namespace),
                threshold = PositiveInt.one,
                signedBy = participantId.uid.namespace.fingerprint,
              ),
              participantAdminConnection.proposeInitialDomainParameters(
                domainId,
                values,
                signedBy = participantId.uid.namespace.fingerprint,
              ),
              TopologyAdminConnection.proposeCollectively(
                NonEmpty.mk(List, participantAdminConnection, domainNode.sequencerAdminConnection)
              ) { case (con, id) =>
                con.proposeInitialSequencerDomainState(
                  domainId,
                  active = Seq(sequencerId),
                  observers = Seq.empty,
                  signedBy = id.namespace.fingerprint,
                )
              },
              TopologyAdminConnection.proposeCollectively(
                NonEmpty.mk(List, participantAdminConnection, domainNode.mediatorAdminConnection)
              ) { case (con, id) =>
                con.proposeInitialMediatorDomainState(
                  domainId,
                  group = NonNegativeInt.zero,
                  active = Seq(mediatorId),
                  observers = Seq.empty,
                  signedBy = id.namespace.fingerprint,
                )
              },
            ).tupled
            bootstrapTransactions =
              (Seq(
                decentralizedNamespace,
                domainParametersState,
                sequencerState,
                mediatorState,
              ) ++ identityTransactions).sorted
                .mapFilter(_.selectOp[TopologyChangeOpX.Replace])
                .map(signed =>
                  StoredTopologyTransactionX(
                    SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
                    EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
                    None,
                    signed.copy(isProposal = false),
                  )
                )
            _ <- domainNode.sequencerAdminConnection.initialize(
              StoredTopologyTransactionsX(bootstrapTransactions),
              domainNode.staticDomainParameters,
              None,
            )
          } yield (),
          logger,
        )
        _ <- retryProvider.ensureThatB(
          RetryFor.WaitingOnInitDependency,
          "mediator is initialized",
          domainNode.mediatorAdminConnection.getStatus.map(_.successOption.isDefined),
          domainNode.mediatorAdminConnection.initialize(
            domainId,
            domainNode.staticDomainParameters,
            domainNode.sequencerConnection,
          ),
          logger,
        )
      } yield (namespace, domainId)
    }
  }

  /** A private class to share the svcStoreWithIngestion and the global domain-id
    * across setup methods.
    */
  private class WithSvcStore(
      svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
      domainId: DomainId,
  ) {

    private val svcStore = svcStoreWithIngestion.store
    private val svcParty = svcStore.key.svcParty
    private val svParty = svcStore.key.svParty
    private val domainNodeReconciler = new DomainNodeReconciler(
      svcStore,
      svcStoreWithIngestion.connection,
      config.scan,
      clock,
      retryProvider,
      logger,
    )

    /** The one and only entry-point: found a fresh collective, given a properly allocated SVC party */
    def foundCollective(): Future[Unit] = retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      "bootstrapping SVC",
      bootstrapSvc(),
      logger,
    )

    def reconcileSequencerConfigIfRequired(
        localDomainNode: Option[LocalDomainNode]
    ): Future[Unit] = {
      domainNodeReconciler.reconcileDomainNodeConfigIfRequired(
        localDomainNode,
        domainId,
        DomainNodeState.Onboarded,
      )
    }

    private def importAcsSnapshot(): Future[Option[cc.round.types.Round]] =
      foundingConfig.bootstrappingDump match {
        case None =>
          logger.debug("Skipping importing an ACS snapshot, as none was configured.")
          Future.successful(None)
        case Some(config) =>
          logger.debug(s"Attempting to import ACS snapshot from ${config.description}")
          for {
            existingCrates <- svcStore.multiDomainAcsStore.listAssignedContracts(
              cc.coinimport.ImportCrate.COMPANION,
              PageLimit.tryCreate(10),
            )
            _ = if (existingCrates.nonEmpty) {
              logger.error(
                show"Aborting founding node initialization, as the following crates already exist (probably due to a earlier partial init of the founding node):\n$existingCrates"
              )
              throw new IllegalStateException(
                "Cannot import the ACS snapshot, as there already was an earlier partial or different import."
              )
            }
            jsonDump <- Future {
              blocking {
                val jsonString = config match {
                  case SvBootstrapDumpConfig.File(file) =>
                    better.files.File(file).contentAsString
                  case SvBootstrapDumpConfig.Gcp(bucketConfig, path) =>
                    val bucket = new GcpBucket(bucketConfig, loggerFactory)
                    bucket.readStringFromBucket(path)
                }
                io.circe.parser
                  .decode[http.GetAcsStoreDumpResponse](jsonString)
                  .fold(
                    err =>
                      throw new IllegalArgumentException(
                        s"Failed to parse ${config.description}: $err"
                      ),
                    result => result,
                  )
              }
            }
            cmds = AcsStoreDump.extractImportCommands(svcParty)(jsonDump.contracts.toSeq)
            _ <-
              if (cmds.isEmpty) {
                logger.debug(
                  show"Extracted zero import commands; not going to submit any transaction."
                )
                Future.unit
              } else {
                logger.debug(
                  show"Extracted ${cmds.size} import commands; attempting to submit them in one transaction"
                )
                // Note: we assume that the Acs dump is small enough to be imported in a single transaction.
                svcStoreWithIngestion.connection
                  .submit(actAs = Seq(svcParty), readAs = Seq.empty, cmds)
                  .withDomainId(domainId)
                  .noDedup
                  .yieldUnit()
              }
          } yield {
            if (cmds.isEmpty) {
              logger.debug(
                "Skipping importing an ACS snapshot, as no ImportCommands are extracted from the acs dump."
              )
              None
            } else {

              val initialOpenMiningRound =
                AcsStoreDump.extractEarliestOpenMiningRound(jsonDump.contracts)
              logger.info(
                s"Completed importing ACS store dump by submitting ${cmds.size} commands in one transaction."
              )
              initialOpenMiningRound
            }
          }
      }

    // Import AcsSnapshot and Create SvcRules and CoinRules and open the first mining round
    private def bootstrapSvc(): Future[Unit] = {
      val svcRulesConfig = SvUtil.defaultSvcRulesConfig(domainId)
      for {
        (participantId, mediatorId, trafficStateForAllMembers, coinRules, svcRules) <- (
          participantAdminConnection.getParticipantId(),
          localDomainNode.mediatorAdminConnection.getMediatorId,
          localDomainNode.sequencerAdminConnection
            .getSequencerTrafficStatus()
            .map(_.members),
          svcStore.lookupCoinRules(),
          svcStore.lookupSvcRulesWithOffset(),
        ).tupled
        participantTrafficState = trafficStateForAllMembers
          .find(_.member == participantId)
          .map(_.trafficState)
        _ <- (
          participantAdminConnection.ensureTrafficControlState(
            domainId,
            participantId,
            participantTrafficState.fold(0L)(
              _.extraTrafficConsumed.value
            ) + svcRulesConfig.initialTrafficGrant,
            participantId.uid.namespace.fingerprint,
          ),
          // TODO(#6256): Remove this and make SvApp pay for mediator traffic as well
          participantAdminConnection.ensureTrafficControlState(
            domainId,
            mediatorId,
            Long.MaxValue,
            participantId.uid.namespace.fingerprint,
          ),
        ).tupled
        _ <- svcRules match {
          case QueryResult(offset, None) => {
            coinRules match {
              case Some(coinRules) =>
                sys.error(
                  "A CoinRules contract was found but no SvcRules contract exists. " +
                    show"This should never happen.\nCoinRules: $coinRules"
                )
              case None =>
                for {
                  optInitialOpenMiningRound <- importAcsSnapshot()
                  initialRoundNumber = optInitialOpenMiningRound.fold(0L)(_.number)
                  founderDomainNodes <- SvUtil.getFounderDomainNodeConfig(
                    cometBftNode,
                    localDomainNode,
                    config.scan,
                    domainId,
                    clock,
                  )
                  _ = logger
                    .info(
                      s"Bootstrapping SVC as $svcParty with initial round number $initialRoundNumber and BFT nodes $founderDomainNodes"
                    )
                  _ <- svcStoreWithIngestion.connection
                    .submit(
                      actAs = Seq(svcParty),
                      readAs = Seq.empty,
                      new cn.svcbootstrap.SvcBootstrap(
                        svcParty.toProtoPrimitive,
                        svParty.toProtoPrimitive,
                        foundingConfig.name,
                        foundingConfig.founderSvRewardWeight,
                        participantId.toProtoPrimitive,
                        founderDomainNodes,
                        initialRoundNumber,
                        new RelTime(
                          TimeUnit.NANOSECONDS.toMicros(
                            foundingConfig.roundZeroDuration
                              .getOrElse(foundingConfig.initialTickDuration)
                              .duration
                              .toNanos
                          )
                        ),
                        defaultCoinConfig(
                          foundingConfig.initialTickDuration,
                          foundingConfig.initialMaxNumInputs,
                          domainId,
                          foundingConfig.initialTrafficControlConfig.baseRateBurstAmount.value,
                          foundingConfig.initialTrafficControlConfig.baseRateBurstWindow,
                          foundingConfig.initialTrafficControlConfig.readVsWriteScalingFactor.value,
                        ),
                        foundingConfig.initialCoinPrice.bigDecimal,
                        defaultCnsConfig(
                          foundingConfig.initialCnsConfig.renewalDuration,
                          foundingConfig.initialCnsConfig.entryLifetime,
                          foundingConfig.initialCnsConfig.entryFee,
                        ),
                        svcRulesConfig,
                        trafficStateForAllMembers
                          .map(m =>
                            m.member.toProtoPrimitive -> new cn.svcrules.TrafficState(
                              m.trafficState.extraTrafficConsumed.value
                            )
                          )
                          .toMap
                          .asJava,
                        foundingConfig.isDevNet,
                      ).createAnd.exerciseSvcBootstrap_Bootstrap,
                    )
                    .withDedup(
                      commandId = CNLedgerConnection
                        .CommandId("com.daml.network.svc.executeSvcBootstrap", Seq()),
                      deduplicationOffset = offset,
                    )
                    .withDomainId(domainId)
                    .yieldUnit()
                } yield ()
            }
          }
          case QueryResult(_, Some(AssignedContract(svcRules, _))) =>
            coinRules match {
              case Some(coinRules) => {
                if (svcRules.payload.members.keySet.contains(svParty.toProtoPrimitive)) {
                  logger.info(
                    "CoinRules and SvcRules already exist and founding party is an SVC member; doing nothing." +
                      show"\nCoinRules: $coinRules\nSvcRules: $svcRules"
                  )
                  Future.successful(())
                } else {
                  sys.error(
                    "CoinRules and SvcRules already exist but party tasked with founding the SVC isn't member." +
                      "Is more than one SV app configured to `found-collective`?" +
                      show"\nCoinRules: $coinRules\nSvcRules: $svcRules"
                  )
                }
              }
              case None =>
                sys.error(
                  "An SvcRules contract was found but no CoinRules contract exists. " +
                    show"This should never happen.\nSvcRules: $svcRules"
                )
            }
        }
      } yield ()
    }

  }

  // TODO(#5364): inline these methods if they are used only once, or share them properly
  private def newSvStore(key: SvStore.Key) = SvSvStore(
    key,
    storage,
    loggerFactory,
    retryProvider,
  )

  private def newSvSvAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      ledgerClient: CNLedgerClient,
  ) =
    new SvSvAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
    )

  private def newSvcStore(key: SvStore.Key) = {
    SvSvcStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
    )
  }

  private def newSvSvcAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      ledgerClient: CNLedgerClient,
      cometBftNode: Option[CometBftNode],
  ) =
    new SvSvcAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
      cometBftNode,
      Some(
        LocalSequencerClientContext(
          localDomainNode.sequencerAdminConnection,
          // Founding SV should have already connected to its sequencer.
          // We put None here to indicate automation service not to create LocalSequencerConnectionsTrigger
          // which is responsible for reconnecting to its own sequencer
          internalClientConfig = None,
          localDomainNode.sequencerPruningConfig.map(pruningConfig =>
            SequencerPruningConfig(
              pruningConfig.pruningInterval,
              pruningConfig.retentionPeriod,
            )
          ),
        )
      ),
      loggerFactory,
    )

  private def newSvcPartyHosting(
      storeKey: SvStore.Key,
      participantAdminConnection: ParticipantAdminConnection,
  ) = new SvcPartyHosting(
    participantAdminConnection,
    storeKey.svcParty,
    retryProvider,
    loggerFactory,
  )

}

object FoundingNodeInitializer {

  /** Same ordering as https://github.com/DACH-NY/canton/blob/2fc1a37d815623cb68dcb4b75bc33a498065990e/enterprise/app-base/src/main/scala/com/digitalasset/canton/console/EnterpriseConsoleMacros.scala#L160
    */
  implicit val bootstrapTransactionOrdering
      : Ordering[SignedTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]] =
    (x, y) => {
      def toOrdinal(t: SignedTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]) = {
        t.transaction.mapping.code match {
          case Code.NamespaceDelegationX => 1
          case Code.OwnerToKeyMappingX => 2
          case Code.DecentralizedNamespaceDefinitionX => 3
          case _ => 4
        }
      }

      Ordering.Int.compare(
        toOrdinal(x),
        toOrdinal(y),
      )
    }

}
