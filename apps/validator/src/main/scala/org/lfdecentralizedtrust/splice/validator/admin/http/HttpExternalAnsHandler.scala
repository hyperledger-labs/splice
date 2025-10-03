// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.http.v0.external.ans.AnsResource as r0
import org.lfdecentralizedtrust.splice.http.v0.{external, definitions as d0}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.admin.http.HttpWalletHandlerUtil
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.wallet.admin.http.UserWalletAuthExtractor.WalletUserRequest

import scala.concurrent.{ExecutionContext, Future}

class HttpExternalAnsHandler(
    override val walletManager: UserWalletManager,
    scanConnection: BftScanConnection,
    override val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends external.ans.AnsHandler[WalletUserRequest]
    with HttpWalletHandlerUtil {

  protected val workflowId = this.getClass.getSimpleName

  override def createAnsEntry(
      respond: r0.CreateAnsEntryResponse.type
  )(
      body: d0.CreateAnsEntryRequest
  )(tuser: WalletUserRequest): Future[r0.CreateAnsEntryResponse] = {
    implicit val WalletUserRequest(user, endUserWallet, traceContext) = tuser
    val partyId = endUserWallet.store.key.endUserParty
    withSpan(s"$workflowId.createAnsEntry") { implicit traceContext => _ =>
      retryProvider.retryForClientCalls(
        "createAnsEntry",
        "create ANS entry",
        for {
          ansRules <- scanConnection.getAnsRules()
          ansRulesCt = ansRules.toAssignedContract.getOrElse(
            throw Status.Code.FAILED_PRECONDITION.toStatus
              .withDescription(
                s"AnsRules contract is not assigned to a domain."
              )
              .asRuntimeException()
          )
          update = ansRulesCt.exercise(
            _.exerciseAnsRules_RequestEntry(
              body.name,
              body.url,
              body.description,
              partyId.toProtoPrimitive,
            )
              .map { e =>
                r0.CreateAnsEntryResponse.OK(
                  d0.CreateAnsEntryResponse(
                    e.exerciseResult.entryCid.contractId,
                    e.exerciseResult.requestCid.contractId,
                    body.name,
                    body.url,
                    body.description,
                  )
                )
              }
          )
          res <- endUserWallet.connection
            .submit(Seq(partyId), Seq(partyId), update)
            .withDisclosedContracts(endUserWallet.connection.disclosedContracts(ansRules))
            .noDedup
            .yieldResult()
        } yield res,
        logger,
      )
    }
  }

  override def listAnsEntries(
      respond: r0.ListAnsEntriesResponse.type
  )()(tuser: WalletUserRequest): Future[r0.ListAnsEntriesResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listAnsEntries") { implicit traceContext => _ =>
      for {
        entriesWithPayData <- userWallet.store.listAnsEntries(walletManager.clock.now)
        res <- Future.successful {
          r0.ListAnsEntriesResponse.OK(
            d0.ListAnsEntriesResponse(entries =
              entriesWithPayData
                .map(e =>
                  d0.AnsEntryResponse(
                    contractId = e.contractId.toString(),
                    name = e.entryName,
                    amount = e.amount.toString(),
                    unit = e.unit.toString(),
                    expiresAt = e.expiresAt.toEpochMilli().toString(),
                    paymentInterval = e.paymentInterval.microseconds.toString(),
                    paymentDuration = e.paymentDuration.microseconds.toString(),
                  )
                )
                .toVector
            )
          )
        }
      } yield res
    }
  }
}
