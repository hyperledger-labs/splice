package com.daml.network.sv.setup

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.*
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.{CNNodeAppStoreWithIngestion, DomainStore}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvUtil, SvcRulesLock}
import com.daml.network.svc.admin.api.client.SvcConnection
import com.daml.network.util.CNNodeUtil.{
  defaultCoinConfig,
  defaultCoinConfigSchedule,
  defaultEnabledChoices,
}
import com.daml.network.util.{TemplateJsonDecoder, UploadablePackage}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

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
    futureSupervisor: FutureSupervisor,
    coinAppParameters: SharedCNNodeAppParameters,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
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
        SvcRulesLock,
    )
  ] = {
    val initConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
    for {
      // TODO(#3856): find a better way to get the SVC party ID
      svcParty <- retryProvider.getValueWithRetries("SVC party ID", getSvcPartyId, logger)
      svParty <- SetupUtil.setupSvParty(initConnection, config)

      storeKey = SvStore.Key(svParty, svcParty)
      svStore = newSvStore(storeKey)
      svAutomation = newSvSvAutomationService(
        svStore,
        ledgerClient,
      )
      _ <- svAutomation.connection.ensureUserMetadataAnnotation(
        config.ledgerApiUser,
        CNLedgerConnection.SVC_PARTY_USER_METADATA_KEY,
        svcParty.toProtoPrimitive,
      )
      globalDomain <- waitForDomainConnection(svStore.domains, config.domains.global.alias)
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
      _ <- waitForDomainConnection(svcStore.domains, config.domains.global.alias)
      _ <- retryProvider.ensureThat(
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
        _ <- svcStoreWithIngestion.connection.uploadDarFiles(requiredDars)
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
        // TODO(#5428): retry on failure
        founderDomainNodes <- SvUtil
          .getFounderDomainNodeConfig(cometBftNode)
          .fold(error => sys.error(s"Failed to initialize the domain nodes: $error"), identity)
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
          case QueryResult(_, Some(svcRules)) =>
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
    futureSupervisor,
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
      retryProvider.timeouts,
    )

  private def newSvcStore(key: SvStore.Key) = SvSvcStore(
    key,
    storage,
    config,
    loggerFactory,
    futureSupervisor,
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
      retryProvider,
      cometBftNode,
      loggerFactory,
      retryProvider.timeouts,
    )

  private def newSvcPartyHosting(
      storeKey: SvStore.Key,
      participantAdminConnection: ParticipantAdminConnection,
  ) = new SvcPartyHosting(
    config.onboarding,
    participantAdminConnection,
    storeKey.svcParty,
    config.xNodes.isDefined,
    coinAppParameters,
    retryProvider,
    loggerFactory,
  )

  // TODO(#5437): remove this duplication of the method which is also present on CNNode
  protected def waitForDomainConnection(
      store: DomainStore,
      domain: DomainAlias,
  ): Future[DomainId] = {
    logger.info(show"Waiting for domain $domain to be connected")
    store.signalWhenConnected(domain).map { r =>
      logger.info(show"Connection to domain $domain has been established")
      r
    }
  }

  private def getSvcPartyId: Future[PartyId] = {
    // From SVC app for now
    val svcConnection = new SvcConnection(
      config.svcClient.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection
      .getDebugInfo()
      .map(_.svcParty)
      .andThen(_ => svcConnection.close())
  }

  private def isOnboarded(svcStore: SvSvcStore): Future[Boolean] = for {
    svcRules <- svcStore.lookupSvcRules()
  } yield svcRules.exists(_.payload.members.keySet.contains(svcStore.key.svParty.toProtoPrimitive))

}
