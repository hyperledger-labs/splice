package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AllProposals
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Authorized the hosting of the SVC party on a new participant.
  * For this to happen the following rules must be met:
  * - There must exist an OnboardingConfirmed contract for the sv party having the same namespace as the participant
  * - There must exist a proposal for the party hosting which is signed by at least 2 participants (candidate and sponsor).
  * This is done to ensure that the candidate is disconnected from the domain when the party is authorized.
  */
class SvOnboardingPartyToParticipantProposalTrigger(
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
    svcStore
      .listSvOnboardingConfirmed()
      .map(_.map(_.payload.svParty).map(PartyId.tryFromProtoPrimitive).map(_.uid.namespace))
      .flatMap { confirmedOnboardingNamespacesThatCorrespondToParticipants =>
        // avoid listing all the proposals if no onboarding contract is present
        if (confirmedOnboardingNamespacesThatCorrespondToParticipants.nonEmpty) {
          for {
            svcRules <- svcStore.getSvcRules()
            currentlyHostingParticipants <- participantAdminConnection.getPartyToParticipant(
              svcRules.domain,
              svcParty,
            )
            svcPartyHostingProposals <- participantAdminConnection.listPartyToParticipant(
              filterStore = svcRules.domain.filterString,
              filterParty = svcParty.filterString,
              proposals = AllProposals,
            )
          } yield {
            val proposalsSignedByCandidateAndSponsor =
              svcPartyHostingProposals.filter(_.base.signedBy.size >= 2)
            val proposalsNotSignedBySv = proposalsSignedByCandidateAndSponsor
              .filterNot(_.base.signedBy.contains(svParty.uid.namespace.fingerprint))
            val newlyAddedParticipantIds = proposalsNotSignedBySv
              .map(
                _.mapping.participantIds.diff(currentlyHostingParticipants.mapping.participantIds)
              )
              .collect {
                // for now we support onboarding just one participant at a time
                case Seq(singleParticipant) => singleParticipant
              }
            newlyAddedParticipantIds
              .filter(participantId =>
                confirmedOnboardingNamespacesThatCorrespondToParticipants.contains(
                  participantId.uid.namespace
                )
              )
          }
        } else Future.successful(Seq.empty)
      }
  }

  override protected def completeTask(task: ParticipantId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      _ <- participantAdminConnection.ensurePartyToParticipantAdditionProposal(
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
