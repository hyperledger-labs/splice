package com.daml.network.scan.store.memory
import com.daml.network.history.CCTransaction
import com.daml.network.scan.store.ScanTransferStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}

class InMemoryScanTransferStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends ScanTransferStore
    with NamedLogging {
  override def close(): Unit = ()

  val events: mutable.Buffer[CCTransaction] = mutable.ListBuffer()

  override def getTransferHistory: Future[Seq[CCTransaction]] = Future {
    blocking { synchronized { events.toSeq } }
  }
  override def addTransaction(transaction: CCTransaction): Future[Unit] = Future {
    blocking {
      synchronized {
        val _ = events.append(transaction)
      }
    }
  }
}
