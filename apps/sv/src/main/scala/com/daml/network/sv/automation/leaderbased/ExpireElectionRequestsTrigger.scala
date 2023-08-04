package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.ElectionRequest
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireElectionRequestsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Contract[
      ElectionRequest.ContractId,
      ElectionRequest,
    ]]
    with SvTaskBasedTrigger[Contract[
      ElectionRequest.ContractId,
      ElectionRequest,
    ]] {
  private val store = svTaskContext.svcStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[
    ElectionRequest.ContractId,
    ElectionRequest,
  ]]] =
    store.listExpiredElectionRequests(svTaskContext.epoch)

  override protected def isStaleTask(
      task: Contract[
        ElectionRequest.ContractId,
        ElectionRequest,
      ]
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = store.multiDomainAcsStore.hasArchived(Seq(task.contractId))

  override def completeTaskAsLeader(
      task: Contract[
        ElectionRequest.ContractId,
        ElectionRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    svcRules <- store.getSvcRules()
    domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
    cmd = svcRules.contractId.exerciseSvcRules_ArchiveOutdatedElectionRequest(
      task.contractId
    )
    _ <- svTaskContext.connection
      .submitWithResultNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        cmd,
        domainId,
      )
  } yield TaskSuccess(
    s"successfully expired the election request with cid ${task.contractId}"
  )
}
