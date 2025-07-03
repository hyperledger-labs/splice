// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ApiRequestLoggerBase
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc, W3CTraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.*
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall

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
) extends ApiRequestLoggerBase(loggerFactory, config)
    with ClientInterceptor
    with NamedLogging {

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

    val optCallerContext = TraceContextGrpc.inferCallerTraceContext(callOptions)
    val requestTraceContext = TraceContext.withNewTraceContext(shortMethod)(identity)
    val callerTraceContext =
      optCallerContext.filter(_.traceId.isDefined).getOrElse(requestTraceContext)

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
          W3CTraceContext.injectIntoGrpcMetadata(callerTraceContext, headers)

          super.start(responseListener, headers)
        }
      }
    } else {
      val tidInfo =
        // TODO(#969): consider flushing out empty and missing trace contexts, as they typically indicate missed opportunities to simplify debugging
        if (optCallerContext.isEmpty) "no caller tid".unquoted
        else if (callerTraceContext == requestTraceContext) "empty caller tid".unquoted
        else requestTraceContext.showTraceId

      def createLogMessage(message: String): String = {
        show"Request ($tidInfo) $shortMethod to ${receiver.unquoted}: ${message.unquoted}"
      }

      new LoggingClientCall(
        clientCall,
        createLogMessage,
        callerTraceContext = callerTraceContext,
        requestTraceContext = requestTraceContext,
      )
    }
  }

  /** Intercepts events sent by the server.
    */
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
      callerTraceContext: TraceContext,
      requestTraceContext: TraceContext,
  ) extends ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](delegate) {

    /** Called when the client starts the call */
    override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
      logger.trace(
        createLogMessage(
          s"sending request headers ${cutMessage(headers)}"
        )
      )(
        callerTraceContext
      )
      val loggingListener =
        new LoggingClientCallListener(
          responseListener,
          createLogMessage,
          callerTraceContext = callerTraceContext,
        )

      W3CTraceContext.injectIntoGrpcMetadata(requestTraceContext, headers)

      delegate.start(loggingListener, headers)
    }

    /** Called when the client sends a message (may be called multiple times for client streaming calls) */
    override def sendMessage(message: ReqT): Unit = {
      logger.debug(
        createLogMessage(
          s"sending request ${cutMessage(message)}"
        )
      )(callerTraceContext)
      delegate.sendMessage(message)
    }

    /** Called when no more messages will be sent by the client. */
    override def halfClose(): Unit = {
      logger.trace(
        createLogMessage(
          s"half closing"
        )
      )(callerTraceContext)
      delegate.halfClose()
    }

    /** Prevents any further processing of this call, and informs the server of the cancellation. */
    override def cancel(message: String, cause: Throwable): Unit = {
      logger.trace(
        createLogMessage(
          s"cancelling request: $message. Caused by $cause."
        )
      )(callerTraceContext)
      delegate.cancel(message, cause)
      cancelled.set(true)
    }

    override def request(numMessages: Int): Unit = {
      logger.trace(
        createLogMessage(
          s"requesting $numMessages to be delivered."
        )
      )(callerTraceContext)
      delegate.request(numMessages)
    }
  }
}
