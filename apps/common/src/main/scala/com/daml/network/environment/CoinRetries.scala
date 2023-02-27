package com.daml.network.environment

import akka.Done
import akka.stream.StreamTcpException
import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.network.admin.api.client.AppConnection
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.lifecycle.{
  FlagCloseable,
  FutureUnlessShutdown,
  RunOnShutdown,
  UnlessShutdown,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.retry.RetryUtil.{
  ErrorKind,
  ExceptionRetryable,
  FatalErrorKind,
  NoErrorKind,
  TransientErrorKind,
}
import com.digitalasset.canton.util.retry.{Backoff, Pause, Success}
import io.grpc.Status
import io.grpc.protobuf.StatusProto

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Try}

/** The CoinRetries class serves two purposes:
  *  1. It provides methods for retrying failed futures when processing RPC requests
  *     and background tasks.
  *  2. It serves to communicate that a node is shutting down. Background services
  *     are expected to use this to avoid retry-loops when the node is attempting to shut down.
  *
  *  TODO(tech-debt): the name of the class could likely be better. Find a better name and change to it.
  */
class CoinRetries(
    override val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
) extends NamedLogging
    with FlagCloseable {

  // shutdown signal together with the registry of the callback setting it
  private val shutdownSignal = Promise[UnlessShutdown[Nothing]]()

  runOnShutdown(new RunOnShutdown {
    override def name: String = s"trigger promise for shutdown signal"
    override def done: Boolean = shutdownSignal.isCompleted
    override def run(): Unit = {
      logger.debug("Sending node-level shutdown signal (via future).")(
        TraceContext.empty
      )
      val _ = shutdownSignal.trySuccess(UnlessShutdown.AbortedDueToShutdown)
    }
  })(TraceContext.empty)

  /** True if node-level shutdown was initiated.
    *
    * This method relies on the guarantee that a `CoinNode` will close its retry provider
    * before closing its other services.
    */
  def isShuttingDown: Boolean = shutdownSignal.isCompleted

  /** Completes when node-level shutdown was initiated. */
  def shutdownInitiated: Future[UnlessShutdown[Nothing]] = shutdownSignal.future

  /** Use this function to log unexpected terminations from background processing loops and
    * ignore exceptions propagated on shutdown.
    *
    * See its usages for examples.
    */
  def logTerminationAndRecoverOnShutdown(name: String, logger: TracedLogger)(
      terminationResult: Try[Done]
  )(implicit
      tc: TraceContext
  ): Try[Done] =
    terminationResult match {
      case scala.util.Success(_) if isShuttingDown =>
        logger.debug(
          s"Observed successful termination of $name during shutdown"
        )
        scala.util.Success(Done)

      case scala.util.Success(_) =>
        val msg = s"Unexpected termination of $name"
        logger.error(msg)
        Failure(new RuntimeException(msg))

      case Failure(ex) if isShuttingDown =>
        logger.debug(s"Ignoring termination of $name with exception, as we are shutting down", ex)
        scala.util.Success(Done)

      case Failure(ex) =>
        val msg = s"Unexpected termination of $name with exception"
        logger.error(msg, ex)
        Failure(ex)
    }

  /** Wait for a signal unless shutdown was initiated.
    *
    * Use this for all functions that wait for an indefinite amount of time,
    * and thereby avoid blocking a clean shutdown.
    */
  def waitUnlessShutdown[T](
      waitForSignal: Future[T]
  )(implicit ec: ExecutionContext): FutureUnlessShutdown[T] = {
    val signalOrShutdown = Promise[UnlessShutdown[T]]()
    signalOrShutdown.completeWith(waitForSignal.map(UnlessShutdown.Outcome(_)))
    signalOrShutdown.completeWith(shutdownInitiated)
    FutureUnlessShutdown(signalOrShutdown.future)
  }

  /** Variant of [[waitUnlessShutdown]] that works with [[FutureUnlessShutdown]]. */
  def waitUnlessShutdown[T](
      waitForSignal: FutureUnlessShutdown[T]
  )(implicit ec: ExecutionContext): FutureUnlessShutdown[T] =
    FutureUnlessShutdown(
      waitUnlessShutdown(waitForSignal.unwrap).onShutdown(UnlessShutdown.AbortedDueToShutdown)
    )

  import CoinRetries.{AutomationRetryConfig, RetryConfig}

  private val retryForAutomationConfig =
    AutomationRetryConfig(
      RetryConfig(maxRetries = 35, initialDelay = 200.millis, maxDelay = 5.seconds),
      outerLoopDelay = 5.seconds,
    )
  private val retryForClientCallsConfig =
    RetryConfig(maxRetries = 10, initialDelay = 100.millis, maxDelay = 1.seconds)

  /** A retry intended for automation calls, retries forever. */
  def retryForAutomation[T](
      operationName: String,
      task: => Future[T],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] =
    retryForAutomation(
      operationName,
      task,
      logger,
      CoinRetries.RetryableError(_, additionalCodes),
    )

  /** A retry intended for automation calls, retries forever. */
  private def retryForAutomation[T](
      operationName: String,
      task: => Future[T],
      logger: TracedLogger,
      retryable: String => ExceptionRetryable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = {
    def outerLoop(task: => Future[T]) = {
      implicit val success: Success[T] = Success.always
      Pause(
        logger,
        this,
        Int.MaxValue, // This is special cased as infinite retries so don’t need to worry about overflows.
        retryForAutomationConfig.outerLoopDelay,
        operationName,
        longDescription = s"Outer retry loop for $operationName",
      ).apply(task, retryable(operationName))
    }
    outerLoop(
      retry(operationName, task, logger, retryForAutomationConfig.config, retryable)
    )
  }

  /** A retry intended for client calls, thus timing out relatively quickly. */
  def retryForClientCalls[T](
      operationName: String,
      task: => Future[T],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] =
    retry(
      operationName,
      task,
      logger,
      retryForClientCallsConfig,
      CoinRetries.RetryableError(_, additionalCodes),
    )

  private def retry[T](
      operationName: String,
      task: => Future[T],
      logger: TracedLogger,
      retryConfig: RetryConfig,
      retryable: String => ExceptionRetryable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = {
    implicit val success: Success[T] = Success.always

    Backoff(
      logger,
      this,
      retryConfig.maxRetries,
      retryConfig.initialDelay,
      retryConfig.maxDelay,
      operationName,
    ).apply(task, retryable(operationName))
  }
}

object CoinRetries {
  case class RetryConfig(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: Duration) {}

  /** Retry config for automation. For automation we use an outer loop that retries forever
    * with a fixed delay `outerLoopDelay` and an inner loop with exponential backoff configured
    * by `config`. This allows us to quickly retry on transient failures while
    * never running out of retries.
    */
  final case class AutomationRetryConfig(
      config: RetryConfig,
      outerLoopDelay: FiniteDuration,
  )

  def apply(loggerFactory: NamedLoggerFactory, timeouts: ProcessingTimeout): CoinRetries = {
    new CoinRetries(loggerFactory, timeouts)
  }

  /** @param additionalCodes Additional gRPC status codes on which we can retry the given call,
    *                        since we know that an external process is changing the system state.
    */
  case class RetryableError(
      operationName: String,
      additionalCodes: Seq[Status.Code],
  ) extends ExceptionRetryable {
    // Additional categories that are not marked as retryable but we
    // can safely retry since we know there are other apps or
    // processes that change the system state.
    private val extraRetryableCategories: Set[ErrorCategory] =
      Set(
        ErrorCategory.InvalidGivenCurrentSystemStateOther,
        ErrorCategory.InvalidGivenCurrentSystemStateResourceExists,
        ErrorCategory.InvalidGivenCurrentSystemStateResourceMissing,
        ErrorCategory.InvalidGivenCurrentSystemStateSeekAfterEnd,
      )

    // TODO (#1066) Remove the need to retry on UNIMPLEMENTED.
    private val retryableStatusCodes = Seq(
      Status.Code.UNIMPLEMENTED,
      Status.Code.UNAVAILABLE,
      Status.Code.NOT_FOUND,
      Status.Code.FAILED_PRECONDITION,
    ) ++ additionalCodes

    override def retryOK(outcome: Try[_], logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = outcome match {
      case Failure(
            ex @ GrpcException(status @ GrpcStatus(statusCode, Some(description)), trailers)
          ) =>
        val errorCategory = ErrorCodeUtils.errorCategoryFromString(description)
        val statusProto = StatusProto.fromStatusAndTrailers(status, trailers)
        val errorDetails = ErrorDetails.from(statusProto)

        def fatalError: ErrorKind = {
          logger.warn(
            Seq(
              s"The operation ${operationName.singleQuoted} failed with a non-retryable error:",
              s"category=$errorCategory",
              s"statusCode=$statusCode",
            )
              .appendedAll(errorDetails.map(_.toString))
              .mkString("\n"),
            ex,
          )
          FatalErrorKind
        }

        errorCategory match {
          case Some(cat) if cat.retryable.nonEmpty || extraRetryableCategories.contains(cat) =>
            //  don't log the stack traces of transient gRPC exceptions to make the logs less noisy.
            val msg =
              Seq(
                s"The operation ${operationName.singleQuoted} failed with a retryable error (full stack trace omitted):",
                s"category=$errorCategory",
              )
                // the message of the exception is already in the error details, so we don't need to append it
                .appendedAll(errorDetails.map(_.toString))
            logger.info(msg.mkString("\n"))
            TransientErrorKind
          case None if retryableStatusCodes.contains(statusCode) =>
            val msg = Seq(
              s"The operation ${operationName.singleQuoted} failed with a retryable error (full stack trace omitted): ${ex.getMessage}",
              s"statusCode=$statusCode",
            )
            logger.info(msg.mkString("\n"))
            TransientErrorKind
          case _ => fatalError
        }

      case Failure(
            ex: StreamTcpException
          ) =>
        val msg =
          s"The operation ${operationName.singleQuoted} failed with a retryable error (full stack trace omitted): $ex"
        logger.info(msg)
        TransientErrorKind
      case Failure(
            ex: AppConnection.UnexpectedHttpResponse
          ) =>
        // TODO (tech-debt) Revisit whether we can provide more useful info here.
        val msg =
          s"The operation ${operationName.singleQuoted} failed with a retryable error (full stack trace omitted): $ex"
        logger.info(msg)
        TransientErrorKind
      case Failure(ex) =>
        logger.warn(s"$operationName failed with an unknown exception, not retrying", ex)
        FatalErrorKind
      case util.Success(_) =>
        NoErrorKind
    }
  }
}
