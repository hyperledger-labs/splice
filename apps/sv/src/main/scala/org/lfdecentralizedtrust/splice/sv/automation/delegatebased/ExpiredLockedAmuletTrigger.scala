// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredLockedAmuletTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import java.util.Optional
import scala.jdk.CollectionConverters.*

class ExpiredLockedAmuletTrigger(
    svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    // TODO(#2885): switch to a low-contention trigger; this one will heavily content among SVs
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.amulet.LockedAmulet.ContractId,
      splice.amulet.LockedAmulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredAmuletBatchSize,
      svTaskContext.dsoStore.listLockedExpiredAmulets(context.config.ignoredExpiredAmuletPartyIds),
      splice.amulet.LockedAmulet.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override protected def completeTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    // latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    externalPartyConfigStates <- store.getExternalPartyConfigStatesPair()
    dsoRules <- store.getDsoRules()
    cmds = task.work.expiredContracts.flatMap(co =>
      // FIXME: make it conditional on the version
      dsoRules
        .exercise(
          _.exerciseDsoRules_LockedAmulet_ExpireAmuletV2(
            co.contractId,
            new splice.amulet.LockedAmulet_ExpireAmuletV2(
              externalPartyConfigStates.oldest.contractId,
              externalPartyConfigStates.newest.contractId,
            ),
            Optional.of(controller),
          )
        )
        .update
        .commands()
        .asScala
        .toSeq
    )
    _ <- svTaskContext
      .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
      .submit(
        Seq(store.key.svParty),
        Seq(store.key.dsoParty),
        update = cmds,
      )
      .noDedup
      .withSynchronizerId(dsoRules.domain)
      .yieldUnit()
  } yield TaskSuccess(s"archived expired locked amulet")
}

object ExpiredLockedAmuletTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amulet.LockedAmulet.ContractId,
        splice.amulet.LockedAmulet,
      ]
    ]
}
