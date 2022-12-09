package com.daml.network.automation

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractCompanion, ContractId}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.CoinRetries
import com.daml.network.store.AcsStore
import com.daml.network.util.{HasHealth, JavaContract}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/** Convenience class to capture the shared context required to instantiate triggers in an automation service. */
case class TriggerContext(
    config: AutomationConfig,
    timeouts: ProcessingTimeout,
    retryProvider: CoinRetries,
    loggerFactory: NamedLoggerFactory,
)

/** Common base trait for all triggers. */
abstract class Trigger[T <: PrettyPrinting](implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends FlagCloseable
    with NamedLogging
    with Spanning
    with HasHealth {
  protected def context: TriggerContext
  protected def timeouts: ProcessingTimeout = context.timeouts
  protected def loggerFactory: NamedLoggerFactory = context.loggerFactory

  /** How to process a task. */
  protected def processTask(task: T)(implicit tc: TraceContext): Future[Option[String]]

  /** How to check whether a task has become stale and can be skipped. */
  protected def isStaleTask(task: T)(implicit tc: TraceContext): Future[Boolean]

  /** Run this trigger in the background. MUST be called exactly once. */
  def run(): Unit

  final protected def processTaskWithRetry(task: T): Future[Unit] =
    // Creating a new trace here, as multiple requests can be processed in parallel.
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      def processTaskWithStalenessCheck(): Future[Option[String]] =
        processTask(task).recoverWith { case NonFatal(ex) =>
          logger.debug("Checking whether the task is stale, as its processing failed with ", ex)
          isStaleTask(task).transform {
            case Success(true) =>
              Success(Some(show"skipped, as the task has become stale"))
            case Success(false) =>
              Failure(ex)
            case Failure(staleCheckEx) =>
              logger.info("Encountered exception when checking task staleness", staleCheckEx)
              Failure(ex)
          }
        }

      logger.info(show"Processing\n$task")
      context.retryProvider
        .retryForAutomation(
          "processTaskWithRetry",
          processTaskWithStalenessCheck(),
          callingService = this,
        )
        .transform {
          case Success(outcomeO) =>
            outcomeO match {
              case None => Success(())
              case Some(outcome) =>
                logger.info(
                  show"Completed processing with outcome: ${outcome.unquoted}"
                )
                Success(())
            }
          case Failure(ex) =>
            if (this.isClosing) {
              logger.info(
                "Ignoring processing failure, as we are shutting down",
                ex,
              )
            } else
              logger.error(
                show"Skipping processing of \n$task\ndue to unexpected failure",
                ex,
              )

            // Here we recover from the failure so that processing can continue for other tasks.
            Success(())
        }
    }
}

/** A trigger receiving its tasks via an Akka source. */
abstract class SourceBasedTrigger[T <: PrettyPrinting](implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends Trigger[T] {

  /** The source from which to consume tasks. */
  protected def source: Source[T, NotUsed]

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private[this] var taskHandlerVar: Option[AutomationService.TaskHandlerService[T]] = None

  override def run(): Unit = blocking {
    synchronized {
      require(taskHandlerVar.isEmpty, "Run must be called at most once!")
      if (!isClosing) {
        taskHandlerVar = Some(
          new AutomationService.TaskHandlerService[T](
            context.config,
            source,
            processTaskWithRetry,
            false,
            logger,
            timeouts,
          )
        )
      }
    }
  }

  override def onClosed(): Unit = {
    blocking {
      synchronized {
        taskHandlerVar.foreach(_.close())
      }
    }
    super.onClosed()
  }

  override def isHealthy: Boolean = taskHandlerVar.exists(_.isHealthy)

}

/** A trigger for processing contract create events. */
abstract class OnCreateTrigger[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
    acs: AcsStore,
    templateCompanion: ContractCompanion[TC, TCid, T],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SourceBasedTrigger[JavaContract[TCid, T]] {

  override final protected val source: Source[JavaContract[TCid, T], NotUsed] =
    acs.streamContracts(templateCompanion)

  override final protected def isStaleTask(
      task: JavaContract[TCid, T]
  )(implicit tc: TraceContext): Future[Boolean] =
    acs
      .lookupContractById(templateCompanion)(task.contractId)
      .map(_.value.isEmpty)
}
