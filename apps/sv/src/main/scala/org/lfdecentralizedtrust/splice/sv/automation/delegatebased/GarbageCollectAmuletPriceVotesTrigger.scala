// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class GarbageCollectAmuletPriceVotesTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      DsoRules.ContractId,
      DsoRules,
    ](
      svTaskContext.dsoStore,
      DsoRules.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[
      DsoRules.ContractId,
      DsoRules,
    ]] {
  type DsoRulesContract = AssignedContract[
    DsoRules.ContractId,
    DsoRules,
  ]

  val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      dsoRules: DsoRulesContract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      amuletPriceVotes <- store.listAllAmuletPriceVotes()
      (svVotes, nonSvVotes) = amuletPriceVotes.partition(v =>
        dsoRules.payload.svs.asScala.contains(v.payload.sv)
      )
      nonSvVoteCids = nonSvVotes.map(_.contractId)
      svDuplicatedVoteCids =
        svVotes
          .groupBy(_.payload.sv)
          .values
          .filter(_.size > 1)
          .map(_.map(_.contractId).asJava)
          .toSeq
      _ <-
        if (nonSvVoteCids.nonEmpty || svDuplicatedVoteCids.nonEmpty) {
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_GarbageCollectAmuletPriceVotes(
              nonSvVoteCids.asJava,
              svDuplicatedVoteCids.asJava,
              Optional.of(controller),
            )
          )
          svTaskContext
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldUnit()
        } else Future.successful(())
    } yield TaskSuccess(
      s"Archived ${nonSvVoteCids.size} non sv votes and deduplicated votes for ${svDuplicatedVoteCids.size} SVs"
    )
  }
}
