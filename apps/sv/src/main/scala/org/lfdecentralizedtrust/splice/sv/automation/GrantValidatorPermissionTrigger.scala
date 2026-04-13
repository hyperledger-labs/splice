// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.ParticipantId
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
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract

import scala.concurrent.{ExecutionContext, Future}

class GrantValidatorPermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      splice.validatorpermission.ValidatorPermission.ContractId,
      splice.validatorpermission.ValidatorPermission,
    ](
      store,
      splice.validatorpermission.ValidatorPermission.COMPANION,
    ) {

  override protected def completeTask(
      task: AssignedContract[
        splice.validatorpermission.ValidatorPermission.ContractId,
        splice.validatorpermission.ValidatorPermission,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = task.payload
    val synchronizerId = task.domain
    val participantId = ParticipantId.tryFromProtoPrimitive(payload.validatorParticipantId)
    val loginAfter: Option[CantonTimestamp] =
      if (payload.loginAfter.isPresent)
        Some(CantonTimestamp.assertFromInstant(payload.loginAfter.get()))
      else
        None
    for {
      _ <-
        if (payload.isRevoked) {
          logger.info(
            s"Skipping ValidatorPermission for $participantId because the contract is marked as revoked."
          )
          // TODO(#4998): add revocation trigger
          Future.unit
        } else {
          logger.info(
            s"Proposing ParticipantSynchronizerPermission (Submission) for $participantId on synchronizer $synchronizerId"
          )
          participantAdminConnection.ensureParticipantSynchronizerPermission(
            synchronizerId = synchronizerId,
            participantId = participantId,
            permission = Submission,
            loginAfter = loginAfter,
            retryFor = RetryFor.Automation,
          )
        }
    } yield TaskSuccess(s"Processed ValidatorPermission for participant $participantId")
  }
}
