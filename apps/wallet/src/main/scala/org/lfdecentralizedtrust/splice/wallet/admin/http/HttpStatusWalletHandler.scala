// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.admin.http

import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport
import org.lfdecentralizedtrust.splice.http.v0.status.wallet.WalletResource as r0
import org.lfdecentralizedtrust.splice.http.v0.{status, definitions as d0}
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.auth.AuthenticationOnlyAuthExtractor.AuthenticatedRequest

import scala.concurrent.{ExecutionContext, Future}

/** Handler for endpoints that require user authentication,
  * but do not require the user to have a wallet installed.
  */
class HttpStatusWalletHandler(
    override protected val walletManager: UserWalletManager,
    protected val loggerFactory: NamedLoggerFactory,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends status.wallet.WalletHandler[AuthenticatedRequest]
    with HttpWalletHandlerUtil {
  protected val workflowId = this.getClass.getSimpleName

  override def userStatus(respond: r0.UserStatusResponse.type)()(
      tuser: AuthenticatedRequest
  ): Future[r0.UserStatusResponse] = {
    implicit val AuthenticatedRequest(user, traceContext) = tuser
    withSpan(s"$workflowId.userStatus") { implicit traceContext => _ =>
      for {
        optWallet <- walletManager.lookupUserWallet(user)
        hasFeaturedAppRight <- optWallet match {
          case None => Future(false)
          case Some(wallet) =>
            wallet.store.lookupFeaturedAppRight().map(_.isDefined)
        }
        optInstall <- optWallet match {
          case None =>
            Future(None)
          case Some(w) => w.store.lookupInstall()
        }
      } yield {
        d0.UserStatusResponse(
          partyId = optWallet.fold("")(_.store.key.endUserParty.toProtoPrimitive),
          userOnboarded = optWallet.isDefined,
          userWalletInstalled = optInstall.isDefined,
          hasFeaturedAppRight = hasFeaturedAppRight,
        )
      }
    }
  }

  override def featureSupport(respond: r0.FeatureSupportResponse.type)()(
      tuser: AuthenticatedRequest
  ): Future[r0.FeatureSupportResponse] = {
    implicit val AuthenticatedRequest(user, traceContext) = tuser
    withSpan(s"$workflowId.featureSupport") { _ => _ =>
      val parties = Seq(store.walletKey.dsoParty)
      val now = CantonTimestamp.now()
      for {
        tokenStandard <- packageVersionSupport.supportsTokenStandard(parties, now)
        preapprovalDescription <- packageVersionSupport.supportsDescriptionInTransferPreapprovals(
          parties,
          now,
        )
      } yield r0.FeatureSupportResponse.OK(
        d0.WalletFeatureSupportResponse(
          tokenStandard = tokenStandard.supported,
          transferPreapprovalDescription = preapprovalDescription.supported,
        )
      )
    }
  }

}
