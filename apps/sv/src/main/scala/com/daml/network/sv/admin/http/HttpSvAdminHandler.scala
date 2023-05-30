package com.daml.network.sv.admin.http

import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId}
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.environment.{CNNodeStatus, SequencerAdminConnection}
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.http.v0.definitions.{
  CometBftNodeDumpResponse,
  CometBftNodeStatusResponse,
  CometBftStatusOrError,
  CreateVoteRequest,
  ErrorResponse,
}
import com.daml.network.http.v0.svAdmin.SvAdminResource
import com.daml.network.http.v0.{definitions, svAdmin as v0}
import com.daml.network.sv.SvApp
import com.daml.network.sv.cometbft.CometBftClient
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.util.Codec
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpSvAdminHandler(
    globalDomain: DomainId,
    svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    cometBftClient: Option[CometBftClient],
    sequencerAdminConnection: Option[SequencerAdminConnection],
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvAdminHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val svStore = svStoreWithIngestion.store
  private val svcStore = svcStoreWithIngestion.store

  def listOngoingValidatorOnboardings(
      respond: v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        validatorOnboardings <- svStore.listValidatorOnboardings()
      } yield {
        definitions.ListOngoingValidatorOnboardingsResponse(
          validatorOnboardings.map(_.toJson).toVector
        )
      }
    }
  }

  def listValidatorLicenses(
      respond: v0.SvAdminResource.ListValidatorLicensesResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListValidatorLicensesResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        validatorLicenses <- svcStore.listValidatorLicenses()
      } yield {
        definitions.ListValidatorLicensesResponse(
          validatorLicenses.map(_.toJson).toVector
        )
      }
    }
  }

  def prepareValidatorOnboarding(
      respond: v0.SvAdminResource.PrepareValidatorOnboardingResponse.type
  )(
      body: definitions.PrepareValidatorOnboardingRequest
  )(adminUser: String): Future[v0.SvAdminResource.PrepareValidatorOnboardingResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val secret = generateRandomOnboardingSecret()
      val expiresIn = NonNegativeFiniteDuration.ofSeconds(body.expiresIn.toLong)
      SvApp
        .prepareValidatorOnboarding(
          secret,
          expiresIn,
          svStoreWithIngestion,
          globalDomain,
          clock,
          logger,
        )
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
  )(adminUser: String): Future[v0.SvAdminResource.ApproveSvIdentityResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      SvApp
        .approveSvIdentity(
          body.candidateName,
          body.candidateKey,
          svStoreWithIngestion,
          globalDomain,
          logger,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.ApproveSvIdentityResponseOK)
        }
    }

  def listCoinPriceVotes(
      respond: v0.SvAdminResource.ListCoinPriceVotesResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListCoinPriceVotesResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        coinPriceVotes <- svcStore.listCoinPriceVotes()
      } yield {
        definitions.ListCoinPriceVotesResponse(
          coinPriceVotes.map(_.toJson).toVector
        )
      }
    }
  }

  def listOpenMiningRounds(respond: v0.SvAdminResource.ListOpenMiningRoundsResponse.type)()(
      adminUser: String
  ): Future[v0.SvAdminResource.ListOpenMiningRoundsResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        openMiningRoundTriple <- svcStore.lookupOpenMiningRoundTriple()
      } yield {
        definitions.ListOpenMiningRoundsResponse(
          (openMiningRoundTriple match {
            case Some(triple) => triple.toSeq
            case _ => Seq.empty
          }).map(_.toJson).toVector
        )
      }
    }
  }

  def updateCoinPriceVote(
      respond: v0.SvAdminResource.UpdateCoinPriceVoteResponse.type
  )(
      body: definitions.UpdateCoinPriceVoteRequest
  )(adminUser: String): Future[v0.SvAdminResource.UpdateCoinPriceVoteResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val coinPrice = Codec.tryDecode(Codec.BigDecimal)(body.coinPrice)
      SvApp
        .updateCoinPriceVote(
          coinPrice,
          svcStoreWithIngestion,
          globalDomain: DomainId,
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
  )(adminUser: String): Future[v0.SvAdminResource.IsAuthorizedResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(v0.SvAdminResource.IsAuthorizedResponseOK)
    }

  def createVoteRequest(respond: SvAdminResource.CreateVoteRequestResponse.type)(
      body: CreateVoteRequest
  )(user: String): Future[SvAdminResource.CreateVoteRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      SvApp
        .createVoteRequest(
          body.requester,
          body.action,
          body.url,
          body.description,
          svcStoreWithIngestion,
          globalDomain,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.CreateVoteRequestResponseOK)
        }
    }

  def listSvcRulesVoteRequests(
      respond: SvAdminResource.ListSvcRulesVoteRequestsResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListSvcRulesVoteRequestsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        svcRulesVoteRequests <- svcStore.listVoteRequests()
      } yield {
        definitions.ListSvcRulesVoteRequestsResponse(
          svcRulesVoteRequests.map(_.toJson).toVector
        )
      }
    }

  def listSvcRulesVotes(
      respond: SvAdminResource.ListSvcRulesVotesResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListSvcRulesVotesResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        svcRulesVotes <- svcStore.listVotes()
      } yield {
        definitions.ListSvcRulesVotesResponse(
          svcRulesVotes.map(_.toJson).toVector
        )
      }
    }

  override def getCometBftNodeStatus(
      respond: SvAdminResource.GetCometBftNodeStatusResponse.type
  )()(extracted: String): Future[
    SvAdminResource.GetCometBftNodeStatusResponse
  ] = withNewTrace(workflowId) { implicit tc => _ =>
    withClientOrNotFound(respond.NotFound) {
      _.nodeStatus()
        .fold(
          error =>
            CometBftStatusOrError(
              error = ErrorResponse(error.message).some
            ),
          status =>
            CometBftStatusOrError(
              response = CometBftNodeStatusResponse(
                status.nodeInfo.id,
                status.syncInfo.catchingUp,
                BigDecimal(status.validatorInfo.votingPower),
              ).some
            ),
        )
    }
  }
  override def getCometBftNodeDebugDump(
      respond: SvAdminResource.GetCometBftNodeDebugDumpResponse.type
  )()(extracted: String): Future[
    SvAdminResource.GetCometBftNodeDebugDumpResponse
  ] = withNewTrace(workflowId) { implicit tc => _ =>
    withClientOrNotFound(respond.NotFound) { client =>
      client
        .nodeDebugDump()
        .fold(
          err =>
            definitions.CometBftNodeDumpOrErrorResponse(
              error = ErrorResponse(err.message).some
            ),
          response =>
            definitions.CometBftNodeDumpOrErrorResponse(
              response = CometBftNodeDumpResponse(
                status = response.status,
                networkInfo = response.networkInfo,
                abciInfo = response.abciInfo,
                validators = response.validators,
              ).some
            ),
        )
    }
  }

  override def getSequencerNodeStatus(
      respond: SvAdminResource.GetSequencerNodeStatusResponse.type
  )()(extracted: String): Future[
    SvAdminResource.GetSequencerNodeStatusResponse
  ] = withNewTrace(workflowId) { implicit tc => _ =>
    withSequencerConnectionOrNotFound(respond.NotFound)(
      _.getStatus.map(CNNodeStatus.toJsonNodeStatus(_))
    )
  }

  private def withClientOrNotFound[T](
      notFound: ErrorResponse => T
  )(call: CometBftClient => Future[T]) = cometBftClient
    .fold {
      notFound(ErrorResponse("CometBFT is not configured."))
        .pure[Future]
    } { call }

  private def withSequencerConnectionOrNotFound[T](
      notFound: ErrorResponse => T
  )(call: SequencerAdminConnection => Future[T]) = sequencerAdminConnection
    .fold {
      notFound(ErrorResponse("Sequencer is not configured."))
        .pure[Future]
    } { call }
}
