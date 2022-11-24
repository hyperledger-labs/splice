package com.daml.network.automation

import akka.Done
import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription, CoinRetries}
import com.daml.network.store.AcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class AcsIngestionService(
    name: String,
    ingestionSink: AcsStore.IngestionSink,
    connection: CoinLedgerConnection,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private val txFilter = ingestionSink.transactionFilter
  private val serviceDescriptor = s"AcsIngestionService($name)"

  /** Ingests the ACS and returns the offset of the ACS, as of which the transaction stream should be read. */
  private def ingestAcs()(implicit traceContext: TraceContext): Future[String] = {
    for {
      // TODO(#790): stream contracts instead of ingesting them as a single Seq
      (evs, off) <- connection.activeContractsWithOffset(txFilter)
      _ <- ingestionSink.ingestActiveContracts(evs)
      offsetAsString = off match {
        case absolute: LedgerOffset.Absolute => absolute.getOffset
        case _ => sys.error("expected absolute offset")
      }
      _ <- ingestionSink.switchToIngestingTransactions(offsetAsString)
    } yield offsetAsString
  }

  // Note that we are tracking the current subscription outside the retry loop instead of just
  // calling 'runOnShutdown' on every newly acquired subscription, as that would leak memory.
  private val currentSubscription = new AtomicReference[Option[CoinLedgerSubscription]](None)

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

  private val subscriptionDone = {
    withNewTrace(serviceDescriptor)(implicit traceContext =>
      _ => {
        logger.debug(s"Starting $serviceDescriptor")
        retryProvider
          .retryForAutomationWithUncleanShutdown(
            s"Ingestion loop for $serviceDescriptor",
            for {
              lastIngestedOffset <- ingestionSink.getLastIngestedOffset
              subscribeFrom <- lastIngestedOffset match {
                case None => ingestAcs()
                case Some(off) => Future.successful(off)
              }
              done <- {
                // Start a ledger subscription, which will return an CoinLedgerConnection whose .close() method
                // must be called to stop receiving data from the ledger.
                val subscription = connection.subscribeAsync(
                  serviceDescriptor,
                  new LedgerOffset.Absolute(subscribeFrom),
                  txFilter,
                )(
                  // Ingest every transaction as we get it.
                  ingestionSink.ingestTransaction(_)
                )
                // Smuggle the current subscription out of the body here, so that the AcsIngestionService.close()
                // method can initiate the shutdown.
                currentSubscription.set(Some(subscription))
                // The above code is racy with the call to close the content of `currentSubscription`, which is issued
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
              }
            } yield done,
            // couple the lifecycle of retrying to the lifecycle of the AcsIngestionService
            flagCloseable = this,
            // also retry on the INTERNAL error above
            additionalCodes = Seq(Status.Code.INTERNAL),
          )
      }
    )
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit def traceContext: TraceContext = TraceContext.empty

    Seq(
      AsyncCloseable(
        s"$serviceDescriptor ledger subscription terminated",
        subscriptionDone.recover(ex => {
          // The retry loop terminates with the last exception it was retrying on. Ignore that.
          logger.info(s"$serviceDescriptor: Ignoring exception due to shutdown: $ex")
          Done
        }),
        timeouts.shutdownNetwork.duration,
      )
    )
  }
}
