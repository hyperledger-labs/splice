// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.admin.api.client

import com.daml.network.config.{NetworkAppClientConfig, UpgradesConfig}
import com.daml.network.environment.{HttpAppConnection, RetryProvider}
import com.daml.network.http.HttpClient
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.Materializer
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class SvConnection private (
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

  /** Ask the SV to start the onboarding of a new SV with an encoded (and signed) onboarding token.
    */
  def startSvOnboarding(token: String)(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(config.url, HttpSvAppClient.StartSvOnboarding(token))

  /** Ask the sponsoring SV to authorize hosting the DSO party at the candidate participant and to prepare the ACS snapshot.
    */
  def authorizeDsoPartyHosting(
      candidateParticipantId: ParticipantId,
      candidateParty: PartyId,
  )(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Either[
    HttpSvAppClient.OnboardSvPartyMigrationAuthorizeProposalNotFound,
    HttpSvAppClient.OnboardSvPartyMigrationAuthorizeResponse,
  ]] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.OnboardSvPartyMigrationAuthorize(
        candidateParticipantId,
        candidateParty,
      ),
    )

  def onboardSvSequencer(
      sequencerId: SequencerId
  )(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[ByteString] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.OnboardSvSequencer(
        sequencerId
      ),
    )

  def getDsoInfo()(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpSvAppClient.DsoInfo] =
    runHttpCmd(
      config.url,
      HttpSvAppClient.GetDsoInfo,
    )
}

object SvConnection {
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
  ): Future[SvConnection] =
    HttpAppConnection.checkVersionOrClose(
      new SvConnection(config, upgradesConfig, retryProvider, loggerFactory),
      retryConnectionOnInitialFailure,
    )
}
