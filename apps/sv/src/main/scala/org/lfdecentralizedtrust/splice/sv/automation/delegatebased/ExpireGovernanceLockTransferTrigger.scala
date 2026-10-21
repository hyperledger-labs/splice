// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.transfer as transferCodegen
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import ExpireGovernanceLockTransferTrigger.{Lock, LockCid, Task, getStakeholders}

/** Garbage-collects expired `GovernanceLockTransfers`s. */
class ExpireGovernanceLockTransferTrigger(
    override protected val svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[LockCid, Lock](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredGovernanceLockTransferBatchSize,
      svTaskContext.dsoStore.listExpiredGovernanceLockTransfers(Some(unavailablePartiesStore)),
      transferCodegen.GovernanceLockTransfer.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      getStakeholders,
    )
    with SvTaskBasedTrigger[Task]
    with UnavailablePartiesGuard {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    completeUnlessAmuletVersionIgnored(
      task.work.vettedVersion.toString,
      task.work.stakeholders,
      ignoreUnresponsiveParties = true,
    )(completeExpiryTaskAsDsoDelegate(task, controller))

  private def completeExpiryTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val expiredLocks = task.work.expiredContracts
    for {
      dsoRules <- store.getDsoRules()
      cmds = expiredLocks.flatMap(co =>
        dsoRules
          .exercise(_.exerciseDsoRules_GovernanceLockTransfer_DsoExpire(co.contractId, controller))
          .update
          .commands()
          .asScala
          .toSeq
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          update = cmds,
        )
        .noDedup
        .withSynchronizerId(dsoRules.domain)
        .yieldUnit()
    } yield TaskSuccess(s"archived ${expiredLocks.size} expired vesting locks")
  }
}

object ExpireGovernanceLockTransferTrigger
    extends ContractStakeholders[transferCodegen.GovernanceLockTransfer] {
  private type LockCid = transferCodegen.GovernanceLockTransfer.ContractId
  private type Lock = transferCodegen.GovernanceLockTransfer

  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[LockCid, Lock]
    ]

  override def informees(payload: transferCodegen.GovernanceLockTransfer): Seq[String] =
    Seq(payload.source.owner, payload.receiver)

  override def dso(payload: transferCodegen.GovernanceLockTransfer): String = payload.source.dso
}
