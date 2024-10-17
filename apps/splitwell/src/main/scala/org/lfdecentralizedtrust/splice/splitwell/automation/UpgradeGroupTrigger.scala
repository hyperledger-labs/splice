// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell
package automation

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.ReassignmentCommand
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

import store.SplitwellStore

/** Unassign certain `Group`s matching the criteria defined in
  * [[SplitwellStore#listTransferrableGroups]].  We expect separate automation
  * to handle the assign.
  *
  * This is not a [[org.lfdecentralizedtrust.splice.automation.UnassignTrigger]] because a
  * contract's readiness is not a good condition to justify its unassign;
  * it applies only to `Group`s on certain domains that have certain associated
  * contracts on the preferred domain.
  */
private[automation] class UpgradeGroupTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
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
      preferredDomain <- store.defaultAcsDomainIdF
      _ <- connection.submitReassignmentAndWaitNoDedup(
        submitter = store.key.providerParty,
        command = ReassignmentCommand.Unassign(
          contractId = groupId,
          source = domainId,
          target = preferredDomain,
        ),
      )
    } yield {
      TaskSuccess(
        show"Successfully unassigned group $groupId from $domainId"
      )
    }
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    val (groupId, domainId) = task
    for {
      // lookup group once again in the source domain to check if it is assigned there;
      // as reassignment is the purpose of this trigger, we need a new task even
      // if domainId is wrong for any reason but success of this trigger
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
