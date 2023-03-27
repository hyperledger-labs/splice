package com.daml.network.splitwell.automation

import com.digitalasset.canton.DomainAlias
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
import com.daml.network.store.MultiDomainAcsStore.{ReadyContract, QueryResult}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class GroupRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
    splitwellDomain: DomainAlias,
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
    val user = PartyId.tryFromProtoPrimitive(req.contract.payload.group.owner)
    val groupId = req.contract.payload.group.id
    for {
      // TODO(M3-19) Use latest domain user has an install contract for.
      domainId <- store.domains.getDomainId(splitwellDomain)
      queryResult <- store.lookupGroupWithOffset(user, groupId)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(
            s"Rejecting duplicate group request from user party $user for group id ${groupId.unpack}"
          )
          val cmd = req.contract.contractId.exerciseGroupRequest_Reject()
          connection
            .submitWithResultNoDedup(Seq(provider), Seq(), cmd, domainId)
            .map(_ => TaskSuccess("rejected request for already existing group."))

        case result @ QueryResult(_, None) =>
          val acceptCmd =
            req.contract.contractId.exerciseGroupRequest_Accept().commands.asScala.toSeq
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
              deduplicationOffset = result.deduplicationOffset,
              domainId = domainId,
            )
            .map(_ => TaskSuccess("accepted group request."))
      }
    } yield taskOutcome
  }
}
