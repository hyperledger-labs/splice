package com.daml.network.environment

import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil._
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

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

trait CoinRetries extends FlagCloseable {

  protected val maxRetries: Int = 35
  protected val initialDelay: FiniteDuration = 200.millis
  protected val maxDelay: Duration = 5.seconds

  def retry[T](operationName: String, task: => Future[T])(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] = {
    implicit val success: Success[T] = Success.always

    Backoff(
      logger,
      this,
      maxRetries,
      initialDelay,
      maxDelay,
      operationName,
    ).apply(task, CoinRetries.RetryableError(operationName))
  }

  def retryUnlessShutdown[T](operationName: String, task: => FutureUnlessShutdown[T])(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): FutureUnlessShutdown[T] = {
    implicit val success: Success[T] = Success.always

    Backoff(
      logger,
      this,
      maxRetries,
      initialDelay,
      maxDelay,
      operationName,
    ).unlessShutdown(task, CoinRetries.RetryableError(operationName))
  }
}

object CoinRetries {

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
