package com.daml.network.directory.automation

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.ReadyContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredDirectoryEntryTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      directoryCodegen.DirectoryEntry.ContractId,
      directoryCodegen.DirectoryEntry,
    ](
      store.multiDomainAcsStore,
      store.listExpiredDirectoryEntries,
      directoryCodegen.DirectoryEntry.COMPANION,
    ) {

  override protected def completeTask(
      co: ScheduledTaskTrigger.ReadyTask[ReadyContract[
        directoryCodegen.DirectoryEntry.ContractId,
        directoryCodegen.DirectoryEntry,
      ]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val cmd =
      co.work.contractId
        .exerciseDirectoryEntry_Expire(store.providerParty.toProtoPrimitive)
    store.domains.waitForDomainConnection(store.defaultAcsDomain).flatMap { domainId =>
      connection
        .submitCommandsNoDedup(
          actAs = Seq(store.providerParty),
          readAs = Seq(),
          commands = cmd.commands.asScala.toSeq,
          domainId,
        )
        .map(_ => TaskSuccess(s"archived expired entry"))
    }
  }
}
