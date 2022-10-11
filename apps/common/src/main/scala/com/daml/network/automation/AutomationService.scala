package com.daml.network.automation

import akka.NotUsed
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.daml.network.environment.CoinRetries
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  Lifecycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.canton.util.AkkaUtil

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
) extends CoinRetries
    with FlagCloseableAsync
    with NamedLogging
    with NoTracing {

  private[this] val automationServices: AtomicReference[Seq[AutoCloseable]] = new AtomicReference(
    Seq.empty
  )

  /** Register a service to orchestrate, which currently means ensuring its prompt closure. */
  final protected def registerService(service: AutoCloseable): Unit = {
    val _ = automationServices.getAndUpdate(_.prepended(service))
    ()
  }

  /** Register a task handler driven from a source of tasks. */
  final protected def registerTaskHandler[T](name: String, source: Source[T, NotUsed])(
      pretty: T => String,
      handler0: T => Future[Unit],
  ): Unit = {
    def handler(task: T): Future[Unit] = {
      lazy val prettiedTask = pretty(task)
      performUnlessClosingF(prettiedTask)({
        // TODO(#790): use tracing within the handler's log messages
        logger.debug(s"Attempting to process $prettiedTask")
        val resultF = retry(prettiedTask, handler0(task))
        resultF.andThen {
          case Success(_) =>
            logger.debug(s"Successfully processed $prettiedTask.")
          case Failure(ex) =>
            logger.warn(s"Processing $prettiedTask failed! Reason:", ex)
        }
      }).onShutdown(())
    }
    val service = new AutomationService.TaskHandlerService[T](
      name,
      source,
      handler,
      loggerFactory,
      timeouts,
    )
    registerService(service)
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Directory automation services",
        Lifecycle.close(automationServices.get(): _*)(logger),
      )
    )
}

object AutomationService {
  private class TaskHandlerService[T](
      name: String,
      source: Source[T, NotUsed],
      processTask: T => Future[Unit],
      override protected val loggerFactory: NamedLoggerFactory,
      override val timeouts: ProcessingTimeout,
  )(implicit
      mat: Materializer
  ) extends FlagCloseableAsync
      //TODO(#790): review logging
      with NamedLogging
      with NoTracing {

    val (killSwitch, completed) = AkkaUtil.runSupervised(
      logger.error("Fatally failed to handle task", _),
      source
        // we place the kill switch before the map operator, such that
        // we can shut down the operator quickly and signal upstream to cancel further sending
        .viaMat(KillSwitches.single)(Keep.right)
        .mapAsync(1)(processTask)
        // and we get the Future[Done] as completed from the sink so we know when the last message
        // was processed
        .toMat(Sink.ignore)(Keep.both),
    )

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
      Seq[AsyncOrSyncCloseable](
        SyncCloseable(s"killSwitch.shutdown $name", killSwitch.shutdown()),
        AsyncCloseable(
          s"graph.completed $name",
          completed,
          timeouts.shutdownShort.unwrap,
        ),
      )
    }
  }
}
