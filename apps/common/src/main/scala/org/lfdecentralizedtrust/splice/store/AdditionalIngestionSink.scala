// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.Transaction
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import slick.dbio.{DBIOAction, Effect, NoStream}

/** Represents a DBIOAction with all parameters already encoded in their
  * native JDBC format. Wrapping the action in a named class simplifies debugging.
  */
trait PreparedIngestionAction extends PrettyPrinting {
  def action: DBIOAction[?, NoStream, Effect.Read & Effect.Write]
}

/** Ingests data derived from the transactions seen by a [[MultiDomainAcsStore]]'s ingestion,
  * in the same SQL transaction as the ACS and the TxLog.
  *
  * The data is tied to the TxLog store of the ACS store, which is not reset when the ACS is.
  * See `DbMultiDomainAcsStore.ingestionSink.initialize`.
  */
trait AdditionalIngestionSink {

  /** Prepares a batch of transactions for ingestion.
    *
    * Returns the database actions that ingest the given transactions. They run in
    * the same SQL transaction as the ingestion of the ACS and the TxLog.
    *
    * Implement this as a function of its inputs: it may be called concurrently,
    * out of order, well before the returned actions run, or for transactions whose
    * actions are never run (e.g., an offset that was already ingested).
    * Logging and updating caches are fine, but must not be relied on.
    *
    * Should not throw exceptions. If this method throws, the whole ingestion service
    * crashes and restarts, and then replays the same updates, so one transaction that
    * cannot be handled blocks ingestion of the store for good. A transaction that this
    * sink cannot handle must yield no action.
    *
    * @param txLogStoreId The id of the TxLog store. Stable for the lifetime of the store descriptor.
    * @param transactions A sequence of consecutive transactions from the update stream,
    *                     excluding the transactions that import the ACS
    */
  def prepareBatch(
      txLogStoreId: TxLogStoreId,
      transactions: NonEmptyList[Transaction],
  )(implicit
      tc: TraceContext
  ): Seq[PreparedIngestionAction]

  /** Called after the SQL transaction that ingests `transactions` has been committed.
    *
    * Only update in-memory state here, such as caches or metrics. State that must
    * survive a restart belongs in the actions returned by `prepareBatch`.
    *
    * Not guaranteed to be called. May be skipped if the application crashes
    * right after the commit.
    *
    * @param transactions The transactions that were passed to `prepareBatch`
    */
  def onTransactionCommitted(
      transactions: NonEmptyList[Transaction]
  )(implicit
      tc: TraceContext
  ): Unit
}
