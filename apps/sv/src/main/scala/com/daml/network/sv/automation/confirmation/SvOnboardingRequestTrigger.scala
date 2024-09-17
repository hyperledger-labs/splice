// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.confirmation

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import com.daml.network.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules,
  DsoRules_ConfirmSvOnboarding,
}
import com.daml.network.codegen.java.splice.svonboarding.SvOnboardingRequest
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvDsoStore}
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class SvOnboardingRequestTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    svStore: SvSvStore,
    config: SvAppBackendConfig,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SvOnboardingRequest.ContractId,
      SvOnboardingRequest,
    ](
      dsoStore,
      SvOnboardingRequest.COMPANION,
    ) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  private def dsoRulesConfirmSvOnboardingAction(
      candidateParty: PartyId,
      candidateName: String,
      weightBps: Long,
      candidateParticipantId: String,
      reason: String,
  ): ActionRequiringConfirmation = {
    new ARC_DsoRules(
      new SRARC_ConfirmSvOnboarding(
        new DsoRules_ConfirmSvOnboarding(
          candidateParty.toProtoPrimitive,
          candidateName,
          candidateParticipantId,
          weightBps,
          reason,
        )
      )
    )
  }

  private def attemptConfirmation(
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      svOnboarding: AssignedContract[
        SvOnboardingRequest.ContractId,
        SvOnboardingRequest,
      ],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val approval = SvApp
      .isApprovedSvIdentity(
        svOnboarding.payload.candidateName,
        PartyId.tryFromProtoPrimitive(svOnboarding.payload.candidateParty),
        svOnboarding.payload.token,
        config,
        svStore,
        logger,
      )
    for {
      (party, name, weightBps) <- approval match {
        case Left(reason) => {
          // we fail so that the task is retried; it's possible that an approval happens eventually
          // TODO(#4073) Add a warning log
          Future.failed(
            Status.NOT_FOUND
              .withDescription(s"Could not match with an approved SV identity; reason: $reason")
              .asRuntimeException()
          )
        }
        case Right((party, name, weightBps)) => Future.successful((party, name, weightBps))
      }
      outcome <-
        if (SvApp.isSv(name, party, dsoRules)) {
          Future.successful(
            TaskSuccess(
              s"skipping as SV $name is already an SV"
            )
          )
        } else {
          SvApp.validateCandidateSv(
            party,
            name,
            dsoRules,
          ) match {
            case Left(err) =>
              Future.failed(err.asRuntimeException())
            case Right(_) =>
              confirm(
                party,
                name,
                weightBps,
                svOnboarding.payload.candidateParticipantId,
                svOnboarding.payload.token,
                dsoRules,
              )
          }
        }
    } yield outcome
  }

  override def completeTask(
      svOnboarding: AssignedContract[
        SvOnboardingRequest.ContractId,
        SvOnboardingRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      isPartyOnboardingConfirmed <- dsoStore
        .lookupSvOnboardingConfirmedByParty(
          PartyId.tryFromProtoPrimitive(svOnboarding.payload.candidateParty)
        )
        .flatMap(optionalConfirmation => Future(optionalConfirmation.isDefined))
      outcome <-
        if (svParty == PartyId.tryFromProtoPrimitive(svOnboarding.payload.candidateParty)) {
          Future.successful(
            TaskSuccess(
              s"skipping as SV is the same as the one executing the trigger, with party ID $svParty"
            )
          )
        } else if (isPartyOnboardingConfirmed) {
          Future.successful(
            TaskSuccess(
              s"skipping as onboarding is already confirmed for SV ${PartyId
                  .tryFromProtoPrimitive(svOnboarding.payload.candidateParty)}"
            )
          )
        } else {
          attemptConfirmation(dsoRules, svOnboarding)
        }
    } yield outcome
  }

  private def confirm(
      party: PartyId,
      name: String,
      weightBps: Long,
      participantId: String,
      reason: String,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val action = dsoRulesConfirmSvOnboardingAction(party, name, weightBps, participantId, reason)
    for {
      queryResult <- dsoStore.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      outcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from ${svParty} is already created for such action"
            )
          )
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(svParty),
              readAs = Seq(dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "com.daml.network.sv.svOnboardingConfirmSvOnboardingConfirmation",
                Seq(svParty, dsoParty),
                party.toProtoPrimitive,
              ),
              deduplicationConfig = DedupOffset(offset),
            )
            .yieldResult()
            .map { _ =>
              TaskSuccess(
                s"created confirmation for confirming the candidate SV ${name} " +
                  s"with party ID ${party}"
              )
            }
      }
    } yield outcome
  }
}
