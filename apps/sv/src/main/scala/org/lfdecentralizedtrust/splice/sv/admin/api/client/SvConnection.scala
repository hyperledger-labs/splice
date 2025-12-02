// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{HttpAppConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvPublicAppClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

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
    runHttpCmd(config.url, HttpSvPublicAppClient.StartSvOnboarding(token))

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
    HttpSvPublicAppClient.OnboardSvPartyMigrationAuthorizeProposalNotFound,
    HttpSvPublicAppClient.OnboardSvPartyMigrationAuthorizeResponse,
  ]] =
    runHttpCmd(
      config.url,
      HttpSvPublicAppClient.OnboardSvPartyMigrationAuthorize(
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
      HttpSvPublicAppClient.OnboardSvSequencer(
        sequencerId
      ),
    )

  def getDsoInfo()(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpSvPublicAppClient.DsoInfo] =
    runHttpCmd(
      config.url,
      HttpSvPublicAppClient.GetDsoInfo,
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
