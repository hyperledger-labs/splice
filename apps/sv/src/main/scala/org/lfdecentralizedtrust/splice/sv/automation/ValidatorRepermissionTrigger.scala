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
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_ArchiveValidatorRepermission
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_ArchiveValidatorRepermission
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorrepermission.ValidatorRepermission
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{Contract, SwitchOverTimes}

import scala.concurrent.{ExecutionContext, Future}

class ValidatorRepermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    connection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ValidatorRepermissionTrigger.Task] {

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorRepermissionTrigger.Task]] = {
    for {
      dsoRules <- store.getDsoRules()
      repermissions <-
        if (SwitchOverTimes.permissionedSynchronizerScheduled(dsoRules.payload))
          store.listValidatorRepermissions()
        else Future.successful(Seq.empty)
    } yield repermissions.map(ValidatorRepermissionTrigger.Task(_))
  }

  override protected def completeTask(
      task: ValidatorRepermissionTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = task.repermission.payload

    ParticipantId
      .fromProtoPrimitive(payload.participantId, "participantId")
      .fold(
        err => {
          logger.warn(s"Skipping ValidatorRepermission with invalid participantId: $err")
          Future.successful(
            TaskSuccess(s"Skipping ValidatorRepermission with invalid participantId: $err")
          )
        },
        participantId => {
          for {
            dsoRules <- store.getDsoRules()
            synchronizerId = SynchronizerId.tryFromString(
              dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
            )

            _ <- participantAdminConnection.ensureParticipantSynchronizerPermission(
              synchronizerId = synchronizerId,
              participantId = participantId,
              permission = Submission,
              retryFor = RetryFor.Automation,
            )

            action = new ARC_DsoRules(
              new SRARC_ArchiveValidatorRepermission(
                new DsoRules_ArchiveValidatorRepermission(task.repermission.contractId)
              )
            )

            confirmationResult <- store.lookupConfirmationByActionWithOffset(svParty, action)

            outcome <- confirmationResult.value match {
              case Some(_) =>
                Future.successful(
                  TaskSuccess(
                    s"Restored permission for $participantId, but skipping confirmation as it already exists."
                  )
                )
              case None =>
                val minOffset = confirmationResult.offset
                val cmd = dsoRules.exercise(
                  _.exerciseDsoRules_ConfirmAction(
                    svParty.toProtoPrimitive,
                    action,
                  )
                )

                connection
                  .submit(
                    actAs = Seq(svParty),
                    readAs = Seq(dsoParty),
                    update = cmd,
                  )
                  .withDedup(
                    commandId = SpliceLedgerConnection.CommandId(
                      "org.lfdecentralizedtrust.splice.sv.confirmArchiveValidatorRepermission",
                      Seq(svParty, dsoParty),
                      task.repermission.contractId.contractId,
                    ),
                    deduplicationConfig = DedupOffset(minOffset),
                  )
                  .yieldResult()
                  .map { _ =>
                    TaskSuccess(
                      s"Restored permission for $participantId and created confirmation to archive ValidatorRepermission"
                    )
                  }
            }
          } yield outcome
        },
      )
  }

  override protected def isStaleTask(
      task: ValidatorRepermissionTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    store.multiDomainAcsStore
      .lookupContractById(ValidatorRepermission.COMPANION)(task.repermission.contractId)
      .map(_.isEmpty)
  }
}

object ValidatorRepermissionTrigger {
  final case class Task(
      repermission: Contract[ValidatorRepermission.ContractId, ValidatorRepermission]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("contractId", _.repermission.contractId.contractId.unquoted),
      param("participantId", _.repermission.payload.participantId.unquoted),
    )
  }
}
