// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.util.{Failure, Success}

/** Auth extractor for APIs that are only available for authenticated users
  * that represent a known, active user on the participant.
  *
  * ==Authentication==
  *
  *  - request must have a valid JWT token authenticating the user
  *
  * ==Authorization==
  *
  *  - user must exist on the participant and be active
  *  - primary party must be set for the user and equal to the app operator party
  *  - user must have actAs rights for the app operator party
  */
final class ActAsKnownPartyAuthExtractor(
    verifier: SignatureVerifier,
    rightsProvider: UserRightsProvider,
    party: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  def directiveForOperationId(
      operationId: String
  ): Directive1[ActAsKnownPartyAuthExtractor.ActAsKnownUserRequest] = {
    authenticateLedgerApiUser(operationId)
      .flatMap { authenticatedUser =>
        onComplete(
          rightsProvider.getUserWithRights(authenticatedUser)
        ).flatMap {
          case Success(Some((user, rights))) =>
            if (user.isDeactivated) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                "User is deactivated",
              )
            } else if (!hasPrimaryParty(user, party)) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                s"Primary party of user ${user.getPrimaryParty} is not equal to the party ${party}",
              )
            } else if (!canActAs(rights, party)) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                s"User may not act as the party ${party}",
              )
            } else {
              provide(ActAsKnownPartyAuthExtractor.ActAsKnownUserRequest(traceContext))
            }
          case Success(None) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, "User not found")
          case Failure(exception) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, exception.getMessage)
        }
      }
  }
}

object ActAsKnownPartyAuthExtractor {
  final case class ActAsKnownUserRequest(traceContext: TraceContext)

  def apply(
      verifier: SignatureVerifier,
      rightsProvider: UserRightsProvider,
      party: PartyId,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[ActAsKnownUserRequest] = {
    new ActAsKnownPartyAuthExtractor(
      verifier,
      rightsProvider,
      party,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}
