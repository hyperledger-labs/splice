// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  TaskOutcome,
  TriggerContext,
  OnAssignedContractTrigger,
  TaskSuccess,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.splitwell.store.SplitwellStore
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, AssignedContract}
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AcceptedAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: SpliceLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      walletCodegen.AcceptedAppPayment.ContractId,
      walletCodegen.AcceptedAppPayment,
    ](
      store,
      walletCodegen.AcceptedAppPayment.COMPANION,
    ) {

  override def completeTask(
      payment: AssignedContract[
        walletCodegen.AcceptedAppPayment.ContractId,
        walletCodegen.AcceptedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val provider = store.key.providerParty
    val round = payment.payload.round
    def rejectPayment(
        reason: String,
        transferContext: splice.amuletrules.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
    ) = {
      logger.warn(s"rejecting accepted app payment: $reason")
      val cmd = payment.exercise(_.exerciseAcceptedAppPayment_Reject(transferContext))
      connection
        .submit(Seq(store.key.providerParty), Seq(), cmd)
        .withDisclosedContracts(disclosedContracts)
        .noDedup
        .yieldResult()
        .map(_ => TaskSuccess(s"rejected accepted app payment: $reason"))
    }
    for {
      transferContextE <- scanConnection
        .getAppTransferContextForRound(connection, store.key.providerParty, round)
      result <- transferContextE match {
        case Right((transferContext, disclosedContracts)) =>
          for {
            transferInProgress <- store
              .lookupTransferInProgress(payment.payload.reference)
              .map(
                _.value.getOrElse(
                  throw Status.NOT_FOUND
                    .withDescription(
                      show"No transfer-in-progress contract found for payment request ${payment.payload.reference}, likely because a transfer is in progress"
                    )
                    .asRuntimeException()
                )
              )
            cmd = transferInProgress.exercise(
              _.exerciseTransferInProgress_CompleteTransfer(
                payment.contractId,
                transferContext,
              )
            )
            _ <- connection
              .submit(actAs = Seq(provider), readAs = Seq(), cmd)
              .withDisclosedContracts(disclosedContracts assertOnDomain payment.domain)
              .noDedup
              .yieldUnit()
          } yield TaskSuccess("accepted payment and completed transfer")
        case Left(err) =>
          scanConnection
            .getAppTransferContext(connection, store.key.providerParty)
            .flatMap { case (transferContext, disclosedContracts) =>
              rejectPayment(
                s"Round ${payment.payload.round} is no longer active: $err",
                transferContext,
                disclosedContracts,
              )
            }

      }
    } yield result
  }
}
