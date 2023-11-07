package com.daml.network.sv.automation.confirmation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRules,
  SvcRules_ConfirmSvOnboarding,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingRequest
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvOnboardingRequestTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    svStore: SvSvStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SvOnboardingRequest.ContractId,
      SvOnboardingRequest,
    ](
      svcStore,
      SvOnboardingRequest.COMPANION,
    ) {

  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  private def svcRulesConfirmSvOnboardingAction(
      candidateParty: PartyId,
      candidateName: String,
      reason: String,
  ): ActionRequiringConfirmation = new ARC_SvcRules(
    new SRARC_ConfirmSvOnboarding(
      new SvcRules_ConfirmSvOnboarding(candidateParty.toProtoPrimitive, candidateName, reason)
    )
  )

  private def attemptConfirmation(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
      svOnboarding: AssignedContract[
        SvOnboardingRequest.ContractId,
        SvOnboardingRequest,
      ],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      approval <- SvApp
        .isApprovedSvIdentity(
          svOnboarding.payload.candidateName,
          PartyId.tryFromProtoPrimitive(svOnboarding.payload.candidateParty),
          svOnboarding.payload.token,
          svStore,
          logger,
        )
      (party, name) <- approval match {
        case Left(reason) => {
          // we fail so that the task is retried; it's possible that an approval happens eventually
          // TODO(#4073) Add a warning log
          Future.failed(
            Status.NOT_FOUND
              .withDescription(s"Could not match with an approved SV identity; reason: $reason")
              .asRuntimeException()
          )
        }
        case Right((party, name)) => Future.successful((party, name))
      }
      outcome <-
        if (SvApp.isSvcMember(name, party, svcRules)) {
          Future.successful(
            TaskSuccess(
              s"skipping as SV $name is already an SVC member"
            )
          )
        } else {
          SvApp.validateCandidateSv(party, name, svcRules) match {
            case Left(err) =>
              Future.failed(err.asRuntimeException())
            case Right(_) =>
              confirm(party, name, svOnboarding.payload.token, svcRules)
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
      svcRules <- svcStore.getSvcRules()
      isPartyOnboardingConfirmed <- svcStore
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
          attemptConfirmation(svcRules, svOnboarding)
        }
    } yield outcome
  }

  private def confirm(
      party: PartyId,
      name: String,
      reason: String,
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val action = svcRulesConfirmSvOnboardingAction(party, name, reason)
    for {
      queryResult <- svcStore.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = svcRules.exercise(
        _.exerciseSvcRules_ConfirmAction(
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
              readAs = Seq(svcParty),
              update = cmd,
            )
            .withDedup(
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.sv.svOnboardingConfirmSvOnboardingConfirmation",
                Seq(svParty, svcParty),
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
