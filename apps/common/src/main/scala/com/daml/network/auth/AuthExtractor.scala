package com.daml.network.auth

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.authenticateOAuth2
import akka.http.scaladsl.server.directives.Credentials
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.NoTracing

object AuthExtractor {
  def apply(
      verifier: SignatureVerifier,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  ): String => Directive1[String] = {
    new AuthExtractor(verifier, loggerFactory, realm).directiveForOperationId
  }
}

final class AuthExtractor(
    verifier: SignatureVerifier,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
) extends NamedLogging
    with NoTracing {
  def directiveForOperationId(operationId: String): Directive1[String] = {
    authenticateOAuth2(
      realm,
      credentials =>
        credentials match {
          case provided: Credentials.Provided =>
            val token = provided.identifier
            val res = (for {
              decodedToken <- verifier.verify(token)
              damlUser <- JwtClaims
                .getDamlUser(decodedToken)
                .toRight(s"No daml user found in token for operation '$operationId'")
            } yield damlUser)
            res match {
              case Right(damlUser) => {
                logger.debug(s"Decoded token with subject = $damlUser for operation '$operationId'")
                Some(damlUser)
              }
              case Left(error) => {
                logger.info(s"Could not validate token for operation '$operationId': $error")
                None
              }
            }
          case Credentials.Missing => None
        },
    )
  }
}
