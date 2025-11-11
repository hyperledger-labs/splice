// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding

import cats.syntax.option.*
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  TopologyAdminConnection,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AllProposals
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

/** Authorized the hosting of the DSO party on a new participant.
  * For this to happen the following rules must be met:
  * - There must exist an OnboardingConfirmed contract for the sv party having the same namespace as the participant
  * - There must exist a proposal for the party hosting which is signed by at least 2 participants (candidate and sponsor).
  * This is done to ensure that the candidate is disconnected from the domain when the party is authorized.
  */
class SvOnboardingPartyToParticipantProposalTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingPartyToParticipantProposalTrigger.Task] {

  import SvOnboardingPartyToParticipantProposalTrigger.Task

  private val svParty = dsoStore.key.svParty
  private val dsoParty: PartyId = dsoStore.key.dsoParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    dsoStore
      .listSvOnboardingConfirmed()
      .map(_.map(_.payload.svParty).map(PartyId.tryFromProtoPrimitive).map(_.uid.namespace))
      .flatMap { confirmedOnboardingNamespacesThatCorrespondToParticipants =>
        // avoid listing all the proposals if no onboarding contract is present
        if (confirmedOnboardingNamespacesThatCorrespondToParticipants.nonEmpty) {
          for {
            dsoRules <- dsoStore.getDsoRules()
            currentlyHostingParticipants <- participantAdminConnection.getPartyToParticipant(
              dsoRules.domain,
              dsoParty,
            )
            dsoPartyHostingProposals <- participantAdminConnection.listPartyToParticipant(
              store = TopologyStoreId.Synchronizer(dsoRules.domain).some,
              filterParty = dsoParty.filterString,
              topologyTransactionType = AllProposals,
            )
            _ = dsoPartyHostingProposals.foreach { proposal =>
              if (
                proposal.base.serial != currentlyHostingParticipants.base.serial + PositiveInt.one
              ) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    show"Topology changed while querying for PartyToParticipant mappings: accepted serial: ${currentlyHostingParticipants.base.serial}, proposal serial: ${proposal.base.serial}"
                  )
                  .asRuntimeException()
              }
            }
          } yield {
            // It is crucial to wait for a proposal from both the candidate and the sponsor.
            // The proposal by the sponsor is only created through the onboard/sv/party-migration/authorize
            // which the candidate calls after having disconnected from the domain.
            // Without this check, the transaction can become valid while the candidate is still connected
            // which then results in all kinds of errors because it does not have an ACS import.
            val proposalsSignedByCandidateAndSponsor =
              dsoPartyHostingProposals.filter(_.base.signedBy.size >= 2)
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
              .map(Task(currentlyHostingParticipants.base.serial, _))
          }
        } else Future.successful(Seq.empty)
      }
  }

  override protected def completeTask(task: Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      _ <- participantAdminConnection
        .ensurePartyToParticipantAdditionProposalWithSerial(
          dsoRules.domain,
          dsoParty,
          task.participantId,
          task.serial,
        )
        .recover { case ex: TopologyAdminConnection.AuthorizedStateChanged =>
          // Turn this into a retryable exception.
          throw Status.FAILED_PRECONDITION.withDescription(ex.getMessage).asRuntimeException()
        }
    } yield {
      TaskSuccess(show"Hosted DSO party $dsoParty on participant $task")
    }
  }

  // We mark it as stale on a serial change. In that case, polling will just bring it up again
  // but importantly we rerun the check that there are already two proposals for the new serial.
  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = for {
    dsoRules <- dsoStore.getDsoRules()
    partyToParticipant <- participantAdminConnection.getPartyToParticipant(
      dsoRules.domain,
      dsoParty,
    )
  } yield {
    partyToParticipant.base.serial != task.serial
  }
}

object SvOnboardingPartyToParticipantProposalTrigger {
  final case class Task(
      serial: PositiveInt, // The serial of the last accepted topology transaction, proposals will have serial + 1
      participantId: ParticipantId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("serial", _.serial),
      param("participantId", _.participantId),
    )
  }
}
