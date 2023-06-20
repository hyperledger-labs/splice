package com.daml.network.sv.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.{HttpAppConnection, RetryProvider}
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext

import java.util.UUID
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class SvConnection private (
    config: NetworkAppClientConfig,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config, retryProvider, loggerFactory) {

  override val serviceName = "sv"

  /** Ask the SV to onboard a validator identified by its validator party.
    */
  def onboardValidator(validator: PartyId, secret: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(config.url, HttpSvAppClient.OnboardValidator(validator, secret))

  /** Ask the SV to start the onboarding of a new SV with an encoded (and signed) onboarding token.
    */
  def startSvOnboarding(token: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(config.url, HttpSvAppClient.StartSvOnboarding(token))

  /** Ask the sponsoring SV to authorize hosting the SVC party at the candidate participant and to prepare the ACS snapshot.
    */
  def authorizeSvcPartyHosting(
      candidateParticipantId: ParticipantId,
      candidateParty: PartyId,
  )(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpSvAppClient.OnboardSvPartyMigrationAuthorizeResponse] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.OnboardSvPartyMigrationAuthorize(
        candidateParticipantId,
        candidateParty,
      ),
    )

  def onboardSvMediator(
      mediatorId: MediatorId
  )(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.OnboardSvMediator(
        mediatorId
      ),
    )

  def onboardSvSequencer(
      sequencerId: SequencerId
  )(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpSvAppClient.SequencerSnapshot] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.OnboardSvSequencer(
        sequencerId
      ),
    )

  def getSvcInfo()(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpSvAppClient.SvcInfo] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.GetSvcInfo,
    )

  def acquireGlobalLock(reason: String, traceId: String): Future[Unit] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.AcquireGlobalLock(reason, traceId),
    )

  def releaseGlobalLock(reason: String, traceId: String): Future[Unit] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.ReleaseGlobalLock(reason, traceId),
    )

  def withGlobalLock[T](reason: String, f: () => Future[T]): Future[T] = {
    val traceId = UUID.randomUUID.toString
    logger.debug(s"Trying to perform operation under lock: $reason ($traceId)")
    retryProvider
      .retryForAutomation(
        "Acquire global lock",
        acquireGlobalLock(reason, traceId),
        logger,
      )
      .flatMap(_ =>
        f().transformWith(result =>
          retryProvider
            .retryForAutomation(
              "Release global lock",
              releaseGlobalLock(reason, traceId),
              logger,
            )
            .transform {
              _.flatMap(_ => result)
            }
        )
      )
      .andThen(_ => logger.debug(s"Completed locked operation ($traceId)"))
  }
}

object SvConnection {
  def apply(
      config: NetworkAppClientConfig,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): Future[SvConnection] =
    HttpAppConnection.checkVersionOrClose(
      new SvConnection(config, retryProvider, loggerFactory)
    )
}
