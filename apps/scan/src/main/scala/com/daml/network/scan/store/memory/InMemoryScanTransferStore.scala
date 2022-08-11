package com.daml.network.scan.store.memory

import java.util.concurrent.atomic.AtomicInteger

import com.daml.network.scan.admin.ReadCcTransfersService.CoinEvent
import com.daml.network.scan.store.{CcTransfers, ScanTransferStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class InMemoryScanTransferStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends ScanTransferStore
    with NamedLogging {
  override def close(): Unit = ()

  override def getTransferHistory: Seq[CcTransfers] = ???

  override def addCoinEvent(events: Seq[CoinEvent]): Unit = ???
}
