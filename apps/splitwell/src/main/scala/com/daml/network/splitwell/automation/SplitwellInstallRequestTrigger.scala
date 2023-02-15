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

class SplitwellInstallRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CoinLedgerConnection,
    splitwellDomain: DomainAlias,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      splitwellCodegen.SplitwellInstallRequest.ContractId,
      splitwellCodegen.SplitwellInstallRequest,
    ](
      store,
      () => store.domains.signalWhenConnected(splitwellDomain),
      splitwellCodegen.SplitwellInstallRequest.COMPANION,
    ) {

  override def completeTask(
      req: Contract[
        splitwellCodegen.SplitwellInstallRequest.ContractId,
        splitwellCodegen.SplitwellInstallRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val user = PartyId.tryFromProtoPrimitive(req.payload.user)
    val provider = store.providerParty
    for {
      domainId <- store.domains.getDomainId(splitwellDomain)
      queryResult <- store.lookupInstallWithOffset(user)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.contractId.exerciseSplitwellInstallRequest_Reject()
          connection
            .submitWithResultNoDedup(Seq(provider), Seq(), cmd, domainId)
            .map(_ => TaskSuccess("rejected request for already existing installation."))

        case QueryResult(off, None) =>
          val acceptCmd =
            req.contractId.exerciseSplitwellInstallRequest_Accept().commands.asScala.toSeq
          connection
            .submitCommands(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd,
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.splitwell.createSplitwellInstall",
                Seq(provider, user),
              ),
              deduplicationOffset = off,
              domainId = domainId,
            )
            .map(_ => TaskSuccess("accepted install request."))
      }
    } yield taskOutcome
  }
}
