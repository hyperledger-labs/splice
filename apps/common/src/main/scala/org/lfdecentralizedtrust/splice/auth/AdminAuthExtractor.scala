// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.daml.ledger.javaapi.data.User
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.util.Success

/** Auth extractor for APIs that perform administrative actions on the participant
  *
  * Authentication: request must have a valid JWT token authenticating the user
  *
  * Authorization: user must be active, have actAs rights for the validator/sv app operator party,
  *                and have ParticipantAdmin rights
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

  private def isAuthorizedAsAdmin(
      user: User,
      rights: Set[User.Right],
  ): Boolean = {
    !user.isDeactivated &&
    hasPrimaryParty(user, adminParty) &&
    canActAs(rights, adminParty) &&
    isParticipantAdmin(rights)
  }

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
          case Success((Some(user), rights)) if isAuthorizedAsAdmin(user, rights) =>
            provide(AdminAuthExtractor.AdminUserRequest(traceContext))
          case _ =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId)
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
