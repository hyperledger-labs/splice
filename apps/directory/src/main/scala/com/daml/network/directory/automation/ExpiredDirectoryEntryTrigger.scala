package com.daml.network.directory.automation

import com.daml.network.automation.{ExpiredContractTrigger, ScheduledTaskTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.JavaContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredDirectoryEntryTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      directoryCodegen.DirectoryEntry.Contract,
      directoryCodegen.DirectoryEntry.ContractId,
      directoryCodegen.DirectoryEntry,
    ](
      store.acs,
      store.listExpiredDirectoryEntries,
      directoryCodegen.DirectoryEntry.COMPANION,
    ) {

  override protected def completeTask(
      co: ScheduledTaskTrigger.ReadyTask[JavaContract[
        directoryCodegen.DirectoryEntry.ContractId,
        directoryCodegen.DirectoryEntry,
      ]]
  )(implicit tc: TraceContext): Future[String] = {
    val cmd =
      co.work.contractId.exerciseDirectoryEntry_Expire(store.providerParty.toProtoPrimitive)
    connection
      .submitCommandsNoDedup(
        actAs = Seq(store.providerParty),
        readAs = Seq(),
        commands = cmd.commands.asScala.toSeq,
      )
      .map(_ => s"archived expired entry")
  }
}
