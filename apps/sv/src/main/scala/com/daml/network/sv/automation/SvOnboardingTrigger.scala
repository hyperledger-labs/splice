package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.svonboarding.SvOnboarding
import com.daml.network.codegen.java.cn.svcrules.{ActionRequiringConfirmation, SvcRules_AddMember}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_AddMember
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

  private def svcRulesAddMemberAction(
      member: PartyId,
      // TODO(#3188) add name
      reason: String,
  ): ActionRequiringConfirmation = new ARC_SvcRules(
    new SRARC_AddMember(new SvcRules_AddMember(member.toProtoPrimitive, reason))
  )

  override def completeTask(
      svOnboarding: Contract[
        SvOnboarding.ContractId,
        SvOnboarding,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    SvApp
      .isApprovedSvIdentity(
        svOnboarding.payload.candidateName,
        PartyId.tryFromProtoPrimitive(svOnboarding.payload.candidateParty),
        svOnboarding.payload.token,
        svStore,
      )
      .flatMap {
        case Left(reason) => {
          // we fail so that the task is retried; it's possible that an approval happens eventually
          Future.failed(
            Status.NOT_FOUND
              .withDescription(s"Could not match with an approved SV identity; reason: $reason")
              .asRuntimeException()
          )
        }
        case Right(party) => {
          confirm(party, svOnboarding.payload.token).map { _ =>
            TaskSuccess(
              s"created confirmation for adding member ${svOnboarding.payload.candidateName} " +
                s"with party ID ${svOnboarding.payload.candidateParty}"
            )
          }
        }
      }
  }

  private def confirm(party: PartyId, reason: String): Future[Unit] = {
    val action = svcRulesAddMemberAction(party, reason)
    for {
      domainId <- svcStore.domains.signalWhenConnected(svcStore.defaultAcsDomain)
      svcRules <- svcStore.getSvcRules()
      queryResult <- svcStore.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = svcRules.contractId.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        action,
      )
      _ <- queryResult match {
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
                "com.daml.network.sv.svOnboardingAddMemberConfirmation",
                Seq(svParty, svcParty),
                party.toProtoPrimitive,
              ),
              deduplicationConfig = DedupOffset(
                offset = offset
              ),
              domainId = domainId,
            )
      }
    } yield ()
  }
}
