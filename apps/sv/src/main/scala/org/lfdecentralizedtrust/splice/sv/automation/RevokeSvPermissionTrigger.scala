// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.topology.transaction.ParticipantPermission.Submission
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.SwitchOverTimes

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class RevokeSvPermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[RevokeSvPermissionTrigger.Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[RevokeSvPermissionTrigger.Task]] = {

    for {
      dsoRules <- store.getDsoRules()
      tasks <-
        if (!SwitchOverTimes.permissionedSynchronizerScheduled(dsoRules.payload)) {
          Future.successful(Seq.empty)
        } else {
          val offboardedParticipants =
            dsoRules.payload.offboardedSvs.values().asScala.map(_.participantId).toList.distinct
          val synchronizerId = SynchronizerId.tryFromString(
            dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
          )
          for {
            unrevokedParticipants <- MonadUtil.sequentialTraverse(offboardedParticipants) {
              participantIdStr =>
                ParticipantId.fromProtoPrimitive(participantIdStr, "participantId") match {
                  case Left(err) =>
                    logger.warn(s"Skipping invalid participant ID in offboardedSvs: $err")
                    Future.successful(None)
                  case Right(participantId) =>
                    participantAdminConnection
                      .listParticipantSynchronizerPermission(
                        synchronizerId,
                        participantId.filterString,
                      )
                      .map { permissions =>
                        Option.when(permissions.exists(_.mapping.permission == Submission))(
                          participantIdStr
                        )
                      }
                }
            }
          } yield unrevokedParticipants.flatten.map(RevokeSvPermissionTrigger.Task(_))
        }
    } yield {
      tasks
    }
  }

  override protected def completeTask(
      task: RevokeSvPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    ParticipantId
      .fromProtoPrimitive(task.participantId, "participantId")
      .fold(
        err => {
          Future.successful(
            TaskSuccess(s"Skipping task with invalid participantId: $err")
          )
        },
        participantId => {
          for {
            dsoRules <- store.getDsoRules()
            synchronizerId = SynchronizerId.tryFromString(
              dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
            )
            _ <- participantAdminConnection.ensureParticipantSynchronizerPermissionRemoved(
              synchronizerId,
              participantId,
            )
          } yield TaskSuccess(
            s"Revoked Submission permission for offboarded SV participant $participantId"
          )
        },
      )
  }

  override protected def isStaleTask(
      task: RevokeSvPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    ParticipantId.fromProtoPrimitive(task.participantId, "participantId") match {
      case Left(_) => Future.successful(true)
      case Right(participantId) =>
        for {
          dsoRules <- store.getDsoRules()
          synchronizerId = SynchronizerId.tryFromString(
            dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
          )
          permissions <- participantAdminConnection
            .listParticipantSynchronizerPermission(
              synchronizerId,
              participantId.filterString,
            )
        } yield !permissions.exists(_.mapping.permission == Submission)
    }
  }
}

object RevokeSvPermissionTrigger {
  final case class Task(
      participantId: String
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("participantId", _.participantId.unquoted)
    )
  }
}
