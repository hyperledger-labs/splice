// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

// Auto-rejects expired MintingDelegation contracts.
class ExpireMintingDelegationTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      mintingDelegationCodegen.MintingDelegation.ContractId,
      mintingDelegationCodegen.MintingDelegation,
    ](
      store.multiDomainAcsStore,
      store.listExpiredMintingDelegations,
      mintingDelegationCodegen.MintingDelegation.COMPANION,
    ) {

  override protected def extraMetricLabels: Seq[(String, String)] =
    Seq("party" -> store.key.endUserParty.toString)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          mintingDelegationCodegen.MintingDelegation.ContractId,
          mintingDelegationCodegen.MintingDelegation,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val beneficiary = task.work.payload.beneficiary
    connection
      .submit(
        actAs = Seq(store.key.endUserParty),
        readAs = Seq.empty,
        update = task.work.exercise(_.exerciseMintingDelegation_Reject()),
      )
      .noDedup
      .yieldUnit()
      .map(_ =>
        TaskSuccess(
          s"Rejected expired MintingDelegation for beneficiary $beneficiary"
        )
      )
  }
}
