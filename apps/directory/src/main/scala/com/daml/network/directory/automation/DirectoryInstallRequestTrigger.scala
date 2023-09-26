package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class DirectoryInstallRequestTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      directoryCodegen.DirectoryInstallRequest.ContractId,
      directoryCodegen.DirectoryInstallRequest,
    ](
      store,
      directoryCodegen.DirectoryInstallRequest.COMPANION,
    ) {

  private val entryFee: BigDecimal = 1.0
  private val renewalDuration = new RelTime(
    // 1 day, so subscriptions can be payed between day 89 and day 90
    24 * 60 * 60 * 1_000_000L
  )
  private val entryLifetime = new RelTime(
    // 90 days
    90 * 24 * 60 * 60 * 1_000_000L
  )

  override def completeTask(
      reqReady: AssignedContract[
        directoryCodegen.DirectoryInstallRequest.ContractId,
        directoryCodegen.DirectoryInstallRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(req, domainId) = reqReady
    val user = PartyId.tryFromProtoPrimitive(req.payload.user)
    val provider = store.providerParty
    for {
      queryResult <- store.lookupInstallByUserWithOffset(user)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.contractId
            .exerciseDirectoryInstallRequest_Reject()
          connection
            .submitCommandsNoDedup(Seq(provider), Seq(), cmd.commands.asScala.toSeq, domainId)
            .map(_ => TaskSuccess("rejected request for already existing installation."))

        case QueryResult(offset, None) =>
          val arg = new directoryCodegen.DirectoryInstallRequest_Accept(
            store.svcParty.toProtoPrimitive,
            entryFee.bigDecimal,
            renewalDuration,
            entryLifetime,
          )
          val acceptCmd = req.exercise(_.exerciseDirectoryInstallRequest_Accept(arg))
          connection
            .submit(
              actAs = Seq(provider),
              readAs = Seq(),
              acceptCmd,
            )
            .withDedup(
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.directory.createDirectoryInstall",
                Seq(provider, user),
              ),
              deduplicationOffset = offset,
            )
            .withDomainId(domainId)
            .yieldUnit()
            .map(_ => TaskSuccess("accepted install request."))
      }
    } yield taskOutcome
  }
}
