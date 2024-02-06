package com.daml.network.sv.automation.singlesv.membership.offboarding

import cats.implicits.showInterpolator
import cats.syntax.traverse.*
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svc.globaldomain.DomainNodeConfig
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, SequencerAdminConnection}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

/** Offboard a sequencer from the current global domain topology state.
  * The offboarding happens if the sequencer is present in the topology state but not in SvcRules.
  * We deliberately do not go through off-boarded member since contrary to the party the sequencer id is mutable
  * so for a single SV we would then need to track a list of sequencer ids that have been used in the past which gets quite messy.
  * Relying on the diff between topology state and SvcRules does allow for a race:
  * An SV might have seen an updated topology state but not yet the updated SvcRules. However for the topology state to be updated a threshold of SVs must have seen the SvcRules change first so the offboarding proposal will just die.
  */
class SvOffboardingSequencerTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
    sequencerAdminConnection: Option[SequencerAdminConnection],
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SequencerId] {

  private val svParty = svcStore.key.svParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SequencerId]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      currentSequencerState <- participantAdminConnection.getSequencerDomainState(
        svcRules.domain
      )
      ourSequencerId <- sequencerAdminConnection.traverse(_.getSequencerId)
    } yield {
      val svcRulesCurrentSequencers = getSequencerIds(
        svcRules.contract.payload.members
          .values()
          .asScala
          .flatMap(_.domainNodes.values().asScala)
      )
      currentSequencerState.mapping.active
        // TODO(#9813) Consider removing the filter for our own sequencer
        // once Canton is fixed.
        .filterNot(e => svcRulesCurrentSequencers.contains(e) || ourSequencerId.contains(e))
    }
  }

  override protected def completeTask(task: SequencerId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      _ <- participantAdminConnection.ensureSequencerDomainStateRemoval(
        svcRules.domain,
        task,
        svParty.uid.namespace.fingerprint,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Removed sequencer $task from domain ${svcRules.domain}")
    }
  }

  override protected def isStaleTask(task: SequencerId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      svcRules <- svcStore.getSvcRules()
      sequencerDomainState <- participantAdminConnection.getSequencerDomainState(
        svcRules.domain
      )
    } yield {
      !sequencerDomainState.mapping.active.contains(task)
    }
  }

  private def getSequencerIds(
      members: Iterable[DomainNodeConfig]
  )(implicit tc: TraceContext) = {
    members
      .flatMap(_.sequencer.toScala)
      .map(_.sequencerId)
      .flatMap(sequencerId =>
        SequencerId
          .fromProtoPrimitive(sequencerId, "sequencerId")
          .fold(
            error => {
              logger.warn(s"Failed to parse sequencer id $sequencerId. $error")
              None
            },
            Some(_),
          )
      )
      .toSeq
  }
}
