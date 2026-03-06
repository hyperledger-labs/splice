// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.PekkoUtil
import io.grpc.StatusRuntimeException
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.{KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.lfdecentralizedtrust.splice.automation.ServiceWithShutdown

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** A ServiceWithShutdown that makes sure that all background processing has been completed before indicating `completed`,
  *  on both graceful termination and exceptions.
  *  Use this when it is crucial to wait for all "old" processing to terminate before restarting the flow, e.g. if two
  *  parallel ledger ingestions (the old and the new) might race in nasty ways.
  */
class ServiceWithGuaranteedShutdown[S](
    source: Source[S, NotUsed],
    map: S => Future[Unit],
    override protected[this] val retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends ServiceWithShutdown
    with RetryProvider.Has
    with FlagCloseableAsync
    with NamedLogging {

  import TraceContext.Implicits.Empty.*

  case object SubscriptionShutDown
  private val lastFutureFinished
      : AtomicReference[Either[SubscriptionShutDown.type, Promise[Done]]] = new AtomicReference(
    Right(Promise.successful(Done))
  )

  private val (killSwitch, completed_) = PekkoUtil.runSupervised(
    source
      // we place the kill switch before the map operator, such that
      // we can shut down the operator quickly and signal upstream to cancel further sending
      .viaMat(KillSwitches.single)(Keep.right)
      .viaMat(Flow[S].mapAsync(1) { el =>
        // map(el) *immediately* launches the future, so it needs to be done after setting the promise,
        // and only if we're not shutting down.
        val myPromise = Promise[Done]()
        val previousState = lastFutureFinished.getAndSet(Right(myPromise))
        previousState match {
          case Left(SubscriptionShutDown) =>
            Future.successful(Done)
          case Right(_) =>
            map(el).andThen { case _ =>
              myPromise.success(Done)
            }
        }
      })(Keep.left)
      // and we get the Future[Done] as completed from the sink so we know when the last message
      // was processed... except when a Failure from the source happens (e.g., `STALE_STREAM_AUTHORIZATION`),
      // in which case the stream will be reported as completed with a failure, while the Future is running.
      // Therefore, we also keep track of the last running future and include that in the completed check.
      // If we didn't, we'd get situations where e.g. two ingestions are running simultaneously (and break a lot).
      // For more information, see https://github.com/DACH-NY/canton-network-node/issues/10126.
      .toMat(Sink.ignore)(Keep.both),
    errorLogMessagePrefix = if (retryProvider.isClosing) {
      "Ignoring failure to handle transaction, as we are shutting down"
    } else {
      "Fatally failed to handle transaction"
    },
  )

  def completed: Future[Done] =
    completed_.transformWith { result =>
      (lastFutureFinished.getAndSet(Left(SubscriptionShutDown)) match {
        case Left(_) => Future.successful(Done)
        case Right(runningFuture) => runningFuture.future
      }).transformWith(_ =>
        Future.fromTry(result)
      ) // Keep whatever the original reason for failure was
    }

  def isActive: Boolean = !completed_.isCompleted

  def initiateShutdown()(implicit tc: TraceContext) =
    killSwitch.shutdown()

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    import TraceContext.Implicits.Empty.*
    List[AsyncOrSyncCloseable](
      SyncCloseable(s"terminating ledger api stream", killSwitch.shutdown()),
      AsyncCloseable(
        s"ledger api stream terminated",
        completed.transform {
          case Success(v) => Success(v)
          case Failure(_: StatusRuntimeException) =>
            // don't fail to close if there was a grpc status runtime exception
            // this can happen (i.e. server not available etc.)
            Success(Done)
          case Failure(ex) => Failure(ex)
        },
        timeouts.shutdownShort,
      ),
    )
  }
}
