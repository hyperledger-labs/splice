// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.provide
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext

/** Auth extractor for APIs that are only available for authenticated users,
  * but otherwise do not require special authorization.
  *
  * Useful for endpoints that serve "public" data, but where we still want to
  * know the identity of the caller (e.g. for rate limiting or auditing purposes).
  *
  * ==Authentication==
  *
  *  - request must have a valid JWT token authenticating the user
  *
  * ==Authorization==
  *
  *  - none, doesn't even check if the user exists on the participant
  */
final class AuthenticationOnlyAuthExtractor(
    verifier: SignatureVerifier,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  def directiveForOperationId(
      operationId: String
  ): Directive1[AuthenticationOnlyAuthExtractor.AuthenticatedRequest] = {
    authenticateLedgerApiUser(operationId)
      .flatMap { authenticatedUser =>
        provide(
          AuthenticationOnlyAuthExtractor.AuthenticatedRequest(authenticatedUser, traceContext)
        )
      }
  }
}

object AuthenticationOnlyAuthExtractor {
  final case class AuthenticatedRequest(user: String, traceContext: TraceContext)

  def apply(
      verifier: SignatureVerifier,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[AuthenticatedRequest] = {
    new AuthenticationOnlyAuthExtractor(
      verifier,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}
