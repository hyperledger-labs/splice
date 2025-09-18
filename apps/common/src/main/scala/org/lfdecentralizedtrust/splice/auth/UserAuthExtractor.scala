// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext

import scala.util.Success

/** Auth extractor for APIs that are only available for authenticated users,
  * but otherwise do not require special authorization.
  *
  * Authentication: request must have a valid JWT token authenticating the user
  *
  * Authorization: user must be active
  */
final class UserAuthExtractor(
    verifier: SignatureVerifier,
    rightsProvider: UserRightsProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  def directiveForOperationId(
      operationId: String
  ): Directive1[UserAuthExtractor.UserRequest] = {
    authenticateLedgerApiUser(operationId)
      .flatMap { authenticatedUser =>
        onComplete(
          rightsProvider.getUser(authenticatedUser)
        ).flatMap {
          case Success(Some(user)) if !user.isDeactivated =>
            provide(UserAuthExtractor.UserRequest(user.getId, traceContext))
          case _ =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId)
        }
      }
  }
}

object UserAuthExtractor {
  final case class UserRequest(user: String, traceContext: TraceContext)

  def apply(
      verifier: SignatureVerifier,
      rightsProvider: UserRightsProvider,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[UserRequest] = {
    new UserAuthExtractor(
      verifier,
      rightsProvider,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}
