package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.svonboarding.SvOnboarding
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRules_ConfirmSv,
  SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSv
import com.daml.network.environment.{CoinLedgerConnection, DedupOffset}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.SvApp
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.PartyId
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvOnboardingTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    svStore: SvSvStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      SvOnboarding.ContractId,
      SvOnboarding,
    ](
      svcStore,
      () => svcStore.domains.signalWhenConnected(svcStore.defaultAcsDomain),
      SvOnboarding.COMPANION,
    ) {

  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  private def svcRulesConfirmSvAction(
      candidateParty: PartyId,
      candidateName: String,
      reason: String,
  ): ActionRequiringConfirmation = new ARC_SvcRules(
    new SRARC_ConfirmSv(
      new SvcRules_ConfirmSv(candidateParty.toProtoPrimitive, candidateName, reason)
    )
  )

  override def completeTask(
      svOnboarding: Contract[
        SvOnboarding.ContractId,
        SvOnboarding,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      approval <- SvApp
        .isApprovedSvIdentity(
          svOnboarding.payload.candidateName,
          PartyId.tryFromProtoPrimitive(svOnboarding.payload.candidateParty),
          svOnboarding.payload.token,
          svStore,
        )
      (party, name) <- approval match {
        case Left(reason) => {
          // we fail so that the task is retried; it's possible that an approval happens eventually
          Future.failed(
            Status.NOT_FOUND
              .withDescription(s"Could not match with an approved SV identity; reason: $reason")
              .asRuntimeException()
          )
        }
        case Right((party, name)) => Future.successful((party, name))
      }
      svcRules <- svcStore.getSvcRules()
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
        } else if (SvApp.isSvcMemberName(name, svcRules)) {
          Future.failed(
            Status.ALREADY_EXISTS
              .withDescription("An SV with that name already exists.")
              .asRuntimeException()
          )
        } else {
          confirm(party, name, svOnboarding.payload.token, svcRules)
        }
    } yield outcome
  }

  private def confirm(
      party: PartyId,
      name: String,
      reason: String,
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  ): Future[TaskOutcome] = {
    val action = svcRulesConfirmSvAction(party, name, reason)
    for {
      domainId <- svcStore.domains.signalWhenConnected(svcStore.defaultAcsDomain)
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
        case QueryResult(offset, None) =>
          connection
            .submitWithResult(
              actAs = Seq(svParty),
              readAs = Seq(svcParty),
              update = cmd,
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.sv.svOnboardingConfirmSvConfirmation",
                Seq(svParty, svcParty),
                party.toProtoPrimitive,
              ),
              deduplicationConfig = DedupOffset(
                offset = offset
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
