package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cn.svcrules.SvcRules_MergeMemberTrafficContracts
import com.daml.network.util.{AssignedContract, Contract}
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
      svTaskContext.svcStore,
      MemberTraffic.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[MemberTraffic.ContractId, MemberTraffic]] {

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      memberTraffic: AssignedContract[MemberTraffic.ContractId, MemberTraffic]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      threshold = svcRules.payload.config.numMemberTrafficContractsThreshold
      memberId = Member.tryFromProtoPrimitive(memberTraffic.payload.memberId)
      domainId = DomainId.tryFromString(memberTraffic.payload.domainId)
      memberTraffics <- store.listMemberTrafficContracts(memberId, domainId, 2 * threshold)
      outcome <-
        if (memberTraffics.length > threshold)
          mergeMemberTrafficContracts(memberId, domainId, memberTraffics)
        else
          Future.successful(
            TaskSuccess(
              s"More than ${threshold} member traffic contracts are required for $memberId on domain $domainId " +
                s"in order to merge them. Currently, there are only ${memberTraffics.length}."
            )
          )
    } yield outcome
  }

  def mergeMemberTrafficContracts(
      memberId: Member,
      domainId: DomainId,
      memberTraffics: Seq[Contract[MemberTraffic.ContractId, MemberTraffic]],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      arg = new SvcRules_MergeMemberTrafficContracts(
        coinRules.contractId,
        memberTraffics.map(_.contractId).asJava,
      )
      cmd = svcRules.contractId.exerciseSvcRules_MergeMemberTrafficContracts(arg)
      outcome <- svTaskContext.connection
        .submitWithResultNoDedup(
          Seq(store.key.svParty),
          Seq(store.key.svcParty),
          cmd,
          domainId,
        )
    } yield TaskSuccess(
      s"Merged ${memberTraffics.length} member traffic contracts for member $memberId on domain $domainId " +
        s"into contract ${outcome.exerciseResult.contractId}"
    )
  }

}
