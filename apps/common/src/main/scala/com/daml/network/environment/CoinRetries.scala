package com.daml.network.environment

import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.Backoff
import com.digitalasset.canton.util.retry.RetryUtil.{
  ErrorKind,
  ExceptionRetryable,
  FatalErrorKind,
  NoErrorKind,
  TransientErrorKind,
}
import io.grpc.Status
import io.grpc.protobuf.StatusProto

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

trait CoinRetries extends FlagCloseable {

  protected val maxRetries: Int = 40
  protected val initialDelay: FiniteDuration = 10.millis
  protected val maxDelay: Duration = 5.seconds

  def retry[T](operationName: String, task: => Future[T])(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ) = {
    implicit val success = com.digitalasset.canton.util.retry.Success.always

    Backoff(
      logger,
      this,
      maxRetries,
      initialDelay,
      maxDelay,
      operationName,
    ).apply(task, CoinRetries.RetryableError(operationName))
  }
}

object CoinRetries {
  case class RetryableError(operationName: String) extends ExceptionRetryable {

    // Additional categories that are not marked as retriable but we
    // can safely retry since we know there are other apps or
    // processes that change the system state.
    private val extraRetryableCategories: Set[ErrorCategory] =
      Set(
        ErrorCategory.InvalidIndependentOfSystemState,
        ErrorCategory.InvalidGivenCurrentSystemStateOther,
        ErrorCategory.InvalidGivenCurrentSystemStateResourceExists,
        ErrorCategory.InvalidGivenCurrentSystemStateResourceMissing,
        ErrorCategory.InvalidGivenCurrentSystemStateSeekAfterEnd,
      )

    override def retryOK(outcome: Try[_], logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = outcome match {
      case Failure(
            ex @ GrpcException(status @ GrpcStatus(statusCode, Some(operationName)), trailers)
          ) =>
        val errorCategory = ErrorCodeUtils.errorCategoryFromString(operationName)
        val statusProto = StatusProto.fromStatusAndTrailers(status, trailers)
        val errorDetails = ErrorDetails.from(statusProto)
        errorCategory match {
          case Some(cat) if cat.retryable.nonEmpty || extraRetryableCategories.contains(cat) =>
            logger.info(
              s"$operationName failed with a retryable error: Error details: ${errorDetails}",
              ex,
            )
            TransientErrorKind
          // TODO (#1066) Remove the need to retry on UNIMPLEMENTED.
          case None
              if Seq(Status.Code.UNIMPLEMENTED, Status.Code.UNAVAILABLE).contains(statusCode) =>
            logger.info(
              s"$operationName failed with a retryable error",
              ex,
            )
            TransientErrorKind
          case _ =>
            logger.warn(
              s"$operationName failed with a non-retryable error: Error details: ${errorDetails} $errorCategory $statusCode ${statusCode == Status.Code.UNIMPLEMENTED}",
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
