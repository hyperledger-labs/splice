// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.util.Success

final case class Response(user: String)

/** **Authentication**: request needs to have a valid JWT token authenticating the user
  *
  * **Authorization**: user must be active, have actAs rights for the given admin party,
  *                    and have ParticipantAdmin rights
  */
object UserWalletAuthExtractor {
  final case class AdminUserRequest(traceContext: TraceContext)

  def apply(
      verifier: SignatureVerifier,
      adminParty: PartyId,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[AdminAuthExtractor.User] = {
    new AdminAuthExtractor(
      verifier,
      adminParty,
      rightsProvider,
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
    rightsProvider: UserRightsProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  private def isAuthorizedAsAdmin(
      user: UserRightsProvider.User,
      rights: Seq[UserRightsProvider.UserRight],
  ): Boolean = {
    !user.isDeactivated &&
    user.primaryParty.contains(adminParty) &&
    rights.exists { case UserRightsProvider.UserRight.CanActAs(`adminParty`) =>
      true
    } &&
    rights.contains(UserRightsProvider.UserRight.ParticipantAdmin)
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
