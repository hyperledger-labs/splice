package com.daml.network.scan.store.memory
import com.daml.network.history.CoinTransaction
import com.daml.network.scan.store.ScanCCHistoryStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}

class InMemoryScanCCHistoryStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends ScanCCHistoryStore
    with NamedLogging {
  override def close(): Unit = ()

  val events: mutable.Buffer[CoinTransaction] = mutable.ListBuffer()

  override def getCCHistory: Future[Seq[CoinTransaction]] = Future {
    blocking {
      synchronized {
        events.toSeq
      }
    }
  }

  override def addTransaction(transaction: CoinTransaction): Future[Unit] = Future {
    blocking {
      synchronized {
        val _ = events.append(transaction)
      }
    }
  }
}
