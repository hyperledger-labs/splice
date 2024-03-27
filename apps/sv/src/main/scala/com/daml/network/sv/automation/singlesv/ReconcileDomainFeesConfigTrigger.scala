package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.automation.singlesv.ReconcileDomainFeesConfigTrigger.Task
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AmuletConfigSchedule
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to reconcile the domain fees configuration from the AmuletConfig to the domain parameters
  */
class ReconcileDomainFeesConfigTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      globalDomainId <- store.getAmuletRulesDomain()(tc)
      amuletRules <- store.getAmuletRules()
      amuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(context.clock.now)
      state <- participantAdminConnection.getDomainParametersState(globalDomainId)
      updatedConfig = updateDomainParameter(state.mapping.parameters, amuletConfig)

    } yield if (state.mapping.parameters != updatedConfig) Seq(Task(amuletConfig)) else Seq.empty
  }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      globalDomainId <- store.getAmuletRulesDomain()(tc)
      participantId <- participantAdminConnection.getId()
      _ <- participantAdminConnection.ensureDomainParameters(
        globalDomainId,
        updateDomainParameter(_, task.amuletConfig),
        participantId.namespace.fingerprint,
      )
    } yield {
      TaskSuccess(
        s"Successfully reconcile DomainFeesConfig $task"
      )
    }
  }

  override def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    Future.successful(false)
  }

  private def updateDomainParameter(
      existingDomainParameter: DynamicDomainParameters,
      amuletConfig: AmuletConfig[USD],
  ): DynamicDomainParameters = {
    val domainFeesConfig = amuletConfig.globalDomain.fees
    existingDomainParameter.tryUpdate(
      trafficControlParameters =
        existingDomainParameter.trafficControlParameters.map { trafficControl =>
          trafficControl.copy(
            maxBaseTrafficAmount =
              NonNegativeLong.tryCreate(domainFeesConfig.baseRateTrafficLimits.burstAmount),
            readVsWriteScalingFactor =
              PositiveInt.tryCreate(domainFeesConfig.readVsWriteScalingFactor.toInt),
            maxBaseTrafficAccumulationDuration = NonNegativeFiniteDuration.tryOfMicros(
              domainFeesConfig.baseRateTrafficLimits.burstWindow.microseconds
            ),
          )
        }
    )
  }
}
object ReconcileDomainFeesConfigTrigger {
  case class Task(amuletConfig: AmuletConfig[USD]) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = {
      prettyOfClass(param("globalFeesConfig", _.amuletConfig.globalDomain.fees))
    }
  }
}
