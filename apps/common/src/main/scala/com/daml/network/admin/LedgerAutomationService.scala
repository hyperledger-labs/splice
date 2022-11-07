package com.daml.network.admin

import com.daml.ledger.javaapi.data.{Identifier, Transaction}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Admin service that connects to the ledger and processes events.
  * Analogue to Canton's [[com.digitalasset.canton.participant.admin.AdminWorkflowService]] workflow services.
  */
trait LedgerAutomationService extends AutoCloseable {

  /** The set of template IDs that this service should subscribe for */
  def templateIds: Seq[Identifier]

  /** Processing the transaction must not block; shutdown problems occur otherwise.
    * Long-running computations or blocking calls should be spawned off into an asynchronous computation
    * so that the service itself can synchronize its closing with the spawned-off computation if needed.
    */
  def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit]
}
