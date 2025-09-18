// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

/** Auth extractor for APIs that are only available for authenticated users
  * that represent an active user on the participant.
  *
  * Authentication: request must have a valid JWT token authenticating the user
  *
  * Authorization: user must exist on the participant and be active
  */
final class ActAsPrimaryPartyAuthExtractor(
    verifier: SignatureVerifier,
    rightsProvider: UserRightsProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  def directiveForOperationId(
      operationId: String
  ): Directive1[ActAsPrimaryPartyAuthExtractor.ActAsUserRequest] = {
    authenticateLedgerApiUser(operationId)
      .flatMap { authenticatedUser =>
        onComplete(
          rightsProvider.getUser(authenticatedUser) zip rightsProvider.listUserRights(
            authenticatedUser
          )
        ).flatMap {
          case Success((Some(user), rights)) =>
            if (user.isDeactivated) {
              rejectWithAuthorizationFailure(authenticatedUser, operationId, "User is deactivated")
            } else {
              val primaryPartyO = user.getPrimaryParty.toScala.map(PartyId.tryFromProtoPrimitive)
              primaryPartyO match {
                case None =>
                  rejectWithAuthorizationFailure(
                    authenticatedUser,
                    operationId,
                    s"User has no primary party",
                  )
                case Some(primaryParty) =>
                  if (canActAs(rights, primaryParty)) {
                    provide(
                      ActAsPrimaryPartyAuthExtractor.ActAsUserRequest(
                        user.getId,
                        primaryParty,
                        traceContext,
                      )
                    )
                  } else {
                    rejectWithAuthorizationFailure(
                      authenticatedUser,
                      operationId,
                      s"User may not act as $primaryParty",
                    )
                  }
              }
            }
          case Success((None, _)) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, "User not found")
          case Failure(exception) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, exception.getMessage)
        }
      }
  }
}

object ActAsPrimaryPartyAuthExtractor {
  final case class ActAsUserRequest(user: String, primaryParty: PartyId, traceContext: TraceContext)

  def apply(
      verifier: SignatureVerifier,
      rightsProvider: UserRightsProvider,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[ActAsUserRequest] = {
    new ActAsPrimaryPartyAuthExtractor(
      verifier,
      rightsProvider,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}
