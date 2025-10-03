// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.admin.http

import cats.implicits.showInterpolator
import org.apache.pekko.http.scaladsl.server.{Directive1, StandardRoute}
import org.apache.pekko.http.scaladsl.server.Directives.{onComplete, provide}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.ShowStringSyntax
import io.grpc.Status
import org.lfdecentralizedtrust.splice.auth.{AuthExtractor, SignatureVerifier, UserRightsProvider}
import org.lfdecentralizedtrust.splice.wallet.{UserWalletManager, UserWalletService}

import scala.util.{Failure, Success}

/** Auth extractor for APIs that perform wallet operations on behalf of the user
  *
  * Authentication: request must have a valid JWT token authenticating the user
  *
  * Authorization: user must be active, and have actAs rights for the party associated with their wallet
  *                (this association is stored in WalletAppInstall contracts)
  */
final class UserWalletAuthExtractor(
    verifier: SignatureVerifier,
    walletManager: UserWalletManager,
    rightsProvider: UserRightsProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    realm: String,
)(implicit
    traceContext: TraceContext
) extends AuthExtractor(verifier, loggerFactory, realm)(traceContext) {

  def directiveForOperationId(
      operationId: String
  ): Directive1[UserWalletAuthExtractor.WalletUserRequest] = {
    authenticateLedgerApiUser(operationId)
      .flatMap { authenticatedUser =>
        onComplete(
          // Double zip is a bit ugly...
          rightsProvider.getUser(authenticatedUser) zip rightsProvider.listUserRights(
            authenticatedUser
          ) zip walletManager.lookupUserWallet(authenticatedUser)
        ).flatMap {
          case Success((_, None)) =>
            rejectWithWalletNotFoundFailure(authenticatedUser, operationId)
          case Success(((None, _), _)) =>
            rejectWithAuthorizationFailure(
              authenticatedUser,
              operationId,
              "User not found",
            )
          case Success(((Some(user), rights), Some(wallet))) =>
            val walletParty = wallet.store.key.endUserParty
            if (user.isDeactivated) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                "User is deactivated",
              )
            } else if (!canActAs(rights, walletParty)) {
              rejectWithAuthorizationFailure(
                authenticatedUser,
                operationId,
                s"User may not act as wallet party ${walletParty}",
              )
            } else {
              provide(
                UserWalletAuthExtractor.WalletUserRequest(
                  authenticatedUser,
                  wallet,
                  traceContext,
                )
              )
            }
          case Failure(exception) =>
            rejectWithAuthorizationFailure(authenticatedUser, operationId, exception.getMessage)
        }
      }
  }

  private def rejectWithWalletNotFoundFailure(
      authenticatedUser: String,
      operationId: String,
  ): StandardRoute = {
    // This is the old behavior in HttpWalletHandler, consider directly returning a 404 instead.
    // At this point, the user is authenticated. It's fine to reveal to them that they haven't onboarded yet,
    // especially since there is an endpoint for checking their onboarding status.
    throw Status.NOT_FOUND
      .withDescription(
        show"No wallet found for user ${authenticatedUser.singleQuoted} for operation '$operationId'"
      )
      .asRuntimeException()
  }
}

object UserWalletAuthExtractor {

  /** @param user         The authenticated user name.
    * @param userWallet   The wallet associated with the authenticated user.
    *                     We need to look up the wallet in the extractor to get the end user party,
    *                     which is needed for authorization checks. And since we already have it,
    *                     we might as well pass it to the handler to avoid looking it up again.
    * @param traceContext The trace context of the request.
    */
  final case class WalletUserRequest(
      user: String,
      userWallet: UserWalletService,
      traceContext: TraceContext,
  )

  def apply(
      verifier: SignatureVerifier,
      walletManager: UserWalletManager,
      rightsProvider: UserRightsProvider,
      loggerFactory: NamedLoggerFactory,
      realm: String,
  )(implicit traceContext: TraceContext): String => Directive1[WalletUserRequest] = {
    new UserWalletAuthExtractor(
      verifier,
      walletManager,
      rightsProvider,
      loggerFactory,
      realm,
    ).directiveForOperationId
  }
}
