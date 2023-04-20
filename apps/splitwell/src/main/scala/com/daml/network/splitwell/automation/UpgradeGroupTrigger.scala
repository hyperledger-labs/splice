package com.daml.network.splitwell
package automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import store.SplitwellStore
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.LedgerClient.TransferCommand
import com.daml.network.util.PrettyInstances.prettyCodegenContractId
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Transfer out certain `Group`s matching the criteria defined in
  * [[SplitwellStore#listTransferrableGroups]].  We expect separate automation
  * to handle the transfer-in.
  *
  * This is not a [[com.daml.network.automation.TransferOutTrigger]] because a
  * contract's readiness is not a good condition to justify its transfer-out;
  * it applies only to `Group`s on certain domains that have certain associated
  * contracts on the preferred domain.
  */
private[automation] class UpgradeGroupTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[UpgradeGroupTrigger.Task] {
  import UpgradeGroupTrigger.*

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    store
      .listTransferrableGroups()
      .map(_.view.flatMap { case (domainId, groupIds) => groupIds.view.map((_, domainId)) }.toSeq)
  }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val (groupId, domainId) = task
    for {
      preferredDomain <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      _ <- connection.submitTransferAndAwaitIngestionNoDedup(
        store.multiDomainAcsStore,
        submitter = store.providerParty,
        command = TransferCommand.Out(
          contractId = groupId,
          source = domainId,
          target = preferredDomain,
        ),
      )
    } yield {
      TaskSuccess(
        show"Successfully transferred out group $groupId from $domainId"
      )
    }
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    val (groupId, domainId) = task
    for {
      // lookup group once again in the source domain to check if it is assigned there
      groupExists <- store.multiDomainAcsStore
        .lookupContractByIdOnDomain(splitwellCodegen.Group.COMPANION)(domainId, groupId)
        .map(_.isDefined)
      isStale = !groupExists
    } yield isStale
  }
}

private[automation] object UpgradeGroupTrigger {
  private type Task = (splitwellCodegen.Group.ContractId, DomainId)
}
