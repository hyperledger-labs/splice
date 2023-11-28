package com.daml.network.automation

import org.apache.pekko.Done
import com.daml.network.environment.{CNLedgerSubscription, RetryFor, RetryProvider}
import com.daml.network.util.HasHealth
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

/** Abstract class to share the retry and shutdown logic between different services for ingesting ledger data using
  * a subscription to a Ledger API stream.
  */
abstract class LedgerIngestionService()(implicit ec: ExecutionContext, tracer: Tracer)
    extends HasHealth
    with FlagCloseableAsync
    with RetryProvider.Has
    with NamedLogging
    with Spanning {

  protected def retryProvider: RetryProvider

  /** Allocate a new subscription that drives ingestion. */
  protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CNLedgerSubscription[?]]

  // Note that we are tracking the current subscription outside the retry loop instead of just
  // calling 'runOnShutdown' on every newly acquired subscription, as that would leak memory.
  private val currentSubscription = new AtomicReference[Option[CNLedgerSubscription[?]]](None)
  private val ingestionLoopTerminatedF = new AtomicReference[Future[Done]](Future.successful(Done))

  retryProvider.runOnShutdown_(new RunOnShutdown {
    override def name: String = s"terminate subscription"
    // this is not perfectly precise, but CNLedgerSubscription.initiateShutdown is idempotent
    override def done: Boolean = false
    override def run(): Unit = currentSubscription
      .get()
      .foreach(subscription => {
        logger
          .debug(s"Terminating ledger ingestion loop, as we are shutting down.")(TraceContext.empty)
        subscription.initiateShutdown()
      })
  })(TraceContext.empty)

  protected def startIngestion(): Unit = {
    withNewTrace("ledger ingestion loop")(implicit traceContext =>
      _ => {
        logger.debug(s"Starting ledger ingestion loop")
        val retryLoopF = retryProvider
          .retry(
            RetryFor.LongRunningAutomation,
            "ledger ingestion loop", {
              newLedgerSubscription().flatMap(subscription => {
                // Smuggle the current subscription out of the body here, so that we can use
                // runOnShutdown outside to signal the termination via a call to .initiateShutdown().
                currentSubscription.set(Some(subscription))
                // The creation of the new subscription races with the call to close the content of `currentSubscription`, which is issued
                // at most once from outside and might end up closing the previous subscription set in a retry loop.
                // We resolve that race by checking here whether we are closing, and issuing the call ourselves.
                if (retryProvider.isClosing) {
                  logger.debug("detected shutdown, closing subscription")
                  subscription.initiateShutdown()
                }
                // The actual return value of the future being retried is the future inside the CNLedgerConnection,
                // which signals when the subscription terminated.
                subscription.completed.map(_ => {
                  // Defensive programming: resubscribe if the subscription terminates normally, outside of closing
                  if (!retryProvider.isClosing) {
                    val msg = "subscription terminated unexpectedly"
                    logger.error(msg)
                    throw Status.INTERNAL.withDescription(msg).asRuntimeException
                  }
                  Done
                })
              })
            },
            // couple the lifecycle of retrying to the lifecycle of the UpdateIngestionService
            logger,
            // also retry on the INTERNAL error above
            Seq(Status.Code.INTERNAL),
          )
        ingestionLoopTerminatedF.set(
          retryLoopF.transform(
            retryProvider.logTerminationAndRecoverOnShutdown("ledger ingestion loop", logger)
          )
        )
      }
    )
  }

  final override def isHealthy: Boolean =
    // Healthy if there's an active subscription
    currentSubscription.get().exists(subscription => !subscription.completed.isCompleted)

  final override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit def traceContext: TraceContext = TraceContext.empty
    Seq(
      AsyncCloseable(
        "waiting for termination of ledger ingestion loop",
        ingestionLoopTerminatedF.get(),
        timeouts.shutdownNetwork.duration,
      )
    )
  }
}
