package com.daml.network.splitwise.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SplitwiseInstallRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwiseStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      splitwiseCodegen.SplitwiseInstallRequest.Contract,
      splitwiseCodegen.SplitwiseInstallRequest.ContractId,
      splitwiseCodegen.SplitwiseInstallRequest,
    ](store.acs, splitwiseCodegen.SplitwiseInstallRequest.COMPANION) {

  override def completeTask(
      req: JavaContract[
        splitwiseCodegen.SplitwiseInstallRequest.ContractId,
        splitwiseCodegen.SplitwiseInstallRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val user = PartyId.tryFromProtoPrimitive(req.payload.user)
    val provider = store.providerParty
    store.lookupInstallWithOffset(user).flatMap {
      case QueryResult(_, Some(_)) =>
        logger.info(s"Rejecting duplicate install request from user party $user")
        val cmd = req.contractId.exerciseSplitwiseInstallRequest_Reject()
        connection
          .submitWithResultNoDedup(Seq(provider), Seq(), cmd)
          .map(_ => TaskSuccess("rejected request for already existing installation."))

      case QueryResult(off, None) =>
        val acceptCmd =
          req.contractId.exerciseSplitwiseInstallRequest_Accept().commands.asScala.toSeq
        connection
          .submitCommands(
            actAs = Seq(provider),
            readAs = Seq(),
            commands = acceptCmd,
            commandId = CoinLedgerConnection.CommandId(
              "com.daml.network.splitwise.createSplitwiseInstall",
              Seq(provider, user),
            ),
            deduplicationOffset = off,
          )
          .map(_ => TaskSuccess("accepted install request."))
    }
  }
}
