package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.automation.*
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermissionX}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** Trigger that promotes participants from observers to submitters for the SVC party.
  *
  * New SV participants hosting the SVC party only receive Observation permission on behalf of the SVC party.
  * This trigger then promotes them to submitter status and respectively updates the Party to Participant threshold
  * once the corresponding SV party has been onboarded to the SvcRules.
  *
  * Updating the threshold only after the SV party has been added to the SvcRules guarantees that
  * its participant has reconnected to the domain and is ready to confirm transactions on behalf of the SVC party.
  */
class SvOnboardingPromoteParticipantToSubmitterTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      SvOnboardingPromoteParticipantToSubmitterTrigger.Task
    ] {

  private val svcParty = svcStore.key.svcParty
  private val svParty = svcStore.key.svParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SvOnboardingPromoteParticipantToSubmitterTrigger.Task]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      svcHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(svcRules.domain, svcParty)
        .map(_.mapping.participants)
      svcMembers = svcRules.contract.payload.members
        .keySet()
        .asScala
        .map(PartyId.tryFromProtoPrimitive)
        .toSeq
      svcMemberParticipants <- svcMembers
        .parTraverse { svParty =>
          participantAdminConnection
            .getPartyToParticipant(svcRules.domain, svParty)
        }
        .map(_.flatMap(_.mapping.participants))
    } yield {
      val observingParticipantIds = svcHostingParticipants
        .filter(_.permission == ParticipantPermissionX.Observation)
        .map(_.participantId)
      val svcMemberParticipantIds = svcMemberParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => svcMemberParticipantIds.contains(participantId))
        .map(participantId =>
          SvOnboardingPromoteParticipantToSubmitterTrigger.Task(svcRules.domain, participantId)
        )
    }
  }

  override protected def completeTask(
      task: SvOnboardingPromoteParticipantToSubmitterTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    logger.info(
      s"Proposing participant ${task.participantId} be promoted to Submission rights for the SVC party, will wait for it to take effect."
    )
    participantAdminConnection
      .ensureHostingParticipantIsPromotedToSubmitter(
        task.domainId,
        svcParty,
        task.participantId,
        svParty.uid.namespace.fingerprint,
        RetryFor.ClientCalls,
      )
      .map(_ =>
        TaskSuccess(
          show"Participant ${task.participantId} was promoted to Submission rights for the SVC party"
        )
      )
  }

  override protected def isStaleTask(
      task: SvOnboardingPromoteParticipantToSubmitterTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      svcRules <- svcStore.getSvcRules()
      svcHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(svcRules.domain, svcParty)
        .map(_.mapping.participants)
    } yield {
      svcHostingParticipants
        .contains(HostingParticipant(task.participantId, ParticipantPermissionX.Submission))
    }
  }

}

object SvOnboardingPromoteParticipantToSubmitterTrigger {
  case class Task(domainId: DomainId, participantId: ParticipantId) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domainId", _.domainId), param("participantId", _.participantId))
  }
}
