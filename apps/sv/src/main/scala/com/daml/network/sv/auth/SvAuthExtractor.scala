package com.daml.network.sv.auth

import akka.http.scaladsl.model.{HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{complete, onComplete, provide}
import akka.util.ByteString
import com.daml.network.auth.{AuthExtractor, SignatureVerifier}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.http.v0.definitions.ErrorResponse
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.circe.Printer
import io.circe.syntax.EncoderOps

import scala.util.Success

final case class Response(user: String)

object SvAuthExtractor {
  def apply(
      verifier: SignatureVerifier,
      svParty: PartyId,
      ledgerConnection: CNLedgerConnection,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  ): String => Directive1[String] = {
    new SvAuthExtractor(
      verifier,
      svParty,
      ledgerConnection,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}

final class SvAuthExtractor(
    verifier: SignatureVerifier,
    svParty: PartyId,
    ledgerConnection: CNLedgerConnection,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
) extends AuthExtractor(verifier, loggerFactory, realm) {

  override def directiveForOperationId(operationId: String): Directive1[String] = {
    super
      .directiveForOperationId(operationId)
      .flatMap(user =>
        onComplete(
          ledgerConnection.getOptionalPrimaryParty(user) zip ledgerConnection.getUserActAs(user)
        ).flatMap {
          case Success((Some(party), actAs)) if party == svParty && actAs.contains(svParty) =>
            provide(user)
          case _ =>
            logger.warn(s"Authorization Failed for $svParty")
            val contentType = MediaTypes.`application/json`
            val errorResponse =
              ErrorResponse(
                s"Authorization Failed for $svParty"
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
      )
  }

}
