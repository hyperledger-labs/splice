package com.daml.network.splitwell.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.MultiDomainAcsStore.{QueryResult, ReadyContract}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SplitwellInstallRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      splitwellCodegen.SplitwellInstallRequest.ContractId,
      splitwellCodegen.SplitwellInstallRequest,
    ](
      store,
      splitwellCodegen.SplitwellInstallRequest.COMPANION,
    ) {

  override def completeTask(
      req: ReadyContract[
        splitwellCodegen.SplitwellInstallRequest.ContractId,
        splitwellCodegen.SplitwellInstallRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val user = PartyId.tryFromProtoPrimitive(req.contract.payload.user)
    val provider = store.providerParty
    for {
      queryResult <- store.lookupInstallWithOffset(req.domain, user)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.contract.contractId.exerciseSplitwellInstallRequest_Reject()
          connection
            .submitWithResultNoDedup(Seq(provider), Seq(), cmd, req.domain)
            .map(_ => TaskSuccess("rejected request for already existing installation."))

        case result @ QueryResult(_, None) =>
          val acceptCmd =
            req.contract.contractId.exerciseSplitwellInstallRequest_Accept().commands.asScala.toSeq
          connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd,
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.splitwell.createSplitwellInstall",
                Seq(provider, user),
                req.domain.toProtoPrimitive,
              ),
              deduplicationOffset = result.deduplicationOffset,
              domainId = req.domain,
            )
            .map(_ => TaskSuccess("accepted install request."))
      }
    } yield taskOutcome
  }
}
