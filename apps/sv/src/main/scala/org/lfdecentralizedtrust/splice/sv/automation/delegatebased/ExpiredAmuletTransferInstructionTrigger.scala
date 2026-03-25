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
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.environment.{DarResources, PackageIdResolver}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import com.digitalasset.canton.time.Clock

import scala.jdk.CollectionConverters.*

class ExpiredAmuletTransferInstructionTrigger(
    svConfig: SvAppBackendConfig,
    clock: Clock,
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
      svConfig.delegatelessAutomationExpiredAmuletTransferInstructionBatchSize,
      svTaskContext.dsoStore.listExpiredAmuletTransferInstructions(
        context.config.ignoredExpiredAmuletTransferInstructionPartyIds
      ),
      splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      instruction =>
        Seq(
          instruction.transfer.sender,
          instruction.transfer.receiver,
          svTaskContext.dsoStore.key.dsoParty.partyId.toProtoPrimitive,
        ).map(PartyId.tryFromProtoPrimitive),
    )
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {

    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()

      inputsWithPartiesOrNone <- MonadUtil.sequentialTraverse(task.work.expiredContracts) {
        contract =>
          val sender = PartyId.tryFromProtoPrimitive(contract.payload.transfer.sender)
          val receiver = PartyId.tryFromProtoPrimitive(contract.payload.transfer.receiver)

          svTaskContext.packageVersionSupport
            .isPackageSupported(
              Seq(
                PackageIdResolver.Package.SpliceAmulet -> Seq(sender, receiver, store.key.dsoParty)
              ),
              clock.now,
              DarResources.amulet_current.metadata,
            )
            .map(_.supported)
            .flatMap { areVetted =>
              if (!areVetted) {
                logger.info(
                  s"Skipping expiry of transfer instruction ${contract.contractId} because sender $sender or receiver $receiver have not vetted the required Amulet package."
                )
                Future.successful(None)
              } else {
                for {
                  lockedAmuletExists <- store.multiDomainAcsStore.lookupContractById(
                    splice.amulet.LockedAmulet.COMPANION
                  )(contract.payload.lockedAmulet)
                } yield {
                  val input = new splice.amuletrules.AmuletRules_ExpireTransferInstructionInput(
                    new splice.api.token.transferinstructionv1.TransferInstruction.ContractId(
                      contract.contractId.contractId
                    ),
                    java.lang.Boolean.valueOf(lockedAmuletExists.isDefined),
                  )
                  Some((input, Set(sender, receiver)))
                }
              }
            }
      }

      inputsWithParties = inputsWithPartiesOrNone.flatten

      inputs = inputsWithParties.map(_._1)

      informees = inputsWithParties.flatMap(_._2).toSet + store.key.dsoParty

      res <-
        if (inputs.isEmpty) {
          Future.successful(TaskSuccess("No vetted expired transfer instructions to process"))
        } else {
          val choiceArg: splice.amuletrules.AmuletRules_Amulet_ExpireTransferInstructions =
            new splice.amuletrules.AmuletRules_Amulet_ExpireTransferInstructions(
              dsoRules.payload.dso,
              inputs.asJava,
              informees.map(_.toProtoPrimitive).toList.asJava,
            )

          svTaskContext
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
            .map(_ =>
              TaskSuccess(s"archived batch of ${inputs.size} expired transfer instructions")
            )
        }
    } yield res
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
