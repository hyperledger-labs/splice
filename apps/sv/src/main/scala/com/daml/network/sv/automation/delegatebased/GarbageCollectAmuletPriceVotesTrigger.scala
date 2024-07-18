// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

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
      dsoRules: DsoRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      amuletPriceVotes <- store.listAllAmuletPriceVotes()
      (memberVotes, nonSvVotes) = amuletPriceVotes.partition(v =>
        dsoRules.payload.svs.asScala.contains(v.payload.sv)
      )
      nonSvVoteCids = nonSvVotes.map(_.contractId)
      memberDuplicatedVoteCids =
        memberVotes
          .groupBy(_.payload.sv)
          .values
          .filter(_.size > 1)
          .map(_.map(_.contractId).asJava)
          .toSeq
      _ <-
        if (nonSvVoteCids.nonEmpty || memberDuplicatedVoteCids.nonEmpty) {
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_GarbageCollectAmuletPriceVotes(
              nonSvVoteCids.asJava,
              memberDuplicatedVoteCids.asJava,
            )
          )
          svTaskContext.connection
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldUnit()
        } else Future.successful(())
    } yield TaskSuccess(
      s"Archived ${nonSvVoteCids.size} non member votes and deduplicated votes for ${memberDuplicatedVoteCids.size} SVs"
    )
  }
}
