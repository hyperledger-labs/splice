// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.base.error.ErrorCategory
import com.digitalasset.base.error.utils.ErrorDetails
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.{MetricInfo, MetricQualification, MetricsContext}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{NonNegativeFiniteDuration, ProcessingTimeout}
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.ledger.error.groups.RequestValidationErrors
import com.digitalasset.canton.lifecycle.{
  FlagCloseable,
  FutureUnlessShutdown,
  RunOnClosing,
  UnlessShutdown,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.sequencing.protocol.SequencerErrors
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.retry.{Backoff, ErrorKind, ExceptionRetryPolicy, Success}
import ErrorKind.*
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.time.Clock
import io.grpc.Status
import io.grpc.protobuf.StatusProto
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.stream.StreamTcpException
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode

import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Try}

/** The RetryProvider class serves two purposes:
  *  1. It provides methods for retrying failed futures when processing RPC requests
  *     and background tasks.
  *  2. It serves to communicate that a node is shutting down. Background services
  *     are expected to use this to avoid retry-loops when the node is attempting to shut down.
  *
  *  TODO(tech-debt): the name of the class could likely be better. Find a better name and change to it.
  */
final class RetryProvider(
    override val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
    val futureSupervisor: FutureSupervisor,
    val metricsFactory: LabeledMetricsFactory,
)(implicit tracer: Tracer)
    extends NamedLogging
    with FlagCloseable
    with Spanning {
  import RetryProvider.Retryable

  // Keep track of promises directly to avoid the promise accumulating references to stale futures that it should complete.
  // For more details check https://github.com/DACH-NY/canton-network-node/issues/9178
  // TODO(DACH-NY/canton-network-node#4405) Derive from FlagCloseable isClosing state.
  private val promisesRunningThatAreShutdownAware: java.util.Set[Promise[UnlessShutdown[?]]] =
    Collections.synchronizedSet(
      Collections.newSetFromMap(
        new java.util.WeakHashMap()
      )
    )
  private val shutdown = new AtomicBoolean(false)

  runOnOrAfterClose_(new RunOnClosing {
    override def name: String = s"trigger promise for shutdown signal"
    override def done: Boolean = promisesRunningThatAreShutdownAware.isEmpty && shutdown.get()
    override def run()(implicit tc: TraceContext): Unit = {
      logger.debug("Sending node-level shutdown signal (via future).")(
        TraceContext.empty
      )
      shutdown.set(true)
      promisesRunningThatAreShutdownAware.forEach(promise => {
        promise.trySuccess(UnlessShutdown.AbortedDueToShutdown).discard
      })
      promisesRunningThatAreShutdownAware.clear()
    }
  })(TraceContext.empty)

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
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def waitUnlessShutdown[T](
      waitForSignal: Future[T]
  )(implicit ec: ExecutionContext): FutureUnlessShutdown[T] = {
    val signalOrShutdown = Promise[UnlessShutdown[T]]()
    promisesRunningThatAreShutdownAware
      .add(signalOrShutdown.asInstanceOf[Promise[UnlessShutdown[?]]])
      .discard
    signalOrShutdown.completeWith(waitForSignal.map(UnlessShutdown.Outcome(_)))
    // complete in case this call raced with the shutdown of the RetryProvider
    if (shutdown.get()) {
      signalOrShutdown.trySuccess(UnlessShutdown.AbortedDueToShutdown).discard
    }
    FutureUnlessShutdown(signalOrShutdown.future)
  }

  /** Variant of [[waitUnlessShutdown]] that works with [[FutureUnlessShutdown]]. */
  def waitUnlessShutdown[T](
      waitForSignal: FutureUnlessShutdown[T]
  )(implicit ec: ExecutionContext): FutureUnlessShutdown[T] =
    FutureUnlessShutdown(
      waitUnlessShutdown(waitForSignal.unwrap).onShutdown(UnlessShutdown.AbortedDueToShutdown)
    )

  /** Schedule an action in the future with early termination on shutdown */
  def scheduleAfterUnlessShutdown[A](
      action: => Future[A],
      clock: Clock,
      delay: NonNegativeFiniteDuration,
      jitter: Double,
  )(implicit ec: ExecutionContext): FutureUnlessShutdown[A] = {
    val pollingIntervalNanos = delay.unwrap.toNanos
    val jitterNanos: Long =
      (pollingIntervalNanos * jitter * (math.random() - 0.5)).toLong
    val delayNanos =
      (pollingIntervalNanos + jitterNanos).max(0L).min(2L * pollingIntervalNanos)
    this
      .waitUnlessShutdown(
        clock
          .scheduleAfter(
            _ => {
              // No work done here, as we are only interested in the scheduling notification
              ()
            },
            java.time.Duration.ofNanos(delayNanos),
          )
      )
      .flatMap(_ => FutureUnlessShutdown.outcomeF(action))
  }

  /** Wait for a condition to become true with proper logging.
    *
    * Intended to be used for one-time waits during app init or like processes.
    */
  def waitUntil(
      retryFor: RetryFor,
      conditionIdentifier: String,
      conditionDescription: String,
      checkCondition: => Future[Unit],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Unit] =
    withSpan(conditionIdentifier)(implicit tc =>
      _ => {
        logger.info(s"Waiting until $conditionDescription")
        retry(
          retryFor,
          conditionIdentifier,
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
      }
    )

  /** Retry getting a value and print it on success.
    *
    * Intended to be used for one-time value retrievals during app init or like processes.
    *
    * @param valueIdentifier - General identifier that is used to tag metrics and spans. Must not be a high cardinality value
    */
  def getValueWithRetriesNoPretty[T](
      retryFor: RetryFor,
      valueDescription: String,
      valueIdentifier: String,
      getValue: => Future[T],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
    implicit val adHocPretty: Pretty[T] = Pretty.prettyOfString(_.toString)
    getValueWithRetries(
      retryFor,
      valueIdentifier,
      valueDescription,
      getValue,
      logger,
      additionalCodes,
    )
  }

  /** Retry getting a value and print it on success.
    *
    * Intended to be used for one-time value retrievals during app init or like processes.
    *
    * @param valueIdentifier - General identifier that is used to tag metrics and spans. Must not be a high cardinality value
    */
  def getValueWithRetries[T](
      retryFor: RetryFor,
      valueIdentifier: String,
      valueDescription: String,
      getValue: => Future[T],
      logger: TracedLogger,
      additionalCodes: Seq[Status.Code] = Seq.empty,
  )(implicit ec: ExecutionContext, tc: TraceContext, pp: Pretty[T]): Future[T] =
    withSpan(valueIdentifier)(implicit tc =>
      _ => {
        val valueDescQuoted = s"'$valueDescription'"
        logger.info(s"Attempting to get $valueDescQuoted")
        retry(
          retryFor,
          valueIdentifier,
          s"Get $valueDescription",
          getValue,
          logger,
          additionalCodes,
        )
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
      }
    )

  /** Variant of ensureThat where the check returns only a Boolean. */
  def ensureThatB[R](
      retryFor: RetryFor,
      conditionId: String,
      conditionDesc: String,
      check: => Future[Boolean],
      establish: => Future[Unit],
      logger: TracedLogger,
      retryable: R = Seq.empty,
  )(implicit ec: ExecutionContext, tc: TraceContext, mkRetryable: Retryable[R]): Future[Unit] =
    ensureThatO(
      retryFor,
      conditionId,
      conditionDesc,
      check.map(Option.when(_)(())),
      establish,
      logger,
      retryable,
    )

  /** Variant of ensureThat where the check returns only a result. */
  def ensureThatO[T, R](
      retryFor: RetryFor,
      conditionId: String,
      conditionDesc: String,
      check: => Future[Option[T]],
      establish: => Future[Unit],
      logger: TracedLogger,
      retryable: R = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      pp: Pretty[T],
      mkRetryable: Retryable[R],
  ): Future[T] =
    ensureThat(
      retryFor,
      conditionId,
      conditionDesc,
      check = check.map(_.toRight(())),
      establish = (_: Unit) => establish,
      logger,
      retryable,
    )

  /** Ensure that a particular condition holds as part of an init-like process.
    *
    * Checks the condition and establishes it, if it does not hold;
    * and waits until the condition holds after establishing it.
    */
  def ensureThat[A, B, R](
      retryFor: RetryFor,
      conditionId: String,
      conditionDesc: String,
      check: => Future[Either[A, B]],
      establish: A => Future[Unit],
      logger: TracedLogger,
      retryable: R = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      pa: Pretty[A],
      pb: Pretty[B],
      mkRetryable: Retryable[R],
  ): Future[B] =
    ensureThatI(retryFor, conditionId, conditionDesc, check, check, establish, logger, retryable)

  /** Ensure that a particular condition holds as part of an init-like process.
    *
    * Checks the condition with [[initialCheck]] and establishes it, if it does not hold;
    * and waits until the condition holds with [[establishedCheck]] after establishing it.
    */
  def ensureThatI[A, B, R](
      retryFor: RetryFor,
      conditionId: String,
      conditionDesc: String,
      initialCheck: => Future[Either[A, B]],
      establishedCheck: => Future[Either[A, B]],
      establish: A => Future[Unit],
      logger: TracedLogger,
      additionalCodes: R = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      pa: Pretty[A],
      pb: Pretty[B],
      mkRetryable: Retryable[R],
  ): Future[B] =
    withSpan(conditionId)(implicit tc =>
      _ => {
        logger.info(s"Ensuring that $conditionDesc")
        retry(
          retryFor,
          conditionId,
          s"Check whether $conditionDesc and establish it if needed",
          initialCheck.flatMap {
            case Right(result) => Future.successful(Right(result))
            case Left(error) =>
              establish(error).map(Left(_))
          },
          logger,
          additionalCodes,
        ).flatMap {
          case Right(result) => Future.successful(result)
          case Left(_) =>
            logger.debug(s"Established that $conditionDesc, waiting until it is observable")
            retry(
              retryFor,
              conditionId,
              s"Wait until observing $conditionDesc",
              establishedCheck.map {
                case Right(result) => result
                case Left(error) =>
                  throw Status.FAILED_PRECONDITION
                    .withDescription(
                      show"Condition $conditionDesc is not (yet) observable; check failed with $error"
                    )
                    .asRuntimeException()
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
      }
    )

  /** A retry intended for client calls, thus timing out relatively quickly. */
  def retryForClientCalls[T, R: Retryable](
      operationId: String,
      operationDescription: String,
      task: => Future[T],
      logger: TracedLogger,
      retryable: R = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[T] =
    retry(
      RetryFor.ClientCalls,
      operationId,
      operationDescription,
      task,
      logger,
      retryable,
    )

  def retry[T, R](
      retryConfig: RetryFor,
      operationId: String,
      operationDescription: String,
      task: => Future[T],
      logger: TracedLogger,
      retryable: R = Seq.empty,
      additionalMetricsLabels: Map[String, String] = Map.empty,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      mkRetryable: Retryable[R],
  ): Future[T] = {
    implicit val success: Success[T] = Success.always
    withSpan(operationId) { implicit traceContext => _ =>
      Backoff(
        logger,
        this,
        retryConfig.maxRetries,
        retryConfig.initialDelay,
        retryConfig.maxDelay,
        operationDescription,
        resetRetriesAfter = retryConfig.resetRetriesAfter,
      ).apply(
        task,
        mkRetryable(
          operationDescription,
          retryable,
          metricsFactory,
          additionalMetricsLabels ++ Map(
            "operation" -> operationId
          ),
          this,
        ),
      )
    }
  }
}

object RetryProvider {
  def apply(
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
      futureSupervisor: FutureSupervisor,
      metricsFactory: LabeledMetricsFactory,
  )(implicit tracer: Tracer): RetryProvider = {
    new RetryProvider(loggerFactory, timeouts, futureSupervisor, metricsFactory)
  }

  type RetryableConditions = Map[Status.Code, Condition]

  sealed abstract class Condition {
    import Condition.*
    final def matches(category: Option[ErrorCategory]): Boolean = this match {
      case Category(cat) => category contains cat
      case All => true
    }
  }

  object Condition {
    case object All extends Condition
    final case class Category(cat: ErrorCategory) extends Condition
  }

  /** @param additionalCodes Additional gRPC status codes on which we can retry the given call,
    *                        since we know that an external process is changing the system state.
    */
  case class RetryableError(
      operationName: String,
      additionalCodes: Seq[Status.Code],
      additionalConditions: RetryableConditions,
      transientDescription: String,
      nonTransientDescription: String,
      fatalBehavior: String,
      metricsFactory: LabeledMetricsFactory,
      additionalMetricsLabels: Map[String, String],
      flagCloseable: FlagCloseable,
  ) extends ExceptionRetryPolicy {
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

    private val retryableStatusCodes = Seq(
      // Canton registers its services after starting the top-level gRPC server, which means we get UNIMPLEMENTED errors
      // for a short period of during the startup of Canton.
      Status.Code.UNIMPLEMENTED,
      Status.Code.UNAVAILABLE,
      Status.Code.NOT_FOUND,
      Status.Code.ALREADY_EXISTS,
      Status.Code.FAILED_PRECONDITION,
      Status.Code.DEADLINE_EXCEEDED,
      Status.Code.ABORTED,
      Status.Code.UNAUTHENTICATED,
      // We can get permission denied if the token expired.
      Status.Code.PERMISSION_DENIED,
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

    private val retryCounter =
      metricsFactory.counter(
        MetricInfo(
          SpliceMetrics.MetricsPrefix :+ "retries" :+ "failures",
          "Number of operations that failed and were retried",
          MetricQualification.Errors,
        )
      )

    override def determineExceptionErrorKind(exception: Throwable, logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = {
      val errorKind: ErrorKind = if (flagCloseable.isClosing) {
        logger.info(
          s"The operation ${operationName.singleQuoted} failed due to shutdown in process (full stack trace omitted): $exception"
        )
        TransientErrorKind()
      } else {
        exception match {
          case ex @ GrpcException(status @ GrpcStatus(statusCode, someDescription), trailers) =>
            val description = someDescription.getOrElse("No description provided")
            val errorCategory = someDescription.flatMap(ErrorCodeUtils.errorCategoryFromString)
            val statusProto = StatusProto.fromStatusAndTrailers(status, trailers)
            val errorDetails: Seq[ErrorDetails.ErrorDetail] = ErrorDetails.from(statusProto)

            def fatalError: ErrorKind = {
              logger.warn(
                Seq(
                  s"The operation ${operationName.singleQuoted} failed with a $nonTransientDescription error, $fatalBehavior:",
                  s"category=$errorCategory",
                  s"statusCode=$statusCode",
                  s"description=$description",
                )
                  .appendedAll(errorDetails.map(_.toString))
                  .mkString("\n"),
                ex,
              )
              FatalErrorKind
            }

            val isPruningError = errorDetails.exists {
              case detail: ErrorDetails.ErrorInfoDetail =>
                detail.errorCodeId == RequestValidationErrors.ParticipantPrunedDataAccessed.id
              case _ => false
            }

            errorCategory match {
              // Pruning errors fall under FAILED_PRECONDITION which we usually retry but there is no chance to recover from it so we instead treat it as a fatal error.
              case _ if isPruningError => fatalError
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
                TransientErrorKind()
              case _
                  if retryableStatusCodes.contains(statusCode) ||
                    (
                      // TODO(DACH-NY/canton-network-node#3933) This is temporarily added to retry on INVALID_ARGUMENT errors when submitting transactions during topology change.
                      statusCode == Status.Code.INVALID_ARGUMENT && description.contains(
                        "An error occurred. Please contact the operator and inquire about the request"
                      ) ||
                        // This can happen if the party allocation has not yet been propagated to the new synchronizer.
                        statusCode == Status.Code.INVALID_ARGUMENT &&
                        raw"No participant of the party .* has confirmation permission on both synchronizers at respective timestamps".r
                          .findFirstMatchIn(description)
                          .isDefined ||
                        // This can happen if the party allocation has not yet been propagated to the new synchronizer.
                        statusCode == Status.Code.INVALID_ARGUMENT &&
                        raw"The following stakeholders are not active on the target synchronizer".r
                          .findFirstMatchIn(description)
                          .isDefined || // TODO(#822) Remove this once Canton fixes the error code.
                        (statusCode == Status.Code.INVALID_ARGUMENT &&
                          description.contains(
                            SequencerErrors.MaxSequencingTimeTooFar.id
                          )) ||
                        // This can occur when we try to get the synchronizer time while still being disconnected from the synchronizer
                        statusCode == Status.Code.INVALID_ARGUMENT &&
                        raw"Not connected to any synchronizer with id".r
                          .findFirstMatchIn(description)
                          .isDefined || // This can occur if the party has not yet been propagated to the ledger API server
                        statusCode == Status.Code.INVALID_ARGUMENT &&
                        raw"Provided parties have not been found in identity_provider_id".r
                          .findFirstMatchIn(description)
                          .isDefined || // CANCELLED can also be a deliberate cancellation from the client
                        // so we only retry if we observe RST_STREAM which we sometimes see
                        // around Canton restarts.
                        (statusCode == Status.Code.CANCELLED && description.contains(
                          "RST_STREAM closed stream"
                        )) ||
                        // UNKNOWN channel closed can be reported by java-grpc when the connection gets torn forcefully
                        // by toxiproxy (or a like network condition). See #4256 for details.
                        (statusCode == Status.Code.UNKNOWN && description.contains(
                          "channel closed"
                        )) ||
                        // DbStorage instance could still be in passive state during startup
                        (statusCode == Status.Code.INTERNAL && description.contains(
                          "DbStorage instance is not active"
                        )) ||
                        // additional codes subject to conditions
                        additionalConditions
                          .get(statusCode)
                          .exists(_.matches(errorCategory))
                    ) =>
                val msg = Seq(
                  s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): ${ex.getMessage}",
                  s"statusCode=$statusCode",
                )
                logger.info(msg.mkString("\n"))
                TransientErrorKind()
              case _ => fatalError
            }

          case ex: StreamTcpException =>
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            logger.info(msg)
            TransientErrorKind()
          case ex: BaseAppConnection.UnexpectedHttpResponse =>
            // TODO (tech-debt) Revisit whether we can provide more useful info here.
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            logger.info(msg)
            TransientErrorKind()
          case ex @ HttpCommandException(_, status, _) =>
            if (retryableHttpStatusCodes.contains(status)) {
              logger.info(
                s"The operation ${operationName.singleQuoted} failed with a $transientDescription HTTP error: $ex"
              )
              TransientErrorKind()
            } else {
              logger.warn(
                s"The operation ${operationName.singleQuoted} failed with a $nonTransientDescription HTTP error, $fatalBehavior: $ex"
              )
              FatalErrorKind
            }
          case ex @ HttpErrorWithHttpCode(code, _) =>
            if (retryableHttpStatusCodes.contains(code)) {
              logger.info(
                s"The operation ${operationName.singleQuoted} failed with a $transientDescription HTTP error: $ex"
              )
              TransientErrorKind()
            } else {
              logger.warn(
                s"The operation ${operationName.singleQuoted} failed with a $nonTransientDescription HTTP error, $fatalBehavior: $ex"
              )
              FatalErrorKind
            }
          // Retry for transient DNS failures #10545
          case ex: ConnectException =>
            logger.info(
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            )
            TransientErrorKind()
          // We encounter this with toxiproxy if the upstream is not yet up.
          // The exception type is org.apache.pekko.http.impl.engine.client.OutgoingConnectionBlueprint.UnexpectedConnectionClosureException
          // but pekko-http does not expose that so we match on the message instead.
          case ex: RuntimeException
              if Option(ex.getMessage).exists(
                _.contains(
                  "The http server closed the connection unexpectedly before delivering responses"
                )
              ) =>
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            logger.info(msg)
            TransientErrorKind()
          // IOExceptions are checked exceptions that are typically raised when external systems or devices are acting up.
          // We add this default retry, as that is more likely to be helpful than failing fatally.
          case ex: IOException =>
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            logger.info(msg)
            TransientErrorKind()
          case ex: java.util.concurrent.TimeoutException =>
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            logger.info(msg)
            TransientErrorKind()
          case ex: java.sql.SQLTransientConnectionException =>
            val msg =
              s"The operation ${operationName.singleQuoted} failed with a $transientDescription error (full stack trace omitted): $ex"
            logger.info(msg)
            TransientErrorKind()
          case ex: java.util.concurrent.CompletionException =>
            Option(ex.getCause) match {
              case None =>
                logger.info(
                  s"The operation ${operationName.singleQuoted} failed with a non-retryable error (CompletionException without a cause), $fatalBehavior",
                  ex,
                )
                FatalErrorKind
              case Some(cause) =>
                determineExceptionErrorKind(cause, logger)
            }
          case ex: QuietNonRetryableException =>
            logger.info(
              s"The operation ${operationName.singleQuoted} failed with a non retryable error, $fatalBehavior",
              ex,
            )
            FatalErrorKind
          case ex =>
            logger.warn(s"$operationName failed with an unknown exception, $fatalBehavior", ex)
            FatalErrorKind
        }
      }
      val errorKindLabel = errorKind match {
        case FatalErrorKind => "fatal"
        case TransientErrorKind(_) => "transient"
        case NoSuccessErrorKind => "no_success"
        case UnknownErrorKind => "unknown"
      }
      implicit val mc = MetricsContext(
        "error_kind" -> errorKindLabel
      ).merge(MetricsContext(additionalMetricsLabels))
      retryCounter.inc()
      errorKind
    }

  }

  sealed abstract class Retryable[-A] {
    private[RetryProvider] def apply(
        operationName: String,
        a: A,
        metricsFactory: LabeledMetricsFactory,
        additionalMetricsLabels: Map[String, String],
        flagCloseable: FlagCloseable,
    ): ExceptionRetryPolicy
  }

  object Retryable {
    implicit val function: Retryable[String => ExceptionRetryPolicy] =
      new Retryable[String => ExceptionRetryPolicy] {
        override def apply(
            operationName: String,
            a: String => ExceptionRetryPolicy,
            metricsFactory: LabeledMetricsFactory,
            additionalMetricsLabels: Map[String, String],
            flagCloseable: FlagCloseable,
        ) = a(operationName)
      }

    implicit val seqErrorCodes: Retryable[Seq[Status.Code]] = new Retryable[Seq[Status.Code]] {
      override def apply(
          operationName: String,
          additionalCodes: Seq[Status.Code],
          metricsFactory: LabeledMetricsFactory,
          additionalMetricsLabels: Map[String, String],
          flagCloseable: FlagCloseable,
      ): RetryableError = RetryProvider.RetryableError(
        operationName,
        additionalCodes,
        Map.empty,
        transientDescription,
        nonTransientDescription,
        fatalBehavior,
        metricsFactory,
        additionalMetricsLabels,
        flagCloseable,
      )
    }

    implicit val mapCodeConditions: Retryable[RetryableConditions] =
      new Retryable[RetryableConditions] {
        override def apply(
            operationName: String,
            additionalConditions: RetryableConditions,
            metricsFactory: LabeledMetricsFactory,
            additionalMetricsLabels: Map[String, String],
            flagCloseable: FlagCloseable,
        ): RetryableError = RetryProvider.RetryableError(
          operationName,
          Seq.empty,
          additionalConditions,
          transientDescription,
          nonTransientDescription,
          fatalBehavior,
          metricsFactory,
          additionalMetricsLabels,
          flagCloseable,
        )
      }
  }

  /** A mixin that always derives `#timeouts` from `#retryProvider`, thus
    * obviating the former parameter.  If you get a scalac error "self-type...
    * Has does not conform...", then you can simply remove this mixin, as it is
    * no longer useful at that place.
    */
  private[splice] trait Has { this: FlagCloseable =>
    protected[this] def retryProvider: RetryProvider
    override protected final def timeouts: ProcessingTimeout = retryProvider.timeouts
  }

  /** Errors that must not be retried by the retry provider
    * These errors can be used to represent errors that can be retried by the client after altering the request
    *  These errors will not log a warning message on failure
    */
  class QuietNonRetryableException(msg: String) extends RuntimeException(msg)

  private val transientDescription = "retryable"
  private val nonTransientDescription = "non-retryable"
  private val fatalBehavior = "not retrying"
}
