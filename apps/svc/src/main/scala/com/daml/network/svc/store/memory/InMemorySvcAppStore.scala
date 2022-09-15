package com.daml.network.svc.store.memory

import com.daml.network.codegen.CC.CoinRules.{TransferResult, TransferSummary}
import com.daml.network.svc.store.SvcAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}

/** Example for in-memory store in the store pattern. */
class InMemorySvcAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends SvcAppStore
    with NamedLogging {

  private val current = new AtomicInteger(0)
  private val transfersPerRound: mutable.Map[Long, Seq[TransferSummary]] = mutable.Map()

  override def increment(int: Int)(implicit tc: TraceContext): Future[Int] = {
    Future { current.addAndGet(int) }
  }

  /** Expected to be called once per transaction, with all transfers found within that transaction
    */
  override def addTransfers(transfers: Seq[TransferResult]): Future[Unit] = Future {
    blocking {
      synchronized {
        transfers.foreach(tr => {
          val round = tr.round.number
          val summary = tr.summary
          transfersPerRound += (round -> (transfersPerRound.get(round).getOrElse(Seq()) :+ summary))
        })
      }
    }
  }

  override def close(): Unit = ()
}
