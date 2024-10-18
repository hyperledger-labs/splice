// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{complete, onComplete, provide}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.auth.{AuthExtractor, SignatureVerifier}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Printer
import io.circe.syntax.EncoderOps

import scala.util.Success

final case class Response(user: String)

object AdminAuthExtractor {
  def apply(
      verifier: SignatureVerifier,
      adminParty: PartyId,
      ledgerConnection: SpliceLedgerConnection,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[AuthExtractor.TracedUser] = {
    new AdminAuthExtractor(
      verifier,
      adminParty,
      ledgerConnection,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}

// Auth extractor for admin APIs that only
// allow access to users that have actAs rights for a specific admin party, e.g.,
// the sv party or the validator operator party.
final class AdminAuthExtractor(
    verifier: SignatureVerifier,
    adminParty: PartyId,
    ledgerConnection: SpliceLedgerConnection,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  override def directiveForOperationId(
      operationId: String
  ): Directive1[AuthExtractor.TracedUser] = {
    super
      .directiveForOperationId(operationId)
      .flatMap { tuser =>
        val AuthExtractor.TracedUser(user, traceContext) = tuser
        implicit val tc = traceContext
        onComplete(
          ledgerConnection.getOptionalPrimaryParty(user) zip ledgerConnection.getUserActAs(user)
        ).flatMap {
          case Success((Some(party), actAs)) if party == adminParty && actAs.contains(adminParty) =>
            provide(tuser)
          case _ =>
            logger.warn(s"Authorization Failed for $adminParty")
            val contentType = MediaTypes.`application/json`
            val errorResponse =
              ErrorResponse(
                s"Authorization Failed for $adminParty"
              )
            val responseEntity = HttpEntity(
              contentType = contentType,
              ByteString(
                Printer.noSpaces
                  .printToByteBuffer(errorResponse.asJson, contentType.charset.nioCharset())
              ),
            )
            complete(
              HttpResponse(
                StatusCodes.Forbidden,
                entity = responseEntity,
              )
            )
        }
      }
  }

}
