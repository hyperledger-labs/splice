package com.daml.network.automation

import akka.Done
import com.daml.network.environment.{CoinLedgerSubscription, CoinRetries}
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
    with NamedLogging
    with Spanning {

  protected def serviceDescriptor: String
  protected def retryProvider: CoinRetries

  /** Allocate a new subscription that drives ingestion. */
  protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CoinLedgerSubscription]

  // Note that we are tracking the current subscription outside the retry loop instead of just
  // calling 'runOnShutdown' on every newly acquired subscription, as that would leak memory.
  private val currentSubscription = new AtomicReference[Option[CoinLedgerSubscription]](None)
  private val ingestionLoopTerminatedF = new AtomicReference[Future[Done]](Future.successful(Done))

  this.runOnShutdown(new RunOnShutdown {
    override def name: String = s"$serviceDescriptor: terminate subscription"
    // this is not perfectly precise, but CoinLedgerSubscription.close is idempotent
    override def done: Boolean = false
    override def run(): Unit = currentSubscription
      .get()
      .foreach(subscription => {
        logger.debug(s"$serviceDescriptor: shutdown initiated, terminating subscription")(
          TraceContext.empty
        )
        subscription.close()
      })
  })(TraceContext.empty)

  protected def startIngestion(): Unit = {
    withNewTrace(serviceDescriptor)(implicit traceContext =>
      _ => {
        logger.debug(s"Starting $serviceDescriptor")
        val retryLoopF = retryProvider
          .retryForAutomation(
            s"Ingestion loop for $serviceDescriptor", {
              newLedgerSubscription().flatMap(subscription => {
                // Smuggle the current subscription out of the body here, so that we can use
                // runOnShutdown outside to signal the termination via a call to .close().
                currentSubscription.set(Some(subscription))
                // The creation of the new subscription races with the call to close the content of `currentSubscription`, which is issued
                // at most once from outside and might end up closing the previous subscription set in a retry loop.
                // We resolve that race by checking here whether we are closing, and issuing the call ourselves.
                if (this.isClosing) {
                  logger.debug(s"$serviceDescriptor: detected shutdown, closing subscription")
                  subscription.close()
                }
                // The actual return value of the future being retried is the future inside the CoinLedgerConnection,
                // which signals when the subscription terminated.
                subscription.completed.map(_ => {
                  // Defensive programming: resubscribe if the subscription terminates normally, outside of closing
                  if (!this.isClosing) {
                    val msg = s"$serviceDescriptor: subscription terminated unexpectedly"
                    logger.error(msg)
                    throw Status.INTERNAL.withDescription(msg).asRuntimeException
                  }
                  Done
                })
              })
            },
            // couple the lifecycle of retrying to the lifecycle of the AcsIngestionService
            callingService = this,
            // also retry on the INTERNAL error above
            additionalCodes = Seq(Status.Code.INTERNAL),
          )
        ingestionLoopTerminatedF.set(retryLoopF)
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
        s"$serviceDescriptor ledger subscription terminated",
        ingestionLoopTerminatedF
          .get()
          .recover(ex => {
            // The retry loop terminates with the last exception it was retrying on. Ignore that.
            logger.info(s"$serviceDescriptor: Ignoring exception due to shutdown: $ex")
            Done
          }),
        timeouts.shutdownNetwork.duration,
      )
    )
  }
}
