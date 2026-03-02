// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredAmuletTransferInstructionTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.jdk.CollectionConverters.*

class ExpiredAmuletTransferInstructionTrigger(
    svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.amulettransferinstruction.AmuletTransferInstruction.ContractId,
      splice.amulettransferinstruction.AmuletTransferInstruction,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredAmuletBatchSize,
      svTaskContext.dsoStore
        .listExpiredAmuletTransferInstructions(context.config.ignoredExpiredAmuletPartyIds),
      splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {

    val informees = task.work.expiredContracts.flatMap { c =>
      Seq(
        PartyId.tryFromProtoPrimitive(c.payload.transfer.sender),
        PartyId.tryFromProtoPrimitive(c.payload.transfer.receiver),
      )
    }.toSet + store.key.dsoParty

    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()

      inputs <- Future.traverse(task.work.expiredContracts) { contract =>
        for {
          // If LockedAmulet still exists, we need to unlock it (expireLock = true). If not, the user already did it (expireLock = false).
          lockedAmuletExists <- store.multiDomainAcsStore.lookupContractById(
            splice.amulet.LockedAmulet.COMPANION
          )(contract.payload.lockedAmulet)
        } yield {
          new splice.amuletrules.AmuletRules_ExpireTransferInstructionInput(
            new splice.api.token.transferinstructionv1.TransferInstruction.ContractId(
              contract.contractId.contractId
            ),
            java.lang.Boolean.valueOf(lockedAmuletExists.isDefined),
          )
        }
      }

      choiceArg: splice.amuletrules.AmuletRules_Amulet_ExpireTransferInstructions =
        new splice.amuletrules.AmuletRules_Amulet_ExpireTransferInstructions(
          dsoRules.payload.dso,
          inputs.asJava,
          informees.map(_.toProtoPrimitive).toList.asJava,
        )

      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          update = dsoRules
            .exercise(
              _.exerciseDsoRules_Amulet_ExpireTransferInstructions(
                amuletRules.contractId,
                choiceArg,
                controller,
              )
            )
            .update
            .commands()
            .asScala
            .toSeq,
        )
        .noDedup
        .withSynchronizerId(dsoRules.domain)
        .yieldUnit()
      _ = logger.info(s"Successfully archived batch of ${task.work.expiredContracts.size} transfer instructions.")
    } yield TaskSuccess("archived batch of expired transfer instructions")
  }
}

object ExpiredAmuletTransferInstructionTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amulettransferinstruction.AmuletTransferInstruction.ContractId,
        splice.amulettransferinstruction.AmuletTransferInstruction,
      ]
    ]
}
