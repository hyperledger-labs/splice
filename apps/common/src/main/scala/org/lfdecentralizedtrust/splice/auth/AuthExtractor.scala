// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.authenticateOAuth2
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

object AuthExtractor {
  final case class TracedUser(user: String, traceContext: TraceContext)
  def apply(
      verifier: SignatureVerifier,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[TracedUser] = {
    new AuthExtractor(verifier, loggerFactory, realm).directiveForOperationId
  }
}

class AuthExtractor(
    verifier: SignatureVerifier,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends NamedLogging {
  def directiveForOperationId(operationId: String): Directive1[AuthExtractor.TracedUser] = {
    authenticateOAuth2(
      realm,
      credentials =>
        credentials match {
          case provided: Credentials.Provided =>
            val token = provided.identifier
            val res = (for {
              decodedToken <- verifier.verify(token)
              ledgerApiUser <- JwtClaims
                .getLedgerApiUser(decodedToken)
                .toRight(s"No daml user found in token for operation '$operationId'")
            } yield ledgerApiUser)
            res match {
              case Right(ledgerApiUser) => {
                logger.debug(
                  s"Decoded token with subject = $ledgerApiUser for operation '$operationId'"
                )
                Some(ledgerApiUser)
              }
              case Left(error) => {
                logger.info(s"Could not validate token for operation '$operationId': $error")
                None
              }
            }
          case Credentials.Missing => None
        },
    ).map(user => AuthExtractor.TracedUser(user, traceContext))
  }
}
