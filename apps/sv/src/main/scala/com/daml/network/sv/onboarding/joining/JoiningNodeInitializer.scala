package com.daml.network.sv.onboarding.joining

import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.implicits.{catsSyntaxTuple3Semigroupal, catsSyntaxTuple4Semigroupal}
import cats.syntax.foldable.*
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingConfirmed
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.*
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.automation.singlesv.SvPackageVettingTrigger
import com.daml.network.sv.cometbft.{
  CometBftClient,
  CometBftConnectionConfig,
  CometBftHttpRpcClient,
  CometBftNode,
}
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.onboarding.DomainNodeReconciler.DomainNodeState
import com.daml.network.sv.onboarding.DomainNodeReconciler.DomainNodeState.{Onboarded, Onboarding}
import com.daml.network.sv.onboarding.{
  DomainNodeReconciler,
  NodeInitializerUtil,
  SetupUtil,
  SvcPartyHosting,
}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.sv.{LocalDomainNode, SvApp}
import com.daml.network.util.{Contract, PackageVetting, TemplateJsonDecoder, UploadablePackage}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermissionX}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.security.interfaces.ECPrivateKey
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize a joining SV node. */
class JoiningNodeInitializer(
    localDomainNode: Option[LocalDomainNode],
    joiningConfig: Option[SvOnboardingConfig.JoinWithKey],
    participantId: ParticipantId,
    requiredDars: Seq[UploadablePackage],
    override protected val config: SvAppBackendConfig,
    override protected val cometBftNode: Option[CometBftNode],
    override protected val ledgerClient: CNLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val storage: Storage,
    override val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
) extends NodeInitializerUtil {

  def joinCollectiveAndOnboardNodes(): Future[
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
    // We need to connect to the domain here because otherwise we create a circular dependency
    // with the validator app: The validator app waits for its user to be provisioned (which happens in createValidatorUser)
    // before establishing a domain connection, but allocating the SV party requires a domain connection.
    val domainConfig = DomainConnectionConfig(
      config.domains.global.alias,
      SequencerConnections.single(
        GrpcSequencerConnection.tryCreate(config.domains.global.url)
      ),
    )
    for {
      (svcPartyId, _, svParty) <- (
        getSvcPartyId(initConnection),
        // TODO(#5803) Consider removing this once Canton stops falling apart.
        // Wait for the sponsoring SV which also ensures the domain is initialized.
        joiningConfig.traverse_ { conf =>
          retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "Sponsoring SV is active",
            withSvConnection(conf.svClient.adminApi)(_.checkActive()),
            logger,
          )
        },
        for {
          _ <- participantAdminConnection.ensureDomainRegistered(
            domainConfig,
            RetryFor.WaitingOnInitDependency,
          )
          svParty <- SetupUtil.setupSvParty(
            initConnection,
            config,
            participantAdminConnection,
            clock,
            retryProvider,
            loggerFactory,
          )
        } yield svParty,
      ).tupled
      _ <- participantAdminConnection.uploadDarFiles(
        requiredDars,
        RetryFor.WaitingOnInitDependency,
      )
      storeKey = SvStore.Key(svParty, svcPartyId)
      svStore = newSvStore(storeKey)
      svcStore = newSvcStore(svStore.key)
      svAutomation = newSvSvAutomationService(
        svStore,
        svcStore,
        ledgerClient,
      )
      globalDomain <- svStore.domains.waitForDomainConnection(config.domains.global.alias)
      // We need to first wait to ensure the CometBFT node is caught up
      // If the CometBFT node is not caught up and we start the CometBFT triggers, if the network doesn't have any
      // fault tolerance then it might be blocked until the CometBFT node is caught up.
      _ <- waitUntilCometBftNodeHasCaughtUp
      svcPartyHosting = newSvcPartyHosting(storeKey)
      svcPartyIsAuthorized <- svcPartyHosting.isSvcPartyAuthorizedOn(
        globalDomain,
        participantId,
      )
      withSvStore = new WithSvStore(
        svAutomation,
        new JoiningNodeSvcPartyHosting(
          participantAdminConnection,
          joiningConfig,
          svcPartyId,
          svcPartyHosting,
          retryProvider,
          loggerFactory,
        ),
        globalDomain,
      )
      svcAutomation <-
        if (svcPartyIsAuthorized) {
          logger.info("SVC party is authorized to our participant.")
          for {
            _ <- SetupUtil.grantSvUserRightReadAsSvc(
              svAutomation.connection,
              config.ledgerApiUser,
              svStore.key.svcParty,
            )
            svcAutomation =
              newSvSvcAutomationService(
                svStore,
                svcStore,
                localDomainNode,
              )
            _ <- svcStore.domains.waitForDomainConnection(config.domains.global.alias)
            _ <- retryProvider.ensureThatB(
              RetryFor.WaitingOnInitDependency,
              show"the SvcRules list the SV party ${svcStore.key.svParty} as a member",
              isOnboarded(svcStore), {
                withSvStore.startOnboardingWithSvcPartyHosted(
                  svcAutomation
                )
              },
              logger,
            )
          } yield svcAutomation
        } else {
          logger.info(
            "The SVC party is not authorized to our participant. " +
              "Starting onboarding with SVC party migration."
          )
          withSvStore
            .startOnboardingWithSvcPartyMigration(initConnection, svcStore)
        }
      _ = svcAutomation.registerPostOnboardingTriggers()
      // It is important to wait only here since at this point we may have been added
      // to the decentralized namespace so we depend on our own automation promoting us to
      // submission rights.
      _ <- waitForSvParticipantToHaveSubmissionRights(svcPartyId, globalDomain)
      _ <- waitForSvcMembership(svcStore)
      _ <- SetupUtil.ensureSvcPartyMetadataAnnotation(svAutomation.connection, config, svcPartyId)
      _ <- withSvStore
        .setDomainNodeConfigIfRequired(svcAutomation, localDomainNode, Onboarding)
      _ <- withLocalDomainNode(localDomainNode) { case (localDomainNode, svConnection) =>
        for {
          _ <-
            localDomainNode.onboardLocalSequencerIfRequired(
              config.domains.global.alias,
              globalDomain,
              participantAdminConnection,
              svConnection,
            )
          _ <- localDomainNode.onboardLocalMediatorIfRequired(
            globalDomain,
            participantAdminConnection,
            svConnection,
          )
        } yield ()
      }
      _ <- withSvStore
        .setDomainNodeConfigIfRequired(svcAutomation, localDomainNode, Onboarded)
    } yield {
      (
        globalDomain,
        svcPartyHosting,
        svStore,
        svAutomation,
        svcStore,
        svcAutomation,
      )
    }
  }

  private def waitForSvParticipantToHaveSubmissionRights(svcParty: PartyId, domainId: DomainId) = {
    val description =
      show"SV participant $participantId has Submission rights for party $svcParty"
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      description,
      for {
        svcPartyHosting <- participantAdminConnection
          .getPartyToParticipant(domainId, svcParty)
      } yield {
        svcPartyHosting.mapping.participants.find(_.participantId == participantId) match {
          case None =>
            throw Status.NOT_FOUND
              .withDescription(
                show"Party $svcParty is not hosted on participant $participantId"
              )
              .asRuntimeException()
          case Some(HostingParticipant(_, permission)) =>
            if (permission == ParticipantPermissionX.Submission)
              svcPartyHosting
            else
              throw Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException()
        }
      },
      logger,
    )
  }

  private def withSvConnection[T](
      sponsorConfig: NetworkAppClientConfig
  )(f: SvConnection => Future[T]): Future[T] = {
    SvConnection(sponsorConfig, retryProvider, loggerFactory).flatMap(con =>
      f(con).andThen(_ => con.close())
    )
  }

  private def newCometBftClient = {
    cometBftNode.map(node =>
      new CometBftClient(
        new CometBftHttpRpcClient(
          CometBftConnectionConfig(node.cometBftConfig.connectionUri),
          loggerFactory,
        ),
        loggerFactory,
      )
    )
  }

  private def waitUntilCometBftNodeHasCaughtUp = {
    newCometBftClient
      .map(cometBftClient =>
        retryProvider.waitUntil(
          RetryFor.WaitingOnInitDependency,
          "CometBFT node has caught up",
          cometBftClient
            .nodeStatus()
            .map(status =>
              if (status.syncInfo.catchingUp) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"CometBFT node is still catching up; currently at block ${status.syncInfo.latestBlockHeight}."
                  )
                  .asRuntimeException()
              }
            ),
          logger,
        )
      )
      .getOrElse({
        logger.info("No CometBFT node found, so not waiting on CometBFT sync.")
        Future.unit
      })
  }

  /** Private class to share svStore, svcPartyHosting, and global domain-id
    * across utility methods.
    */
  private class WithSvStore(
      svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
      svcPartyHosting: JoiningNodeSvcPartyHosting,
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

    def setDomainNodeConfigIfRequired(
        svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
        localDomainNode: Option[LocalDomainNode],
        domainNodeState: DomainNodeState,
    ): Future[Unit] = {
      new WithSvcStore(svcStoreWithIngestion)
        .reconcileDomainNodeConfigIfRequired(localDomainNode, domainNodeState)
    }

    /** A private class to share the svcStoreWithIngestion across utility methods. */
    private class WithSvcStore(
        svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore]
    ) {
      private val svcStore: SvSvcStore = svcStoreWithIngestion.store
      private val domainNodeReconciler = new DomainNodeReconciler(
        svcStore,
        svcStoreWithIngestion.connection,
        config.scan,
        clock,
        retryProvider,
        logger,
      )

      def startOnboardingWithSvcPartyHosted(): Future[Unit] = {
        val SvOnboardingConfig.JoinWithKey(name, svClient, publicKey, privateKey) =
          joiningConfig.getOrElse(
            sys.error("An onboarding config is required to start onboarding; exiting.")
          )
        SvUtil.keyPairMatches(publicKey, privateKey) match {
          case Right(privateKey_) =>
            for {
              _ <- requestOnboarding(
                svClient.adminApi,
                name,
                participantId,
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
          _ <- retryProvider.retry(
            RetryFor.WaitingOnInitDependency,
            "add member to Svc",
            for {
              (svcRules, coinRules, openMiningRounds, svOnboardingConfirmedOpt) <- (
                svcStore.getSvcRules(),
                svcStore.getCoinRules(),
                svcStore.getOpenMiningRoundTriple(),
                svcStore.lookupSvOnboardingConfirmedByParty(
                  svcStore.key.svParty
                ),
              ).tupled
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
                    val cmd = svcRules.exercise(
                      _.exerciseSvcRules_AddConfirmedMember(
                        svcStore.key.svParty.toProtoPrimitive,
                        confirmed.contractId,
                        openMiningRounds.oldest.contractId,
                        openMiningRounds.middle.contractId,
                        openMiningRounds.newest.contractId,
                        coinRules.contractId,
                      )
                    )
                    svcStoreWithIngestion.connection
                      .submit(Seq(svcStore.key.svParty), Seq(svcStore.key.svcParty), cmd)
                      .noDedup
                      .yieldUnit()
                  }
              }
            } yield svcRules,
            logger,
          )
          _ = logger.info("Adding member to the decentralized namespace.")
          _ <- participantAdminConnection
            .ensureDecentralizedNamespaceDefinitionProposalAccepted(
              domainId,
              svcParty.uid.namespace,
              svParty.uid.namespace,
              svParty.uid.namespace.fingerprint,
              RetryFor.WaitingOnInitDependency,
            )
        } yield ()
      }

      def reconcileDomainNodeConfigIfRequired(
          localDomainNode: Option[LocalDomainNode],
          domainNodeState: DomainNodeState,
      ): Future[Unit] = {
        domainNodeReconciler.reconcileDomainNodeConfigIfRequired(
          localDomainNode,
          domainId,
          domainNodeState,
        )
      }
    }

    def startOnboardingWithSvcPartyMigration(
        initConnection: BaseLedgerConnection,
        svcStore: SvSvcStore,
    ): Future[SvSvcAutomationService] = {
      joiningConfig.getOrElse(
        sys.error("An onboarding config is required to start onboarding; exiting.")
      ) match {
        case SvOnboardingConfig.JoinWithKey(name, svClient, publicKey, privateKey) =>
          SvUtil.keyPairMatches(publicKey, privateKey) match {
            case Right(privateKey_) =>
              for {
                _ <- requestOnboarding(
                  svClient.adminApi,
                  name,
                  participantId,
                  publicKey,
                  privateKey_,
                )
                // Wait on the SV store because the SVC party is not yet onboarded.
                _ <- waitForSvOnboardingConfirmedInSvStore()
                _ <- startHostingSvcPartyInParticipant()
                // We need to wait for the ledger API server to see the party otherwise the
                // grantUserRights call will fail.
                _ <- initConnection.waitForPartyOnLedgerApi(svStore.key.svcParty)
                _ <- svStoreWithIngestion.connection.grantUserRights(
                  config.ledgerApiUser,
                  Seq.empty,
                  Seq(svStore.key.svcParty),
                )
                _ = logger.info(s"granted ${config.ledgerApiUser} readAs rights for svcParty")
                svcAutomation = newSvSvcAutomationService(svStore, svcStore, localDomainNode)
                _ <- svcAutomation.store.domains.waitForDomainConnection(
                  config.domains.global.alias
                )
                withSvcStore = new WithSvcStore(svcAutomation)
                _ <- withSvcStore.addConfirmedMemberToSvc()
              } yield svcAutomation
            case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
          }
      }
    }

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
        RetryFor.WaitingOnInitDependency,
        description,
        for {
          svOnboardingConfirmedOpt <- lookupSvOnboardingConfirmed()
          svOnboardingConfirmed <- svOnboardingConfirmedOpt match {
            case Some(sc) => Future.successful(sc)
            case None =>
              throw Status.NOT_FOUND.withDescription(description).asRuntimeException()
          }
        } yield svOnboardingConfirmed,
        logger,
      )
    }

    private def vetThroughSponsor(sponsorConfig: NetworkAppClientConfig): Future[Unit] = {
      logger.info("Vetting packages based on state from sponsor")
      for {
        // This is not a BFT read: That's acceptable because
        // we will only vet packages that have been statically compiled into the app.
        // At most, we can be tricked into vetting a package a bit too early.
        svcInfo <- withSvConnection(sponsorConfig)(_.getSvcInfo())
        coinRules = svcInfo.coinRules
        vetting = new PackageVetting(
          SvPackageVettingTrigger.packages,
          config.prevetDuration,
          clock,
          participantAdminConnection,
          loggerFactory,
        )
        _ <- vetting.vetPackages(coinRules)
        _ = logger.info("Packages vetting completed")
      } yield ()
    }

    private def requestOnboarding(
        sponsorConfig: NetworkAppClientConfig,
        name: String,
        participantId: ParticipantId,
        publicKey: String,
        privateKey: ECPrivateKey,
    ): Future[Unit] = {
      SvOnboardingToken(name, publicKey, svParty, participantId, svcParty).signAndEncode(
        privateKey
      ) match {
        case Right(token) =>
          // startSvOnboarding creates a contract with the SV as an observer so we need to vet before.
          // technically we can still get issues if the config changes while we are onboarding. However,
          // we prevet so this is extremely unlikely and even if we hit it,
          // we will just crash and retry so it doesn't seem worth the complexity
          // to wrap everything in a giant retry.
          for {
            _ <- vetThroughSponsor(sponsorConfig)
            _ = logger.info(s"Requesting to be onboarded via SV at: ${sponsorConfig.url}")
            _ <- retryProvider.retry(
              RetryFor.WaitingOnInitDependency,
              "request onboarding",
              withSvConnection(sponsorConfig)(_.startSvOnboarding(token)),
              logger,
            )
          } yield ()
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
        .hostPartyOnOwnParticipant(domainId, participantId, svParty)
        .map(
          _.getOrElse(
            sys.error(s"Failed to host SVC party on participant $participantId")
          )
        )
    }
  }

  private def getSvcPartyId(connection: BaseLedgerConnection): Future[PartyId] = for {
    svcPartyFromMetadata <- connection.lookupSvcPartyFromUserMetadata(config.ledgerApiUser)
    svcParty <- svcPartyFromMetadata
      .fold(
        {
          val sponsorConfig = joiningConfig
            .getOrElse(
              sys.error(
                "An onboarding config is required to get the SVC party ID from a sponsoring SV; exiting."
              )
            )
            .svClient
            .adminApi
          retryProvider.getValueWithRetries(
            RetryFor.WaitingOnInitDependency,
            "SVC party ID from sponsoring SV",
            getSvcPartyIdFromSponsor(sponsorConfig),
            logger,
          )
        }
      )(Future.successful)
  } yield svcParty

  private def getSvcPartyIdFromSponsor(sponsorConfig: NetworkAppClientConfig): Future[PartyId] =
    SvConnection(
      sponsorConfig,
      retryProvider,
      loggerFactory,
    ).flatMap { svConnection =>
      svConnection.getSvcInfo().map(_.svcParty).andThen(_ => svConnection.close())
    }

  private def isOnboarded(svcStore: SvSvcStore): Future[Boolean] = for {
    svcRules <- svcStore.lookupSvcRules()
  } yield svcRules.exists(
    _.payload.members.keySet.contains(svcStore.key.svParty.toProtoPrimitive)
  )

  private def withLocalDomainNode[A](
      localDomainNodeO: Option[LocalDomainNode]
  )(f: (LocalDomainNode, SvConnection) => Future[A]): Future[Unit] =
    (for {
      localDomainNode <- localDomainNodeO
      svConfig <- joiningConfig.map(_.svClient.adminApi)
    } yield (localDomainNode, svConfig)).traverse_ { case (localDomainNode, svConfig) =>
      SvConnection(
        svConfig,
        retryProvider,
        loggerFactory,
      ).flatMap { svConnection =>
        f(localDomainNode, svConnection).andThen(_ => svConnection.close())
      }
    }

  private def waitForSvcMembership(svcStore: SvSvcStore): Future[Unit] = {
    val svParty = svcStore.key.svParty
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      show"SvcRules are visible and list $svParty as a member",
      for {
        svcRules <- svcStore.lookupSvcRules()
        _ <- svcRules match {
          case Some(c) =>
            if (SvApp.isSvcMemberParty(svcStore.key.svParty, c.contract)) {
              Future.successful(())
            } else {
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  show"SvcRules found but $svParty is not a member"
                )
                .asRuntimeException()
            }
          case None =>
            throw Status.NOT_FOUND
              .withDescription(show"SvcRules contract not found")
              .asRuntimeException()
        }
      } yield (),
      logger,
    )
  }
}
