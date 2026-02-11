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
import com.digitalasset.canton.admin.api.client.data.DynamicSynchronizerParameters as ConsoleDynamicSynchronizerParameters
import com.digitalasset.canton.time.{PositiveFiniteDuration, PositiveSeconds}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.time.NonNegativeFiniteDuration as InternalNonNegativeFiniteDuration
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags}
import com.digitalasset.canton.topology.transaction.SynchronizerParametersState
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A trigger to reconcile the domain config from the AmuletConfig to the dynamic domain parameters
  */
class ReconcileDynamicSynchronizerParametersTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    config: SvAppBackendConfig,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  private val internalPreparationTimeRecordTimeTolerance =
    InternalNonNegativeFiniteDuration.fromConfig(config.preparationTimeRecordTimeTolerance)

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
      stateHistory <- participantAdminConnection.listSynchronizerParametersStateHistory(
        decentralizedSynchronizerId
      )
      state <- participantAdminConnection.getSynchronizerParametersState(
        decentralizedSynchronizerId
      )
      preparationTimeRecordTimeToleranceTarget = getPreparationTimeRecordTimeToleranceTarget(
        domainTime,
        state,
        stateHistory,
      )
      updatedConfig = updateDomainParameters(
        state.mapping.parameters,
        amuletConfig,
        decentralizedSynchronizerConfig,
        preparationTimeRecordTimeToleranceTarget,
        config.enableFreeConfirmationResponses,
      )
    } yield
      if (state.mapping.parameters != updatedConfig)
        Seq(
          Task(
            amuletConfig,
            decentralizedSynchronizerConfig,
            preparationTimeRecordTimeToleranceTarget,
          )
        )
      else Seq.empty
  }

  // This replicates the logic from Canton’s securely_set_preparation_time_record_time_tolerance macro.
  // The TLDR is that to set the tolerance to 24h, we first need to set the mediator deduplication timeout
  // to 48, then wait 48h and then change the tolerance to 24h.
  private def getPreparationTimeRecordTimeToleranceTarget(
      domainTime: CantonTimestamp,
      currentState: TopologyResult[SynchronizerParametersState],
      stateHistory: Seq[TopologyResult[SynchronizerParametersState]],
  )(implicit tc: TraceContext): Option[InternalNonNegativeFiniteDuration] = {
    val requiredMinMediatorDeduplicationTimeout =
      InternalNonNegativeFiniteDuration.fromConfig(config.preparationTimeRecordTimeTolerance * 2)
    val currentParameters = currentState.mapping.parameters
    if (currentParameters.mediatorDeduplicationTimeout < requiredMinMediatorDeduplicationTimeout) {
      logger.info(
        s"Current mediator deduplication timeout is ${currentParameters.mediatorDeduplicationTimeout}, to change to preparationTimeRecordTimeTolerance $config.preparationTimeRecordTimeTolerance it must be at least $requiredMinMediatorDeduplicationTimeout"
      )
      None
    } else {
      val incompatibleTransactions = stateHistory.filterNot(tx =>
        ConsoleDynamicSynchronizerParameters(tx.mapping.parameters)
          .compatibleWithNewPreparationTimeRecordTimeTolerance(
            config.preparationTimeRecordTimeTolerance
          )
      )
      val lastValidUntil: Instant = incompatibleTransactions
        .map(_.base.validUntil)
        .maxOption
        .flatten
        .getOrElse(CantonTimestamp.MinValue.toInstant)
      val minSafeChangePoint = lastValidUntil.plus(requiredMinMediatorDeduplicationTimeout.duration)
      if (minSafeChangePoint.compareTo(domainTime.toInstant) < 0) {
        Some(internalPreparationTimeRecordTimeTolerance)
      } else {
        logger.info(
          s"preparationTimeRecordTimeTolerance can only be changed to $config.preparationTimeRecordTimeTolerance at $minSafeChangePoint, current domain time is $domainTime"
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
      _ <- participantAdminConnection.ensureDomainParameters(
        decentralizedSynchronizerId,
        updateDomainParameters(
          _,
          task.amuletConfig,
          task.synchronizerConfig,
          task.preparationTimeRecordTimeToleranceTarget,
          config.enableFreeConfirmationResponses,
        ),
        forceChanges =
          if (task.preparationTimeRecordTimeToleranceTarget.isDefined)
            // Canton's validation is not very clever and requires a force flag for all increases
            // even if you waited long enough. We only set it if needed to limit accidental
            // impact of a force flag in other cases.
            ForceFlags(ForceFlag.PreparationTimeRecordTimeToleranceIncrease)
          else
            ForceFlags.none,
      )
    } yield {
      TaskSuccess(
        s"Successfully reconciled DynamicSynchronizerParameters $task"
      )
    }
  }

  override def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    Future.successful(false)
  }

  private def updateDomainParameters(
      existingDomainParameters: DynamicSynchronizerParameters,
      amuletConfig: AmuletConfig[USD],
      synchronizerConfig: Option[SynchronizerConfig],
      preparationTimeRecordTimeToleranceTarget: Option[InternalNonNegativeFiniteDuration],
      enableFreeConfirmationResponses: Boolean,
  ): DynamicSynchronizerParameters = {
    val domainFeesConfig = amuletConfig.decentralizedSynchronizer.fees
    // Make sure that the bootstrap script for the upgrade domain is aligned with any changes made to the
    // dynamic domain parameters here to prevent the soft synchronizer upgrade test from failing
    existingDomainParameters.tryUpdate(
      trafficControlParameters = existingDomainParameters.trafficControl.map { trafficControl =>
        trafficControl.copy(
          maxBaseTrafficAmount =
            NonNegativeLong.tryCreate(domainFeesConfig.baseRateTrafficLimits.burstAmount),
          readVsWriteScalingFactor =
            PositiveInt.tryCreate(domainFeesConfig.readVsWriteScalingFactor.toInt),
          maxBaseTrafficAccumulationDuration = PositiveFiniteDuration.tryOfSeconds(
            domainFeesConfig.baseRateTrafficLimits.burstWindow.microseconds / 1000_000
          ),
          freeConfirmationResponses = enableFreeConfirmationResponses,
        )
      },
      reconciliationInterval = synchronizerConfig
        .flatMap(_.acsCommitmentReconciliationInterval.toScala)
        .fold(
          PositiveSeconds.fromConfig(SvUtil.defaultAcsCommitmentReconciliationInterval)
        )(PositiveSeconds.tryOfSeconds(_)),
      acsCommitmentsCatchUp = Some(SvUtil.defaultAcsCommitmentsCatchUpParameters),
      preparationTimeRecordTimeTolerance = preparationTimeRecordTimeToleranceTarget.getOrElse(
        existingDomainParameters.preparationTimeRecordTimeTolerance
      ),
      mediatorDeduplicationTimeout =
        InternalNonNegativeFiniteDuration.fromConfig(config.mediatorDeduplicationTimeout),
    )
  }
}

object ReconcileSynchronizerFeesConfigTrigger {
  case class Task(
      amuletConfig: AmuletConfig[USD],
      synchronizerConfig: Option[SynchronizerConfig],
      preparationTimeRecordTimeToleranceTarget: Option[InternalNonNegativeFiniteDuration],
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
          "preparationTimeRecordTimeToleranceTarget",
          _.preparationTimeRecordTimeToleranceTarget,
        ),
      )
    }
  }
}
