// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.networking.grpc

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.common.annotations.VisibleForTesting
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener
import io.grpc.*

import java.util.concurrent.atomic.AtomicBoolean

/** Server side interceptor that logs incoming and outgoing traffic.
  *
  * @param config Configuration to tailor the output
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ApiRequestLogger(
    override protected val loggerFactory: NamedLoggerFactory,
    config: ApiLoggingConfig,
) extends ApiRequestLoggerBase(loggerFactory, config)
    with ServerInterceptor
    with NamedLogging {

  @VisibleForTesting
  private[networking] val cancelled: AtomicBoolean = new AtomicBoolean(false)

  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] = {
    val requestTraceContext: TraceContext = inferRequestTraceContext

    val sender = Option(call.getAttributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString)
      .getOrElse("unknown sender")
    val method = call.getMethodDescriptor.getFullMethodName

    def createLogMessage(message: String): String =
      show"Request ${method.readableLoggerName(config.maxMethodLength)} by ${sender.unquoted}: ${message.unquoted}"

    logger.trace(createLogMessage(s"received headers ${stringOfMetadata(headers)}"))(
      requestTraceContext
    )

    val loggingServerCall = new LoggingServerCall(call, createLogMessage, requestTraceContext)
    val serverCallListener = next.startCall(loggingServerCall, headers)
    new LoggingServerCallListener(serverCallListener, createLogMessage, requestTraceContext)
  }

  /** Intercepts events sent by the client.
    */
  class LoggingServerCallListener[ReqT, RespT](
      delegate: ServerCall.Listener[ReqT],
      createLogMessage: String => String,
      requestTraceContext: TraceContext,
  ) extends SimpleForwardingServerCallListener[ReqT](delegate) {

    /** Called when the server receives the request. */
    override def onMessage(message: ReqT): Unit = {
      val traceContext = traceContextOfMessage(message).getOrElse(requestTraceContext)
      logger.debug(
        createLogMessage(
          show"received a message ${cutMessage(message).unquoted}\n  Request ${requestTraceContext.showTraceId}"
        )
      )(traceContext)
      logThrowable(delegate.onMessage(message))(createLogMessage, traceContext)
    }

    /** Called when the client completed all message sending (except for cancellation). */
    override def onHalfClose(): Unit = {
      logger.trace(createLogMessage(s"finished receiving messages"))(requestTraceContext)
      logThrowable(delegate.onHalfClose())(createLogMessage, requestTraceContext)
    }

    /** Called when the client cancels the call. */
    override def onCancel(): Unit = {
      logger.info(createLogMessage("cancelled"))(requestTraceContext)
      logThrowable(delegate.onCancel())(createLogMessage, requestTraceContext)
      cancelled.set(true)
    }

    /** Called when the server considers the call completed. */
    override def onComplete(): Unit = {
      logger.trace(createLogMessage("completed"))(requestTraceContext)
      logThrowable(delegate.onComplete())(createLogMessage, requestTraceContext)
    }

    override def onReady(): Unit = {
      // This call is "just a suggestion" according to the docs and turns out to be quite flaky, even in simple scenarios.
      // Not logging therefore.
      logThrowable(delegate.onReady())(createLogMessage, requestTraceContext)
    }
  }

  /** Intercepts events sent by the server.
    */
  class LoggingServerCall[ReqT, RespT](
      delegate: ServerCall[ReqT, RespT],
      createLogMessage: String => String,
      requestTraceContext: TraceContext,
  ) extends SimpleForwardingServerCall[ReqT, RespT](delegate) {

    /** Called when the server sends the response headers. */
    override def sendHeaders(headers: Metadata): Unit = {
      logger.trace(createLogMessage(s"sending response headers ${cutMessage(headers)}"))(
        requestTraceContext
      )
      delegate.sendHeaders(headers)
    }

    /** Called when the server sends a response. */
    override def sendMessage(message: RespT): Unit = {
      val traceContext = traceContextOfMessage(message).getOrElse(requestTraceContext)
      logger.debug(
        createLogMessage(
          s"sending response ${cutMessage(message)}\n  Request ${requestTraceContext.showTraceId}"
        )
      )(traceContext)
      delegate.sendMessage(message)
    }

    /** Called when the server closes the call. */
    override def close(status: Status, trailers: Metadata): Unit = {
      val enhancedStatus = logStatusOnClose(status, trailers, createLogMessage)(requestTraceContext)
      delegate.close(enhancedStatus, trailers)
    }
  }
}
