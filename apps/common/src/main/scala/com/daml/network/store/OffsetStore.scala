package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.LedgerOffset
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

class OffsetStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends AutoCloseable
    with NamedLogging {
  import OffsetStore.State

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: State = State(
    None,
    Promise(),
  )

  def streamOffsets(
      start: LedgerOffset.Absolute
  ): Source[LedgerOffset.Absolute, NotUsed] = {
    Source.unfoldAsync(start)(nextOffset(_).map(Some(_)))
  }
  private def nextOffset(
      prevOffset: LedgerOffset.Absolute
  ): Future[(LedgerOffset.Absolute, LedgerOffset.Absolute)] = {
    val st = stateVar
    st.prevOffset match {
      case Some(off) if prevOffset.getOffset < off.getOffset =>
        Future.successful((off, off))
      case _ =>
        st.offsetChanged.future.flatMap(_ => nextOffset(prevOffset))
    }
  }

  private def updateState[T](
      f: State => (State, T)
  ): Future[T] = {
    Future {
      blocking {
        synchronized {
          val (stNew, result) = f(stateVar)
          stateVar = stNew
          result
        }
      }
    }
  }

  val ingestionSink: OffsetStore.IngestionSink = new OffsetStore.IngestionSink {
    override def ingestOffset(
        offset: LedgerOffset.Absolute
    )(implicit traceContext: TraceContext): Future[Unit] = {
      updateState(_.updateOffset(offset)).map { promiseO =>
        promiseO.foreach(_.success(offset))
      }
    }
  }

  override def close(): Unit = ()
}

object OffsetStore {
  trait IngestionSink {
    def ingestOffset(offset: LedgerOffset.Absolute)(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }

  private case class State(
      prevOffset: Option[LedgerOffset.Absolute],
      offsetChanged: Promise[LedgerOffset.Absolute],
  ) {
    def updateOffset(
        offset: LedgerOffset.Absolute
    ): (State, Option[Promise[LedgerOffset.Absolute]]) = {
      if (prevOffset.fold(true)(prevOffset => prevOffset.getOffset < offset.getOffset)) {
        val state = State(
          Some(offset),
          Promise(),
        )
        (state, Some(offsetChanged))
      } else (this, None)
    }
  }
}
