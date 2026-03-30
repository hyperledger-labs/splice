// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ApiRequestLoggerBase
import com.digitalasset.canton.tracing.{Spanning, TraceContext, TraceContextGrpc, W3CTraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.*
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.opentelemetry.api.trace.{Span, Tracer}

import java.util.concurrent.atomic.AtomicBoolean

/** Client side interceptor that logs incoming and outgoing traffic, and attaches appropriate trace-contexts to the
  * requests. It combines trace-context management with logging, as the logging serve to correlate the new trace-context
  * with the one of the method caller.
  *
  * @param config Configuration to tailor the output
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ApiClientRequestLogger(
    override protected val loggerFactory: NamedLoggerFactory,
    config: ApiLoggingConfig,
    tracer: Tracer,
) extends ApiRequestLoggerBase(loggerFactory, config)
    with ClientInterceptor
    with NamedLogging
    with Spanning {

  private val cancelled: AtomicBoolean = new AtomicBoolean(false)

  private val requestsToIgnore: Set[String] =
    Set(
      "com.daml.ledger.api.v2.StateService/GetLedgerEnd",
      "com.daml.ledger.api.v2.StateService/GetConnectedDomains",
    )

  override def interceptCall[ReqT, RespT](
      method: MethodDescriptor[ReqT, RespT],
      callOptions: CallOptions,
      next: Channel,
  ): ClientCall[ReqT, RespT] = {

    val methodName = method.getFullMethodName
    val shortMethod = show"${methodName.readableLoggerName(config.maxMethodLength)}"

    val (span, traceContext) = TraceContextGrpc
      .inferCallerTraceContext(callOptions)
      .fold(
        TraceContext.withNewTraceContext(shortMethod)(traceContext =>
          startSpan(shortMethod)(traceContext, tracer)
        )
      )(existingTrace => startSpan(shortMethod)(existingTrace, tracer))

    val receiver = next.authority()

    val clientCall = next.newCall(method, callOptions)

    if (requestsToIgnore.contains(methodName)) {
      new SimpleForwardingClientCall[ReqT, RespT](clientCall) {
        override def start(
            responseListener: ClientCall.Listener[RespT],
            headers: Metadata,
        ): Unit = {
          // We use the caller context here, as there's no log message to communicate the binding of the new trace-id
          // to the one used by the caller.
          W3CTraceContext.injectIntoGrpcMetadata(
            traceContext,
            headers,
          )

          super.start(
            new SpanClosingClientCallListener[RespT](responseListener, span),
            headers,
          )
        }

      }
    } else {
      val tidInfo = traceContext.showTraceId

      def createLogMessage(message: String): String = {
        show"Request ($tidInfo) $shortMethod to ${receiver.unquoted}: ${message.unquoted}"
      }

      new LoggingClientCall(
        clientCall,
        createLogMessage,
        traceContext,
        span,
      )
    }
  }

  class SpanClosingClientCallListener[RespT](
      delegate: ClientCall.Listener[RespT],
      span: Span,
  ) extends SimpleForwardingClientCallListener[RespT](delegate) {

    override def onClose(status: Status, trailers: Metadata): Unit = {
      super.onClose(status, trailers)
      span.end()
    }
  }

  class LoggingClientCallListener[ReqT, RespT](
      delegate: ClientCall.Listener[RespT],
      createLogMessage: String => String,
      callerTraceContext: TraceContext,
  ) extends SimpleForwardingClientCallListener[RespT](delegate) {

    /** Called when the client receives a response (may be called multiple times for server streaming calls). */
    override def onMessage(message: RespT): Unit = {
      logger.debug(
        createLogMessage(
          show"received a message ${cutMessage(message).unquoted}"
        )
      )(callerTraceContext)
      logThrowable(delegate.onMessage(message))(createLogMessage, callerTraceContext)
    }

    /** Called when the client receives response headers. */
    override def onHeaders(headers: Metadata): Unit = {
      logger.trace(createLogMessage(s"received response headers ${cutMessage(headers)}"))(
        callerTraceContext
      )
      logThrowable(delegate.onHeaders(headers))(createLogMessage, callerTraceContext)
    }

    /** Called when the call has been closed (no further processing will be done for this call). */
    override def onClose(status: Status, trailers: Metadata): Unit = {
      val enhancedStatus = logStatusOnClose(status, trailers, createLogMessage)(callerTraceContext)
      delegate.onClose(enhancedStatus, trailers)
    }
  }

  /** Intercepts requests sent by the client. */
  class LoggingClientCall[ReqT, RespT](
      delegate: ClientCall[ReqT, RespT],
      createLogMessage: String => String,
      traceContext: TraceContext,
      span: Span,
  ) extends ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](delegate) {

    /** Called when the client starts the call */
    override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
      logger.trace(
        createLogMessage(
          s"sending request headers ${cutMessage(headers)}"
        )
      )(
        traceContext
      )
      val loggingListener =
        new SpanClosingClientCallListener(
          new LoggingClientCallListener(
            responseListener,
            createLogMessage,
            callerTraceContext = traceContext,
          ),
          span,
        )

      W3CTraceContext.injectIntoGrpcMetadata(traceContext, headers)

      delegate.start(loggingListener, headers)
    }

    /** Called when the client sends a message (may be called multiple times for client streaming calls) */
    override def sendMessage(message: ReqT): Unit = {
      logger.debug(
        createLogMessage(
          s"sending request ${cutMessage(message)}"
        )
      )(traceContext)
      delegate.sendMessage(message)
    }

    /** Called when no more messages will be sent by the client. */
    override def halfClose(): Unit = {
      logger.trace(
        createLogMessage(
          s"half closing"
        )
      )(traceContext)
      delegate.halfClose()
    }

    /** Prevents any further processing of this call, and informs the server of the cancellation. */
    override def cancel(message: String, cause: Throwable): Unit = {
      logger.trace(
        createLogMessage(
          s"cancelling request: $message. Caused by $cause."
        )
      )(traceContext)
      delegate.cancel(message, cause)
      cancelled.set(true)
    }

    override def request(numMessages: Int): Unit = {
      logger.trace(
        createLogMessage(
          s"requesting $numMessages to be delivered."
        )
      )(traceContext)
      delegate.request(numMessages)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Product"))
  private def cutMessage(message: Any): String =
    if (config.messagePayloads) {
      config.printer.printAdHoc(message)
    } else ""

  protected def logThrowable(
      within: => Unit
  )(createLogMessage: String => String, traceContext: TraceContext): Unit = {
    try {
      within
    } catch {
      // If the server implementation fails, the server method must return a failed future or call StreamObserver.onError.
      // This handler is invoked, when an internal GRPC error occurs or the server implementation throws.
      case t: Throwable =>
        logger.error(createLogMessage("failed with an unexpected throwable"), t)(traceContext)
        t match {
          case _: RuntimeException =>
            throw t
          case _: Exception =>
            // Convert to a RuntimeException, because GRPC is unable to handle undeclared checked exceptions.
            throw new RuntimeException(t)
          case _: Throwable =>
            throw t
        }
    }
  }

  protected def logStatusOnClose(
      status: Status,
      trailers: Metadata,
      createLogMessage: String => String,
  )(implicit requestTraceContext: TraceContext): Status = {
    val enhancedStatus = enhance(status)

    val statusString = Option(enhancedStatus.getDescription).filterNot(_.isEmpty) match {
      case Some(d) => s"${enhancedStatus.getCode}/$d"
      case None => enhancedStatus.getCode.toString
    }

    val trailersString = stringOfTrailers(trailers)

    if (enhancedStatus.getCode == Status.OK.getCode) {
      logger.debug(
        createLogMessage(s"succeeded($statusString)$trailersString"),
        enhancedStatus.getCause,
      )
    } else {
      val message = createLogMessage(s"failed with $statusString$trailersString")
      if (
        enhancedStatus.getCode == Status.Code.UNKNOWN || enhancedStatus.getCode == Status.Code.DATA_LOSS
      ) {
        logger.error(message, enhancedStatus.getCause)
      } else if (enhancedStatus.getCode == Status.Code.INTERNAL) {
        logger.error(message, enhancedStatus.getCause)
      } else if (enhancedStatus.getCode == Status.Code.UNAUTHENTICATED) {
        logger.debug(message, enhancedStatus.getCause)
      } else {
        logger.info(message, enhancedStatus.getCause)
      }
    }

    enhancedStatus
  }
}
