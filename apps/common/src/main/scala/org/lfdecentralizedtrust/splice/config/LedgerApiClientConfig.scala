// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.FullClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.auth.{AuthToken, AuthTokenSource}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** @param clientConfig Connection parameters
  * @param authConfig Auth tokens used by the app
  */
case class LedgerApiClientConfig(
    clientConfig: FullClientConfig,
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
