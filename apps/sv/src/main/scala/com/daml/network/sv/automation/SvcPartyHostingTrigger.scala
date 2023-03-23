package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.svonboarding.SvConfirmed
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SvcPartyHostingTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      SvConfirmed.ContractId,
      SvConfirmed,
    ](
      svcStore,
      () => svcStore.domains.signalWhenConnected(svcStore.defaultAcsDomain),
      SvConfirmed.COMPANION,
    ) {

  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  override def completeTask(
      svConfirmed: Contract[
        SvConfirmed.ContractId,
        SvConfirmed,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    if (svConfirmed.payload.svParty == svParty.toProtoPrimitive)
      for {
        // TODO(#3428): _ <- svcPartyMigration(participantId)
        _ <- addMemberToSvc(svConfirmed)
      } yield TaskSuccess(
        s"Added member ${svConfirmed.payload.svParty} (\"${svConfirmed.payload.svName}\") to SVC"
      )
    else
      Future.successful(
        TaskSuccess(show"ignoring $svConfirmed, as we're not the new SV")
      )
  }

  private def addMemberToSvc(
      svConfirmed: Contract[SvConfirmed.ContractId, SvConfirmed]
  ): Future[Unit] = {
    for {
      domainId <- svcStore.domains.signalWhenConnected(svcStore.defaultAcsDomain)
      svcRules <- svcStore.getSvcRules()
      cmd = svcRules.contractId.exerciseSvcRules_AddConfirmedMember(
        svParty.toProtoPrimitive,
        svConfirmed.contractId,
      )
      _ <- connection.submitCommandsNoDedup(
        Seq(svParty),
        Seq(svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = domainId,
      )
    } yield ()
  }
}
