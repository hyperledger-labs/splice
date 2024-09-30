// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ExpireTransferOfferTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      transferOffersCodegen.TransferOffer.ContractId,
      transferOffersCodegen.TransferOffer,
    ](
      store.multiDomainAcsStore,
      store.listExpiredTransferOffers,
      transferOffersCodegen.TransferOffer.COMPANION,
    ) {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          transferOffersCodegen.TransferOffer.ContractId,
          transferOffersCodegen.TransferOffer,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      user = store.key.endUserParty.toProtoPrimitive
      _ <- user match {
        case task.work.contract.payload.sender | task.work.contract.payload.receiver =>
          val cmd = install.exercise(
            _.exerciseWalletAppInstall_TransferOffer_Expire(
              task.work.contractId
            )
          )
          logger.debug("Expiring expired transfer offer")
          connection
            .submit(Seq(store.key.validatorParty), Seq(store.key.endUserParty), cmd)
            .noDedup
            .yieldResult()
        case _ =>
          Future.failed(
            Status.INTERNAL
              .withDescription(
                s"User ($user) is unexpectedly neither sender (${task.work.contract.payload.sender}) nor receiver (${task.work.contract.payload.receiver})"
              )
              .asRuntimeException()
          )
      }
    } yield TaskSuccess("expired transfer offer")
  }
}
