// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerConfig
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReconcileSynchronizerFeesConfigTrigger.Task
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule
import com.digitalasset.canton.admin.api.client.data.{
  DynamicDomainParameters as ConsoleDynamicDomainParameters
}
import com.digitalasset.canton.time.{PositiveFiniteDuration, PositiveSeconds}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.time.{NonNegativeFiniteDuration as InternalNonNegativeFiniteDuration}
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags}
import com.digitalasset.canton.topology.transaction.DomainParametersState
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import org.apache.pekko.stream.Materializer

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A trigger to reconcile the domain config from the AmuletConfig to the dynamic domain parameters
  */
class ReconcileDynamicDomainParametersTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    submissionTimeRecordTimeTolerance: NonNegativeFiniteDuration,
    mediatorDeduplicationTimeout: NonNegativeFiniteDuration,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  private val internalSubmissionTimeRecordTimeTolerance =
    InternalNonNegativeFiniteDuration.fromConfig(submissionTimeRecordTimeTolerance)

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      decentralizedSynchronizerId <- store.getAmuletRulesDomain()(tc)
      amuletRules <- store.getAmuletRules()
      amuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(context.clock.now)
      dsoRules <- store.getDsoRules()
      domainTime <- participantAdminConnection
        .getDomainTimeLowerBound(
          dsoRules.domain,
          maxDomainTimeLag = context.config.pollingInterval,
        )
        .map(_.timestamp)
      decentralizedSynchronizerConfig =
        dsoRules.payload.config.decentralizedSynchronizer.synchronizers.asScala
          .get(decentralizedSynchronizerId.toProtoPrimitive)
      stateHistory <- participantAdminConnection.listDomainParametersState(
        decentralizedSynchronizerId
      )
      state <- participantAdminConnection.getDomainParametersState(decentralizedSynchronizerId)
      submissionTimeRecordTimeToleranceTarget = getSubmissionTimeRecordTimeToleranceTarget(
        domainTime,
        state,
        stateHistory,
      )
      updatedConfig = updateDomainParameters(
        state.mapping.parameters,
        amuletConfig,
        decentralizedSynchronizerConfig,
        submissionTimeRecordTimeToleranceTarget,
      )
    } yield
      if (state.mapping.parameters != updatedConfig)
        Seq(
          Task(
            amuletConfig,
            decentralizedSynchronizerConfig,
            submissionTimeRecordTimeToleranceTarget,
          )
        )
      else Seq.empty
  }

  // This replicates the logic from Canton’s securely_set_submission_time_record_time_tolerance macro.
  // The TLDR is that to set the tolerance to 24h, we first need to set the mediator deduplication timeout
  // to 48, then wait 48h and then change the tolerance to 24h.
  private def getSubmissionTimeRecordTimeToleranceTarget(
      domainTime: CantonTimestamp,
      currentState: TopologyResult[DomainParametersState],
      stateHistory: Seq[TopologyResult[DomainParametersState]],
  )(implicit tc: TraceContext): Option[InternalNonNegativeFiniteDuration] = {
    val requiredMinMediatorDeduplicationTimeout =
      InternalNonNegativeFiniteDuration.fromConfig(submissionTimeRecordTimeTolerance * 2)
    val currentParameters = currentState.mapping.parameters
    if (currentParameters.mediatorDeduplicationTimeout < requiredMinMediatorDeduplicationTimeout) {
      logger.info(
        s"Current mediator deduplication timeout is ${currentParameters.mediatorDeduplicationTimeout}, to change to submissionTimeRecordTimeTolerance $submissionTimeRecordTimeTolerance it must be at least $requiredMinMediatorDeduplicationTimeout"
      )
      None
    } else {
      val incompatibleTransactions = stateHistory.filterNot(tx =>
        ConsoleDynamicDomainParameters(tx.mapping.parameters)
          .compatibleWithNewSubmissionTimeRecordTimeTolerance(submissionTimeRecordTimeTolerance)
      )
      val lastValidUntil: Instant = incompatibleTransactions
        .map(_.base.validUntil)
        .maxOption
        .flatten
        .getOrElse(CantonTimestamp.MinValue.toInstant)
      val minSafeChangePoint = lastValidUntil.plus(requiredMinMediatorDeduplicationTimeout.duration)
      if (minSafeChangePoint.compareTo(domainTime.toInstant) < 0) {
        Some(internalSubmissionTimeRecordTimeTolerance)
      } else {
        logger.info(
          s"submissionTimeRecordTimeTolerance can only be changed to $submissionTimeRecordTimeTolerance at $minSafeChangePoint, current domain time is $domainTime"
        )
        None
      }
    }
  }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      decentralizedSynchronizerId <- store.getAmuletRulesDomain()(tc)
      participantId <- participantAdminConnection.getId()
      _ <- participantAdminConnection.ensureDomainParameters(
        decentralizedSynchronizerId,
        updateDomainParameters(
          _,
          task.amuletConfig,
          task.synchronizerConfig,
          task.submissionTimeRecordTimeToleranceTarget,
        ),
        participantId.namespace.fingerprint,
        forceChanges =
          if (task.submissionTimeRecordTimeToleranceTarget.isDefined)
            // Canton's validation is not very clever and requires a force flag for all increases
            // even if you waited long enough. We only set it if needed to limit accidental
            // impact of a force flag in other cases.
            ForceFlags(ForceFlag.SubmissionTimeRecordTimeToleranceIncrease)
          else
            ForceFlags.none,
      )
    } yield {
      TaskSuccess(
        s"Successfully reconcile SynchronizerFeesConfig $task"
      )
    }
  }

  override def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    Future.successful(false)
  }

  private def updateDomainParameters(
      existingDomainParameters: DynamicDomainParameters,
      amuletConfig: AmuletConfig[USD],
      synchronizerConfig: Option[SynchronizerConfig],
      submissionTimeRecordTimeToleranceTarget: Option[InternalNonNegativeFiniteDuration],
  ): DynamicDomainParameters = {
    val domainFeesConfig = amuletConfig.decentralizedSynchronizer.fees
    // Make sure that the bootstrap script for the upgrade domain is aligned with any changes made to the
    // dynamic domain parameters here to prevent the soft synchronizer upgrade test from failing
    existingDomainParameters.tryUpdate(
      trafficControlParameters =
        existingDomainParameters.trafficControlParameters.map { trafficControl =>
          trafficControl.copy(
            maxBaseTrafficAmount =
              NonNegativeLong.tryCreate(domainFeesConfig.baseRateTrafficLimits.burstAmount),
            readVsWriteScalingFactor =
              PositiveInt.tryCreate(domainFeesConfig.readVsWriteScalingFactor.toInt),
            maxBaseTrafficAccumulationDuration = PositiveFiniteDuration.tryOfSeconds(
              domainFeesConfig.baseRateTrafficLimits.burstWindow.microseconds / 1000_000
            ),
          )
        },
      reconciliationInterval = synchronizerConfig
        .flatMap(_.acsCommitmentReconciliationInterval.toScala)
        .fold(
          PositiveSeconds.fromConfig(SvUtil.defaultAcsCommitmentReconciliationInterval)
        )(PositiveSeconds.tryOfSeconds(_)),
      acsCommitmentsCatchUpConfigParameter = Some(SvUtil.defaultAcsCommitmentsCatchUpConfig),
      submissionTimeRecordTimeTolerance = submissionTimeRecordTimeToleranceTarget.getOrElse(
        existingDomainParameters.submissionTimeRecordTimeTolerance
      ),
      mediatorDeduplicationTimeout =
        InternalNonNegativeFiniteDuration.fromConfig(mediatorDeduplicationTimeout),
    )
  }
}

object ReconcileSynchronizerFeesConfigTrigger {
  case class Task(
      amuletConfig: AmuletConfig[USD],
      synchronizerConfig: Option[SynchronizerConfig],
      submissionTimeRecordTimeToleranceTarget: Option[InternalNonNegativeFiniteDuration],
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("globalFeesConfig", _.amuletConfig.decentralizedSynchronizer.fees),
        param(
          "acsCommitmentreconciliationInterval",
          _.synchronizerConfig.flatMap(_.acsCommitmentReconciliationInterval.toScala),
        ),
        paramIfDefined(
          "submissionTimeRecordTimeToleranceTarget",
          _.submissionTimeRecordTimeToleranceTarget,
        ),
      )
    }
  }
}
