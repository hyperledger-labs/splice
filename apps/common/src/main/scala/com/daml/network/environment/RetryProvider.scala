package com.daml.network.environment

import akka.Done
import akka.stream.StreamTcpException
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.network.admin.api.client.commands.HttpCommandException
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.lifecycle.{
  FlagCloseable,
  FutureUnlessShutdown,
  RunOnShutdown,
  UnlessShutdown,
}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.retry.{Backoff, Success}
import com.digitalasset.canton.util.retry.RetryUtil.{
  ErrorKind,
  ExceptionRetryable,
  FatalErrorKind,
  NoErrorKind,
  TransientErrorKind,
}
import io.grpc.Status
import io.grpc.protobuf.StatusProto

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Try}

/** The RetryProvider class serves two purposes:
  *  1. It provides methods for retrying failed futures when processing RPC requests
  *     and background tasks.
  *  2. It serves to communicate that a node is shutting down. Background services
  *     are expected to use this to avoid retry-loops when the node is attempting to shut down.
  *
  *  TODO(tech-debt): the name of the class could likely be better. Find a better name and change to it.
  */
class RetryProvider(
    override val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
    val futureSupervisor: FutureSupervisor,
) extends NamedLogging
    with FlagCloseable {

  private val transientDescription = "retryable"
  private val nonTransientDescription = "non-retryable"
  private val fatalBehavior = "not retrying"

  // shutdown signal as a promise so we can wait on it more easily than polling isClosing.
  // TODO(#4405) Derive from FlagCloseable isClosing state.
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
      case scala.util.Success(_) if isClosing =>
        logger.debug(
          s"Observed successful termination of $name during shutdown"
        )
        scala.util.Success(Done)

      case scala.util.Success(_) =>
        val msg = s"Unexpected termination of $name"
        logger.error(msg)
        Failure(new RuntimeException(msg))

      case Failure(ex) if isClosing =>
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

  /** Wait for a condition to become true with proper logging.
    *
    * Intended to be used for one-time waits during app init or like processes.
    */
  def waitUntil(
      conditionDescription: String,
      checkCondition: => Future[Unit],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext): Future[Unit] =
    TraceContext.withNewTraceContext(tc0 => {
      implicit val tc: TraceContext = tc0;
      logger.info(s"Waiting until $conditionDescription")
      retryForAutomation(
        s"Check whether $conditionDescription",
        checkCondition,
        logger,
        additionalCodes,
      )
        .transform(
          result => {
            logger.info(s"Success: $conditionDescription")
            result
          },
          exception => {
            if (isClosing)
              logger.info(s"Gave up waiting until $conditionDescription, as we are shutting down")
            else
              logger.error(s"Gave up waiting until $conditionDescription", exception)
            exception
          },
        )
    })

  /** Retry getting a value and print it on success.
    *
    * Intended to be used for one-time value retrievals during app init or like processes.
    */
  def getValueWithRetriesNoPretty[T](
      valueDescription: String,
      getValue: => Future[T],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext): Future[T] = {
    implicit val adHocPretty: Pretty[T] = Pretty.prettyOfString(_.toString)
    getValueWithRetries(valueDescription, getValue, logger, additionalCodes)
  }

  /** Retry getting a value and print it on success.
    *
    * Intended to be used for one-time value retrievals during app init or like processes.
    */
  def getValueWithRetries[T](
      valueDescription: String,
      getValue: => Future[T],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext, pp: Pretty[T]): Future[T] =
    TraceContext.withNewTraceContext(tc0 => {
      implicit val tc: TraceContext = tc0;
      val valueDescQuoted = s"'$valueDescription'"
      logger.info(s"Attempting to get $valueDescQuoted")
      retryForAutomation(s"Get $valueDescription", getValue, logger, additionalCodes)
        .transform(
          value => {
            logger.info(show"Got ${valueDescQuoted.unquoted}: $value")
            value
          },
          exception => {
            if (isClosing)
              logger.info(s"Gave up waiting to get $valueDescQuoted, as we are shutting down")
            else
              logger.error(s"Gave up getting $valueDescQuoted", exception)
            exception
          },
        )
    })

  /** Ensure that a particular condition holds as part of an init-like process.
    *
    * Checks the condition and establishes it, if it does not. Also double-checks
    * that the condition holds after establishing it.
    */
  def ensureThat(
      conditionDesc: String,
      check: => Future[Boolean],
      establish: => Future[Unit],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext): Future[Unit] =
    ensureThatWithResult(
      conditionDesc,
      check.map(Option.when(_)(())),
      establish,
      logger,
      additionalCodes,
    )

  /** Variant of ensureThat that allows the condition to
    * return a result if it is successful.
    */
  def ensureThatWithResult[T](
      conditionDesc: String,
      check: => Future[Option[T]],
      establish: => Future[Unit],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext, pp: Pretty[T]): Future[T] =
    ensureThatWithResultAndFailedState(
      conditionDesc,
      check = check.map(_.toRight(())),
      establish = (_: Unit) => establish,
      logger,
      additionalCodes,
    )

  /** Variant of ensureThatWithResult that allows the check to
    * pass a result to the establish function.
    */
  def ensureThatWithResultAndFailedState[A, B](
      conditionDesc: String,
      check: => Future[Either[A, B]],
      establish: A => Future[Unit],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext, pa: Pretty[A], pb: Pretty[B]): Future[B] =
    TraceContext.withNewTraceContext(tc0 => {
      implicit val tc: TraceContext = tc0;
      logger.info(s"Ensuring that $conditionDesc")
      retryForAutomation(
        s"Check whether $conditionDesc and establishing it if needed",
        check.flatMap {
          case Right(result) => Future.successful(Right(result))
          case Left(error) =>
            establish(error).map(Left(_))
        },
        logger,
        additionalCodes,
      ).flatMap {
        case Right(result) => Future.successful(result)
        case Left(()) =>
          logger.debug(s"Established that $conditionDesc, waiting until it is observable")
          retryForAutomation(
            s"Wait until observing $conditionDesc",
            check.flatMap {
              case Right(result) => Future.successful(result)
              case Left(error) =>
                Future.failed(
                  Status.FAILED_PRECONDITION
                    .withDescription(
                      show"Condition $conditionDesc is not (yet) observable; check failed with $error"
                    )
                    .asRuntimeException()
                )
            },
            logger,
            additionalCodes,
          )
      }.transform(
        result => {
          logger.info(show"Success: $conditionDesc, result is $result")
          result
        },
        exception => {
          if (isClosing)
            logger.info(s"Gave up ensuring $conditionDesc, as we are shutting down")
          else
            logger.error(s"Gave up ensuring $conditionDesc", exception)
          exception
        },
      )
    })

  import RetryProvider.{AutomationRetryConfig, RetryConfig}

  private val retryForAutomationConfig =
    AutomationRetryConfig(
      RetryConfig(
        maxRetries = 35,
        initialDelay = 200.millis,
        maxDelay = 5.seconds,
        resetRetriesAfter = 1.minute,
      ),
      outerLoopDelay = 5.seconds,
    )
  private val retryForClientCallsConfig =
    RetryConfig(
      maxRetries = 10,
      initialDelay = 100.millis,
      maxDelay = 1.seconds,
      resetRetriesAfter = 24.hours,
    )

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
      RetryProvider.RetryableError(
        _,
        additionalCodes,
        transientDescription,
        nonTransientDescription,
        fatalBehavior,
      ),
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
  ): Future[T] =
    retry(operationName, task, logger, retryForAutomationConfig.config, retryable)

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
    retryForClientCalls(
      operationName,
      task,
      logger,
      RetryProvider.RetryableError(
        _,
        additionalCodes,
        transientDescription,
        nonTransientDescription,
        fatalBehavior,
      ),
    )

  /** A retry intended for client calls, thus timing out relatively quickly. */
  def retryForClientCalls[T](
      operationName: String,
      task: => Future[T],
      logger: TracedLogger,
      retryable: String => ExceptionRetryable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] =
    retry(
      operationName,
      task,
      logger,
      retryForClientCallsConfig,
      retryable,
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
      resetRetriesAfter = retryConfig.resetRetriesAfter,
    ).apply(task, retryable(operationName))
  }
}

object RetryProvider {
  case class RetryConfig(
      maxRetries: Int,
      initialDelay: FiniteDuration,
      maxDelay: Duration,
      resetRetriesAfter: FiniteDuration,
  )

  /** Retry config for automation. For automation we use an outer loop that retries forever
    * with a fixed delay `outerLoopDelay` and an inner loop with exponential backoff configured
    * by `config`. This allows us to quickly retry on transient failures while
    * never running out of retries.
    */
  final case class AutomationRetryConfig(
      config: RetryConfig,
      outerLoopDelay: FiniteDuration,
  )

  def apply(
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
      futureSupervisor: FutureSupervisor,
  ): RetryProvider = {
    new RetryProvider(loggerFactory, timeouts, futureSupervisor)
  }

  /** @param additionalCodes Additional gRPC status codes on which we can retry the given call,
    *                        since we know that an external process is changing the system state.
    */
  case class RetryableError(
      operationName: String,
      additionalCodes: Seq[Status.Code],
      transientDescription: String,
      nonTransientDescription: String,
      fatalBehavior: String,
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

    // TODO (#2770) Remove the need to retry on UNIMPLEMENTED.
    private val retryableStatusCodes = Seq(
      Status.Code.UNIMPLEMENTED,
      Status.Code.UNAVAILABLE,
      Status.Code.NOT_FOUND,
      Status.Code.FAILED_PRECONDITION,
      Status.Code.DEADLINE_EXCEEDED,
    ) ++ additionalCodes

    private val retryableHttpStatusCodes: Set[StatusCode] = Set(
      StatusCodes.RequestTimeout,
      StatusCodes.Conflict,
      StatusCodes.NotFound,
      StatusCodes.TooManyRequests,
      StatusCodes.InternalServerError,
      StatusCodes.BadGateway,
      StatusCodes.ServiceUnavailable,
      StatusCodes.GatewayTimeout,
    )

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
              s"The operation ${operationName.singleQuoted} failed with a $nonTransientDescription error, $fatalBehavior:",
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
                s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted):",
                s"category=$errorCategory",
              )
                // the message of the exception is already in the error details, so we don't need to append it
                .appendedAll(errorDetails.map(_.toString))
            logger.info(msg.mkString("\n"))
            TransientErrorKind
          case None
              if retryableStatusCodes.contains(statusCode) ||
                (
                  // TODO(#3933) This is temporarily added to retry on INVALID_ARGUMENT errors when submitting transactions during topology change.
                  statusCode == Status.Code.INVALID_ARGUMENT && description.contains(
                    "An error occurred. Please contact the operator and inquire about the request"
                  ) ||
                    // CANCELLED can also be a deliberate cancellation from the client
                    // so we only retry if we observe RST_STREAM which we sometimes see
                    // around Canton restarts.
                    (statusCode == Status.Code.CANCELLED && description.contains(
                      "RST_STREAM closed stream"
                    )) ||
                    // UNKNOWN channel closed can be reported by java-grpc when the connection gets torn forcefully
                    // by toxiproxy (or a like network condition). See #4256 for details.
                    (statusCode == Status.Code.UNKNOWN && description.contains(
                      "channel closed"
                    ))
                ) =>
            val msg = Seq(
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): ${ex.getMessage}",
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
          s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
        logger.info(msg)
        TransientErrorKind
      case Failure(
            ex: BaseAppConnection.UnexpectedHttpResponse
          ) =>
        // TODO (tech-debt) Revisit whether we can provide more useful info here.
        val msg =
          s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
        logger.info(msg)
        TransientErrorKind
      case Failure(ex @ HttpCommandException(status, _)) =>
        if (retryableHttpStatusCodes.contains(status)) {
          logger.info(
            s"The operation ${operationName.singleQuoted} failed with a $transientDescription HTTP error: $ex"
          )
          TransientErrorKind
        } else {
          logger.warn(
            s"The operation ${operationName.singleQuoted} failed with a $nonTransientDescription HTTP error, $fatalBehavior: $ex"
          )
          FatalErrorKind
        }
      // We encounter this with toxiproxy if the upstream is not yet up.
      // The exception type is akka.http.impl.engine.client.OutgoingConnectionBlueprint.UnexpectedConnectionClosureException
      // but akka-http does not expose that so we match on the message instead.
      case Failure(ex: RuntimeException)
          if ex.getMessage.contains(
            "The http server closed the connection unexpectedly before delivering responses"
          ) =>
        val msg =
          s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
        logger.info(msg)
        TransientErrorKind
      case Failure(ex: java.util.concurrent.TimeoutException) =>
        val msg =
          s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
        logger.info(msg)
        TransientErrorKind
      case Failure(ex) =>
        logger.warn(s"$operationName failed with an unknown exception, $fatalBehavior", ex)
        FatalErrorKind
      case util.Success(_) =>
        NoErrorKind
    }
  }
}
