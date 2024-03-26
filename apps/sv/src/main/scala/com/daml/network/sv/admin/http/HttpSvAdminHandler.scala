package com.daml.network.sv.admin.http

import cats.implicits.catsSyntaxApplicativeId
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.splice
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
import com.daml.network.sv.store.{SvSvStore, SvDsoStore}
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.util.{BackupDump, Codec, TemplateJsonDecoder}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.circe.syntax.EncoderOps
import io.opentelemetry.api.trace.Tracer

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class HttpSvAdminHandler(
    config: SvAppBackendConfig,
    optDomainMigrationDumpConfig: Option[Path],
    svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: CNNodeAppStoreWithIngestion[SvDsoStore],
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
  private val dsoStore = dsoStoreWithIngestion.store

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
        validatorLicenses <- dsoStore.listValidatorLicenses()
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
    withSpan(s"$workflowId.updateAmuletPriceVote") { _ => _ =>
      val amuletPrice = Codec.tryDecode(Codec.BigDecimal)(body.amuletPrice)
      SvApp
        .updateAmuletPriceVote(
          amuletPrice,
          dsoStoreWithIngestion,
          logger,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.UpdateAmuletPriceVoteResponseOK)
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
          dsoStoreWithIngestion,
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
        electionRequests <- SvApp.getElectionRequest(dsoStoreWithIngestion)
      } yield {
        definitions.GetElectionRequestResponse(
          electionRequests.map(_.toHttp).toVector
        )
      }
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
          dsoStoreWithIngestion,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.CreateVoteRequestResponseOK)
        }
    }
  }

  def listDsoRulesVoteRequests(
      respond: v0.SvAdminResource.ListDsoRulesVoteRequestsResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListDsoRulesVoteRequestsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listDsoRulesVoteRequests") { _ => _ =>
      for {
        dsoRulesVoteRequests <- dsoStore.listVoteRequests()
      } yield {
        definitions.ListDsoRulesVoteRequestsResponse(
          dsoRulesVoteRequests.map(_.toHttp).toVector
        )
      }
    }
  }

  def listVoteRequestResults(
      respond: v0.SvAdminResource.ListVoteRequestResultsResponse.type
  )(
      body: definitions.ListVoteResultsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ListVoteRequestResultsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listDsoRulesVoteResults") { _ => _ =>
      for {
        voteResults <- dsoStore.listVoteRequestResults(
          body.actionName,
          body.executed,
          body.requester,
          body.effectiveFrom,
          body.effectiveTo,
          PageLimit.tryCreate(body.limit.intValue),
        )
      } yield {
        definitions.ListDsoRulesVoteResultsResponse(
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

  def lookupDsoRulesVoteRequest(
      respond: v0.SvAdminResource.LookupDsoRulesVoteRequestResponse.type
  )(
      voteRequestContractId: String
  )(tuser: TracedUser): Future[v0.SvAdminResource.LookupDsoRulesVoteRequestResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.lookupDsoRulesVoteRequest") { _ => _ =>
      dsoStore
        .lookupVoteRequest(
          new splice.dsorules.VoteRequest.ContractId(voteRequestContractId)
        )
        .flatMap {
          case Some(voteRequest) =>
            Future.successful(
              v0.SvAdminResource.LookupDsoRulesVoteRequestResponse.OK(
                definitions.LookupDsoRulesVoteRequestResponse(
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
    withSpan(s"$workflowId.listVoteRequestsByTrackingCid") { _ => _ =>
      for {
        dsoRulesVotes <- dsoStore.listVoteRequestsByTrackingCid(
          body.voteRequestContractIds.map(new splice.dsorules.VoteRequest.ContractId(_))
        )
      } yield {
        definitions.ListVoteRequestByTrackingCidResponse(
          dsoRulesVotes.map(_.toHttp).toVector
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
        globalDomain <- dsoStore.getDsoRules().map(_.domain)
        _ <- changeDomainRatePerParticipant(globalDomain, NonNegativeInt.zero)
      } yield v0.SvAdminResource.PauseGlobalDomainResponseOK
    }
  }

  override def unpauseGlobalDomain(respond: v0.SvAdminResource.UnpauseGlobalDomainResponse.type)()(
      tuser: TracedUser
  ): Future[v0.SvAdminResource.UnpauseGlobalDomainResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.unpauseGlobalDomain") { _ => _ =>
      for {
        globalDomain <- dsoStore.getDsoRules().map(_.domain)
        _ <- changeDomainRatePerParticipant(
          globalDomain,
          DynamicDomainParameters.defaultConfirmationRequestsMaxRate,
        )
      } yield v0.SvAdminResource.UnpauseGlobalDomainResponseOK
    }
  }

  override def getDomainMigrationDump(
      respond: v0.SvAdminResource.GetDomainMigrationDumpResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetDomainMigrationDumpResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainMigrationDump") { implicit tc => _ =>
      localDomainNode match {
        case Some(domainNode) =>
          dsoStore.getDsoRules().flatMap { dsoRules =>
            dsoRules.payload.config.nextScheduledDomainUpgrade.toScala match {
              case Some(scheduled) =>
                DomainMigrationDump
                  .getDomainMigrationDump(
                    config.domains.global.alias,
                    participantAdminConnection,
                    domainNode,
                    loggerFactory,
                    dsoStore,
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
              dsoStore,
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
                    dsoStore,
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
