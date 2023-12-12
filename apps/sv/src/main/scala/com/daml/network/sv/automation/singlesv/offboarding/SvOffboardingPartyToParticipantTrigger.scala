package com.daml.network.sv.automation.singlesv.offboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.SetHasAsScala

/** Offboard a participant from the hosting of the SVC party.
  * - Runs when a member part of offboardedMembers still exist on the partyToParticipantX mapping
  */
class SvOffboardingPartyToParticipantProposalTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ParticipantId] {

  private val svParty = svcStore.key.svParty
  private val svcParty: PartyId = svcStore.key.svcParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ParticipantId]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      offboardedMembers = svcRules.contract.payload.offboardedMembers
      offboardedSvNamespaces = offboardedMembers
        .keySet()
        .asScala
        .map(PartyId.tryFromProtoPrimitive)
        .map(_.uid.namespace)
        .toSeq
      currentHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(
          svcRules.domain,
          svcParty,
        )
        .map(_.mapping.participants)
    } yield {
      val currentParticipantIds = currentHostingParticipants.map(_.participantId)
      val participantsToBeRemoved =
        currentParticipantIds.filter(e => offboardedSvNamespaces.contains(e.uid.namespace))
      participantsToBeRemoved
    }
  }

  override protected def completeTask(task: ParticipantId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    logger.info(s"Removing participant with namespace ${task} from the hosting of the SVC party")
    for {
      svcRules <- svcStore.getSvcRules()
      _ <- participantAdminConnection.ensurePartyToParticipantRemovalProposal(
        svcRules.domain,
        svcParty,
        task,
        svParty.uid.namespace.fingerprint,
      )
    } yield {
      TaskSuccess(show"Hosted SVC party $svcParty on participant $task")
    }
  }

  // proposing is safe and it checks when running the task so no need to duplicate the same check here
  override protected def isStaleTask(task: ParticipantId)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}
