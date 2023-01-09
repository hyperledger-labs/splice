package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class DirectoryInstallRequestTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      directoryCodegen.DirectoryInstallRequest.Contract,
      directoryCodegen.DirectoryInstallRequest.ContractId,
      directoryCodegen.DirectoryInstallRequest,
    ](store.acs, directoryCodegen.DirectoryInstallRequest.COMPANION) {

  private val entryFee: BigDecimal = 1.0
  private val collectionDuration = new RelTime(
    10_000_000
  )
  private val renewalDuration = new RelTime(
    // 1 day, so subscriptions can be payed between day 89 and day 90
    24 * 60 * 60 * 1_000_000L
  )
  private val entryLifetime = new RelTime(
    // 90 days
    90 * 24 * 60 * 60 * 1_000_000L
  )

  override def completeTask(
      req: JavaContract[
        directoryCodegen.DirectoryInstallRequest.ContractId,
        directoryCodegen.DirectoryInstallRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val user = PartyId.tryFromProtoPrimitive(req.payload.user)
    val provider = store.providerParty
    store.lookupInstallByUserWithOffset(user).flatMap {
      case QueryResult(_, Some(_)) =>
        logger.info(s"Rejecting duplicate install request from user party $user")
        val cmd = req.contractId
          .exerciseDirectoryInstallRequest_Reject()
        connection
          .submitCommandsNoDedup(Seq(provider), Seq(), cmd.commands.asScala.toSeq)
          .map(_ => TaskSuccess("rejected request for already existing installation."))

      case QueryResult(off, None) =>
        val arg = new directoryCodegen.DirectoryInstallRequest_Accept(
          store.svcParty.toProtoPrimitive,
          entryFee.bigDecimal,
          collectionDuration,
          renewalDuration,
          entryLifetime,
        )
        val acceptCmd = req.contractId
          .exerciseDirectoryInstallRequest_Accept(arg)
        connection
          .submitCommands(
            actAs = Seq(provider),
            readAs = Seq(),
            commands = acceptCmd.commands.asScala.toSeq,
            commandId = CoinLedgerConnection.CommandId(
              "com.daml.network.directory.createDirectoryInstall",
              Seq(provider, user),
            ),
            deduplicationOffset = off,
          )
          .map(_ => TaskSuccess("accepted install request."))
    }
  }

}
