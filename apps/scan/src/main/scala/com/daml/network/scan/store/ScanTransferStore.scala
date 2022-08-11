package com.daml.network.scan.store

import com.daml.network.scan.admin.ReadCcTransfersService.CoinEvent
import com.daml.network.scan.store.memory.InMemoryScanTransferStore
import com.digitalasset.canton.data.Timestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.ExecutionContext

// TODO(Arne): iterate on this. Do we even want to return only transfers still?
case class CcTransfers(
    txId: String,
    eventId: String,
    recordTime: Timestamp,
    issuanceRound: Int,
    from: PartyId,
    to: PartyId,
    quantity: BigDecimal,
    fees: BigDecimal,
)

trait ScanTransferStore extends AutoCloseable {
  // TODO(i300): eventually this probably needs a start & end offset - related to #300
  def getTransferHistory: Seq[CcTransfers]
  def addCoinEvent(events: Seq[CoinEvent]): Unit
}

object ScanTransferStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): ScanTransferStore =
    storage match {
      case _: MemoryStorage => new InMemoryScanTransferStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
