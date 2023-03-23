package com.daml.network.sv

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.config.{CoinHttpClientConfig, SharedCoinAppParameters}
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinNode}
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.http.HttpSvHandler
import com.daml.network.sv.automation.SvAutomationService
import com.daml.network.sv.config.{LocalSvAppConfig, SvBootstrapConfig}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.svc.admin.api.client.SvcConnection
import com.daml.network.util.CoinUtil.{defaultCoinConfigSchedule, defaultEnabledChoices}
import com.daml.network.util.Contract
import com.daml.network.util.{HasHealth, UploadablePackage}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.security.interfaces.ECPrivateKey
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import com.daml.network.admin.api.TraceContextDirectives.newTraceContext

class SvApp(
    override val name: InstanceName,
    val config: LocalSvAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CoinNode[SvApp.State](
      config.ledgerApiUser,
      config.remoteParticipant,
      coinAppParameters,
      loggerFactory,
      tracerProvider,
    ) {

  override def initialize(
      ledgerClient: CoinLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      svPartyId: PartyId,
  ): Future[SvApp.State] =
    for {
      svcPartyId <- retryProvider.retryForAutomation("get SVC party ID", getSvcPartyId, logger)
      storeKey = SvStore.Key(svPartyId, svcPartyId)
      svStore = SvSvStore(
        storeKey,
        storage,
        config.domains,
        loggerFactory,
        futureSupervisor,
        retryProvider,
      )
      svcStore = SvSvcStore(
        storeKey,
        storage,
        config.domains,
        loggerFactory,
        futureSupervisor,
        retryProvider,
      )
      automation = new SvAutomationService(
        clock,
        config,
        svStore,
        svcStore,
        ledgerClient,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
        timeouts,
      )
      _ <- waitForDomainConnection(svStore.domains, config.domains.global)
      globalDomain <- waitForDomainConnection(svcStore.domains, config.domains.global)
      ledgerConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
      _ <- ensureBootstrapped(ledgerConnection, svcStore, globalDomain)
      _ <- expectConfiguredValidatorOnboardings(svStore, ledgerConnection, globalDomain, clock)
      _ <- approveConfiguredSvIdentities(svStore, ledgerConnection, globalDomain)
      isDevNet <- retryProvider.retryForAutomation(
        "get CoinRules to determine if we are in a DevNet",
        svcStore.getCoinRules().map(coinRules => coinRules.payload.isDevNet),
        logger,
      )
      // TODO(M3-46) split the SV API into a client API and an admin API with auth
      handler = new HttpSvHandler(
        ledgerClient,
        globalDomain,
        config.ledgerApiUser,
        svStore,
        svcStore,
        isDevNet,
        clock,
        participantAdminConnection,
        retryProvider,
        loggerFactory,
      )
      routes = cors() {
        newTraceContext { traceContext =>
          requestLogger(traceContext) {
            SvResource.routes(
              handler
              // TODO(M3-46) add client authentication via `AuthExtractor`
            )
          }
        }

      }
      httpConfig = config.adminApi.clientConfig.copy(
        // TODO(#2019) Remove once we disabled Canton's `CommunityAdminServer`.
        port = config.adminApi.port + 1000
      )
      _ = logger.info(s"Starting http server on ${httpConfig}")
      binding <- Http()
        .newServerAt(
          httpConfig.address,
          httpConfig.port.unwrap,
        )
        .bind(
          routes
        )
      _ = logger.info(s"SV App is initialized")
    } yield {
      SvApp.State(
        storage,
        svStore,
        svcStore,
        automation,
        binding,
        logger,
        timeouts,
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  // SV app uploads package so no dep.
  override lazy val requiredTemplates = Set.empty

  private def getSvcPartyId: Future[PartyId] = {
    // From SVC app for now
    val svcConnection = new SvcConnection(
      config.remoteSvc.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection
      .getDebugInfo()
      .map(_.svcParty)
      .andThen(_ => svcConnection.close())
  }

  private def ensureBootstrapped(
      ledgerConnection: CoinLedgerConnection,
      svcStore: SvSvcStore,
      globalDomain: DomainId,
  ): Future[Unit] = {
    for {
      svcRules <- svcStore.lookupSvcRules()
      bootstrapped = svcRules.map(SvApp.isSvcMemberParty(svcStore.key.svParty, _)).getOrElse(false)
      _ <-
        if (bootstrapped) {
          logger.info(
            "We can see the SvcRules and are listed as an SVC member => already bootstrapped."
          )
          Future.successful(())
        } else {
          startBootstrapping(ledgerConnection, svcStore, globalDomain)
        }
      _ <- waitForSvcMembership(svcStore)
    } yield ()
  }

  private def startBootstrapping(
      ledgerConnection: CoinLedgerConnection,
      svcStore: SvSvcStore,
      globalDomain: DomainId,
  ): Future[Unit] = {
    for {
      _ <- config.bootstrap match {
        case foundingConfig: SvBootstrapConfig.FoundCollective =>
          foundCollective(foundingConfig, svcStore, ledgerConnection, globalDomain)
        case _: SvBootstrapConfig.JoinViaSvcApp =>
          retryProvider.retryForAutomation(
            "join existing SV collective",
            joinCollective(svcStore.key.svParty),
            logger,
          )
        case SvBootstrapConfig.JoinWithKey(name, remoteSv, publicKey, privateKey) =>
          SvUtil.keyPairMatches(publicKey, privateKey) match {
            case Right(privateKey_) =>
              requestOnboarding(
                remoteSv.adminApi,
                name,
                svcStore.key.svParty,
                publicKey,
                svcStore.key.svcParty,
                privateKey_,
              )
            case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
          }
        // TODO(#2241) throw an error here if bootstrap config is not set (once it becomes optional)
        // case None => sys.error("Not bootstrapped but no bootstrap config found; exiting.")
      }
    } yield ()
  }

  private def waitForSvcMembership(svcStore: SvSvcStore): Future[Unit] = {
    logger.info("Waiting for SvcRules to become visible and list us as a member")
    retryProvider.retryForAutomation(
      "Wait for SVC membership",
      for {
        svcRules <- svcStore.lookupSvcRules()
        _ <- svcRules match {
          case Some(c) =>
            if (SvApp.isSvcMemberParty(svcStore.key.svParty, c)) {
              logger.info("SvcRules found and we are member, done waiting")
              Future.successful(())
            } else {
              throw new StatusRuntimeException(
                Status.FAILED_PRECONDITION.withDescription(
                  s"SvcRules found but we are not a member"
                )
              )
            }
          case None =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(s"SvcRules contract not found yet")
            )
        }
      } yield (),
      logger,
    )
  }

  private def foundCollective(
      foundingConfig: SvBootstrapConfig.FoundCollective,
      svcStore: SvSvcStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
  ): Future[Unit] = {
    for {
      _ <- uploadDars(ledgerConnection)
      _ <- retryProvider.retryForAutomation(
        "bootstrapping SVC",
        bootstrapSvc(foundingConfig, svcStore, ledgerConnection, globalDomain),
        logger,
      )
      // make sure we can't act as the svc party anymore now that `SvcBootstrap` is done
      _ <- waiveSvcRights(svcStore.key.svcParty, ledgerConnection)
    } yield ()
  }

  private def uploadDars(ledgerConnection: CoinLedgerConnection): Future[Unit] =
    for {
      _ <- ledgerConnection.uploadDarFile(SvApp.coinPackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.svcGovernancePackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.validatorLifecyclePackage)
      _ <- ledgerConnection.uploadDarFile(SvApp.directoryPackage)
    } yield ()

  // Create SvcRules and CoinRules and open the first mining round
  private def bootstrapSvc(
      foundingConfig: SvBootstrapConfig.FoundCollective,
      store: SvSvcStore,
      ledgerConnection: CoinLedgerConnection,
      domainId: DomainId,
  ): Future[Unit] = {
    val svcParty = store.key.svcParty
    val svParty = store.key.svParty
    for {
      coinRules <- store.lookupCoinRules()
      _ <- store.lookupSvcRulesWithOffset().flatMap {
        case QueryResult(off, None) => {
          coinRules match {
            case Some(coinRules) =>
              sys.error(
                "A CoinRules contract was found but no SvcRules contract exists. " +
                  show"This should never happen.\nCoinRules: $coinRules"
              )
            case None =>
              ledgerConnection
                .submitCommands(
                  actAs = Seq(svcParty),
                  readAs = Seq.empty,
                  commands = new cn.svcbootstrap.SvcBootstrap(
                    svcParty.toProtoPrimitive,
                    svParty.toProtoPrimitive,
                    foundingConfig.name,
                    defaultCoinConfigSchedule(
                      foundingConfig.initialTickDuration,
                      foundingConfig.initialMaxNumInputs,
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
                  commandId = CoinLedgerConnection
                    .CommandId("com.daml.network.svc.executeSvcBootstrap", Seq()),
                  deduplicationOffset = off,
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
    } yield ()
  }

  private def waiveSvcRights(
      svcParty: PartyId,
      ledgerConnection: CoinLedgerConnection,
  ): Future[Unit] =
    for {
      _ <- ledgerConnection.grantUserRights(config.ledgerApiUser, Seq.empty, Seq(svcParty))
      _ <- ledgerConnection.revokeUserRights(config.ledgerApiUser, Seq(svcParty), Seq.empty)
    } yield ()

  private def requestOnboarding(
      sponsorConfig: CoinHttpClientConfig,
      name: String,
      partyId: PartyId,
      publicKey: String,
      svcPartyId: PartyId,
      privateKey: ECPrivateKey,
  ): Future[Unit] = {
    SvOnboardingToken(name, publicKey, partyId, svcPartyId).signAndEncode(privateKey) match {
      case Right(token) => {
        logger.info(s"Requesting to be onboarded via SV at: ${sponsorConfig.url}")
        retryProvider.retryForAutomation(
          "request onboarding", {
            val svConnection = new SvConnection(
              sponsorConfig,
              coinAppParameters.processingTimeouts,
              loggerFactory,
            )
            svConnection
              .onboardSv(token)
              .andThen(_ => svConnection.close())
          },
          logger,
        )
      }
      case Left(error) =>
        Future.failed(
          Status.INTERNAL
            .withDescription(s"Could not create onboarding token: $error")
            .asRuntimeException()
        )
    }
  }

  private def joinCollective(svPartyId: PartyId): Future[Unit] = {
    val svcConnection = new SvcConnection(
      config.remoteSvc.clientAdminApi,
      coinAppParameters.processingTimeouts,
      loggerFactory,
    )
    svcConnection
      .joinCollective(svPartyId)
      .andThen(_ => svcConnection.close())
  }

  private def expectConfiguredValidatorOnboardings(
      svStore: SvSvStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
      clock: Clock,
  ): Future[List[Unit]] = {
    if (config.expectedOnboardings.map(_.secret).toSet.size != config.expectedOnboardings.size) {
      sys.error("Expected onboarding secrets must be unique! Check your SV app config.")
    }
    Future.sequence(
      config.expectedOnboardings.map(c =>
        expectConfiguredValidatorOnboarding(
          c.secret,
          c.expiresIn,
          svStore,
          ledgerConnection,
          globalDomain,
          clock,
        )
      )
    )
  }

  private def expectConfiguredValidatorOnboarding(
      secret: String,
      expiresIn: NonNegativeFiniteDuration,
      svStore: SvSvStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
      clock: Clock,
  ): Future[Unit] = {
    logger.info("Ensuring that a validator lifecycle contract exists for preconfigured secret")
    SvApp
      .prepareValidatorOnboarding(
        secret,
        expiresIn,
        svStore,
        ledgerConnection,
        globalDomain,
        clock,
        logger,
      )
      .map {
        case Left(reason) => logger.info(s"Did not prepare validator onboarding: $reason")
        case Right(()) => ()
      }
  }

  private def approveConfiguredSvIdentities(
      svStore: SvSvStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
  ): Future[List[Unit]] = {
    if (
      config.approvedSvIdentities.map(_.publicKey).toSet.size != config.approvedSvIdentities.size
    ) {
      sys.error("Approved SV keys must be unique! Check your SV app config.")
    }
    if (config.approvedSvIdentities.map(_.name).toSet.size != config.approvedSvIdentities.size) {
      sys.error("Approved SV names must be unique! Check your SV app config.")
    }
    Future.sequence(
      config.approvedSvIdentities.map(c =>
        approveConfiguredSvIdentity(
          c.name,
          c.publicKey,
          svStore,
          ledgerConnection,
          globalDomain,
        )
      )
    )
  }

  private def approveConfiguredSvIdentity(
      name: String,
      key: String,
      svStore: SvSvStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
  ): Future[Unit] = {
    logger.info("Ensuring that a SV state contract exists for the configured name and key")
    SvApp.approveSvIdentity(name, key, svStore, ledgerConnection, globalDomain, logger).map {
      case Left(reason) => logger.info(s"Failed to approve SV identity: $reason")
      case Right(()) => ()
    }
  }
}

object SvApp {
  case class State(
      storage: Storage,
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      automation: SvAutomationService,
      binding: Http.ServerBinding,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  )(implicit el: ErrorLoggingContext)
      extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      Lifecycle.close(
        AsyncCloseable(
          "http binding",
          binding.terminate(timeouts.shutdownNetwork.asFiniteApproximation),
          timeouts.shutdownNetwork.unwrap,
        ),
        storage,
        svStore,
        svcStore,
        automation,
      )(logger)

  }

  def prepareValidatorOnboarding(
      secret: String,
      expiresIn: NonNegativeFiniteDuration,
      svStore: SvSvStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
      clock: Clock,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val svParty = svStore.key.svParty
    val validatorOnboarding = new cn.validatoronboarding.ValidatorOnboarding(
      svParty.toProtoPrimitive,
      secret,
      (clock.now + expiresIn).toInstant,
    ).create.commands.asScala.toSeq
    for {
      res <- svStore.lookupUsedSecretWithOffset(secret).flatMap {
        case QueryResult(_, Some(usedSecret)) => {
          val validator = usedSecret.payload.validator
          Future.successful(
            Left(s"This secret has already been used before, for onboarding validator $validator")
          )
        }
        case QueryResult(off, None) => {
          svStore.lookupValidatorOnboardingBySecretWithOffset(secret).flatMap {
            case QueryResult(_, Some(_)) => {
              Future.successful(
                Left("A validator onboarding contract with this secret already exists.")
              )
            }
            case QueryResult(_, None) => {
              ledgerConnection
                .submitCommands(
                  actAs = Seq(svParty),
                  readAs = Seq.empty,
                  commands = validatorOnboarding,
                  commandId = CoinLedgerConnection
                    .CommandId(
                      "com.daml.network.sv.expectValidatorOnboarding",
                      Seq(svParty),
                      secret, // not a leak as this gets hashed before it's used
                    ),
                  deduplicationOffset = off,
                  domainId = globalDomain,
                )
                .map(_ => {
                  logger.info("Created new ValidatorOnboarding contract.")
                  Right(())
                })
            }
          }
        }
      }
    } yield res
  }

  private def approveSvIdentity(
      name: String,
      key: String,
      svStore: SvSvStore,
      ledgerConnection: CoinLedgerConnection,
      globalDomain: DomainId,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Either[String, Unit]] = {
    val svParty = svStore.key.svParty
    val approvedSvIdentity = new cn.svonboarding.ApprovedSvIdentity(
      svParty.toProtoPrimitive,
      name,
      key,
    ).create.commands.asScala.toSeq
    SvUtil.parsePublicKey(key) match {
      case Left(error) => Future.successful(Left(error))
      case Right(_) =>
        for {
          res <- svStore.lookupApprovedSvIdentityByNameWithOffset(name).flatMap {
            case QueryResult(_, Some(id)) => {
              Future.successful(
                Left(if (id.payload.candidateKey == key) {
                  s"The SV identitiy ($name:$key) is already approved."
                } else {
                  s"Tried to approve SV identity ($name:$key), but name $name " +
                    s"is already approved with key ${id.payload.candidateKey}."
                })
              )
            }
            case QueryResult(off, None) => {
              ledgerConnection
                .submitCommands(
                  actAs = Seq(svParty),
                  readAs = Seq.empty,
                  commands = approvedSvIdentity,
                  commandId = CoinLedgerConnection
                    .CommandId(
                      "com.daml.network.sv.approveSvIdentity",
                      Seq(svParty),
                      s"$key",
                    ),
                  deduplicationOffset = off,
                  domainId = globalDomain,
                )
                .map(_ => {
                  logger.info("Created new ApprovedSvIdentity contract.")
                  Right(())
                })
            }
          }
        } yield res
    }
  }

  private[sv] def isApprovedSvIdentity(
      candidateName: String,
      candidateParty: PartyId,
      rawToken: String,
      svStore: SvSvStore,
  )(implicit ec: ExecutionContext): Future[Either[String, (PartyId, String)]] = {
    svStore
      .lookupApprovedSvIdentityByName(candidateName)
      .map(approvedSvO =>
        for {
          approvedSv <- approvedSvO.toRight("no matching approved SV identity found")
          token <- SvOnboardingToken.verifyAndDecode(rawToken)
          _ <-
            if (token.candidateName == candidateName) Right(())
            else Left("provided candidate name doesn't match name in token")
          _ <-
            if (token.candidateKey == approvedSv.payload.candidateKey) Right(())
            else Left("candidate key doesn't match approved key")
          _ <-
            if (token.candidateParty == candidateParty) Right(())
            else Left("provided party name doesn't match party in token")
          _ <- if (token.svcParty == svStore.key.svcParty) Right(()) else Left("wrong svc party")
        } yield (token.candidateParty, token.candidateName)
      )
  }

  private[sv] def isSvcMember(
      name: String,
      party: PartyId,
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean =
    svcRules.payload.members.asScala
      .get(party.toProtoPrimitive)
      .map(_.name == name)
      .getOrElse(false)

  private[sv] def isSvcMemberParty(
      party: PartyId,
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean = svcRules.payload.members.containsKey(party.toProtoPrimitive)

  private[sv] def isSvcMemberName(
      name: String,
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
  ): Boolean = svcRules.payload.members.values.asScala.exists(_.name == name)

  val coinPackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = cc.coin.Coin.COMPANION.TEMPLATE_ID.getPackageId

    // See `Compile / resourceGenerators` in build.sbt
    lazy val resourcePath: String = "dar/canton-coin-0.1.0.dar"
  }
  val svcGovernancePackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = cn.svcrules.SvcRules.COMPANION.TEMPLATE_ID.getPackageId
    lazy val resourcePath: String = "dar/svc-governance-0.1.0.dar"
  }
  val validatorLifecyclePackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String =
      cn.validatoronboarding.ValidatorOnboarding.COMPANION.TEMPLATE_ID.getPackageId
    lazy val resourcePath: String = "dar/validator-lifecycle-0.1.0.dar"
  }
  val directoryPackage: UploadablePackage = new UploadablePackage {
    lazy val packageId: String = cn.directory.DirectoryInstall.TEMPLATE_ID.getPackageId
    lazy val resourcePath: String = "dar/directory-service-0.1.0.dar"
  }
}
