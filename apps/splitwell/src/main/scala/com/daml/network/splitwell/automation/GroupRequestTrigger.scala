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
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.ReadyContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class GroupRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      splitwellCodegen.GroupRequest.ContractId,
      splitwellCodegen.GroupRequest,
    ](
      store,
      splitwellCodegen.GroupRequest.COMPANION,
    ) {

  override def completeTask(
      req: ReadyContract[
        splitwellCodegen.GroupRequest.ContractId,
        splitwellCodegen.GroupRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val provider = store.providerParty
    val user = PartyId.tryFromProtoPrimitive(req.payload.group.owner)
    val groupId = req.payload.group.id
    for {
      queryResult <- store.lookupGroupWithOffset(user, groupId)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(
            s"Rejecting duplicate group request from user party $user for group id ${groupId.unpack}"
          )
          val cmd = req.contractId.exerciseGroupRequest_Reject()
          connection
            .submitWithResultNoDedup(Seq(provider), Seq(), cmd, req.domain)
            .map(_ => TaskSuccess("rejected request for already existing group."))

        case QueryResult(offset, None) =>
          val acceptCmd =
            req.contractId.exerciseGroupRequest_Accept().commands.asScala.toSeq
          connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd,
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.splitwell.createGroupRequest",
                Seq(provider, user),
                groupId.unpack,
              ),
              deduplicationOffset = offset,
              domainId = req.domain,
            )
            .map(_ => TaskSuccess("accepted group request."))
      }
    } yield taskOutcome
  }
}
