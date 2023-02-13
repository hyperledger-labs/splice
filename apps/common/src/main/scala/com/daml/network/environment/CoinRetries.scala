package com.daml.network.environment

import akka.stream.StreamTcpException
import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.network.admin.api.client.AppConnection
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.lifecycle.FlagCloseable
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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

class CoinRetries(override val loggerFactory: NamedLoggerFactory) extends NamedLogging {

  import CoinRetries.{AutomationRetryConfig, RetryConfig}

  val retryForAutomationConfig =
    AutomationRetryConfig(
      RetryConfig(maxRetries = 35, initialDelay = 200.millis, maxDelay = 5.seconds),
      outerLoopDelay = 5.seconds,
    )
  val retryForClientCallsConfig =
    RetryConfig(maxRetries = 10, initialDelay = 100.millis, maxDelay = 1.seconds)

  /** A retry intended for automation calls, retries forever. */
  def retryForAutomation[T](
      operationName: String,
      task: => Future[T],
      callingService: FlagCloseable,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] =
    retryForAutomation(
      operationName,
      task,
      callingService,
      CoinRetries.RetryableError(_, additionalCodes),
    )

  /** A retry intended for automation calls, retries forever. */
  private def retryForAutomation[T](
      operationName: String,
      task: => Future[T],
      callingService: FlagCloseable,
      retryable: String => ExceptionRetryable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = {
    def outerLoop(task: => Future[T]) = {
      implicit val success: Success[T] = Success.always
      Pause(
        loggerFactory.getTracedLogger(callingService.getClass),
        callingService,
        Int.MaxValue, // This is special cased as infinite retries so don’t need to worry about overflows.
        retryForAutomationConfig.outerLoopDelay,
        operationName,
        longDescription = s"Outer retry loop for $operationName",
      ).apply(task, retryable(operationName))
    }
    outerLoop(
      retry(operationName, task, callingService, retryForAutomationConfig.config, retryable)
    )
  }

  /** A retry intended for client calls, thus timing out relatively quickly. */
  def retryForClientCalls[T](
      operationName: String,
      task: => Future[T],
      callingService: FlagCloseable,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] =
    retry(
      operationName,
      task,
      callingService,
      retryForClientCallsConfig,
      CoinRetries.RetryableError(_, additionalCodes),
    )

  private def retry[T](
      operationName: String,
      task: => Future[T],
      callingService: FlagCloseable,
      retryConfig: RetryConfig,
      retryable: String => ExceptionRetryable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = {
    implicit val success: Success[T] = Success.always

    Backoff(
      loggerFactory.getTracedLogger(callingService.getClass),
      callingService,
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

  def apply(loggerFactory: NamedLoggerFactory): CoinRetries = {
    new CoinRetries(loggerFactory)
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
        errorCategory match {
          case Some(cat) if cat.retryable.nonEmpty || extraRetryableCategories.contains(cat) =>
            //  don't log the stack traces of transient gRPC exceptions to make the logs less noisy.
            val msg =
              Seq(
                s"The operation ${operationName.singleQuoted} failed with a retryable error (full stack trace omitted):"
              )
                // the message of the exception is already in the error details, so we don't need to append it
                .appendedAll(errorDetails.map(_.toString))
            logger.info(msg.mkString("\n"))
            TransientErrorKind
          case None if retryableStatusCodes.contains(statusCode) =>
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a retryable error (full stack trace omitted): "
            logger.info(msg + ex.getMessage)
            TransientErrorKind
          case _ =>
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
