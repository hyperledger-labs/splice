package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.automation.*
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermission}
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
  * once the corresponding SV party has been onboarded to the SvcRules and the ACS offset has no locking state i.e.
  * at least `mediatorReactionTimeout + confirmationResponseTimeout` domain time has elapsed since the participant was made an observer for the SVC Party.
  *
  * Updating the threshold only after the SV party has been added to the SvcRules guarantees that
  * its participant has reconnected to the domain and is ready to confirm transactions on behalf of the SVC party.
  */
class SvOnboardingPromoteParticipantToSubmitterTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
    withPromotionDelay: Boolean,
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
    logger.info(
      s"Retrieving tasks with the onboarding participant promotion delay ${if (withPromotionDelay) "enabled"
        else "disabled"}"
    )
    for {
      svcRules <- svcStore.getSvcRules()
      svcHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(svcRules.domain, svcParty)
        .map(_.mapping.participants)
      res <-
        if (svcHostingParticipants.forall(_.permission == ParticipantPermission.Submission)) {
          Future.successful(Seq.empty[SvOnboardingPromoteParticipantToSubmitterTrigger.Task])
        } else if (withPromotionDelay) {
          retrieveTasksWithPromotionDelay(svcRules)
        } else {
          retrieveTasksWithoutPromotionDelay(svcHostingParticipants, svcRules)
        }
    } yield res
  }

  private def retrieveTasksWithoutPromotionDelay(
      svcHostingParticipants: Seq[HostingParticipant],
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvOnboardingPromoteParticipantToSubmitterTrigger.Task]] = {
    for {
      svcMemberParticipants <- getSvcMemberParticipants(svcRules)
    } yield {
      val observingParticipantIds = svcHostingParticipants
        .filter(_.permission == ParticipantPermission.Observation)
        .map(_.participantId)
      val svcMemberParticipantIds = svcMemberParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => svcMemberParticipantIds.contains(participantId))
        .map(participantId =>
          SvOnboardingPromoteParticipantToSubmitterTrigger.Task(svcRules.domain, participantId)
        )
    }
  }

  private def retrieveTasksWithPromotionDelay(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvOnboardingPromoteParticipantToSubmitterTrigger.Task]] = {
    for {
      domainParametersState <- participantAdminConnection.getDomainParametersState(svcRules.domain)
      domainTimeLowerBound <-
        participantAdminConnection
          .getDomainTimeLowerBound(
            svcRules.domain,
            maxDomainTimeLag = context.config.pollingInterval,
          )
      svcHostingParticipants <- participantAdminConnection.listPartyToParticipant(
        filterStore = svcRules.domain.filterString,
        filterParty = svcParty.filterString,
        proposals = AuthorizedState,
        timeQuery = TimeQuery.Range(None, None),
      )
      svcMemberParticipants <- getSvcMemberParticipants(svcRules)
    } yield {
      val orderedSvcHostingParticipants = svcHostingParticipants
        .sortBy(
          _.base.validFrom
        ) // redundant sorting to ensure that the last mapping is the most recent one
      val domainParameters = domainParametersState.mapping.parameters
      val delay = domainParameters.mediatorReactionTimeout.duration
        .plus(domainParameters.confirmationResponseTimeout.duration)
      val observingParticipantIds = orderedSvcHostingParticipants.lastOption match {
        case Some(svcHosting) =>
          svcHosting.mapping.participants
            .filter(_.permission == ParticipantPermission.Observation)
            .map(_.participantId)
        case _ =>
          Seq.empty[ParticipantId]
      }
      val svcMemberParticipantIds = svcMemberParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => svcMemberParticipantIds.contains(participantId))
        .filter(participantId =>
          // Find the first mapping that added the participant and count from there.
          // Note that this relies on the same participant not being readded which is given by our current onboarding procedures.
          orderedSvcHostingParticipants.find(
            _.mapping.participantIds.contains(participantId)
          ) match {
            case Some(svcHosting) =>
              domainTimeLowerBound.timestamp.toInstant
                .isAfter(svcHosting.base.validFrom.plus(delay))
            case _ => false
          }
        )
        .map(participantId =>
          SvOnboardingPromoteParticipantToSubmitterTrigger
            .Task(svcRules.domain, participantId)
        )
    }
  }

  private def getSvcMemberParticipants(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules]
  )(implicit tc: TraceContext) = {
    val svcMembers = svcRules.contract.payload.members
      .keySet()
      .asScala
      .map(PartyId.tryFromProtoPrimitive)
      .toSeq
    svcMembers
      .parTraverse { svParty =>
        participantAdminConnection
          .getPartyToParticipant(svcRules.domain, svParty)
      }
      .map(_.flatMap(_.mapping.participants))
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
        .contains(HostingParticipant(task.participantId, ParticipantPermission.Submission))
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
