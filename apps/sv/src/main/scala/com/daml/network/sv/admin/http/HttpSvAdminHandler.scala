package com.daml.network.sv.admin.http

import cats.implicits.catsSyntaxApplicativeId
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.cn
import com.daml.network.config.BackupDumpConfig
import com.daml.network.environment.{
  CNNodeStatus,
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SequencerAdminConnection,
}
import com.daml.network.http.v0.{definitions, sv_admin as v0}
import com.daml.network.http.v0.definitions.TriggerDomainMigrationDumpRequest
import com.daml.network.http.v0.sv_admin.SvAdminResource
import com.daml.network.store.{CNNodeAppStoreWithIngestion, PageLimit}
import com.daml.network.store.db.AcsJdbcTypes
import com.daml.network.sv.{DomainMigrationDump, DomainNodeIdentitiesDump, LocalDomainNode, SvApp}
import com.daml.network.sv.cometbft.CometBftClient
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.util.{BackupDump, Codec, JsonUtil, TemplateJsonDecoder}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import slick.jdbc.PostgresProfile

import java.nio.file.Path
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
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
) extends v0.SvAdminHandler[TracedUser]
    with Spanning
    with NamedLogging
    with AcsJdbcTypes {

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

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

  def approveSvIdentity(
      respond: v0.SvAdminResource.ApproveSvIdentityResponse.type
  )(
      body: definitions.ApproveSvIdentityRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ApproveSvIdentityResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.approveSvIdentity") { _ => _ =>
      svcStore
        .getSvcRules()
        .flatMap { svcRules =>
          SvApp
            .approveSvIdentity(
              body.candidateName,
              body.candidateKey,
              svStoreWithIngestion,
              svcRules.domain,
              logger,
            )
        }
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.ApproveSvIdentityResponseOK)
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
          svcStoreWithIngestion,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.CreateVoteRequestResponseOK)
        }
    }
  }

  def listSvcRulesVoteRequests(
      respond: v0.SvAdminResource.ListSvcRulesVoteRequestsResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.ListSvcRulesVoteRequestsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listSvcRulesVoteRequests") { _ => _ =>
      for {
        svcRulesVoteRequests <- svcStore.listVoteRequests()
      } yield {
        definitions.ListSvcRulesVoteRequestsResponse(
          svcRulesVoteRequests.map(_.toHttp).toVector
        )
      }
    }
  }

  def listSvcRulesVoteResults(
      respond: v0.SvAdminResource.ListSvcRulesVoteResultsResponse.type
  )(
      body: definitions.ListVoteResultsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.ListSvcRulesVoteResultsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listSvcRulesVoteResults") { _ => _ =>
      for {
        voteResults <- svcStore.listVoteResults(
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
            .map(res =>
              JsonUtil.sprayJsValueToCirceJson(
                payloadJsonFromDefinedDataType(
                  res
                )
              )
            )
            .toVector
        )
      }
    }
  }

  def lookupSvcRulesVoteRequest(respond: v0.SvAdminResource.LookupSvcRulesVoteRequestResponse.type)(
      voteRequestContractId: String
  )(tuser: TracedUser): Future[v0.SvAdminResource.LookupSvcRulesVoteRequestResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.lookupSvcRulesVoteRequest") { _ => _ =>
      svcStore
        .lookupVoteRequest(
          new cn.svcrules.VoteRequest.ContractId(voteRequestContractId)
        )
        .flatMap {
          case Some(voteRequest) =>
            Future.successful(
              v0.SvAdminResource.LookupSvcRulesVoteRequestResponse.OK(
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

  def castVote(respond: v0.SvAdminResource.CastVoteResponse.type)(
      body: definitions.CastVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.CastVoteResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.castVote") { _ => _ =>
      SvApp
        .castVote(
          new cn.svcrules.VoteRequest.ContractId(body.voteRequestContractId),
          body.isAccepted,
          body.reasonUrl,
          body.reasonDescription,
          svcStoreWithIngestion,
        )
        .flatMap {
          case Left(cause) => Future.failed(HttpErrorHandler.badRequest(cause))
          case Right(_) => Future.successful(v0.SvAdminResource.CastVoteResponseCreated)
        }
    }
  }

  def updateVote(respond: v0.SvAdminResource.UpdateVoteResponse.type)(
      body: definitions.UpdateVoteRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.UpdateVoteResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.updateVote") { _ => _ =>
      SvApp
        .updateVote(
          new cn.svcrules.Vote.ContractId(body.voteContractId),
          body.isAccepted,
          body.reasonUrl,
          body.reasonDescription,
          svcStoreWithIngestion,
          logger,
        )
        .flatMap {
          case None =>
            Future.failed(
              HttpErrorHandler.notFound(s"No Vote found contract: ${body.voteContractId}")
            )
          case Some(_) => Future.successful(v0.SvAdminResource.UpdateVoteResponseOK)
        }
    }
  }

  def batchListVotesByVoteRequests(
      respond: v0.SvAdminResource.BatchListVotesByVoteRequestsResponse.type
  )(
      body: definitions.BatchListVotesByVoteRequestsRequest
  )(tuser: TracedUser): Future[v0.SvAdminResource.BatchListVotesByVoteRequestsResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.batchListVotesByVoteRequests") { _ => _ =>
      for {
        svcRulesVotes <- svcStore.listVotesByVoteRequests(
          body.voteRequestContractIds.map(new cn.svcrules.VoteRequest.ContractId(_))
        )
      } yield {
        definitions.ListVotesResponse(
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
        case Some(acsDumpConfig: BackupDumpConfig) =>
          for {
            // Note: we expect the snapshots to be small enough to be delivered within the request timeout.
            (filename, snapshot) <- SvUtil.writeAcsStoreDump(
              acsDumpConfig,
              loggerFactory,
              svcStore,
              clock,
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
                  .getDomainPausedTime(
                    participantAdminConnection,
                    svcStore,
                  )
                  .flatMap {
                    case Some(pausedAt) =>
                      DomainMigrationDump
                        .getDomainMigrationDump(
                          config.domains.global.alias,
                          participantAdminConnection,
                          domainNode,
                          loggerFactory,
                          svcStore,
                          clock,
                          scheduled.migrationId,
                          pausedAt,
                        )
                        .map { response =>
                          v0.SvAdminResource.GetDomainMigrationDumpResponse.OK(response.toHttp)
                        }
                    case None =>
                      Future.failed(
                        HttpErrorHandler.internalServerError(
                          s"Could not get DomainMigrationDump because domain is not paused yet"
                        )
                      )
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

  override def getDomainNodeIdentitiesDump(
      respond: v0.SvAdminResource.GetDomainNodeIdentitiesDumpResponse.type
  )()(tuser: TracedUser): Future[v0.SvAdminResource.GetDomainNodeIdentitiesDumpResponse] = {
    val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.getDomainNodeIdentitiesDump") { implicit tc => _ =>
      localDomainNode match {
        case Some(domainNode) =>
          DomainNodeIdentitiesDump
            .getDomainNodeIdentitiesDump(
              participantAdminConnection,
              domainNode,
              clock,
              loggerFactory,
            )
            .map { response =>
              v0.SvAdminResource.GetDomainNodeIdentitiesDumpResponse.OK(
                DomainNodeIdentitiesDump(response).toHttp
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
        _.tryUpdate(maxRatePerParticipant = rate),
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
                domainPausedAt <- DomainMigrationDump.getDomainPausedTime(
                  participantAdminConnection,
                  svcStore,
                )
                dump <- domainPausedAt match {
                  case Some(pausedAt) =>
                    DomainMigrationDump
                      .getDomainMigrationDump(
                        config.domains.global.alias,
                        participantAdminConnection,
                        domainNode,
                        loggerFactory,
                        svcStore,
                        clock,
                        request.migrationId,
                        pausedAt,
                      )
                  case None =>
                    Future.failed(
                      HttpErrorHandler.internalServerError(
                        s"Could not trigger DomainMigrationDump because domain is not paused yet"
                      )
                    )
                }
              } yield {
                val path = BackupDump.writeToPath(
                  dumpPath,
                  dump.toJson.noSpaces,
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
