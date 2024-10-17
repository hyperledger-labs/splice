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
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReconcileSynchronizerFeesConfigTrigger.Task
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule
import com.digitalasset.canton.time.{NonNegativeFiniteDuration, PositiveSeconds}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A trigger to reconcile the domain config from the AmuletConfig to the dynamic domain parameters
  */
class ReconcileDynamicDomainParametersTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      decentralizedSynchronizerId <- store.getAmuletRulesDomain()(tc)
      amuletRules <- store.getAmuletRules()
      amuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(context.clock.now)
      dsoRules <- store.getDsoRules()
      decentralizedSynchronizerConfig =
        dsoRules.payload.config.decentralizedSynchronizer.synchronizers.asScala
          .get(decentralizedSynchronizerId.toProtoPrimitive)
      state <- participantAdminConnection.getDomainParametersState(decentralizedSynchronizerId)
      updatedConfig = updateDomainParameters(
        state.mapping.parameters,
        amuletConfig,
        decentralizedSynchronizerConfig,
      )
    } yield
      if (state.mapping.parameters != updatedConfig)
        Seq(Task(amuletConfig, decentralizedSynchronizerConfig))
      else Seq.empty
  }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      decentralizedSynchronizerId <- store.getAmuletRulesDomain()(tc)
      participantId <- participantAdminConnection.getId()
      _ <- participantAdminConnection.ensureDomainParameters(
        decentralizedSynchronizerId,
        updateDomainParameters(_, task.amuletConfig, task.synchronizerConfig),
        participantId.namespace.fingerprint,
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
            maxBaseTrafficAccumulationDuration = NonNegativeFiniteDuration.tryOfMicros(
              domainFeesConfig.baseRateTrafficLimits.burstWindow.microseconds
            ),
          )
        },
      reconciliationInterval = synchronizerConfig
        .flatMap(_.acsCommitmentReconciliationInterval.toScala)
        .fold(
          PositiveSeconds.fromConfig(SvUtil.defaultAcsCommitmentReconciliationInterval)
        )(PositiveSeconds.tryOfSeconds(_)),
      acsCommitmentsCatchUpConfigParameter = Some(SvUtil.defaultAcsCommitmentsCatchUpConfig),
    )
  }
}

object ReconcileSynchronizerFeesConfigTrigger {
  case class Task(amuletConfig: AmuletConfig[USD], synchronizerConfig: Option[SynchronizerConfig])
      extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("globalFeesConfig", _.amuletConfig.decentralizedSynchronizer.fees),
        param(
          "acsCommitmentreconciliationInterval",
          _.synchronizerConfig.flatMap(_.acsCommitmentReconciliationInterval.toScala),
        ),
      )
    }
  }
}
