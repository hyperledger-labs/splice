// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders
import org.lfdecentralizedtrust.splice.util.GovernanceLockUtil

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import ProvisionalFeaturedAppLockConversionTrigger.{Task, getStakeholders}

/** Converts provisional featured app `GovernanceLock`s into real ones once their provider holds a
  * `FeaturedAppRight`.
  *
  * These locks don't expire. We use the expired-contract base class because it batches the locks
  * and groups each batch by a Daml version that all the parties on those locks have vetted.
  */
class ProvisionalFeaturedAppLockConversionTrigger(
    override protected val svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.governancelock.GovernanceLock.ContractId,
      splice.governancelock.GovernanceLock,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationProvisionalFeaturedAppLockConversionBatchSize,
      svTaskContext.dsoStore.listProvisionalGovernanceLocksWithFeaturedAppRightSample(
        Some(unavailablePartiesStore)
      ),
      splice.governancelock.GovernanceLock.COMPANION,
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
    )(convertLocks(task, controller))

  /** The query only returns locks whose provider has a live `FeaturedAppRight`, so a missing one
    * here means it was archived between the query and this lookup. We drop those locks rather than
    * fail, since the whole batch goes out as a single transaction.
    */
  private def convertLocks(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      locksWithRights <- MonadUtil.sequentialTraverse(task.work.expiredContracts) { lock =>
        providerOf(lock.payload) match {
          case Some(provider) =>
            store.lookupFeaturedAppRight(provider).map(_.map(right => lock -> right.contractId))
          case None => Future.successful(None)
        }
      }
      convertible = locksWithRights.flatten
      res <-
        if (convertible.isEmpty) Future.successful(TaskNoop)
        else
          svTaskContext
            .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              update = convertible.flatMap { case (lock, rightCid) =>
                dsoRules
                  .exercise(
                    _.exerciseDsoRules_GovernanceLock_ConvertProvisionalFeaturedAppLock(
                      lock.contractId,
                      rightCid,
                      controller,
                    )
                  )
                  .update
                  .commands()
                  .asScala
                  .toSeq
              },
            )
            .noDedup
            .withSynchronizerId(dsoRules.domain)
            .yieldUnit()
            .map(_ =>
              TaskSuccess(
                s"converted ${convertible.size} of ${task.work.expiredContracts.size} " +
                  s"provisional featured app locks in batch"
              )
            )
    } yield res

  private def providerOf(payload: splice.governancelock.GovernanceLock): Option[PartyId] =
    GovernanceLockUtil.getGovernanceLockKind(payload) match {
      case kind: splice.governancelock.governancelockkind.GLK_ProvisionalFeaturedApp =>
        Some(PartyId.tryFromProtoPrimitive(kind.provider))
      case _ => None
    }
}

object ProvisionalFeaturedAppLockConversionTrigger
    extends ContractStakeholders[splice.governancelock.GovernanceLock] {

  type Task = ScheduledTaskTrigger.ReadyTask[
    BatchedMultiDomainExpiredContractTrigger.Batch[
      splice.governancelock.GovernanceLock.ContractId,
      splice.governancelock.GovernanceLock,
    ]
  ]

  override def informees(payload: splice.governancelock.GovernanceLock): Seq[String] =
    (payload.owner +: extraObservers(payload)).distinct

  private def extraObservers(payload: splice.governancelock.GovernanceLock): Seq[String] =
    GovernanceLockUtil.getGovernanceLockKind(payload) match {
      case kind: splice.governancelock.governancelockkind.GLK_ProvisionalFeaturedApp =>
        Seq(kind.provider)
      case kind: splice.governancelock.governancelockkind.GLK_FeaturedApp =>
        Seq(kind.provider)
      case _ => Seq.empty
    }

  override def dso(payload: splice.governancelock.GovernanceLock): String = payload.dso
}
