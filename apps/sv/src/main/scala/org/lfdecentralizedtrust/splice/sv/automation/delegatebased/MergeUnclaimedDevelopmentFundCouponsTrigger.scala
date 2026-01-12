// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedDevelopmentFundCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_MergeUnclaimedDevelopmentFundCoupons
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_MergeUnclaimedDevelopmentFundCoupons
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeUnclaimedDevelopmentFundCouponsTrigger(
    svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[MergeUnclaimedDevelopmentFundCouponsTask]
    with SvTaskBasedTrigger[MergeUnclaimedDevelopmentFundCouponsTask] {

  private val store = svTaskContext.dsoStore

  protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[MergeUnclaimedDevelopmentFundCouponsTask]] = {
    val threshold = svConfig.numUnclaimedDevelopmentFundCouponsThreshold
    val limit = PageLimit.tryCreate(threshold * 2)
    store.listUnclaimedDevelopmentFundCoupons(limit).map { unclaimedDevelopmentFundCoupons =>
      if (unclaimedDevelopmentFundCoupons.length > threshold) {
        Seq(MergeUnclaimedDevelopmentFundCouponsTask(unclaimedDevelopmentFundCoupons))
      } else {
        Seq()
      }
    }
  }

  protected def isStaleTask(
      unclaimedDevelopmentFundCouponsTask: MergeUnclaimedDevelopmentFundCouponsTask
  )(implicit tc: TraceContext): Future[Boolean] = store.multiDomainAcsStore.containsArchived(
    unclaimedDevelopmentFundCouponsTask.contracts.map(_.contractId)
  )

  override def completeTaskAsDsoDelegate(
      unclaimedDevelopmentFundCouponsTask: MergeUnclaimedDevelopmentFundCouponsTask,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      choiceArg = new AmuletRules_MergeUnclaimedDevelopmentFundCoupons(
        unclaimedDevelopmentFundCouponsTask.contracts.map(_.contractId).asJava
      )
      arg = new DsoRules_MergeUnclaimedDevelopmentFundCoupons(
        amuletRules.contractId,
        choiceArg,
        controller,
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeUnclaimedDevelopmentFundCoupons(arg))
      res <- for {
        outcome <- svTaskContext
          .connection(SpliceLedgerConnectionPriority.Low)
          .submit(
            Seq(store.key.svParty),
            Seq(store.key.dsoParty),
            cmd,
          )
          .noDedup
          .yieldResult()
      } yield Some(outcome)
    } yield {
      res
        .map(cid => {
          TaskSuccess(
            s"Merged unclaimed development fund coupons into contract ${cid.exerciseResult.result.unclaimedDevelopmentFundCouponCid.contractId}"
          )
        })
        .getOrElse(TaskSuccess(s"Not enough unclaimed development fund coupons to merge"))
    }
  }
}

case class MergeUnclaimedDevelopmentFundCouponsTask(
    contracts: Seq[
      Contract[UnclaimedDevelopmentFundCoupon.ContractId, UnclaimedDevelopmentFundCoupon]
    ]
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(param("contracts", _.contracts))
}
