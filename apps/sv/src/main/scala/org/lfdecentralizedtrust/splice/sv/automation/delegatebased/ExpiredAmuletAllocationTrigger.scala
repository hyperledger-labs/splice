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
import ExpiredAmuletAllocationTrigger.*
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.jdk.CollectionConverters.*

class ExpiredAmuletAllocationTrigger(
    svConfig: SvAppBackendConfig,
    clock: Clock,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
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
      svTaskContext.dsoStore.listExpiredAmuletAllocations(
        context.config.ignoredExpiredAmuletAllocationPartyIds
      ),
      splice.amuletallocation.AmuletAllocation.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      allocation =>
        Seq(
          allocation.allocation.transferLeg.sender,
          allocation.allocation.transferLeg.receiver,
          allocation.allocation.settlement.executor,
          svTaskContext.dsoStore.key.dsoParty.partyId.toProtoPrimitive,
        ).map(PartyId.tryFromProtoPrimitive),
    )
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {

    val allParties = task.work.expiredContracts.flatMap { contract =>
      val sender = PartyId.tryFromProtoPrimitive(contract.payload.allocation.transferLeg.sender)
      val receiver = PartyId.tryFromProtoPrimitive(contract.payload.allocation.transferLeg.receiver)
      val executor = PartyId.tryFromProtoPrimitive(contract.payload.allocation.settlement.executor)
      Seq(sender, receiver, executor)
    }.toSet + store.key.dsoParty

    for {
      packageSupport <- svTaskContext.packageVersionSupport.supportsExpireAmuletAllocations(
        allParties.toSeq,
        clock.now,
      )
      res <-
        if (!packageSupport.supported) {
          logger.info(
            s"Skipping expiry of ${task.work.expiredContracts.size} allocations because not all parties have vetted the required Amulet package version. Parties: ${allParties
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

            inputsWithParties <- MonadUtil.sequentialTraverse(task.work.expiredContracts) {
              contract =>
                val sender = PartyId.tryFromProtoPrimitive(
                  contract.payload.allocation.transferLeg.sender
                )
                val receiver = PartyId.tryFromProtoPrimitive(
                  contract.payload.allocation.transferLeg.receiver
                )
                val executor = PartyId.tryFromProtoPrimitive(
                  contract.payload.allocation.settlement.executor
                )

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
                  (input, Set(sender, receiver, executor))
                }
            }

            inputs = inputsWithParties.map(_._1)
            informees = inputsWithParties.flatMap(_._2).toSet

            res <-
              if (inputs.isEmpty) {
                Future.successful(
                  TaskSuccess("No vetted expired Amulet Allocations to process")
                )
              } else {
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
                  .map(_ =>
                    TaskSuccess(
                      s"archived batch of ${inputs.size} expired Amulet Allocations"
                    )
                  )
              }
          } yield res
        }
    } yield res
  }
}

object ExpiredAmuletAllocationTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amuletallocation.AmuletAllocation.ContractId,
        splice.amuletallocation.AmuletAllocation,
      ]
    ]
}
