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
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_ArchiveValidatorUnblocked
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_ArchiveValidatorUnblocked
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorunblocked.ValidatorUnblocked
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{Contract, SwitchOverTimes}

import scala.concurrent.{ExecutionContext, Future}

class ValidatorUnblockedTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    connection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ValidatorUnblockedTrigger.Task] {

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorUnblockedTrigger.Task]] = {
    for {
      dsoRules <- store.getDsoRules()
      unblockeds <-
        if (SwitchOverTimes.trafficBasedOnboardingScheduled(dsoRules.payload))
          store.listValidatorUnblockeds()
        else Future.successful(Seq.empty)
    } yield unblockeds.map(ValidatorUnblockedTrigger.Task(_))
  }

  override protected def completeTask(
      task: ValidatorUnblockedTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = task.unblocked.payload

    ParticipantId
      .fromProtoPrimitive(payload.participantId, "participantId")
      .fold(
        err => {
          logger.warn(s"Skipping ValidatorUnblocked with invalid participantId: $err")
          Future.successful(
            TaskSuccess(s"Skipping ValidatorUnblocked with invalid participantId: $err")
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
              new SRARC_ArchiveValidatorUnblocked(
                new DsoRules_ArchiveValidatorUnblocked(task.unblocked.contractId)
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
                      "org.lfdecentralizedtrust.splice.sv.confirmArchiveValidatorUnblocked",
                      Seq(svParty, dsoParty),
                      task.unblocked.contractId.contractId,
                    ),
                    deduplicationConfig = DedupOffset(minOffset),
                  )
                  .yieldResult()
                  .map { _ =>
                    TaskSuccess(
                      s"Restored permission for $participantId and created confirmation to archive ValidatorUnblocked"
                    )
                  }
            }
          } yield outcome
        },
      )
  }

  override protected def isStaleTask(
      task: ValidatorUnblockedTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    store.multiDomainAcsStore
      .lookupContractById(ValidatorUnblocked.COMPANION)(task.unblocked.contractId)
      .map(_.isEmpty)
  }
}

object ValidatorUnblockedTrigger {
  final case class Task(
      unblocked: Contract[ValidatorUnblocked.ContractId, ValidatorUnblocked]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("contractId", _.unblocked.contractId.contractId.unquoted),
      param("participantId", _.unblocked.payload.participantId.unquoted),
    )
  }
}
