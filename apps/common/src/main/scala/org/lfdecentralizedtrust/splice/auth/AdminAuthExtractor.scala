// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.util.{Failure, Success}

/** Auth extractor for APIs that perform administrative actions on the participant
  *
  * ==Authentication==
  *
  *  - request must have a valid JWT token authenticating the user
  *
  * ==Authorization==
  *
  *  - user must exist on the participant and be active
  *  - primary party must be set for the user and equal to `adminParty`
  *  - user must have actAs rights for `adminParty`
  *  - user must have ParticipantAdmin rights
  */
final class AdminAuthExtractor(
    verifier: SignatureVerifier,
    adminParty: PartyId,
    rightsProvider: UserRightsProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  def directiveForOperationId(
      operationId: String
  ): Directive1[AdminAuthExtractor.AdminUserRequest] = {
    authenticateLedgerApiUser(operationId)
      .flatMap { authenticatedUser =>
        onComplete(
          rightsProvider.getUser(authenticatedUser) zip rightsProvider.listUserRights(
            authenticatedUser
          )
        ).flatMap {
          case Success((Some(user), rights)) =>
            if (user.isDeactivated) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                "User is deactivated",
              )
            } else if (!hasPrimaryParty(user, adminParty)) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                s"Primary party of user ${user.getPrimaryParty} is not equal to the operator party ${adminParty}",
              )
            } else if (!canActAs(rights, adminParty)) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                s"User may not act as the operator party ${adminParty}",
              )
            } else if (!isParticipantAdmin(rights)) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                s"User is not a participant administrator",
              )
            } else {
              provide(AdminAuthExtractor.AdminUserRequest(traceContext))
            }
          case Success((None, _)) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, "User not found")
          case Failure(exception) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, exception.getMessage)
        }
      }
  }
}

object AdminAuthExtractor {
  final case class AdminUserRequest(traceContext: TraceContext)

  def apply(
      verifier: SignatureVerifier,
      adminParty: PartyId,
      rightsProvider: UserRightsProvider,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[AdminUserRequest] = {
    new AdminAuthExtractor(
      verifier,
      adminParty,
      rightsProvider,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}
