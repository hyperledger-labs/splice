// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.topology.transaction.ParticipantPermission.Submission
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorunpermission.ValidatorUnpermission
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{AssignedContract, SwitchOverTimes}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class ValidatorUnpermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorUnpermission.ContractId,
      ValidatorUnpermission,
    ](
      store,
      ValidatorUnpermission.COMPANION,
    ) {

  override protected def completeTask(
      unpermission: AssignedContract[ValidatorUnpermission.ContractId, ValidatorUnpermission]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = unpermission.payload

    ParticipantId
      .fromProtoPrimitive(payload.participantId, "participantId")
      .fold(
        err =>
          Future.successful(
            TaskSuccess(s"Skipping ValidatorUnpermission with invalid participantId: $err")
          ),
        participantId => {
          for {
            dsoRules <- store.getDsoRules()
            outcome <-
              if (!SwitchOverTimes.permissionedSynchronizerScheduled(dsoRules.payload)) {
                Future.successful(
                  TaskSuccess(
                    "Skipped because permissionedSynchronizer switchover has not occurred"
                  )
                )
              } else {
                val synchronizerId = SynchronizerId.tryFromString(
                  dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
                )

                if (payload.revoked) {
                  participantAdminConnection
                    .ensureParticipantSynchronizerPermissionRemoved(
                      synchronizerId,
                      participantId,
                    )
                    .map { _ =>
                      TaskSuccess(
                        s"Permanently revoked ParticipantSynchronizerPermission for participant $participantId"
                      )
                    }
                } else {
                  for {
                    existingMappings <- participantAdminConnection
                      .listParticipantSynchronizerPermission(
                        synchronizerId,
                        participantId.filterString,
                      )

                    _ <- participantAdminConnection.ensureParticipantSynchronizerPermission(
                      synchronizerId = synchronizerId,
                      participantId = participantId,
                      permission = Submission,
                      retryFor = RetryFor.Automation,
                      limits = existingMappings.headOption.flatMap(_.mapping.limits),
                      loginAfter = payload.loginAfter.toScala
                        .map(t => CantonTimestamp.assertFromInstant(t)),
                    )
                  } yield TaskSuccess(
                    s"Temporarily revoked ParticipantSynchronizerPermission for participant $participantId (loginAfter: ${payload.loginAfter.toScala
                        .map(t => CantonTimestamp.assertFromInstant(t))})"
                  )
                }
              }
          } yield outcome
        },
      )
  }
}
