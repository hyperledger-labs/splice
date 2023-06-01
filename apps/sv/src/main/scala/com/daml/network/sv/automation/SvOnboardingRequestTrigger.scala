package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
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
import com.daml.network.store.MultiDomainAcsStore.{QueryResult, ReadyContract}
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.{DomainId, PartyId}
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
) extends OnReadyContractTrigger.Template[
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
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      svOnboarding: ReadyContract[
        SvOnboardingRequest.ContractId,
        SvOnboardingRequest,
      ],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      approval <- SvApp
        .isApprovedSvIdentity(
          svOnboarding.contract.payload.candidateName,
          PartyId.tryFromProtoPrimitive(svOnboarding.contract.payload.candidateParty),
          svOnboarding.contract.payload.token,
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
              s"skipping as SV is already an SVC member"
            )
          )
        } else if (SvApp.isSvcMemberParty(party, svcRules)) {
          Future.failed(
            Status.ALREADY_EXISTS
              .withDescription("An SV with that party ID already exists.")
              .asRuntimeException()
          )
        } else if (!SvApp.isDevNet(svcRules) && SvApp.isSvcMemberName(name, svcRules)) {
          Future.failed(
            Status.ALREADY_EXISTS
              .withDescription("An SV with that name already exists.")
              .asRuntimeException()
          )
        } else {
          confirm(party, name, svOnboarding.contract.payload.token, svcRules, svOnboarding.domain)
        }
    } yield outcome
  }

  override def completeTask(
      svOnboarding: ReadyContract[
        SvOnboardingRequest.ContractId,
        SvOnboardingRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      isPartyOnboardingConfirmed <- svcStore
        .lookupSvOnboardingConfirmedByParty(
          PartyId.tryFromProtoPrimitive(svOnboarding.contract.payload.candidateParty)
        )
        .flatMap(optionalConfirmation => Future(optionalConfirmation.isDefined))
      outcome <-
        if (
          svParty == PartyId.tryFromProtoPrimitive(svOnboarding.contract.payload.candidateParty)
        ) {
          Future.successful(
            TaskSuccess(
              s"skipping as SV is the same as the one executing the trigger, with party ID $svParty"
            )
          )
        } else if (isPartyOnboardingConfirmed) {
          Future.successful(
            TaskSuccess(
              s"skipping as onboarding is already confirmed for SV ${PartyId
                  .tryFromProtoPrimitive(svOnboarding.contract.payload.candidateParty)}"
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
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val action = svcRulesConfirmSvOnboardingAction(party, name, reason)
    for {
      queryResult <- svcStore.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = svcRules.contractId.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        action,
      )
      outcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from ${svParty} is already created for such action"
            )
          )
        case result @ QueryResult(_, None) =>
          connection
            .submitWithResult(
              actAs = Seq(svParty),
              readAs = Seq(svcParty),
              update = cmd,
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.sv.svOnboardingConfirmSvOnboardingConfirmation",
                Seq(svParty, svcParty),
                party.toProtoPrimitive,
              ),
              deduplicationConfig = DedupOffset(
                offset = result.deduplicationOffset
              ),
              domainId = domainId,
            )
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
