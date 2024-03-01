package com.daml.network.sv.admin.http

import cats.implicits.catsSyntaxApplicativeId
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.cn
import com.daml.network.config.PeriodicBackupDumpConfig
import com.daml.network.environment.{
  CNNodeStatus,
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.http.v0.{definitions, sv_admin as v0}
import com.daml.network.http.v0.definitions.TriggerDomainMigrationDumpRequest
import com.daml.network.http.v0.sv_admin.SvAdminResource
import com.daml.network.store.{CNNodeAppStoreWithIngestion, PageLimit}
import com.daml.network.sv.{LocalDomainNode, SvApp}
import com.daml.network.sv.cometbft.CometBftClient
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.migration.{
  DomainDataSnapshotGenerator,
  DomainMigrationDump,
  DomainNodeIdentities,
}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.util.{BackupDump, Codec, TemplateJsonDecoder}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.circe.syntax.EncoderOps
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class HttpSvAdminHandler(
    config: SvAppBackendConfig,
    optDomainMigrationDumpConfig: Option[Path],
    svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    cometBftClient: Option[CometBftClient],
    localDomainNode: Option[LocalDomainNode],
    participantAdminConnection: ParticipantAdminConnection,
    domainDataSnapshotGenerator: DomainDataSnapshotGenerator,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
) extends v0.SvAdminHandler[TracedUser]
    with Spanning
    with NamedLogging {

  implicit private val loggingContext: ErrorLoggingContext =
    ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)

  private val workflowId = this.getClass.getSimpleName
  private val svStore = svStoreWithIngestion.store
  private val svcStore = svcStoreWithIngestion.store

  def listOngoingValidatorOnboardings(
      respond: v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listOngoingValidatorOnboardings") { _ => _ =>
      for {
        validatorOnboardings <- svStore.listValidatorOnboardings()
      } yield {
        definitions.ListOngoingValidatorOnboardingsResponse(
          validatorOnboardings.map(_.toHttp).toVector
        )
      }
    }
  }

  def listValidatorLicenses(
      respond: v0.SvAdminResource.ListValidatorLicensesResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListValidatorLicensesResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listValidatorLicenses") { _ => _ =>
      for {
        validatorLicenses <- svcStore.listValidatorLicenses()
      } yield {
        definitions.ListValidatorLicensesResponse(
          validatorLicenses.map(_.toHttp).toVector
        )
      }
    }
  }

  def prepareValidatorOnboarding(
      respond: v0.SvAdminResource.PrepareValidatorOnboardingResponse.type
  )(
      body: definitions.PrepareValidatorOnboardingRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.PrepareValidatorOnboardingResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.prepareValidatorOnboarding") { _ => _ =>
      val secret = generateRandomOnboardingSecret()
      val expiresIn = NonNegativeFiniteDuration.ofSeconds(body.expiresIn.toLong)
      svcStore
        .getSvcRules()
        .flatMap { svcRules =>
          SvApp
            .prepareValidatorOnboarding(
              secret,
              expiresIn,
              svStoreWithIngestion,
              svcRules.domain,
              clock,
              logger,
            )
        }
        .flatMap {
          case Left(reason) =>
            Future.failed(
              HttpErrorHandler.internalServerError(s"Could not prepare onboarding: $reason")
            )
          case Right(()) =>
            Future.successful(definitions.PrepareValidatorOnboardingResponse(secret))
        }
    }
  }

  def listCoinPriceVotes(
      respond: v0.SvAdminResource.ListCoinPriceVotesResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListCoinPriceVotesResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listCoinPriceVotes") { _ => _ =>
      for {
        coinPriceVotes <- svcStore.listCoinPriceVotes()
      } yield {
        definitions.ListCoinPriceVotesResponse(
          coinPriceVotes.map(_.toHttp).toVector
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
        openMiningRoundTriple <- svcStore.lookupOpenMiningRoundTriple()
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

  def updateCoinPriceVote(
      respond: v0.SvAdminResource.UpdateCoinPriceVoteResponse.type
  )(
      body: definitions.UpdateCoinPriceVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.UpdateCoinPriceVoteResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.updateCoinPriceVote") { _ => _ =>
      val coinPrice = Codec.tryDecode(Codec.BigDecimal)(body.coinPrice)
      SvApp
        .updateCoinPriceVote(
          coinPrice,
          svcStoreWithIngestion,
          logger,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.UpdateCoinPriceVoteResponseOK)
        }
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

  def createElectionRequest(respond: v0.SvAdminResource.CreateElectionRequestResponse.type)(
      body: definitions.CreateElectionRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.CreateElectionRequestResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.createElectionRequest") { _ => _ =>
      SvApp
        .createElectionRequest(
          body.requester,
          body.ranking,
          svcStoreWithIngestion,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.CreateElectionRequestResponseOK)
        }
    }
  }

  def getElectionRequest(
      respond: v0.SvAdminResource.GetElectionRequestResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetElectionRequestResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getElectionRequest") { _ => _ =>
      for {
        electionRequests <- SvApp.getElectionRequest(svcStoreWithIngestion)
      } yield {
        definitions.GetElectionRequestResponse(
          electionRequests.map(_.toHttp).toVector
        )
      }
    }
  }

  def createVoteRequest2(respond: v0.SvAdminResource.CreateVoteRequest2Response.type)(
      body: definitions.CreateVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.CreateVoteRequest2Response] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.createVoteRequest2") { _ => _ =>
      SvApp
        .createVoteRequest2(
          body.requester,
          body.action,
          body.url,
          body.description,
          body.expiration,
          svcStoreWithIngestion,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.CreateVoteRequest2ResponseOK)
        }
    }
  }

  def listSvcRulesVoteRequests2(
      respond: v0.SvAdminResource.ListSvcRulesVoteRequests2Response.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListSvcRulesVoteRequests2Response] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listSvcRulesVoteRequests2") { _ => _ =>
      for {
        svcRulesVoteRequests <- svcStore.listVoteRequests2()
      } yield {
        definitions.ListSvcRulesVoteRequestsResponse(
          svcRulesVoteRequests.map(_.toHttp).toVector
        )
      }
    }
  }

  def listVoteRequestResults2(
      respond: v0.SvAdminResource.ListVoteRequestResults2Response.type
  )(
      body: definitions.ListVoteResultsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ListVoteRequestResults2Response] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listSvcRulesVoteResults2") { _ => _ =>
      for {
        voteResults <- svcStore.listVoteRequestResults2(
          body.actionName,
          body.executed,
          body.requester,
          body.effectiveFrom,
          body.effectiveTo,
          PageLimit.tryCreate(body.limit.intValue),
        )
      } yield {
        definitions.ListSvcRulesVoteResultsResponse(
          voteResults
            .map(_.toJson)
            .map(json =>
              io.circe.parser
                .parse(json)
                .getOrElse(throw new IllegalStateException(s"Failed to parse $json"))
            )
            .toVector
        )
      }
    }
  }

  def lookupSvcRulesVoteRequest2(
      respond: v0.SvAdminResource.LookupSvcRulesVoteRequest2Response.type
  )(
      voteRequestContractId: String
  )(tuser: TracedUser): Future[v0.SvAdminResource.LookupSvcRulesVoteRequest2Response] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.lookupSvcRulesVoteRequest2") { _ => _ =>
      svcStore
        .lookupVoteRequest2(
          new cn.svcrules.VoteRequest2.ContractId(voteRequestContractId)
        )
        .flatMap {
          case Some(voteRequest) =>
            Future.successful(
              v0.SvAdminResource.LookupSvcRulesVoteRequest2Response.OK(
                definitions.LookupSvcRulesVoteRequestResponse(
                  voteRequest.toHttp
                )
              )
            )
          case None =>
            Future.failed(
              HttpErrorHandler.notFound(
                s"No VoteRequest found contract: $voteRequestContractId"
              )
            )
        }
    }
  }

  override def castVote2(respond: SvAdminResource.CastVote2Response.type)(
      body: definitions.CastVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.CastVote2Response] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.castVote2") { _ => _ =>
      SvApp
        .castVote2(
          new cn.svcrules.VoteRequest2.ContractId(body.voteRequestContractId),
          body.isAccepted,
          body.reasonUrl,
          body.reasonDescription,
          svcStoreWithIngestion,
          retryProvider,
          logger,
        )
        .flatMap {
          case Left(cause) => Future.failed(HttpErrorHandler.badRequest(cause))
          case Right(_) => Future.successful(v0.SvAdminResource.CastVote2ResponseCreated)
        }
    }
  }

  override def listVoteRequests2ByTrackingCid(
      respond: v0.SvAdminResource.ListVoteRequests2ByTrackingCidResponse.type
  )(
      body: definitions.BatchListVotesByVoteRequestsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ListVoteRequests2ByTrackingCidResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listVoteRequests2ByTrackingCid") { _ => _ =>
      for {
        svcRulesVotes <- svcStore.listVoteRequests2ByTrackingCid(
          body.voteRequestContractIds.map(new cn.svcrules.VoteRequest2.ContractId(_))
        )
      } yield {
        definitions.ListVoteRequest2ByTrackingCidResponse(
          svcRulesVotes.map(_.toHttp).toVector
        )
      }
    }
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

  override def triggerAcsDump(respond: v0.SvAdminResource.TriggerAcsDumpResponse.type)()(
      tuser: TracedUser
  ): Future[v0.SvAdminResource.TriggerAcsDumpResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.triggerAcsDump") { _ => _ =>
      config.acsStoreDump match {
        case None =>
          Future.failed(
            Status.FAILED_PRECONDITION
              .withDescription("No ACS store dump directory configured")
              .asRuntimeException()
          )
        case Some(acsDumpConfig: PeriodicBackupDumpConfig) =>
          for {
            // Note: we expect the snapshots to be small enough to be delivered within the request timeout.
            (filename, snapshot) <- SvUtil.writeAcsStoreDump(
              acsDumpConfig.location,
              loggerFactory,
              svcStore,
              clock.now,
            )
          } yield v0.SvAdminResource.TriggerAcsDumpResponseOK(
            definitions.TriggerAcsDumpResponse(
              filename = filename.toString,
              numEvents = snapshot.contracts.size,
              offset = snapshot.offset,
            )
          )
      }
    }
  }
  override def getAcsStoreDump(
      respond: v0.SvAdminResource.GetAcsStoreDumpResponse.type
  )()(tuser: TracedUser): scala.concurrent.Future[
    v0.SvAdminResource.GetAcsStoreDumpResponse
  ] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getAcsStoreDump") { _ => _ =>
      svcStore
        .getJsonAcsSnapshot()
        .map(snapshot =>
          v0.SvAdminResource.GetAcsStoreDumpResponse.OK(
            definitions.GetAcsStoreDumpResponse(
              offset = snapshot.offset,
              contracts = snapshot.contracts.map(_.toHttp).toVector,
            )
          )
        )
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
        _.getStatus.map(CNNodeStatus.toHttpNodeStatus(_))
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
        _.getStatus.map(CNNodeStatus.toHttpNodeStatus(_))
      )
    }
  }

  override def pauseGlobalDomain(respond: v0.SvAdminResource.PauseGlobalDomainResponse.type)()(
      tuser: TracedUser
  ): Future[v0.SvAdminResource.PauseGlobalDomainResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.pauseGlobalDomain") { _ => _ =>
      for {
        globalDomain <- svcStore.getSvcRules().map(_.domain)
        _ <- changeDomainRatePerParticipant(globalDomain, NonNegativeInt.zero)
      } yield v0.SvAdminResource.PauseGlobalDomainResponseOK
    }
  }

  override def getDomainMigrationDump(
      respond: v0.SvAdminResource.GetDomainMigrationDumpResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetDomainMigrationDumpResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainMigrationDump") { implicit tc => _ =>
      localDomainNode match {
        case Some(domainNode) =>
          svcStore.getSvcRules().flatMap { svcRules =>
            svcRules.payload.config.nextScheduledDomainUpgrade.toScala match {
              case Some(scheduled) =>
                DomainMigrationDump
                  .getDomainMigrationDump(
                    config.domains.global.alias,
                    participantAdminConnection,
                    domainNode,
                    loggerFactory,
                    svcStore,
                    scheduled.migrationId,
                    domainDataSnapshotGenerator,
                  )
                  .map { response =>
                    v0.SvAdminResource.GetDomainMigrationDumpResponse.OK(response.toHttp)
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
      body: definitions.GetDomainDataSnapshotRequest
  )(
      tuser: TracedUser
  ): Future[SvAdminResource.GetDomainDataSnapshotResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainDataSnapshot") { implicit tc => _ =>
      domainDataSnapshotGenerator
        .getDomainDataSnapshot(
          Instant.parse(body.timestamp),
          body.partyId.map(Codec.tryDecode(Codec.Party)(_)),
        )
        .map { response =>
          SvAdminResource.GetDomainDataSnapshotResponse.OK(
            definitions.GetDomainDataSnapshotResponse(response.toHttp)
          )
        }
    }(traceContext, tracer)
  }

  override def getDomainNodeIdentitiesDump(
      respond: v0.SvAdminResource.GetDomainNodeIdentitiesDumpResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetDomainNodeIdentitiesDumpResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainNodeIdentitiesDump") { implicit tc => _ =>
      localDomainNode match {
        case Some(domainNode) =>
          DomainNodeIdentities
            .getDomainNodeIdentities(
              participantAdminConnection,
              domainNode,
              svcStore,
              config.domains.global.alias,
              loggerFactory,
            )
            .map { response =>
              SvAdminResource.GetDomainNodeIdentitiesDumpResponse.OK(
                definitions.GetDomainNodeIdentitiesDumpResponse(response.toHttp())
              )
            }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not prepare DomainNodeIdentitiesDump because domain node is not configured"
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
  )(call: SequencerAdminConnection => Future[T]) = localDomainNode
    .map(_.sequencerAdminConnection)
    .fold {
      notFound(definitions.ErrorResponse("Sequencer is not configured."))
        .pure[Future]
    } { call }

  private def withMediatorConnectionOrNotFound[T](
      notFound: definitions.ErrorResponse => T
  )(call: MediatorAdminConnection => Future[T]) = localDomainNode
    .map(_.mediatorAdminConnection)
    .fold {
      notFound(definitions.ErrorResponse("Mediator is not configured."))
        .pure[Future]
    } { call }

  private def changeDomainRatePerParticipant(globalDomainId: DomainId, rate: NonNegativeInt)(
      implicit tc: TraceContext
  ) = for {
    id <- participantAdminConnection.getId()
    result <- participantAdminConnection
      .ensureDomainParameters(
        globalDomainId,
        _.tryUpdate(confirmationRequestsMaxRate = rate),
        signedBy = id.namespace.fingerprint,
      )
  } yield result

  override def triggerDomainMigrationDump(
      respond: SvAdminResource.TriggerDomainMigrationDumpResponse.type
  )(
      request: TriggerDomainMigrationDumpRequest
  )(extracted: TracedUser): Future[SvAdminResource.TriggerDomainMigrationDumpResponse] = {
    withSpan(s"$workflowId.triggerDomainMigrationDump") { implicit tc => _ =>
      localDomainNode match {
        case Some(domainNode) =>
          optDomainMigrationDumpConfig match {
            case Some(dumpPath) =>
              for {
                dump <- DomainMigrationDump
                  .getDomainMigrationDump(
                    config.domains.global.alias,
                    participantAdminConnection,
                    domainNode,
                    loggerFactory,
                    svcStore,
                    request.migrationId,
                    domainDataSnapshotGenerator,
                  )
              } yield {
                val path = BackupDump.writeToPath(
                  dumpPath,
                  dump.asJson.noSpaces,
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

}
