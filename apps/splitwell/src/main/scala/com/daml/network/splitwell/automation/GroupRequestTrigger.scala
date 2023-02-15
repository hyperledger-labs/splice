package com.daml.network.splitwell.automation

import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class GroupRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CoinLedgerConnection,
    splitwellDomain: DomainAlias,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      splitwellCodegen.GroupRequest.ContractId,
      splitwellCodegen.GroupRequest,
    ](
      store,
      () => store.domains.signalWhenConnected(splitwellDomain),
      splitwellCodegen.GroupRequest.COMPANION,
    ) {

  override def completeTask(
      req: Contract[
        splitwellCodegen.GroupRequest.ContractId,
        splitwellCodegen.GroupRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val provider = store.providerParty
    val user = PartyId.tryFromProtoPrimitive(req.payload.group.owner)
    val groupId = req.payload.group.id
    for {
      domainId <- store.domains.getDomainId(splitwellDomain)
      queryResult <- store.lookupGroupWithOffset(user, groupId)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(
            s"Rejecting duplicate group request from user party $user for group id ${groupId.unpack}"
          )
          val cmd = req.contractId.exerciseGroupRequest_Reject()
          connection
            .submitWithResultNoDedup(Seq(provider), Seq(), cmd, domainId)
            .map(_ => TaskSuccess("rejected request for already existing group."))

        case QueryResult(off, None) =>
          val acceptCmd = req.contractId.exerciseGroupRequest_Accept().commands.asScala.toSeq
          connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd,
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.splitwell.createGroupRequest",
                Seq(provider, user),
                groupId.unpack,
              ),
              deduplicationOffset = off,
              domainId = domainId,
            )
            .map(_ => TaskSuccess("accepted group request."))
      }
    } yield taskOutcome
  }
}
