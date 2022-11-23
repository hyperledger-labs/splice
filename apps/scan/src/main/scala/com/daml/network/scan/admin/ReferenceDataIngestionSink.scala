package com.daml.network.scan.admin

import cats.instances.future.*
import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.{Identifier, Transaction}
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.store.AuditLogIngestionSink
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

class ReferenceDataIngestionSink(
    svcParty: PartyId,
    store: ScanCCHistoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AuditLogIngestionSink
    with NamedLogging {

  override def filterParty: PartyId = svcParty

  override def templateIds: Seq[Identifier] =
    Seq(roundCodegen.OpenMiningRound.TEMPLATE_ID)

  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    JavaDecodeUtil
      .decodeAllCreated(roundCodegen.OpenMiningRound.COMPANION)(tx)
      .traverse(c => store.setCurrentRound(c.data.round.number))
      .map(_ => ())
}
