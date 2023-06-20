package com.daml.network.sv.setup

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.syntax.functorFilter.*
import cats.syntax.traverse.*
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn
import com.daml.network.environment.*
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.{QueryResult, ReadyContract}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvUtil, SvcRulesLock}
import com.daml.network.util.CNNodeUtil.{
  defaultCoinConfig,
  defaultCoinConfigSchedule,
  defaultEnabledChoices,
}
import com.daml.network.util.{TemplateJsonDecoder, UploadablePackage}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
}
import com.digitalasset.canton.topology.transaction.{TopologyChangeOpX, UnionspaceDefinitionX}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContextExecutor, Future}
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
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
) extends NamedLogging {

  @nowarn("cat=unused")
  private def noLock[T](reason: String, f: () => Future[T]): Future[T] = f()

  def bootstrapCollective(): Future[
    (
        DomainId,
        SvcPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvSvcStore,
        SvSvcAutomationService,
        SvcRulesLock,
    )
  ] = {
    val initConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
    for {
      namespace <- bootstrapDomain(localDomainNode)
      _ = logger.info("Domain is bootstrapped, connecting founding participant to domain")
      _ <- participantAdminConnection.ensureDomainRegistered(
        DomainConnectionConfig(
          config.domains.global.alias,
          sequencerConnections = SequencerConnections.single(
            GrpcSequencerConnection.tryCreate(config.domains.global.url)
          ),
          manualConnect = false,
          domainId = None,
        )
      )
      _ = logger.info("Participant connected to domain")
      svcParty <- setupSvcParty(initConnection, namespace)
      // founder does not need to lock here
      svParty <- SetupUtil.setupSvParty(initConnection, config, noLock)
      storeKey = SvStore.Key(svParty, svcParty)
      svStore = newSvStore(storeKey)
      svAutomation = newSvSvAutomationService(
        svStore,
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

      svcStore = newSvcStore(svStore.key)
      svcAutomation = newSvSvcAutomationService(svStore, svcStore, ledgerClient, cometBftNode)
      _ <- svcStore.domains.waitForDomainConnection(config.domains.global.alias)
      _ <- retryProvider.ensureThatB(
        show"the SvcRules and CoinRules are bootstrapped",
        isOnboarded(svcStore), {
          new WithSvcStore(svcAutomation, globalDomain).foundCollective()
        },
        logger,
      )
      svcRulesLock = new SvcRulesLock(globalDomain, svcAutomation, retryProvider, loggerFactory)
    } yield (
      globalDomain,
      svcPartyHosting,
      svStore,
      svAutomation,
      svcStore,
      svcAutomation,
      svcRulesLock,
    )
  }

  private def setupSvcParty(connection: CNLedgerConnection, namespace: Namespace): Future[PartyId] =
    for {
      svc <- connection.ensurePartyAllocated(
        foundingConfig.svcPartyHint,
        namespace,
        participantAdminConnection,
        // founder does not need to lock here.
        noLock,
      )
      // this is idempotent
      _ <- connection.grantUserRights(
        config.ledgerApiUser,
        Seq(svc),
        Seq.empty,
      )
    } yield svc

  private def bootstrapDomain(domainNode: LocalDomainNode): Future[Namespace] = {
    logger.info("Bootstrapping the domain as the founding node")
    for {
      participantId <- participantAdminConnection.getParticipantId()
      mediatorId <- domainNode.mediatorAdminConnection.getMediatorId
      sequencerId <- domainNode.sequencerAdminConnection.getSequencerId
      namespace = UnionspaceDefinitionX.computeNamespace(Set(participantId.uid.namespace))
      domainId <- retryProvider.ensureThatO(
        "sequencer is initialized",
        domainNode.sequencerAdminConnection.getStatus.map(_.successOption.map(_.domainId)),
        for {
          identityTransactions <- List(
            participantAdminConnection,
            domainNode.mediatorAdminConnection,
            domainNode.sequencerAdminConnection,
          ).traverse { con =>
            con.getId().flatMap(con.getIdentityTransactions(_, domainId = None))
          }.map(_.flatten)
          // Proposing the same state is idempotent so we don't bother wrapping all of these in a check if the transaction has already
          // been proposed.
          unionspace <- participantAdminConnection.proposeInitialUnionspaceDefinition(
            namespace,
            NonEmpty.mk(Set, participantId.uid.namespace),
            threshold = PositiveInt.one,
            signedBy = participantId.uid.namespace.fingerprint,
          )
          domainId = DomainId(
            UniqueIdentifier(
              Identifier.tryCreate("global-domain"),
              namespace,
            )
          )
          domainParametersState <- participantAdminConnection.proposeInitialDomainParameters(
            domainId,
            DynamicDomainParameters.initialXValues(clock, ProtocolVersion.dev),
            signedBy = participantId.uid.namespace.fingerprint,
          )
          sequencerState <- TopologyAdminConnection.proposeCollectively(
            NonEmpty.mk(List, participantAdminConnection, domainNode.sequencerAdminConnection)
          ) { case (con, id) =>
            con.proposeInitialSequencerDomainState(
              domainId,
              active = Seq(sequencerId),
              observers = Seq.empty,
              signedBy = id.namespace.fingerprint,
            )
          }
          mediatorState <- TopologyAdminConnection.proposeCollectively(
            NonEmpty.mk(List, participantAdminConnection, domainNode.mediatorAdminConnection)
          ) { case (con, id) =>
            con.proposeInitialMediatorDomainState(
              domainId,
              group = NonNegativeInt.zero,
              active = Seq(mediatorId),
              observers = Seq.empty,
              signedBy = id.namespace.fingerprint,
            )
          }
          bootstrapTransactions =
            (Seq(
              unionspace,
              domainParametersState,
              sequencerState,
              mediatorState,
            ) ++ identityTransactions)
              .mapFilter(_.selectOp[TopologyChangeOpX.Replace])
              .map(signed =>
                StoredTopologyTransactionX(
                  SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
                  EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
                  None,
                  signed,
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
        "mediator is initialized",
        domainNode.mediatorAdminConnection.getStatus.map(_.successOption.isDefined),
        domainNode.mediatorAdminConnection.initialize(
          domainId,
          domainNode.staticDomainParameters,
          domainNode.sequencerConnection,
        ),
        logger,
      )
    } yield namespace
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

    /** The one and only entry-point: found a fresh collective, given a properly allocated SVC party */
    def foundCollective(): Future[Unit] = {
      for {
        // Founder does not need to lock
        _ <- svcStoreWithIngestion.connection.uploadDarFiles(requiredDars, noLock)
        _ <- retryProvider.retryForAutomation(
          "bootstrapping SVC",
          bootstrapSvc(),
          logger,
        )
      } yield ()
    }

    // Create SvcRules and CoinRules and open the first mining round
    private def bootstrapSvc(): Future[Unit] = {
      for {
        coinRules <- svcStore.lookupCoinRules()
        founderDomainNodes <- SvUtil
          .getFounderDomainNodeConfig(cometBftNode)
        _ <- svcStore.lookupSvcRulesWithOffset().flatMap {
          case QueryResult(offset, None) => {
            coinRules match {
              case Some(coinRules) =>
                sys.error(
                  "A CoinRules contract was found but no SvcRules contract exists. " +
                    show"This should never happen.\nCoinRules: $coinRules"
                )
              case None =>
                logger.info(s"Bootstrapping SVC as $svcParty with BFT nodes $founderDomainNodes")
                svcStoreWithIngestion.connection
                  .submitCommands(
                    actAs = Seq(svcParty),
                    readAs = Seq.empty,
                    commands = new cn.svcbootstrap.SvcBootstrap(
                      svcParty.toProtoPrimitive,
                      svParty.toProtoPrimitive,
                      foundingConfig.name,
                      founderDomainNodes,
                      defaultCoinConfig(
                        foundingConfig.initialTickDuration,
                        foundingConfig.initialMaxNumInputs,
                        domainId,
                      ),
                      foundingConfig.initialCoinPrice.bigDecimal,
                      SvUtil.defaultSvcRulesConfig(),
                      defaultEnabledChoices,
                      config.isDevNet,
                    ).createAnd
                      .exerciseSvcBootstrap_Bootstrap()
                      .commands
                      .asScala
                      .toSeq,
                    commandId = CNLedgerConnection
                      .CommandId("com.daml.network.svc.executeSvcBootstrap", Seq()),
                    deduplicationOffset = offset,
                    domainId = domainId,
                  )
            }
          }
          case QueryResult(_, Some(ReadyContract(svcRules, _))) =>
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
        _ <- createUpgradedCoinRulesIfEnabled()
      } yield ()
    }

    private def createUpgradedCoinRulesIfEnabled(): Future[Unit] =
      if (config.enableCoinRulesUpgrade)
        createUpgradedCoinRules()
      else
        Future.unit

    private def createUpgradedCoinRules(): Future[Unit] = {
      for {
        _ <- svcStore.lookupCoinRulesV1TestWithOffset().flatMap {
          case QueryResult(offset, None) =>
            svcStoreWithIngestion.connection.submitWithResult(
              actAs = Seq(svcParty),
              readAs = Seq.empty,
              update = new ccV1Test.coin.CoinRulesV1Test(
                svcParty.toProtoPrimitive,
                defaultCoinConfigSchedule(
                  foundingConfig.initialTickDuration,
                  foundingConfig.initialMaxNumInputs,
                  domainId,
                ),
                defaultEnabledChoices,
                config.isDevNet,
                false,
              ).create(),
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.svc.initiateCoinRulesUpgrade",
                Seq(svcParty),
              ),
              deduplicationConfig = DedupOffset(offset),
              domainId = domainId,
            )
          case QueryResult(_, Some(_)) =>
            logger.info("Upgraded CoinRules (V1Test) contract already exists")
            Future.successful(())
        }
      } yield logger.debug("Created an upgraded CoinRules (V1Test) contract")
    }
  }

  // TODO(#5364): inline these methods if they are used only once, or share them properly
  private def newSvStore(key: SvStore.Key) = SvSvStore(
    key,
    storage,
    config.domains,
    loggerFactory,
    retryProvider,
  )

  private def newSvSvAutomationService(
      svStore: SvSvStore,
      ledgerClient: CNLedgerClient,
  ) =
    new SvSvAutomationService(
      clock,
      config,
      svStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
    )

  private def newSvcStore(key: SvStore.Key) = SvSvcStore(
    key,
    storage,
    config,
    loggerFactory,
    retryProvider,
  )

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
      loggerFactory,
    )

  private def newSvcPartyHosting(
      storeKey: SvStore.Key,
      participantAdminConnection: ParticipantAdminConnection,
  ) = new SvcPartyHosting(
    config.onboarding,
    participantAdminConnection,
    storeKey.svcParty,
    retryProvider,
    loggerFactory,
  )

  private def isOnboarded(svcStore: SvSvcStore): Future[Boolean] = for {
    svcRules <- svcStore.lookupSvcRules()
  } yield svcRules.exists(
    _.contract.payload.members.keySet.contains(svcStore.key.svParty.toProtoPrimitive)
  )

}
