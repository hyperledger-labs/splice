// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator

import cats.data.EitherT
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{BuildInfo, HttpAppConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv as http}
import org.lfdecentralizedtrust.splice.sv.http.SvHttpClient.BaseCommand
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.validator.ValidatorSvConnection.OnboardValidator
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class ValidatorSvConnection private (
    config: NetworkAppClientConfig,
    upgradesConfig: UpgradesConfig,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config, upgradesConfig, "sv", retryProvider, loggerFactory) {

  /** Ask the SV to onboard a validator identified by its validator party.
    */
  def onboardValidator(validator: PartyId, secret: String, contactPoint: String)(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(config.url, OnboardValidator(validator, secret, contactPoint))
}

object ValidatorSvConnection {
  def apply(
      config: NetworkAppClientConfig,
      upgradesConfig: UpgradesConfig,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      retryConnectionOnInitialFailure: Boolean = true,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[ValidatorSvConnection] =
    HttpAppConnection.checkVersionOrClose(
      new ValidatorSvConnection(config, upgradesConfig, retryProvider, loggerFactory),
      retryConnectionOnInitialFailure,
    )

  case class OnboardValidator(candidate: PartyId, secret: String, contactPoint: String)
      extends BaseCommand[http.OnboardValidatorResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardValidatorResponse] =
      client.onboardValidator(
        body = definitions.OnboardValidatorRequest(
          candidate.toProtoPrimitive,
          secret,
          Some(BuildInfo.compiledVersion),
          Some(contactPoint),
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.OnboardValidatorResponse.OK =>
      Right(())
    }
  }
}
