// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.time.Clock
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredAmuletAllocationTrigger.{Task, getStakeholders}
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

import scala.jdk.CollectionConverters.*

class ExpiredAmuletAllocationTrigger(
    override protected val svConfig: SvAppBackendConfig,
    clock: Clock,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.amuletallocation.AmuletAllocation.ContractId,
      splice.amuletallocation.AmuletAllocation,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredAmuletAllocationBatchSize,
      svTaskContext.dsoStore.listExpiredAmuletAllocations(Some(unavailablePartiesStore)),
      splice.amuletallocation.AmuletAllocation.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      getStakeholders,
    )
    with SvTaskBasedTrigger[Task]
    with UnavailablePartiesGuard {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    completeUnlessAmuletVersionIgnored(
      task.work.vettedVersion.toString,
      task.work.stakeholders,
      ignoreUnresponsiveParties = true,
      restoreOnSuccess = false,
    )(completeExpiryTaskAsDsoDelegate(task, controller))
  }

  private def completeExpiryTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val stakeholders = task.work.stakeholders
    for {
      packageSupport <- svTaskContext.packageVersionSupport.supportsExpireAmuletAllocations(
        stakeholders.toSeq,
        Seq(store.key.dsoParty),
        clock.now,
      )
      res <-
        if (!packageSupport.supported) {
          logger.info(
            s"Skipping expiry of ${task.work.expiredContracts.size} allocations because not all parties have vetted the required Amulet package version. Parties: ${stakeholders
                .mkString(", ")}"
          )
          Future.successful(
            TaskSuccess(
              s"Batch of ${task.work.expiredContracts.size} skipped due to old package version."
            )
          )
        } else {
          for {
            dsoRules <- store.getDsoRules()
            extAmuletRules <- store.getExternalPartyAmuletRules()

            inputsWithParties <- MonadUtil.sequentialTraverse(task.work.expiredContracts)(
              buildExpireInput
            )

            res <-
              if (inputsWithParties.isEmpty) {
                Future.successful(
                  TaskSuccess("No vetted expired Amulet Allocations to process")
                )
              } else {
                submitBatchWithSplitting(inputsWithParties, (c: InputWithParties) => c._2) {
                  batch =>
                    val inputs = batch.map(_._1)
                    val informees = batch.flatMap(_._2).toSet
                    val expireAllocations =
                      new splice.externalpartyamuletrules.ExternalPartyAmuletRules_ExpireAmuletAllocations(
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
                            _.exerciseDsoRules_ExpireAmuletAllocations(
                              extAmuletRules.contract.contractId,
                              expireAllocations,
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
                }
              }
          } yield res
        }
    } yield res
  }

  private type InputWithParties = (
      splice.externalpartyamuletrules.ExternalPartyAmuletRules_ExpireAmuletAllocationInput,
      Set[PartyId],
  )

  private def buildExpireInput(
      contract: org.lfdecentralizedtrust.splice.util.AssignedContract[
        splice.amuletallocation.AmuletAllocation.ContractId,
        splice.amuletallocation.AmuletAllocation,
      ]
  )(implicit tc: TraceContext): Future[
    (
        splice.externalpartyamuletrules.ExternalPartyAmuletRules_ExpireAmuletAllocationInput,
        Set[PartyId],
    )
  ] = {
    val sender = PartyId.tryFromProtoPrimitive(contract.payload.allocation.transferLeg.sender)
    val executor = PartyId.tryFromProtoPrimitive(contract.payload.allocation.settlement.executor)

    for {
      lockedAmuletExists <- store.multiDomainAcsStore.lookupContractById(
        splice.amulet.LockedAmulet.COMPANION
      )(contract.payload.lockedAmulet)
    } yield {
      val input =
        new splice.externalpartyamuletrules.ExternalPartyAmuletRules_ExpireAmuletAllocationInput(
          new splice.amuletallocation.AmuletAllocation.ContractId(
            contract.contractId.contractId
          ),
          java.lang.Boolean.valueOf(lockedAmuletExists.isDefined),
        )
      (input, Set(sender, executor))
    }
  }
}

object ExpiredAmuletAllocationTrigger
    extends ContractStakeholders[splice.amuletallocation.AmuletAllocation] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amuletallocation.AmuletAllocation.ContractId,
        splice.amuletallocation.AmuletAllocation,
      ]
    ]

  override def informees(payload: splice.amuletallocation.AmuletAllocation): Seq[String] =
    Seq(payload.allocation.transferLeg.sender, payload.allocation.settlement.executor)

  override def dso(payload: splice.amuletallocation.AmuletAllocation): String =
    payload.allocation.transferLeg.instrumentId.admin
}
