// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import cats.syntax.applicative.*
import cats.syntax.either.*
import com.digitalasset.canton.config.{
  NonNegativeDuration,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ErrorUtil
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice as spliceCodegen
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.auth.ActAsKnownPartyAuthExtractor.ActAsKnownUserRequest
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv_operator as v0}
import org.lfdecentralizedtrust.splice.http.v0.sv_operator.SvOperatorResource as r0
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.{
  HttpClient,
  HttpFeatureSupportHandler,
  HttpValidatorLicensesHandler,
  HttpVotesHandler,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.{ActiveVotesStore, AppStore, AppStoreWithIngestion}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftClient
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil.generateRandomOnboardingSecret
import org.lfdecentralizedtrust.splice.sv.util.Secrets
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SvApp}
import org.lfdecentralizedtrust.splice.util.{Codec, Contract, TemplateJsonDecoder}

import java.util.Optional
import scala.concurrent.{ExecutionContextExecutor, Future, blocking}

class HttpSvOperatorHandler(
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    config: SvAppBackendConfig,
    clock: Clock,
    localSynchronizerNode: Option[LocalSynchronizerNode],
    retryProvider: RetryProvider,
    cometBftClient: Option[CometBftClient],
    override protected val packageVersionSupport: PackageVersionSupport,
    override protected val timeouts: ProcessingTimeout,
    protected val loggerFactory: NamedLoggerFactory,
    upgradesConfig: UpgradesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    protected val tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    mat: Materializer,
    httpClient: HttpClient,
) extends v0.SvOperatorHandler[ActAsKnownUserRequest]
    with FlagCloseableAsync
    with Spanning
    with NamedLogging
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
  private def createScanConnection(): Future[ScanConnection] = {
    implicit val tc: TraceContext = TraceContext.empty
    ScanConnection
      .singleUncached(
        ScanAppClientConfig(NetworkAppClientConfig(config.scan.internalUrl)),
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

  override def listDsoRulesVoteRequests(
      respond: r0.ListDsoRulesVoteRequestsResponse.type
  )()(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListDsoRulesVoteRequestsResponse] = {
    this
      .listDsoRulesVoteRequests(extracted.traceContext, ec)
      .map(r0.ListDsoRulesVoteRequestsResponse.OK)
  }

  override def listVoteRequestResults(
      respond: r0.ListVoteRequestResultsResponse.type
  )(
      body: definitions.ListVoteResultsRequest
  )(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListVoteRequestResultsResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.listVoteRequestResults") { _ => _ =>
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
        r0.ListVoteRequestResultsResponse.OK(
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

  override def listValidatorLicenses(
      respond: r0.ListValidatorLicensesResponse.type
  )(after: Option[Long], limit: Option[Int])(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListValidatorLicensesResponse] = {
    this
      .listValidatorLicenses(after, limit)(extracted.traceContext, ec)
      .map(r0.ListValidatorLicensesResponse.OK)
  }

  def listOngoingValidatorOnboardings(
      respond: r0.ListOngoingValidatorOnboardingsResponse.type
  )()(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListOngoingValidatorOnboardingsResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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

  override def prepareValidatorOnboarding(
      respond: r0.PrepareValidatorOnboardingResponse.type
  )(
      body: definitions.PrepareValidatorOnboardingRequest
  )(
      extracted: ActAsKnownUserRequest
  ): Future[r0.PrepareValidatorOnboardingResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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

  override def listAmuletPriceVotes(
      respond: r0.ListAmuletPriceVotesResponse.type
  )()(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListAmuletPriceVotesResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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

  override def listOpenMiningRounds(respond: r0.ListOpenMiningRoundsResponse.type)()(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListOpenMiningRoundsResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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

  override def updateAmuletPriceVote(
      respond: r0.UpdateAmuletPriceVoteResponse.type
  )(
      body: definitions.UpdateAmuletPriceVoteRequest
  )(
      extracted: ActAsKnownUserRequest
  ): Future[r0.UpdateAmuletPriceVoteResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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
                Future.successful(r0.UpdateAmuletPriceVoteResponseOK)
            }
        },
        logger,
      )
    }
  }

  def isAuthorized(
      respond: r0.IsAuthorizedResponse.type
  )(
  )(extracted: ActAsKnownUserRequest): Future[r0.IsAuthorizedResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.isAuthorized") { _ => _ =>
      Future.successful(r0.IsAuthorizedResponseOK)
    }
  }

  override def createVoteRequest(respond: r0.CreateVoteRequestResponse.type)(
      body: definitions.CreateVoteRequest
  )(extracted: ActAsKnownUserRequest): Future[r0.CreateVoteRequestResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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
          case Right(_) => Future.successful(r0.CreateVoteRequestResponseOK)
        }
    }
  }

  override def lookupDsoRulesVoteRequest(
      respond: r0.LookupDsoRulesVoteRequestResponse.type
  )(
      voteRequestContractId: String
  )(extracted: ActAsKnownUserRequest): Future[r0.LookupDsoRulesVoteRequestResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    this
      .lookupDsoRulesVoteRequest(voteRequestContractId)
      .map(r0.LookupDsoRulesVoteRequestResponse.OK)
  }

  override def castVote(respond: r0.CastVoteResponse.type)(
      body: definitions.CastVoteRequest
  )(extracted: ActAsKnownUserRequest): Future[r0.CastVoteResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.castVote") { _ => _ =>
      SvApp
        .castVote(
          new spliceCodegen.dsorules.VoteRequest.ContractId(body.voteRequestContractId),
          body.isAccepted,
          body.reasonUrl,
          body.reasonDescription,
          dsoStoreWithIngestion,
          retryProvider,
          logger,
        )
        .flatMap {
          case Left(cause) => Future.failed(HttpErrorHandler.badRequest(cause))
          case Right(_) => Future.successful(r0.CastVoteResponseCreated)
        }
    }
  }

  override def listVoteRequestsByTrackingCid(
      respond: r0.ListVoteRequestsByTrackingCidResponse.type
  )(
      body: definitions.BatchListVotesByVoteRequestsRequest
  )(
      extracted: ActAsKnownUserRequest
  ): Future[r0.ListVoteRequestsByTrackingCidResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    this
      .listVoteRequestsByTrackingCid(body)
      .map(r0.ListVoteRequestsByTrackingCidResponse.OK)
  }

  override def getSequencerNodeStatus(
      respond: r0.GetSequencerNodeStatusResponse.type
  )()(extracted: ActAsKnownUserRequest): Future[
    r0.GetSequencerNodeStatusResponse
  ] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.getSequencerNodeStatus") { _ => _ =>
      withSequencerConnectionOrNotFound(respond.NotFound)(
        _.getStatus.map(SpliceStatus.toHttpNodeStatus(_))
      )
    }
  }

  override def getMediatorNodeStatus(
      respond: r0.GetMediatorNodeStatusResponse.type
  )()(extracted: ActAsKnownUserRequest): Future[
    r0.GetMediatorNodeStatusResponse
  ] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.getMediatorNodeStatus") { _ => _ =>
      withMediatorConnectionOrNotFound(respond.NotFound)(
        _.getStatus.map(SpliceStatus.toHttpNodeStatus(_))
      )
    }
  }

  override def getPartyToParticipant(
      respond: r0.GetPartyToParticipantResponse.type
  )(partyId: String)(
      extracted: ActAsKnownUserRequest
  ): Future[r0.GetPartyToParticipantResponse] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.getPartyToParticipant") { _ => _ =>
      withSequencerConnectionOrNotFound(respond.NotFound) { sequencerConnection =>
        for {
          party <- PartyId.fromProtoPrimitive(partyId, "partyId") match {
            case Right(party) => Future.successful(party)
            case Left(error) =>
              Future.failed(
                HttpErrorHandler.badRequest(s"Could not decode party ID: $error")
              )
          }
          dsoRules <- dsoStore.getDsoRules()
          partyToParticipant <- sequencerConnection
            .getPartyToParticipant(
              dsoRules.domain,
              party,
            )
          _ <- {
            if (partyToParticipant.mapping.partyId == party) {
              Future.unit
            } else {
              Future.failed(
                HttpErrorHandler.notFound(s"Party not found: $partyId")
              )
            }
          }
          participantId <- partyToParticipant.mapping.participants match {
            case Seq(participant) => Future.successful(participant.participantId.toProtoPrimitive)
            case Seq() =>
              Future.failed(
                HttpErrorHandler.notFound(s"No participant id found hosting party: $partyId")
              )
            case _ =>
              Future.failed(
                HttpErrorHandler.internalServerError(
                  s"Party $partyId is hosted on multiple participants, which is not currently supported"
                )
              )
          }
        } yield {
          r0.GetPartyToParticipantResponse.OK(
            definitions.GetPartyToParticipantResponse(participantId)
          )
        }
      }.recoverWith {
        case e: StatusRuntimeException if e.getStatus.getCode == Status.NOT_FOUND.getCode =>
          Future.successful(
            respond.NotFound(definitions.ErrorResponse(s"Party not found: $partyId"))
          )
      }
    }
  }

  override def featureSupport(respond: r0.FeatureSupportResponse.type)()(
      extracted: ActAsKnownUserRequest
  ): Future[r0.FeatureSupportResponse] = {
    readFeatureSupport(dsoStore.key.dsoParty)(
      ec,
      extracted.traceContext,
      tracer,
    )
      .map(r0.FeatureSupportResponseOK(_))
  }

  override def getCometBftNodeDebugDump(
      respond: r0.GetCometBftNodeDebugDumpResponse.type
  )()(extracted: ActAsKnownUserRequest): Future[
    r0.GetCometBftNodeDebugDumpResponse
  ] = {
    implicit val ActAsKnownUserRequest(traceContext) = extracted
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

  private def withClientOrNotFound[T](
      notFound: definitions.ErrorResponse => T
  )(call: CometBftClient => Future[T]) = cometBftClient
    .fold {
      notFound(definitions.ErrorResponse("CometBFT is not configured."))
        .pure[Future]
    } {
      call
    }

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
