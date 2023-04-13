// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.api.client

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ApiRequestLoggerBase
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.*
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener

import java.util.concurrent.atomic.AtomicBoolean

/** Client side interceptor that logs incoming and outgoing traffic.
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
      "com.digitalasset.canton.research.participant.multidomain.UpdateService/GetLedgerEnd",
      "com.digitalasset.canton.research.participant.multidomain.StateSnapshotService/GetConnectedDomains",
    )

  override def interceptCall[ReqT, RespT](
      method: MethodDescriptor[ReqT, RespT],
      callOptions: CallOptions,
      next: Channel,
  ): ClientCall[ReqT, RespT] = {
    val requestTraceContext: TraceContext = inferRequestTraceContext

    val receiver = next.authority()
    val methodName = method.getFullMethodName

    val clientCall = next.newCall(method, callOptions)

    if (requestsToIgnore.contains(methodName)) {
      clientCall
    } else {
      def createLogMessage(message: String): String =
        show"Request ${methodName.readableLoggerName(config.maxMethodLength)} to ${receiver.unquoted}: ${message.unquoted}"

      new LoggingClientCall(clientCall, createLogMessage, requestTraceContext)
    }
  }

  /** Intercepts events sent by the server.
    */
  class LoggingClientCallListener[ReqT, RespT](
      delegate: ClientCall.Listener[RespT],
      createLogMessage: String => String,
      requestTraceContext: TraceContext,
  ) extends SimpleForwardingClientCallListener[RespT](delegate) {

    /** Called when the client receives a response (may be called multiple times for server streaming calls). */
    override def onMessage(message: RespT): Unit = {
      val traceContext = traceContextOfMessage(message).getOrElse(requestTraceContext)
      logger.debug(
        createLogMessage(
          show"received a message ${cutMessage(message).unquoted}\n  Request ${requestTraceContext.showTraceId}"
        )
      )(traceContext)
      logThrowable(delegate.onMessage(message))(createLogMessage, traceContext)
    }

    /** Called when the client receives response headers. */
    override def onHeaders(headers: Metadata): Unit = {
      logger.trace(createLogMessage(s"received response headers ${cutMessage(headers)}"))(
        requestTraceContext
      )
      logThrowable(delegate.onHeaders(headers))(createLogMessage, requestTraceContext)
    }

    /** Called when the call has been closed (no further processing will be done for this call). */
    override def onClose(status: Status, trailers: Metadata): Unit = {
      val enhancedStatus = logStatusOnClose(status, trailers, createLogMessage)(requestTraceContext)
      delegate.onClose(enhancedStatus, trailers)
    }
  }

  /** Intercepts requests sent by the client. */
  class LoggingClientCall[ReqT, RespT](
      delegate: ClientCall[ReqT, RespT],
      createLogMessage: String => String,
      requestTraceContext: TraceContext,
  ) extends ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](delegate) {

    /** Called when the client starts the call */
    override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
      logger.trace(
        createLogMessage(
          s"sending request headers ${cutMessage(headers)}\n  Request ${requestTraceContext.showTraceId}"
        )
      )(
        requestTraceContext
      )
      val loggingListener =
        new LoggingClientCallListener(responseListener, createLogMessage, requestTraceContext)
      delegate.start(loggingListener, headers)
    }

    /** Called when the client sends a message (may be called multiple times for client streaming calls) */
    override def sendMessage(message: ReqT): Unit = {
      val traceContext = traceContextOfMessage(message).getOrElse(requestTraceContext)
      logger.debug(
        createLogMessage(
          s"sending request ${cutMessage(message)}\n  Request ${requestTraceContext.showTraceId}"
        )
      )(traceContext)
      delegate.sendMessage(message)
    }

    /** Called when no more messages will be sent by the client. */
    override def halfClose(): Unit = {
      logger.trace(
        createLogMessage(
          s"half closing\n  Request ${requestTraceContext.showTraceId}"
        )
      )(requestTraceContext)
      delegate.halfClose()
    }

    /** Prevents any further processing of this call, and informs the server of the cancellation. */
    override def cancel(message: String, cause: Throwable): Unit = {
      logger.trace(
        createLogMessage(
          s"cancelling request: $message. Caused by $cause.\n  Request ${requestTraceContext.showTraceId}"
        )
      )(requestTraceContext)
      delegate.cancel(message, cause)
      cancelled.set(true)
    }

    override def request(numMessages: Int): Unit = {
      logger.trace(
        createLogMessage(
          s"requesting $numMessages to be delivered.\n  Request ${requestTraceContext.showTraceId}"
        )
      )(requestTraceContext)
      delegate.request(numMessages)
    }
  }
}
