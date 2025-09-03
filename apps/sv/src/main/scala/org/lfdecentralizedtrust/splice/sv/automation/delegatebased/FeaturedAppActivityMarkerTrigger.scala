// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet, amuletrules}
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import FeaturedAppActivityMarkerTrigger.Task
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional

class FeaturedAppActivityMarkerTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    // This is a polling trigger as we usually expect to be able to batch together the conversion
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task] {

  val store = svTaskContext.dsoStore

  // The amount of markers converted in a single transaction.
  private val batchSize: Int = 5
  // The amount of markers fetched from the DB from which we sample batchSize markers randomly.
  // The random sampling is to minimize contention between SVs (once delegate-based automation is removed).
  private val batchSampleSize: Int = 1000

  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    store.listFeaturedAppActivityMarkers(batchSampleSize).map { markers =>
      if (markers.isEmpty) {
        Seq.empty
      } else {
        val shuffledMarkers = scala.util.Random.shuffle(markers)
        Seq(Task(shuffledMarkers.take(batchSize)))
      }
    }

  override def completeTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      now = context.clock.now
      openMiningRound <- store.getLatestUsableOpenMiningRound(now)
      // Note that we don't group by provider or beneficiary. There is no strong need to do so
      // as we want to
      update = dsoRules.exercise(
        _.exerciseDsoRules_AmuletRules_ConvertFeaturedAppActivityMarkers(
          amuletRules.contractId,
          new amuletrules.AmuletRules_ConvertFeaturedAppActivityMarkers(
            task.markers.map(_.contractId).asJava,
            openMiningRound.contractId,
          ),
          Optional.of(controller),
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Medium)
        .submit(
          actAs = Seq(store.key.svParty),
          readAs = Seq(store.key.dsoParty),
          update = update,
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"Converted featured app activity markers with contract ids: ${task.markers.map(_.contractId)}"
    )
  }

  override protected def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    for {
      markers <- MonadUtil.sequentialTraverse(task.markers.toList)(m =>
        store.multiDomainAcsStore.lookupContractById(amulet.FeaturedAppActivityMarker.COMPANION)(
          m.contractId
        )
      )
    } yield markers.exists(_.isEmpty)
}

object FeaturedAppActivityMarkerTrigger {
  final case class Task(
      markers: Seq[
        Contract[amulet.FeaturedAppActivityMarker.ContractId, amulet.FeaturedAppActivityMarker]
      ]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("markers", _.markers)
      )
  }
}
