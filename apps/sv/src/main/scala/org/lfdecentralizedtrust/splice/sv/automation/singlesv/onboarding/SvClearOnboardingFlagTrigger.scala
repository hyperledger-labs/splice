// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding

import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.util.MonadUtil
import cats.syntax.option.*
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermission}
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SyncConnectionStalenessCheck

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** Trigger that removes the onboarding flag from participants that host the DSO party.
  *
  * New SV participants hosting the DSO party receive Submission permission on behalf of the DSO party,
  * but with the onboarding flag set to true.
  * This trigger then clears said flag and respectively updates the Party to Participant threshold
  * (onboarding participants do not count towards the threshold).
  * This is done once the corresponding SV party has been onboarded to the DsoRules and the ACS offset has no locking state i.e.
  * at least `mediatorReactionTimeout + confirmationResponseTimeout` domain time has elapsed since the participant was made an observer for the DSO Party.
  *
  * Updating the threshold only after the SV party has been added to the DsoRules guarantees that
  * its participant has reconnected to the domain and is ready to confirm transactions on behalf of the DSO party.
  */
// TODO (#5164): adjust so the onboarding flag is only force-cleared in tests once we're using pv=35
class SvClearOnboardingFlagTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    val participantAdminConnection: ParticipantAdminConnection,
    withPromotionDelay: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      SvClearOnboardingFlagTrigger.Task
    ]
    with SyncConnectionStalenessCheck {

  private val dsoParty = dsoStore.key.dsoParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SvClearOnboardingFlagTrigger.Task]] = {
    logger.info(
      s"Retrieving tasks with the onboarding participant promotion delay ${if (withPromotionDelay) "enabled"
        else "disabled"}"
    )
    for {
      dsoRules <- dsoStore.getDsoRules()
      dsoHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(
          dsoRules.domain,
          dsoParty,
          topologySnapshot = TopologySnapshot.Sequenced,
        )
        .map(_.mapping.participants)
      res <-
        if (
          dsoHostingParticipants.forall(participant =>
            participant.permission == ParticipantPermission.Submission && !participant.onboarding
          )
        ) {
          Future.successful(Seq.empty[SvClearOnboardingFlagTrigger.Task])
        } else if (withPromotionDelay) {
          retrieveTasksWithPromotionDelay(dsoRules)
        } else {
          retrieveTasksWithoutPromotionDelay(dsoHostingParticipants, dsoRules)
        }
    } yield res
  }

  private def retrieveTasksWithoutPromotionDelay(
      dsoHostingParticipants: Seq[HostingParticipant],
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvClearOnboardingFlagTrigger.Task]] = {
    for {
      svParticipants <- getSvParticipants(dsoRules)
    } yield {
      val observingParticipantIds = dsoHostingParticipants
        .filter(_.onboarding)
        .map(_.participantId)
      val svParticipantIds = svParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => svParticipantIds.contains(participantId))
        .map(participantId => SvClearOnboardingFlagTrigger.Task(dsoRules.domain, participantId))
    }
  }

  private def retrieveTasksWithPromotionDelay(
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvClearOnboardingFlagTrigger.Task]] = {
    for {
      synchronizerParametersState <- participantAdminConnection.getSynchronizerParametersState(
        dsoRules.domain
      )
      domainTimeLowerBound <-
        participantAdminConnection
          .getDomainTimeLowerBound(
            dsoRules.domain,
            maxDomainTimeLag = context.config.pollingInterval,
          )
      dsoHostingParticipants <- participantAdminConnection.listPartyToParticipant(
        store = TopologyStoreId.Synchronizer(dsoRules.domain).some,
        filterParty = dsoParty.filterString,
        topologyTransactionType = AuthorizedState,
        timeQuery = TimeQuery.Range(None, None),
      )
      svParticipants <- getSvParticipants(dsoRules)
    } yield {
      val orderedDsoHostingParticipants = dsoHostingParticipants
        .sortBy(
          _.base.validFrom
        ) // redundant sorting to ensure that the last mapping is the most recent one
      val domainParameters = synchronizerParametersState.mapping.parameters
      val delay = domainParameters.mediatorReactionTimeout.duration
        .plus(domainParameters.confirmationResponseTimeout.duration)
      val observingParticipantIds = orderedDsoHostingParticipants.lastOption match {
        case Some(dsoHosting) =>
          dsoHosting.mapping.participants
            .filter(_.onboarding)
            .map(_.participantId)
        case _ =>
          Seq.empty[ParticipantId]
      }
      val svParticipantIds = svParticipants.map(_.participantId)
      observingParticipantIds
        .filter(participantId => svParticipantIds.contains(participantId))
        .filter(participantId =>
          // Find the first mapping that added the participant and count from there.
          // Note that this relies on the same participant not being readded which is given by our current onboarding procedures.
          orderedDsoHostingParticipants.find(
            _.mapping.participantIds.contains(participantId)
          ) match {
            case Some(dsoHosting) =>
              domainTimeLowerBound.timestamp.toInstant
                .isAfter(dsoHosting.base.validFrom.plus(delay))
            case _ => false
          }
        )
        .map(participantId =>
          SvClearOnboardingFlagTrigger
            .Task(dsoRules.domain, participantId)
        )
    }
  }

  private def getSvParticipants(
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules]
  )(implicit tc: TraceContext) = {
    val svs = dsoRules.contract.payload.svs
      .keySet()
      .asScala
      .map(PartyId.tryFromProtoPrimitive)
      .toSeq
    MonadUtil
      .parTraverseWithLimit(PositiveInt.tryCreate(16))(svs) { svParty =>
        participantAdminConnection
          .getPartyToParticipant(
            dsoRules.domain,
            svParty,
            topologySnapshot = TopologySnapshot.Sequenced,
          )
      }
      .map(_.flatMap(_.mapping.participants))
  }

  override protected def completeTask(
      task: SvClearOnboardingFlagTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    logger.info(
      s"Proposing participant ${task.participantId} be promoted to Submission rights for the DSO party, will wait for it to take effect."
    )
    participantAdminConnection
      .ensureHostingParticipantIsPromotedToSubmitterAndIsNotOnboarding(
        task.synchronizerId,
        dsoParty,
        task.participantId,
        RetryFor.ClientCalls,
      )
      .map(_ =>
        TaskSuccess(
          show"Onboarding flag cleared for Participant ${task.participantId} for the DSO party"
        )
      )
  }

  override protected def isStaleTask(
      task: SvClearOnboardingFlagTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      dsoHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(
          dsoRules.domain,
          dsoParty,
          topologySnapshot = TopologySnapshot.Sequenced,
        )
        .map(_.mapping.participants)
      notConnected <- isNotConnectedToSync()
    } yield {
      dsoHostingParticipants
        .contains(
          HostingParticipant(
            task.participantId,
            ParticipantPermission.Submission,
            onboarding = false,
          )
        ) || notConnected
    }
  }

}

object SvClearOnboardingFlagTrigger {
  case class Task(synchronizerId: SynchronizerId, participantId: ParticipantId)
      extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("synchronizerId", _.synchronizerId),
        param("participantId", _.participantId),
      )
  }
}
