// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import better.files.File.apply
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
  SpliceStatus,
}
import org.lfdecentralizedtrust.splice.http.{
  HttpClient,
  HttpFeatureSupportHandler,
  HttpValidatorLicensesHandler,
  HttpVotesHandler,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv_admin as v0}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TriggerDomainMigrationDumpRequest
import org.lfdecentralizedtrust.splice.http.v0.sv_admin.SvAdminResource
import org.lfdecentralizedtrust.splice.store.{ActiveVotesStore, AppStore, AppStoreWithIngestion}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftClient
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.migration.{
  DomainDataSnapshotGenerator,
  DomainMigrationDump,
  SynchronizerNodeIdentities,
}
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil.generateRandomOnboardingSecret
import org.lfdecentralizedtrust.splice.sv.util.Secrets
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SvApp}

import java.util.Optional
import org.lfdecentralizedtrust.splice.util.{
  BackupDump,
  Codec,
  Contract,
  SynchronizerMigrationUtil,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.config.{
  NonNegativeDuration,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.migration.ParticipantUsersDataExporter
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future, blocking}
import scala.jdk.OptionConverters.*

class HttpSvAdminHandler(
    config: SvAppBackendConfig,
    optDomainMigrationDumpConfig: Option[Path],
    upgradesConfig: UpgradesConfig,
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    cometBftClient: Option[CometBftClient],
    localSynchronizerNode: Option[LocalSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
    domainDataSnapshotGenerator: DomainDataSnapshotGenerator,
    clock: Clock,
    retryProvider: RetryProvider,
    override protected val packageVersionSupport: PackageVersionSupport,
    override protected val timeouts: ProcessingTimeout,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    protected val tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    mat: Materializer,
    httpClient: HttpClient,
) extends v0.SvAdminHandler[TracedUser]
    with FlagCloseableAsync
    with HttpVotesHandler
    with HttpValidatorLicensesHandler
    with HttpFeatureSupportHandler {

  implicit private val loggingContext: ErrorLoggingContext =
    ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)

  override protected val workflowId: String = this.getClass.getSimpleName
  private val svStore = svStoreWithIngestion.store
  private val dsoStore = dsoStoreWithIngestion.store
  override protected val votesStore: ActiveVotesStore = dsoStore
  override protected val validatorLicensesStore: AppStore = dsoStore

  // Similar to PublishScanConfigTrigger, this class creates its own scan connection
  // on demand, because scan might not be available at application startup.
  private def createScanConnection(): Future[ScanConnection] =
    config.scan match {
      case None =>
        Future.failed(
          Status.UNAVAILABLE
            .withDescription(
              "This application is not configured to connect to a scan service. " +
                " Check the application configuration or use the scan API to query votes information."
            )
            .asRuntimeException()
        )
      case Some(scanConfig) =>
        implicit val tc: TraceContext = TraceContext.empty
        ScanConnection
          .singleUncached(
            ScanAppClientConfig(NetworkAppClientConfig(scanConfig.internalUrl)),
            upgradesConfig,
            clock,
            retryProvider,
            loggerFactory,
            retryConnectionOnInitialFailure = true,
          )
    }
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var scanConnectionV: Option[Future[ScanConnection]] = None
  private def scanConnectionF: Future[ScanConnection] = blocking {
    this.synchronized {
      scanConnectionV match {
        case Some(f) => f
        case None =>
          val f = createScanConnection()
          scanConnectionV = Some(f)
          f
      }
    }
  }

  def listOngoingValidatorOnboardings(
      respond: v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listOngoingValidatorOnboardings") { _ => _ =>
      for {
        validatorOnboardings <- svStore.listValidatorOnboardings()
      } yield {
        definitions.ListOngoingValidatorOnboardingsResponse(
          validatorOnboardings.map { onboarding =>
            val secret = Secrets
              .decodeValidatorOnboardingSecret(
                onboarding.payload.candidateSecret,
                dsoStore.key.svParty,
              )

            definitions.ValidatorOnboarding(
              secret.toApiResponse,
              onboarding.toHttp,
              secret.partyHint,
            )
          }.toVector
        )
      }
    }
  }

  override def listValidatorLicenses(
      respond: SvAdminResource.ListValidatorLicensesResponse.type
  )(after: Option[Long], limit: Option[Int])(
      tuser: TracedUser
  ): Future[SvAdminResource.ListValidatorLicensesResponse] = {
    this
      .listValidatorLicenses(after, limit)(tuser.traceContext, ec)
      .map(SvAdminResource.ListValidatorLicensesResponse.OK)
  }

  def prepareValidatorOnboarding(
      respond: v0.SvAdminResource.PrepareValidatorOnboardingResponse.type
  )(
      body: definitions.PrepareValidatorOnboardingRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.PrepareValidatorOnboardingResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.prepareValidatorOnboarding") { _ => _ =>
      val secret = generateRandomOnboardingSecret(svStore.key.svParty, body.partyHint)
      val expiresIn = NonNegativeFiniteDuration.ofSeconds(body.expiresIn.toLong)
      dsoStore
        .getDsoRules()
        .flatMap { dsoRules =>
          SvApp
            .prepareValidatorOnboarding(
              secret,
              expiresIn,
              svStoreWithIngestion,
              dsoRules.domain,
              clock,
              logger,
              retryProvider,
            )
        }
        .flatMap {
          case Left(reason) =>
            Future.failed(
              HttpErrorHandler.internalServerError(s"Could not prepare onboarding: $reason")
            )
          case Right(()) =>
            Future.successful(definitions.PrepareValidatorOnboardingResponse(secret.toApiResponse))
        }
    }
  }

  def listAmuletPriceVotes(
      respond: v0.SvAdminResource.ListAmuletPriceVotesResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListAmuletPriceVotesResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listAmuletPriceVotes") { _ => _ =>
      for {
        amuletPriceVotes <- dsoStore.listAmuletPriceVotes()
      } yield {
        definitions.ListAmuletPriceVotesResponse(
          amuletPriceVotes.map(_.toHttp).toVector
        )
      }
    }
  }

  def listOpenMiningRounds(respond: v0.SvAdminResource.ListOpenMiningRoundsResponse.type)()(
      tuser: TracedUser
  ): Future[v0.SvAdminResource.ListOpenMiningRoundsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listOpenMiningRounds") { _ => _ =>
      for {
        openMiningRoundTriple <- dsoStore.lookupOpenMiningRoundTriple()
      } yield {
        definitions.ListOpenMiningRoundsResponse(
          (openMiningRoundTriple match {
            case Some(triple) => triple.toSeq
            case _ => Seq.empty
          }).map(_.toHttp).toVector
        )
      }
    }
  }

  def updateAmuletPriceVote(
      respond: v0.SvAdminResource.UpdateAmuletPriceVoteResponse.type
  )(
      body: definitions.UpdateAmuletPriceVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.UpdateAmuletPriceVoteResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.updateAmuletPriceVote") { implicit traceContext => _ =>
      retryProvider.retryForClientCalls(
        "updateAmuletPriceVote",
        "Update Amulet Price Vote", {
          val amuletPrice = Codec.tryDecode(Codec.BigDecimal)(body.amuletPrice)
          SvApp
            .updateAmuletPriceVote(
              amuletPrice,
              dsoStoreWithIngestion,
              logger,
            )
            .flatMap {
              case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
              case Right(()) =>
                Future.successful(v0.SvAdminResource.UpdateAmuletPriceVoteResponseOK)
            }
        },
        logger,
      )
    }
  }

  def isAuthorized(
      respond: v0.SvAdminResource.IsAuthorizedResponse.type
  )(
  )(tuser: TracedUser): Future[v0.SvAdminResource.IsAuthorizedResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.isAuthorized") { _ => _ =>
      Future.successful(v0.SvAdminResource.IsAuthorizedResponseOK)
    }
  }

  def createVoteRequest(respond: v0.SvAdminResource.CreateVoteRequestResponse.type)(
      body: definitions.CreateVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.CreateVoteRequestResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.createVoteRequest") { _ => _ =>
      SvApp
        .createVoteRequest(
          body.requester,
          body.action,
          body.url,
          body.description,
          body.expiration,
          body.effectiveTime match {
            case Some(effectiveTime) => Optional.of(effectiveTime.toInstant)
            case None => Optional.empty()
          },
          dsoStoreWithIngestion,
          retryProvider,
          logger,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(_) => Future.successful(v0.SvAdminResource.CreateVoteRequestResponseOK)
        }
    }
  }

  override def listDsoRulesVoteRequests(
      respond: v0.SvAdminResource.ListDsoRulesVoteRequestsResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListDsoRulesVoteRequestsResponse] = {
    this
      .listDsoRulesVoteRequests(tuser.traceContext, ec)
      .map(v0.SvAdminResource.ListDsoRulesVoteRequestsResponse.OK)
  }

  override def listVoteRequestResults(
      respond: v0.SvAdminResource.ListVoteRequestResultsResponse.type
  )(
      body: definitions.ListVoteResultsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ListVoteRequestResultsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listDsoRulesVoteResults") { _ => _ =>
      for {
        scanConnection <- scanConnectionF
        voteResults <- scanConnection.listVoteRequestResults(
          body.actionName,
          body.accepted,
          body.requester,
          body.effectiveFrom,
          body.effectiveTo,
          body.limit.intValue,
        )
      } yield {
        v0.SvAdminResource.ListVoteRequestResultsResponse.OK(
          definitions.ListDsoRulesVoteResultsResponse(
            voteResults
              .map(voteResult => {
                io.circe.parser
                  .parse(
                    ApiCodecCompressed
                      .apiValueToJsValue(Contract.javaValueToLfValue(voteResult.toValue))
                      .compactPrint
                  )
                  .valueOr(err =>
                    ErrorUtil.invalidState(s"Failed to convert from spray to circe: $err")
                  )
              })
              .toVector
          )
        )
      }
    }
  }

  override def lookupDsoRulesVoteRequest(
      respond: v0.SvAdminResource.LookupDsoRulesVoteRequestResponse.type
  )(
      voteRequestContractId: String
  )(tuser: TracedUser): Future[v0.SvAdminResource.LookupDsoRulesVoteRequestResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    this
      .lookupDsoRulesVoteRequest(voteRequestContractId)
      .map(v0.SvAdminResource.LookupDsoRulesVoteRequestResponse.OK)
  }

  override def castVote(respond: SvAdminResource.CastVoteResponse.type)(
      body: definitions.CastVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.CastVoteResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.castVote") { _ => _ =>
      SvApp
        .castVote(
          new splice.dsorules.VoteRequest.ContractId(body.voteRequestContractId),
          body.isAccepted,
          body.reasonUrl,
          body.reasonDescription,
          dsoStoreWithIngestion,
          retryProvider,
          logger,
        )
        .flatMap {
          case Left(cause) => Future.failed(HttpErrorHandler.badRequest(cause))
          case Right(_) => Future.successful(v0.SvAdminResource.CastVoteResponseCreated)
        }
    }
  }

  override def listVoteRequestsByTrackingCid(
      respond: v0.SvAdminResource.ListVoteRequestsByTrackingCidResponse.type
  )(
      body: definitions.BatchListVotesByVoteRequestsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ListVoteRequestsByTrackingCidResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    this
      .listVoteRequestsByTrackingCid(body)
      .map(v0.SvAdminResource.ListVoteRequestsByTrackingCidResponse.OK)
  }

  override def getCometBftNodeDebugDump(
      respond: v0.SvAdminResource.GetCometBftNodeDebugDumpResponse.type
  )()(tuser: TracedUser): Future[
    v0.SvAdminResource.GetCometBftNodeDebugDumpResponse
  ] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getCometBftNodeDebugDump") { _ => _ =>
      withClientOrNotFound(respond.NotFound) { client =>
        client
          .nodeDebugDump()
          .map(response =>
            definitions.CometBftNodeDumpOrErrorResponse(
              definitions.CometBftNodeDumpResponse(
                status = response.status,
                networkInfo = response.networkInfo,
                abciInfo = response.abciInfo,
                validators = response.validators,
              )
            )
          )
      }
    }
  }

  override def getSequencerNodeStatus(
      respond: v0.SvAdminResource.GetSequencerNodeStatusResponse.type
  )()(tuser: TracedUser): Future[
    v0.SvAdminResource.GetSequencerNodeStatusResponse
  ] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getSequencerNodeStatus") { _ => _ =>
      withSequencerConnectionOrNotFound(respond.NotFound)(
        _.getStatus.map(SpliceStatus.toHttpNodeStatus(_))
      )
    }
  }

  override def getMediatorNodeStatus(
      respond: v0.SvAdminResource.GetMediatorNodeStatusResponse.type
  )()(tuser: TracedUser): Future[
    v0.SvAdminResource.GetMediatorNodeStatusResponse
  ] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getMediatorNodeStatus") { _ => _ =>
      withMediatorConnectionOrNotFound(respond.NotFound)(
        _.getStatus.map(SpliceStatus.toHttpNodeStatus(_))
      )
    }
  }

  override def pauseDecentralizedSynchronizer(
      respond: v0.SvAdminResource.PauseDecentralizedSynchronizerResponse.type
  )()(
      tuser: TracedUser
  ): Future[v0.SvAdminResource.PauseDecentralizedSynchronizerResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.pauseDecentralizedSynchronizer") { _ => _ =>
      for {
        decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
        _ <- SynchronizerMigrationUtil.ensureSynchronizerIsPaused(
          participantAdminConnection,
          decentralizedSynchronizer,
        )
      } yield v0.SvAdminResource.PauseDecentralizedSynchronizerResponseOK
    }
  }

  override def unpauseDecentralizedSynchronizer(
      respond: v0.SvAdminResource.UnpauseDecentralizedSynchronizerResponse.type
  )()(
      tuser: TracedUser
  ): Future[v0.SvAdminResource.UnpauseDecentralizedSynchronizerResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.unpauseDecentralizedSynchronizer") { _ => _ =>
      for {
        decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
        _ <- SynchronizerMigrationUtil.ensureSynchronizerIsUnpaused(
          participantAdminConnection,
          decentralizedSynchronizer,
        )
      } yield v0.SvAdminResource.UnpauseDecentralizedSynchronizerResponseOK
    }
  }

  override def getDomainMigrationDump(
      respond: v0.SvAdminResource.GetDomainMigrationDumpResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetDomainMigrationDumpResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainMigrationDump") { implicit tc => _ =>
      localSynchronizerNode match {
        case Some(synchronizerNode) =>
          dsoStore.getDsoRules().flatMap { dsoRules =>
            dsoRules.payload.config.nextScheduledSynchronizerUpgrade.toScala match {
              case Some(scheduled) =>
                DomainMigrationDump
                  .getDomainMigrationDump(
                    config.domains.global.alias,
                    svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Medium),
                    participantAdminConnection,
                    synchronizerNode,
                    loggerFactory,
                    dsoStore,
                    scheduled.migrationId,
                    domainDataSnapshotGenerator,
                  )
                  .map { response =>
                    // DR endpoint does not support separate output files so set outputDirectory = None
                    v0.SvAdminResource.GetDomainMigrationDumpResponse
                      .OK(response.toHttp(outputDirectory = None))
                  }
              case None =>
                Future.failed(
                  HttpErrorHandler.internalServerError(
                    s"Could not get DomainMigrationDump because migration is not scheduled"
                  )
                )
            }
          }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not prepare DomainMigrationDump because domain node is not configured"
            )
          )
      }
    }(traceContext, tracer)
  }

  override def getDomainDataSnapshot(respond: SvAdminResource.GetDomainDataSnapshotResponse.type)(
      timestamp: String,
      partyId: Option[String],
      migrationId: Option[Long],
      force: Option[Boolean],
  )(
      tuser: TracedUser
  ): Future[SvAdminResource.GetDomainDataSnapshotResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainDataSnapshot") { implicit tc => _ =>
      for {
        participantUsersData <- new ParticipantUsersDataExporter(
          svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Medium)
        )
          .exportParticipantUsersData()
      } yield domainDataSnapshotGenerator
        .getDomainDataSnapshot(
          Instant.parse(timestamp),
          partyId.map(Codec.tryDecode(Codec.Party)(_)),
          force.getOrElse(false),
        )
        .map { response =>
          // No output directory for HTTP: Note that this means that it breaks on
          // large outputs.
          val responseHttp = response.toHttp(outputDirectory = None)
          SvAdminResource.GetDomainDataSnapshotResponse.OK(
            definitions
              .GetDomainDataSnapshotResponse(
                responseHttp.acsTimestamp,
                migrationId getOrElse (config.domainMigrationId + 1),
                responseHttp,
                participantUsersData.toHttp,
              )
          )
        }
    }(traceContext, tracer)
  }.flatten

  override def getSynchronizerNodeIdentitiesDump(
      respond: v0.SvAdminResource.GetSynchronizerNodeIdentitiesDumpResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetSynchronizerNodeIdentitiesDumpResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getSynchronizerNodeIdentitiesDump") { implicit tc => _ =>
      localSynchronizerNode match {
        case Some(synchronizerNode) =>
          SynchronizerNodeIdentities
            .getSynchronizerNodeIdentities(
              participantAdminConnection,
              synchronizerNode,
              dsoStore,
              config.domains.global.alias,
              loggerFactory,
            )
            .map { response =>
              SvAdminResource.GetSynchronizerNodeIdentitiesDumpResponse.OK(
                definitions.GetSynchronizerNodeIdentitiesDumpResponse(response.toHttp())
              )
            }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not prepare SynchronizerNodeIdentitiesDump because domain node is not configured"
            )
          )
      }
    }(traceContext, tracer)
  }

  private def withClientOrNotFound[T](
      notFound: definitions.ErrorResponse => T
  )(call: CometBftClient => Future[T]) = cometBftClient
    .fold {
      notFound(definitions.ErrorResponse("CometBFT is not configured."))
        .pure[Future]
    } { call }

  private def withSequencerConnectionOrNotFound[T](
      notFound: definitions.ErrorResponse => T
  )(call: SequencerAdminConnection => Future[T]) = localSynchronizerNode
    .map(_.sequencerAdminConnection)
    .fold {
      notFound(definitions.ErrorResponse("Sequencer is not configured."))
        .pure[Future]
    } { call }

  private def withMediatorConnectionOrNotFound[T](
      notFound: definitions.ErrorResponse => T
  )(call: MediatorAdminConnection => Future[T]) = localSynchronizerNode
    .map(_.mediatorAdminConnection)
    .fold {
      notFound(definitions.ErrorResponse("Mediator is not configured."))
        .pure[Future]
    } { call }

  override def triggerDomainMigrationDump(
      respond: SvAdminResource.TriggerDomainMigrationDumpResponse.type
  )(
      request: TriggerDomainMigrationDumpRequest
  )(extracted: TracedUser): Future[SvAdminResource.TriggerDomainMigrationDumpResponse] = {
    withSpan(s"$workflowId.triggerDomainMigrationDump") { implicit tc => _ =>
      localSynchronizerNode match {
        case Some(synchronizerNode) =>
          optDomainMigrationDumpConfig match {
            case Some(dumpPath) =>
              val exportAt = request.timestamp.map(Instant.parse)
              val dumpRequest = exportAt match {
                case Some(at) =>
                  logger.info(
                    s"Triggering synchronizer migration dump for possibly unpaused synchronizer at $at"
                  )
                  DomainMigrationDump.getDomainMigrationDumpUnsafe(
                    config.domains.global.alias,
                    svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Low),
                    participantAdminConnection,
                    synchronizerNode,
                    loggerFactory,
                    dsoStore,
                    request.migrationId,
                    domainDataSnapshotGenerator,
                    at,
                  )
                case None =>
                  logger.info("Triggerin synchronizer migration dump for expected synchronizer")
                  DomainMigrationDump
                    .getDomainMigrationDump(
                      config.domains.global.alias,
                      svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Low),
                      participantAdminConnection,
                      synchronizerNode,
                      loggerFactory,
                      dsoStore,
                      request.migrationId,
                      domainDataSnapshotGenerator,
                    )
              }
              for {
                dump <- dumpRequest
              } yield {
                import io.circe.syntax.*
                val pathForTheFiles = exportAt.fold(dumpPath.getParent)(at =>
                  dumpPath.getParent
                    .createChild(
                      s"export_at_${at.toEpochMilli}",
                      asDirectory = true,
                      createParents = true,
                    )
                    .path
                )
                logger.info(s"Writing dump at $pathForTheFiles")
                val path = BackupDump.writeToPath(
                  (pathForTheFiles / dumpPath.name).path,
                  dump.toHttp(outputDirectory = Some(pathForTheFiles.toString)).asJson.noSpaces,
                )
                logger.info(s"Wrote domain migration dump at path $path")
                SvAdminResource.TriggerDomainMigrationDumpResponseOK
              }
            case None =>
              Future.failed(
                HttpErrorHandler.internalServerError(
                  s"Could not trigger DomainMigrationDump because dump path is not configured"
                )
              )
          }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not trigger DomainMigrationDump because domain node is not configured"
            )
          )
      }
    }(extracted.traceContext, tracer)
  }

  override def featureSupport(respond: SvAdminResource.FeatureSupportResponse.type)()(
      extracted: TracedUser
  ): Future[SvAdminResource.FeatureSupportResponse] = {
    readFeatureSupport(dsoStore.key.dsoParty)(
      ec,
      extracted.traceContext,
      tracer,
    )
      .map(SvAdminResource.FeatureSupportResponseOK(_))
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = blocking {
    this.synchronized {
      Seq[AsyncOrSyncCloseable](
        AsyncCloseable(
          "scanConnection",
          scanConnectionV.fold(Future.unit)(_.map(_.close())),
          NonNegativeDuration.tryFromDuration(timeouts.shutdownNetwork.duration),
        )
      )
    }
  }
}
