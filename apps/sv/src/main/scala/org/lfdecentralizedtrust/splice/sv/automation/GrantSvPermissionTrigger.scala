// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.topology.transaction.ParticipantPermission.Submission
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.SwitchOverTimes

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class GrantSvPermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[GrantSvPermissionTrigger.Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[GrantSvPermissionTrigger.Task]] = {
    import cats.implicits.*
    import com.digitalasset.canton.util.FutureInstances.*
    for {
      dsoRules <- store.getDsoRules()
      tasks <-
        if (!SwitchOverTimes.permissionedSynchronizerScheduled(dsoRules.payload)) {
          Future.successful(Seq.empty)
        } else {
          for {
            confirmations <- store.listSvOnboardingConfirmed()
            inFlightParticipants = confirmations.map(_.payload.svParticipantId)
            establishedParticipants = dsoRules.payload.svs.asScala.values.map(_.participantId).toSeq
            allTargetParticipants = (inFlightParticipants ++ establishedParticipants).distinct
            unpermissionedParticipants <- allTargetParticipants.toList.parFilterA { participantId =>
              participantAdminConnection
                .listParticipantSynchronizerPermission(
                  SynchronizerId.tryFromString(
                    dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
                  ),
                  participantId,
                )
                .map(permissions => !permissions.exists(_.mapping.permission == Submission))
            }
          } yield unpermissionedParticipants.map(GrantSvPermissionTrigger.Task(_))
        }
    } yield tasks
  }

  override protected def completeTask(
      task: GrantSvPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    ParticipantId
      .fromProtoPrimitive(task.participantId, "svParticipantId")
      .fold(
        err => {
          Future.successful(
            TaskSuccess(s"Skipping SV permission task with invalid participantId: $err")
          )
        },
        participantId => {
          for {
            dsoRules <- store.getDsoRules()
            outcome <- {
              val synchronizerId = SynchronizerId.tryFromString(
                dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
              )
              participantAdminConnection
                .ensureParticipantSynchronizerPermission(
                  synchronizerId = synchronizerId,
                  participantId = participantId,
                  permission = Submission,
                  retryFor = RetryFor.Automation,
                )
                .map { _ =>
                  TaskSuccess(
                    s"Granted Submission permission for SV participant $participantId"
                  )
                }
            }

          } yield outcome
        },
      )
  }

  override protected def isStaleTask(
      task: GrantSvPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      dsoRules <- store.getDsoRules()
      synchronizerId = SynchronizerId.tryFromString(
        dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
      )
      permissions <- participantAdminConnection
        .listParticipantSynchronizerPermission(
          synchronizerId,
          task.participantId,
        )
    } yield permissions.exists(_.mapping.permission == Submission)
  }
}

object GrantSvPermissionTrigger {
  final case class Task(
      participantId: String
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("participantId", _.participantId.unquoted)
    )
  }
}
