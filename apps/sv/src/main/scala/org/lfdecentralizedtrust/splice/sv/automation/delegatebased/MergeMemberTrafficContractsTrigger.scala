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
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_MergeMemberTrafficContracts
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import com.digitalasset.canton.topology.{DomainId, Member}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeMemberTrafficContractsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[MemberTraffic.ContractId, MemberTraffic](
      svTaskContext.dsoStore,
      MemberTraffic.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[MemberTraffic.ContractId, MemberTraffic]] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      memberTraffic: AssignedContract[MemberTraffic.ContractId, MemberTraffic]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Member
      .fromProtoPrimitive_(memberTraffic.payload.memberId)
      .fold(
        err => {
          // Skip contracts with invalid member ids
          Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid memberId: ${err}"))
        },
        memberId => {
          for {
            dsoRules <- store.getDsoRules()
            threshold = dsoRules.payload.config.numMemberTrafficContractsThreshold
            domainId = DomainId.tryFromString(memberTraffic.payload.synchronizerId)
            memberTraffics <- store.listMemberTrafficContracts(
              memberId,
              domainId,
              PageLimit.tryCreate(2 * threshold.toInt),
            )
            outcome <-
              if (memberTraffics.length > threshold)
                mergeMemberTrafficContracts(memberId, memberTraffics)
              else
                Future.successful(
                  TaskSuccess(
                    s"More than ${threshold} member traffic contracts are required for $memberId on domain $domainId " +
                      s"in order to merge them. Currently, there are only ${memberTraffics.length}."
                  )
                )
          } yield outcome
        },
      )
  }

  def mergeMemberTrafficContracts(
      memberId: Member,
      memberTraffics: Seq[Contract[MemberTraffic.ContractId, MemberTraffic]],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      arg = new DsoRules_MergeMemberTrafficContracts(
        amuletRules.contractId,
        memberTraffics.map(_.contractId).asJava,
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeMemberTrafficContracts(arg))
      outcome <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Merged ${memberTraffics.length} member traffic contracts for member $memberId on domain ${dsoRules.domain} " +
        s"into contract ${outcome.exerciseResult.memberTraffic.contractId}"
    )
  }

}
