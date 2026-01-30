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

// Auto-rejects expired MintingDelegationProposal contracts.
class ExpireMintingDelegationProposalTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      mintingDelegationCodegen.MintingDelegationProposal.ContractId,
      mintingDelegationCodegen.MintingDelegationProposal,
    ](
      store.multiDomainAcsStore,
      store.listExpiredMintingDelegationProposals,
      mintingDelegationCodegen.MintingDelegationProposal.COMPANION,
    ) {

  override protected def extraMetricLabels: Seq[(String, String)] =
    Seq("party" -> store.key.endUserParty.toString)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          mintingDelegationCodegen.MintingDelegationProposal.ContractId,
          mintingDelegationCodegen.MintingDelegationProposal,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val beneficiary = task.work.payload.delegation.beneficiary
    connection
      .submit(
        actAs = Seq(store.key.endUserParty),
        readAs = Seq.empty,
        update = task.work.exercise(_.exerciseMintingDelegationProposal_Reject()),
      )
      .noDedup
      .yieldUnit()
      .map(_ =>
        TaskSuccess(
          s"Rejected expired MintingDelegationProposal for beneficiary $beneficiary"
        )
      )
  }
}
