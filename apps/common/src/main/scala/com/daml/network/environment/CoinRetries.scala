package com.daml.network.environment

import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown}
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
import com.digitalasset.canton.util.retry.{Backoff, Success}
import io.grpc.Status
import io.grpc.protobuf.StatusProto

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

class CoinRetries(override val loggerFactory: NamedLoggerFactory) extends NamedLogging {

  case class RetryConfig(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: Duration) {}

  val retryForAutomationConfig =
    RetryConfig(maxRetries = 35, initialDelay = 200.millis, maxDelay = 5.seconds)
  val retryForClientCallsConfig =
    RetryConfig(maxRetries = 10, initialDelay = 100.millis, maxDelay = 1.seconds)

  /** A retry intended for automation calls, may retry for relatively long.
    * This implementation does not guarantee clean shutdown, and should be avoided if possible in favor of [[retryForAutomation()]]
    */
  // TODO(i1408): strive to avoid using this, or convince ourselves that this does not lead to unclean shutdowns
  def retryForAutomationWithUncleanShutdown[T](
      operationName: String,
      task: => Future[T],
      flagCloseable: FlagCloseable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = retry(operationName, task, flagCloseable, retryForAutomationConfig)

  /** A retry intended for automation calls, may retry for relatively long. */
  def retryForAutomation[T](
      operationName: String,
      task: => FutureUnlessShutdown[T],
      flagCloseable: FlagCloseable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): FutureUnlessShutdown[T] =
    retryUnlessShutdown(operationName, task, flagCloseable, retryForAutomationConfig)

  /** A retry intended for client calls, thus timing out relatively quickly.
    * This implementation does not guarantee clean shutdown, and should be avoided if possible in favor of [[retryForClientCalls()]]
    */
  // TODO(i1408): strive to avoid using this, or convince ourselves that this does not lead to unclean shutdowns
  def retryForClientCallsWithUncleanShutdowns[T](
      operationName: String,
      task: => Future[T],
      flagCloseable: FlagCloseable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = retry(operationName, task, flagCloseable, retryForClientCallsConfig)

  /** A retry intended for client calls, thus timing out relatively quickly. */
  def retryForClientCalls[T](
      operationName: String,
      task: => FutureUnlessShutdown[T],
      flagCloseable: FlagCloseable,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): FutureUnlessShutdown[T] =
    retryUnlessShutdown(operationName, task, flagCloseable, retryForClientCallsConfig)

  private def retry[T](
      operationName: String,
      task: => Future[T],
      flagCloseable: FlagCloseable,
      retryConfig: RetryConfig,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = {
    implicit val success: Success[T] = Success.always

    Backoff(
      logger,
      flagCloseable,
      retryConfig.maxRetries,
      retryConfig.initialDelay,
      retryConfig.maxDelay,
      operationName,
    ).apply(task, CoinRetries.RetryableError(operationName))
  }

  private def retryUnlessShutdown[T](
      operationName: String,
      task: => FutureUnlessShutdown[T],
      flagCloseable: FlagCloseable,
      retryConfig: RetryConfig,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): FutureUnlessShutdown[T] = {
    implicit val success: Success[T] = Success.always

    Backoff(
      logger,
      flagCloseable,
      retryConfig.maxRetries,
      retryConfig.initialDelay,
      retryConfig.maxDelay,
      operationName,
    ).unlessShutdown(task, CoinRetries.RetryableError(operationName))
  }
}

object CoinRetries {

  def apply(loggerFactory: NamedLoggerFactory): CoinRetries = {
    new CoinRetries(loggerFactory)
  }

  case class RetryableError(operationName: String) extends ExceptionRetryable {
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
            logger.info(msg.mkString(System.lineSeparator()))
            TransientErrorKind
          // TODO (#1066) Remove the need to retry on UNIMPLEMENTED.
          case None
              if Seq(
                Status.Code.UNIMPLEMENTED,
                Status.Code.UNAVAILABLE,
                Status.Code.NOT_FOUND,
                Status.Code.FAILED_PRECONDITION,
              )
                .contains(statusCode) =>
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
                .mkString(System.lineSeparator()),
              ex,
            )
            FatalErrorKind
        }
      case Failure(ex) =>
        logger.warn(s"$operationName failed with an unknown exception, not retrying", ex)
        FatalErrorKind
      case util.Success(_) =>
        NoErrorKind
    }
  }
}
