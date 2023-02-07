package com.daml.network.sv.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.admin.api.client.AppConnection
import com.daml.network.config.CoinHttpClientConfig
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class SvConnection(
    config: CoinHttpClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config.clientConfig, timeouts, loggerFactory) {

  override val serviceName = "sv"

  /** Ask the SV to onboard a validator identified by its validator party.
    */
  // TODO(#2657) use secret
  def onboardValidator(validator: PartyId)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.OnboardValidator(validator, List()),
    )
}
