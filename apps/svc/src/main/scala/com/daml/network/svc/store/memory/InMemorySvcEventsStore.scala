package com.daml.network.svc.store.memory

import com.daml.network.codegen.java.cc.api.v1.coin.{TransferResult, TransferSummary}
import com.daml.network.svc.store.SvcEventsStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}

class InMemorySvcEventsStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends SvcEventsStore
    with NamedLogging {

  private val current = new AtomicInteger(0)
  private val transfersPerRound: mutable.Map[Long, Seq[TransferSummary]] = mutable.Map()

  /** Expected to be called once per transaction, with all transfers found within that transaction
    */
  override def addTransfers(transfers: Seq[TransferResult]): Future[Unit] = Future {
    blocking {
      synchronized {
        transfers.foreach(tr => {
          val round: Long = tr.round.number
          val summary = tr.summary
          transfersPerRound += (round -> (transfersPerRound.get(round).getOrElse(Seq()) :+ summary))
        })
      }
    }
  }

  def getTransferSummariesPerRound(round: Long): Seq[TransferSummary] =
    transfersPerRound.get(round).getOrElse(Seq())

  override def close(): Unit = ()
}
