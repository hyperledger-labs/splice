package com.daml.network.validator

import cats.data.EitherT
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.{HttpAppConnection, RetryProvider}
import com.daml.network.http.v0.{definitions, sv as http}
import com.daml.network.sv.http.SvHttpClient.BaseCommand
import com.daml.network.util.TemplateJsonDecoder
import com.daml.network.validator.ValidatorSvConnection.OnboardValidator
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class ValidatorSvConnection private (
    config: NetworkAppClientConfig,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config, "sv", retryProvider, loggerFactory) {

  /** Ask the SV to onboard a validator identified by its validator party.
    */
  def onboardValidator(validator: PartyId, secret: String)(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(config.url, OnboardValidator(validator, secret))
}

object ValidatorSvConnection {
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
  ): Future[ValidatorSvConnection] =
    HttpAppConnection.checkVersionOrClose(
      new ValidatorSvConnection(config, retryProvider, loggerFactory)
    )

  case class OnboardValidator(candidate: PartyId, secret: String)
      extends BaseCommand[http.OnboardValidatorResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardValidatorResponse] =
      client.onboardValidator(
        body = definitions.OnboardValidatorRequest(candidate.toProtoPrimitive, secret),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardValidatorResponse.OK =>
      Right(())
    }
  }
}
