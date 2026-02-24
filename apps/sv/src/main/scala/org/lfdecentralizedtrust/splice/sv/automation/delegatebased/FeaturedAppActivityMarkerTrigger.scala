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
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet, amuletrules, dsorules}
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import FeaturedAppActivityMarkerTrigger.Task
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import java.util.Optional
import scala.util.Random

class FeaturedAppActivityMarkerTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    svConfig: SvAppBackendConfig,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    // This is a polling trigger as we usually expect to be able to batch together the conversion
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task] {

  private val rng: Random = new Random()

  private val store = svTaskContext.dsoStore

  // The amount of markers converted in a single transaction.
  private val batchSize: Int = svConfig.delegatelessAutomationFeaturedAppActivityMarkerBatchSize
  private val maxNumSamples: Int = 10
  private val minCatchupThreshold: Int =
    4 * batchSize // 4x so that 10x resampling gives 1/4^10 ~= 0.05% chance of giving up on resampling

  // We want to be on the safe side wrt when to switch into catchup mode.
  private val activityMarkerCatchupModeThreshold: Int = Math.max(
    svConfig.delegatelessAutomationFeaturedAppActivityMarkerCatchupThreshold,
    minCatchupThreshold,
  )

  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    store
      .featuredAppActivityMarkerCountAboveOrEqualTo(
        activityMarkerCatchupModeThreshold
      )
      .flatMap {
        case false =>
          // there are not too many activity markers ==> slice them up across all SVs
          store.getDsoRules().flatMap(co => retrieveBatchesBySvIndex(co.payload))
        case true =>
          // one or more SVs seem to be lagging wrt their activity markers ==> start working on all of them
          retrieveBatchesByRandomSampling(numSamplingRuns = 1)
      }

  private def retrieveBatchesBySvIndex(
      dsoRules: dsorules.DsoRules
  )(implicit tc: TraceContext): Future[Seq[Task]] = {
    // guaranteedly no contention between different SVs
    val minInt: Long = Int.MinValue.toLong
    val maxInt: Long = Int.MaxValue.toLong
    val numSvs: Long = dsoRules.svs.size().toLong
    val svs = dsoRules.svs.keySet().asScala.toSeq.sorted
    val svIndex: Long = svs.indexOf(store.key.svParty.toProtoPrimitive).toLong
    val stride: Long = (maxInt - minInt) / numSvs
    val hashMinBoundIncl: Int = (minInt + stride * svIndex).toInt
    val hashMaxBoundIncl: Int =
      if (svIndex == numSvs - 1) {
        // The stride is rounded down, so we'd miss an entry if we wouldn't use Int.MaxValue here
        Int.MaxValue
      } else {
        (minInt + stride * (svIndex + 1) - 1).toInt
      }
    val recencyCutoff = context.clock.now.toInstant
      .minus(svConfig.delegatelessAutomationFeaturedAppActivityMarkerMaxAge.asJava)
    getBatchesByBounds("bySvIndex", hashMinBoundIncl, hashMaxBoundIncl).map(tasks =>
      tasks.filter(task =>
        // Only keep full batches or ones that contain at least one old enough marker
        task.markers.size == batchSize ||
          task.markers.exists(co => co.createdAt.isBefore(recencyCutoff))
      )
    )
  }

  private def retrieveBatchesByRandomSampling(
      numSamplingRuns: Int
  )(implicit tc: TraceContext): Future[Seq[Task]] = {
    // probabilistic guarantee of no overlap between different SVs
    val hashMinBoundIncl: Int = rng.nextInt()
    val hashMaxBoundIncl: Int = Int.MaxValue
    getBatchesByBounds("byRandomSampling", hashMinBoundIncl, hashMaxBoundIncl)
      .map(tasks => {
        logger.trace(
          show"Sampling attempt $numSamplingRuns retrieved ${tasks.size} tasks for bounds [$hashMinBoundIncl, $hashMaxBoundIncl]: $tasks"
        )
        tasks.filter(task =>
          // Only keep full batches. Non-full ones can occur due to sampling too far to the end of the list of markers.
          task.markers.size == batchSize
        )
      })
      .flatMap(tasks =>
        if (tasks.isEmpty) {
          // We only resample if there is no tasks left-over to allow for catchup mode thresholds that are close
          // to the batchSize.
          if (numSamplingRuns < maxNumSamples) {
            retrieveBatchesByRandomSampling(numSamplingRuns + 1)
          } else {
            logger.info(
              s"Stopping resampling after $maxNumSamples sampling attempts with activityMarkerCatchupModeThreshold $activityMarkerCatchupModeThreshold, batch size $batchSize, and parallelism ${context.config.parallelism}"
            )
            Future.successful(tasks)
          }
        } else {
          Future.successful(tasks)
        }
      )
  }

  private def getBatchesByBounds(kind: String, hashMinBoundIncl: Int, hashMaxBoundIncl: Int)(
      implicit tc: TraceContext
  ): Future[Seq[Task]] = {
    val numMarkers = context.config.parallelism * batchSize
    store
      .listFeaturedAppActivityMarkersByContractIdHash(
        hashMinBoundIncl,
        hashMaxBoundIncl,
        numMarkers,
      )
      .map(markers =>
        markers
          .grouped(batchSize)
          .map(Task(kind, _))
          .toSeq
      )
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
      informees = (dsoRules.payload.dso +: task.markers.flatMap(m =>
        Seq(m.payload.provider, m.payload.beneficiary)
      )).toSet
      supportsConvertFeaturedAppActivityMarkerObservers <-
        if (svConfig.convertFeaturedAppActivityMarkerObservers) {
          svTaskContext.packageVersionSupport
            .supportsConvertFeaturedAppActivityMarkerObservers(
              informees.map(PartyId.tryFromProtoPrimitive(_)).toSeq,
              context.clock.now,
            )
            .map(_.supported)
        } else {
          Future.successful(false)
        }
      // Note that we don't group by provider or beneficiary. There is no strong need to do so
      // as we want to
      update = dsoRules.exercise(
        _.exerciseDsoRules_AmuletRules_ConvertFeaturedAppActivityMarkers(
          amuletRules.contractId,
          new amuletrules.AmuletRules_ConvertFeaturedAppActivityMarkers(
            task.markers.map(_.contractId).asJava,
            openMiningRound.contractId,
            Option
              .when(
                supportsConvertFeaturedAppActivityMarkerObservers
              )(informees.toSeq.asJava)
              .toJava,
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
      s"Converted ${task.markers.size} featured app activity markers (retrieved ${task.retrievalKind})."
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
      retrievalKind: String,
      markers: Seq[
        Contract[amulet.FeaturedAppActivityMarker.ContractId, amulet.FeaturedAppActivityMarker]
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("retrievalKind", _.retrievalKind.unquoted),
        param("numMarkers", _.markers.size),
        param("markerCids", _.markers.map(_.contractId.contractId.unquoted)),
      )
  }
}
