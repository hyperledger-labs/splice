// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.config

import org.apache.pekko.actor.ActorSystem
import com.daml.network.auth.{AuthTokenSource, AuthToken}
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** @param clientConfig Connection parameters
  * @param authConfig Auth tokens used by the app
  */
case class LedgerApiClientConfig(
    clientConfig: ClientConfig,
    authConfig: AuthTokenSourceConfig,
) {
  // Note: Some places, e.g., Cantons RemoteParticipantConfig expects a static token,
  // LedgerApiClientConfig contains information for how to acquire tokens.
  // We need to perform some blocking IO to generate the token here.
  def getToken()(implicit actorSystem: ActorSystem): Option[AuthToken] = {
    implicit val executionContext = actorSystem.dispatcher
    implicit val traceContext = TraceContext.empty
    val loggerFactory = NamedLoggerFactory.root
    val authTokenSource = AuthTokenSource.fromConfig(
      authConfig,
      loggerFactory,
    )
    Await.result(authTokenSource.getToken, 30.seconds)
  }
}
