// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

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
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{AssignedContract, SwitchOverTimes}
import com.digitalasset.canton.topology.Member

import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil

import scala.jdk.OptionConverters.*

class GrantValidatorPermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      MemberTraffic.ContractId,
      MemberTraffic,
    ](
      store,
      MemberTraffic.COMPANION,
    ) {

  override protected def completeTask(
      task: AssignedContract[
        MemberTraffic.ContractId,
        MemberTraffic,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = task.payload

    Member
      .fromProtoPrimitive_(payload.memberId)
      .fold(
        err => {
          Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid memberId: $err"))
        },
        memberId => {
          val synchronizerId =
            com.digitalasset.canton.topology.SynchronizerId.tryFromString(payload.synchronizerId)
          val participantId = ParticipantId.tryFromProtoPrimitive(payload.memberId)

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
                val synchronizerConfig = Option(
                  dsoRules.payload.config.decentralizedSynchronizer.synchronizers
                    .get(dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId)
                )
                val minMemberTrafficToOnboardValidator = synchronizerConfig
                  .flatMap(_.minMemberTrafficToOnboardValidator.toScala)
                  .map(_.longValue())
                  .getOrElse(SvUtil.DefaultMinMemberTrafficToOnboardValidator)

                for {
                  totalPurchasedTraffic <- store.getTotalPurchasedMemberTraffic(
                    StoreMemberId(memberId),
                    synchronizerId,
                  )
                  unpermissions <- store.listValidatorUnpermissions(payload.memberId)

                  _ <-
                    if (
                      totalPurchasedTraffic >= minMemberTrafficToOnboardValidator && unpermissions.isEmpty
                    ) {
                      participantAdminConnection.ensureParticipantSynchronizerPermission(
                        synchronizerId = synchronizerId,
                        participantId = participantId,
                        permission = Submission,
                        retryFor = RetryFor.Automation,
                      )
                    } else {
                      Future.unit
                    }
                } yield {
                  if (unpermissions.nonEmpty) {
                    TaskSuccess(
                      s"Skipped Submission permission for participant $participantId because a ValidatorUnpermission contract exists."
                    )
                  } else if (totalPurchasedTraffic >= minMemberTrafficToOnboardValidator) {
                    TaskSuccess(
                      s"Granted Submission permission for participant $participantId (Total Purchased: $totalPurchasedTraffic >= Threshold: $minMemberTrafficToOnboardValidator)"
                    )
                  } else {
                    TaskSuccess(
                      s"Skipped Submission permission for participant $participantId (Total Purchased: $totalPurchasedTraffic < Threshold: $minMemberTrafficToOnboardValidator)"
                    )
                  }
                }
              }
          } yield outcome
        },
      )
  }
}
