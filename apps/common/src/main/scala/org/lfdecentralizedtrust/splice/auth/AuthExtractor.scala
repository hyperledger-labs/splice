// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.daml.ledger.javaapi.data.User
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{Directive1, StandardRoute}
import org.apache.pekko.http.scaladsl.server.Directives.{authenticateOAuth2, complete}
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Printer
import io.circe.syntax.EncoderOps
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.v0.definitions.ErrorResponse

import java.util.Optional

abstract class AuthExtractor(
    verifier: SignatureVerifier,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends NamedLogging {

  protected final def authenticateLedgerApiUser(operationId: String): Directive1[String] = {
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
    )
  }

  protected final def rejectWithAuthorizationFailure(
      authenticatedUser: String,
      operationId: String,
      reason: String,
  ): StandardRoute = {
    // Reason is logged at WARN level, but not returned to the client, to avoid leaking information
    logger.warn(
      s"Authorization Failed for $authenticatedUser for operation '$operationId'. Reason: $reason"
    )

    // Note: we do not use `reject(AuthorizationFailedRejection)` because that one does not include the
    // CORS headers that we add when we construct the routes in the app.
    val contentType = MediaTypes.`application/json`
    val errorResponse =
      ErrorResponse(
        s"Authorization Failed for $authenticatedUser"
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

  protected final def hasPrimaryParty(
      user: User,
      party: PartyId,
  ): Boolean = {
    val partyAsString = party.toProtoPrimitive
    user.getPrimaryParty.equals(Optional.of(partyAsString))
  }

  protected final def canActAs(rights: Set[User.Right], party: PartyId): Boolean = {
    val partyAsString = party.toProtoPrimitive
    rights.exists {
      case actAs: User.Right.CanActAs =>
        actAs.party == partyAsString
      case _ => false
    }
  }

  protected final def canReadAs(rights: Set[User.Right], party: PartyId): Boolean = {
    val partyAsString = party.toProtoPrimitive
    rights.exists {
      // ActAs rights imply ReadAs rights
      case actAs: User.Right.CanActAs =>
        actAs.party == partyAsString
      case actAs: User.Right.CanReadAs =>
        actAs.party == partyAsString
      case _ => false
    }
  }

  protected final def isParticipantAdmin(rights: Set[User.Right]): Boolean = {
    rights.exists {
      case _: User.Right.ParticipantAdmin =>
        true
      case _ => false
    }
  }
}
