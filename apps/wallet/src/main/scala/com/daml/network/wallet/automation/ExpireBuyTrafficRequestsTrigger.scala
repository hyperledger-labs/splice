// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.splice.wallet.buytrafficrequest as trafficRequestCodegen
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ExpireBuyTrafficRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      trafficRequestCodegen.BuyTrafficRequest.ContractId,
      trafficRequestCodegen.BuyTrafficRequest,
    ](
      store.multiDomainAcsStore,
      store.listExpiredBuyTrafficRequests,
      trafficRequestCodegen.BuyTrafficRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          trafficRequestCodegen.BuyTrafficRequest.ContractId,
          trafficRequestCodegen.BuyTrafficRequest,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      user = store.key.endUserParty.toProtoPrimitive
      _ <- user match {
        case task.work.contract.payload.endUserParty =>
          val cmd = install.exercise(
            _.exerciseWalletAppInstall_BuyTrafficRequest_Expire(
              task.work.contractId
            )
          )
          connection
            .submit(Seq(store.key.validatorParty), Seq(store.key.endUserParty), cmd)
            .noDedup
            .yieldResult()
        case _ =>
          Future.failed(
            Status.INTERNAL
              .withDescription(
                s"User ($user) is not the requester of extra traffic (${task.work.contract.payload.endUserParty})"
              )
              .asRuntimeException()
          )
      }
    } yield TaskSuccess("expired buy traffic request")
  }
}
