package com.daml.network.store

import com.daml.network.history.CoinTransaction
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}

class InMemoryCCHistoryStore(
    override protected val loggerFactory: NamedLoggerFactory
)(implicit ec: ExecutionContext)
    extends CCHistoryStore
    with NamedLogging {

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

  override def close(): Unit = ()
}
