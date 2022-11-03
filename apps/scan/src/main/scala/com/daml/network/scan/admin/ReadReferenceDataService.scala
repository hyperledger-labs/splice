package com.daml.network.scan.admin

import cats.instances.future._
import cats.syntax.traverse._
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.CC.{Round => roundCodegen}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.store.ScanCCHistoryStore
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

class ReadReferenceDataService(
    svcParty: PartyId,
    connection: CoinLedgerConnection,
    store: ScanCCHistoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[binding.Primitive.TemplateId[_]] =
    Seq(roundCodegen.OpenMiningRound.id)

  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    // Note: With the workaround for lack of explicit disclosure, we have multiple copies of state contracts
    // (one copy per validator). We could find the original contracts by only looking at those where the observer
    // is equal to the signatory, but it's not necessary because all copies should be identical.
    DecodeUtil
      .decodeAllCreated(roundCodegen.OpenMiningRound)(tx)
      .traverse(c => store.setCurrentRound(c.value.round.number))
      .map(_ => ())

  override def close(): Unit = Lifecycle.close(connection)(logger)

}
