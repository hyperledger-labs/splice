package com.daml.network.sv.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.util.ByteString
import com.daml.network.config.CNHttpClientConfig.*
import com.daml.network.environment.HttpAppConnection
import com.daml.network.environment.RetryProvider
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class SvConnection(
    config: ClientConfig,
    retryProvider: RetryProvider,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config, retryProvider, timeouts, loggerFactory) {

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
  def authorizeSvcPartyHosting(candidateParticipantId: ParticipantId)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[ByteString] =
    runHttpCmd(config.url, HttpSvAppClient.OnboardSvPartyMigrationAuthorize(candidateParticipantId))
}
