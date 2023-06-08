package com.daml.network.sv.setup

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.syntax.foldable.*
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingConfirmed
import com.daml.network.config.{NetworkAppClientConfig, SharedCNNodeAppParameters}
import com.daml.network.environment.*
import com.daml.network.store.{CNNodeAppStoreWithIngestion, DomainStore}
import com.daml.network.sv.{LocalDomainNode, SvApp}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil, SvcRulesLock}
import com.daml.network.svc.admin.api.client.SvcConnection
import com.daml.network.util.{Contract, TemplateJsonDecoder, UploadablePackage}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.security.interfaces.ECPrivateKey
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize a joining SV node. */
class JoiningNodeInitializer(
    // TODO(#5364): cleanup the order and mass of these parameters
    config: SvAppBackendConfig,
    joiningConfig: SvOnboardingConfig.JoinWithKey,
    cometBftNode: Option[CometBftNode],
    requiredDars: Seq[UploadablePackage],
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    svParty: PartyId,
    participantId: ParticipantId,
    clock: Clock,
    storage: Storage,
    futureSupervisor: FutureSupervisor,
    coinAppParameters: SharedCNNodeAppParameters,
    localDomainNode: Option[LocalDomainNode],
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
) extends NamedLogging {

  def joinCollectiveAndOnboardNodes(): Future[
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
    for {
      // TODO(#3856): find a better way to get the SVC party ID
      svcPartyId <- retryProvider.getValueWithRetries("SVC party ID", getSvcPartyId, logger)
      storeKey = SvStore.Key(svParty, svcPartyId)
      svStore = newSvStore(storeKey)
      svAutomation = newSvSvAutomationService(
        svStore,
        ledgerClient,
      )
      globalDomain <- waitForDomainConnection(svStore.domains, config.domains.global.alias)
      svcPartyHosting = newSvcPartyHosting(
        storeKey,
        participantAdminConnection,
      )
      svcPartyIsAuthorized <- svcPartyHosting.isSvcPartyAuthorizedOn(
        globalDomain,
        participantId,
      )
      (svcStore, svcAutomation) <-
        if (svcPartyIsAuthorized) {
          logger.info("SVC party is authorized to our participant.")
          for {
            _ <- svAutomation.connection.grantUserRights(
              config.ledgerApiUser,
              Seq.empty,
              Seq(svStore.key.svcParty),
            )
            svcStore = newSvcStore(svStore.key)
            svcAutomation =
              newSvSvcAutomationService(svStore, svcStore, ledgerClient, cometBftNode)
            _ <- waitForDomainConnection(svcStore.domains, config.domains.global.alias)
            _ <- retryProvider.ensureThat(
              show"the SvcRules list the SV party ${svcStore.key.svParty} as a member",
              isOnboarded(svcStore), {
                new WithSvStore(
                  svAutomation,
                  svcPartyHosting,
                  globalDomain,
                ).startOnboardingWithSvcPartyHosted(
                  svcAutomation
                )
              },
              logger,
            )
          } yield (svcStore, svcAutomation)
        } else {
          logger.info(
            "The SVC party is not authorized to our participant. " +
              "Starting onboarding with SVC party migration."
          )
          new WithSvStore(svAutomation, svcPartyHosting, globalDomain)
            .startOnboardingWithSvcPartyMigration()
        }
      _ <- waitForSvcMembership(svcStore)
      svcRulesLock = new SvcRulesLock(globalDomain, svcAutomation, retryProvider, loggerFactory)
      _ <- withLocalDomainNode(localDomainNode) { case (localDomainNode, svConnection) =>
        for {
          _ <- localDomainNode.onboardLocalSequencerIfRequired(
            config.domains.global.alias,
            globalDomain,
            participantAdminConnection,
            svConnection,
          )
          _ <- localDomainNode.onboardLocalMediatorIfRequired(
            globalDomain,
            participantAdminConnection,
            svConnection,
            svcRulesLock,
          )
        } yield ()
      }
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

  /** Private class to share svStore, svcPartyHosting, and global domain-id
    * across utility methods.
    */
  private class WithSvStore(
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      svcPartyHosting: SvcPartyHosting,
      domainId: DomainId,
  ) {

    private val svStore = svStoreWithIngestion.store
    private val svParty = svStore.key.svParty
    private val svcParty = svStore.key.svcParty

    def startOnboardingWithSvcPartyHosted(
        svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore]
    ): Future[Unit] = {
      new WithSvcStore(svcStoreWithIngestion).startOnboardingWithSvcPartyHosted()
    }

    /** A private class to share the svcStoreWithIngestion across utility methods. */
    private class WithSvcStore(
        svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore]
    ) {
      private val svcStore: SvSvcStore = svcStoreWithIngestion.store

      def startOnboardingWithSvcPartyHosted(): Future[Unit] = {
        val SvOnboardingConfig.JoinWithKey(name, svClient, publicKey, privateKey) = joiningConfig
        SvUtil.keyPairMatches(publicKey, privateKey) match {
          case Right(privateKey_) =>
            for {
              _ <- requestOnboarding(
                svClient.adminApi,
                name,
                publicKey,
                privateKey_,
              )
              _ <- addConfirmedMemberToSvc()
            } yield ()
          case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
        }
      }

      private def waitForSvOnboardingConfirmedInSvcStore()
          : Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] =
        waitForSvOnboardingConfirmed(() =>
          svcStore.lookupSvOnboardingConfirmedByParty(svcStore.key.svParty)
        )

      def addConfirmedMemberToSvc(): Future[Unit] = {
        val svcStore = svcStoreWithIngestion.store
        for {
          // Wait on the SVC store to make sure that we atomically see either the SvOnboardingConfirmed contract
          // or the SvcRules contract.
          _ <- waitForSvOnboardingConfirmedInSvcStore()
          _ <- retryProvider.retryForAutomation(
            "add member to Svc",
            for {
              svcRules <- svcStore.getSvcRules()
              openMiningRounds <- svcStore.getOpenMiningRoundTriple()
              svOnboardingConfirmedOpt <- svcStore.lookupSvOnboardingConfirmedByParty(
                svcStore.key.svParty
              )
              svIsSvcMember = svcRules.payload.members.asScala
                .contains(svcStore.key.svParty.toProtoPrimitive)
              _ <- svOnboardingConfirmedOpt match {
                case None =>
                  if (svIsSvcMember) {
                    logger.info(s"SV is already a member of the SVC")
                    Future.unit
                  } else {
                    val msg =
                      "SV is not a member of the SVC but there is also no confirmed onboarding, giving up"
                    logger.error(msg)
                    Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
                  }
                case Some(confirmed) =>
                  if (svIsSvcMember) {
                    logger.info(
                      "SvOnboardingConfirmed exists but SV is already a member of the SVC"
                    )
                    Future.unit
                  } else {
                    val cmd = svcRules.contractId.exerciseSvcRules_AddConfirmedMember(
                      svcStore.key.svParty.toProtoPrimitive,
                      confirmed.contractId,
                      openMiningRounds.oldest.contractId,
                      openMiningRounds.middle.contractId,
                      openMiningRounds.newest.contractId,
                    )
                    svcStoreWithIngestion.connection.submitCommandsNoDedup(
                      Seq(svcStore.key.svParty),
                      Seq(svcStore.key.svcParty),
                      commands = cmd.commands.asScala.toSeq,
                      domainId = domainId,
                    )
                  }
              }
            } yield (),
            logger,
          )
        } yield ()
      }
    }

    def startOnboardingWithSvcPartyMigration(): Future[(SvSvcStore, SvSvcAutomationService)] = {
      config.onboarding match {
        case SvOnboardingConfig.JoinWithKey(name, svClient, publicKey, privateKey) =>
          SvUtil.keyPairMatches(publicKey, privateKey) match {
            case Right(privateKey_) =>
              for {
                _ <- svStoreWithIngestion.connection.uploadDarFiles(requiredDars)
                _ <- waitForValidatorLicense()
                _ <- requestOnboarding(
                  svClient.adminApi,
                  name,
                  publicKey,
                  privateKey_,
                )
                // Wait on the SV store because the SVC party is not yet onboarded.
                _ <- waitForSvOnboardingConfirmedInSvStore()
                _ <- startHostingSvcPartyInParticipant()
                _ <- svStoreWithIngestion.connection.grantUserRights(
                  config.ledgerApiUser,
                  Seq.empty,
                  Seq(svStore.key.svcParty),
                )
                _ = logger.info(s"granted ${config.ledgerApiUser} readAs rights for svcParty")
                svcStore = newSvcStore(svStore.key)
                svcAutomation = newSvSvcAutomationService(svcStore)
                _ <- waitForDomainConnection(
                  svcAutomation.store.domains,
                  config.domains.global.alias,
                )
                withSvcStore = new WithSvcStore(svcAutomation)
                _ <- withSvcStore.addConfirmedMemberToSvc()
              } yield (svcStore, svcAutomation)
            case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
          }
        case _ => sys.error(s"only JoinWithKey is expected")
      }
    }

    private def waitForValidatorLicense(): Future[
      Contract[
        cc.validatorlicense.ValidatorLicense.ContractId,
        cc.validatorlicense.ValidatorLicense,
      ]
    ] =
      // If the validator license contract gets created after we disconnected from the domain, Canton blows up during the SVC party migration
      // because the contract gets added both via the party migration and through the regular event stream for the SV party which is an observer.
      // Therefore, we let the validator app do its thing first.
      retryProvider.getValueWithRetries(
        show"ValidatorLicense contract for ${svStore.key.svParty}",
        svStore.getValidatorLicense(),
        logger,
      )

    private def waitForSvOnboardingConfirmedInSvStore()
        : Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] =
      waitForSvOnboardingConfirmed(() => svStore.lookupSvOnboardingConfirmed())

    private def waitForSvOnboardingConfirmed(
        lookupSvOnboardingConfirmed: () => Future[
          Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
        ]
    ): Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] = {
      val description = show"SvOnboardingConfirmed contract for $svParty"
      retryProvider.getValueWithRetries(
        description,
        for {
          svOnboardingConfirmedOpt <- lookupSvOnboardingConfirmed()
          svOnboardingConfirmed <- svOnboardingConfirmedOpt match {
            case Some(sc) => Future.successful(sc)
            case None =>
              throw new StatusRuntimeException(Status.NOT_FOUND.withDescription(description))
          }
        } yield svOnboardingConfirmed,
        logger,
      )
    }

    private def requestOnboarding(
        sponsorConfig: NetworkAppClientConfig,
        name: String,
        publicKey: String,
        privateKey: ECPrivateKey,
    ): Future[Unit] = {
      SvOnboardingToken(name, publicKey, svParty, svcParty).signAndEncode(privateKey) match {
        case Right(token) =>
          logger.info(s"Requesting to be onboarded via SV at: ${sponsorConfig.url}")
          retryProvider.retryForAutomation(
            "request onboarding",
            SvConnection(
              sponsorConfig,
              retryProvider,
              retryProvider.timeouts,
              loggerFactory,
            ).flatMap { svConnection =>
              svConnection
                .startSvOnboarding(token)
                .andThen(_ => svConnection.close())
            },
            logger,
          )
        case Left(error) =>
          Future.failed(
            Status.INTERNAL
              .withDescription(s"Could not create onboarding token: $error")
              .asRuntimeException()
          )
      }
    }

    private def startHostingSvcPartyInParticipant(): Future[Unit] = {
      svcPartyHosting
        // TODO(#5364): consider inlining the relevant parts from SvcPartyHosting
        .start(domainId, participantId, svParty)
        .map(
          _.getOrElse(
            sys.error(s"Failed to host svc party on participant $participantId")
          )
        )
    }

    // TODO(#5364): inline these methods where they are used only once, or move them up.
    private def newSvSvcAutomationService(
        svcStore: SvSvcStore
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
  }

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

  private def withLocalDomainNode[A](
      localDomainNodeO: Option[LocalDomainNode]
  )(f: (LocalDomainNode, SvConnection) => Future[A]): Future[Unit] =
    (for {
      localDomainNode <- localDomainNodeO
      svConfig <- config.onboarding match {
        case conf: SvOnboardingConfig.JoinWithKey => Some(conf.svClient.adminApi)
        case _: SvOnboardingConfig.FoundCollective => None
      }
    } yield (localDomainNode, svConfig)).traverse_ { case (localDomainNode, svConfig) =>
      SvConnection(
        svConfig,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      ).flatMap { svConnection =>
        f(localDomainNode, svConnection).andThen(_ => svConnection.close())
      }
    }

  private def waitForSvcMembership(svcStore: SvSvcStore): Future[Unit] = {
    val svParty = svcStore.key.svParty
    retryProvider.waitUntil(
      show"SvcRules are visible and list $svParty as a member",
      for {
        svcRules <- svcStore.lookupSvcRules()
        _ <- svcRules match {
          case Some(c) =>
            if (SvApp.isSvcMemberParty(svcStore.key.svParty, c)) {
              Future.successful(())
            } else {
              throw new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription(
                  show"SvcRules found but $svParty is not a member"
                )
              )
            }
          case None =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(show"SvcRules contract not found")
            )
        }
      } yield (),
      logger,
    )
  }
}
