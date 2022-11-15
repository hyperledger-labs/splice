package com.daml.network.scan.store.memory

import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.store.InMemoryCCHistoryStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.{ExecutionContext, Future, blocking}

class InMemoryScanCCHistoryStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends InMemoryCCHistoryStore(loggerFactory)
    with ScanCCHistoryStore
    with NamedLogging {
  override def close(): Unit = ()

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var currentRound: Long = 0

  override def setCurrentRound(round: Long): Future[Unit] = Future {
    blocking {
      synchronized {
        currentRound = round
      }
    }
  }

  override def getCurrentRound: Future[Long] = Future {
    blocking {
      synchronized {
        currentRound
      }
    }
  }
}
