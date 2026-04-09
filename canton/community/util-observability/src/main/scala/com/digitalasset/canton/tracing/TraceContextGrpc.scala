// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.tracing

import io.grpc.*
import io.grpc.Context as GrpcContext
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.stub.AbstractStub

import scala.util.{Try, Using}

/** Support for propagating TraceContext values across GRPC boundaries. Includes:
  *   - a client interceptor for setting context values when sending requests to a server
  *   - a server interceptor for receiving context values when receiving requests from a client
  */
object TraceContextGrpc {
  // value of trace context in the GRPC Context
  // There are two options for implicitly propagating the trace context within a process: thread-local storage and
  // attaching custom call options to a GRPC call. Thread-local storage does *not* work with Futures, so we recommend
  // using the latter approach where possible. The former is used sometimes for historical purposes, and sometimes
  // because for technical reasons.
  private val TraceContextThreadLocalKey =
    Context.keyWithDefault[TraceContext]("TraceContextThreadLocalKey", TraceContext.empty)

  val TraceContextCallOptionKey =
    CallOptions.Key.create[TraceContext]("TraceContextCallOptionKey")

  def fromGrpcContext: TraceContext = TraceContextThreadLocalKey.get()

  def fromGrpcContextOrNew(name: String): TraceContext = {
    val grpcTraceContext = TraceContextGrpc.fromGrpcContext
    if (grpcTraceContext.traceId.isDefined) {
      grpcTraceContext
    } else {
      TraceContext.withNewTraceContext(name)(identity)
    }
  }

  def withGrpcTraceContext[A](f: TraceContext => A): A = f(fromGrpcContext)

  def withGrpcContext[A](traceContext: TraceContext)(fn: => A): A = {
    val context = GrpcContext.current().withValue(TraceContextThreadLocalKey, traceContext)

    context.call(() => fn)
  }

  def addTraceContextToCallOptions[T <: AbstractStub[T]](
      stub: T
  )(implicit traceContext: TraceContext): T = {
    stub.withOption(TraceContextCallOptionKey, traceContext)
  }

  def inferServerRequestTraceContext(span: String): TraceContext = {
    val grpcTraceContext = TraceContextGrpc.fromGrpcContext
    if (grpcTraceContext.traceId.isDefined) {
      grpcTraceContext
    } else {
      TraceContext.withNewTraceContext(span)(identity)
    }
  }

  def inferCallerTraceContext(callOptions: CallOptions): Option[TraceContext] = {
    val callOptionTraceContext = callOptions.getOption(TraceContextGrpc.TraceContextCallOptionKey)
    if (callOptionTraceContext == null) {
      // TODO(#9754): remove the need to infer the trace context from thread-local storage, which doesn't work with Futures in the mix, and log a big fat warning if we do
      val grpcTraceContext = TraceContextGrpc.fromGrpcContext
      Option.when(grpcTraceContext.traceId.isDefined)(grpcTraceContext)
    } else {
      Some(callOptionTraceContext)
    }
  }

  private implicit final class TryFailedOps[A](private val a: Try[A]) extends AnyVal {
    @inline
    def valueOrThrow: A = a.fold(throw _, identity)
  }

  /** Injects headers TraceContext from into headers
    * @param wrappedInterceptor
    *   an optional interceptor to wrap with traceContext.context.makeCurrent(), to allow
    *   interoperability with GrpcTelemetry or other context-propagating interceptors
    */
  def clientInterceptor(wrappedInterceptor: Option[ClientInterceptor] = None): ClientInterceptor =
    new TraceContextClientInterceptor(wrappedInterceptor)
  def serverInterceptor: ServerInterceptor = new TraceContextServerInterceptor

  private class TraceContextClientInterceptor(wrappedInterceptor: Option[ClientInterceptor])
      extends ClientInterceptor {
    override def interceptCall[ReqT, RespT](
        method: MethodDescriptor[ReqT, RespT],
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall[ReqT, RespT] = {
      val tcOpts = Option(callOptions.getOption(TraceContextCallOptionKey))
      val traceContext = tcOpts.getOrElse(TraceContextThreadLocalKey.get())
      val contextToPropagate = traceContext.context

      def withPropagatedContext[T](fn: => T): T =
        wrappedInterceptor match {
          case Some(_) =>
            Using(contextToPropagate.makeCurrent()) { _ =>
              fn
            }.valueOrThrow
          case None => fn
        }

      val nextCall = withPropagatedContext {
        wrappedInterceptor.fold(next.newCall(method, callOptions))(
          _.interceptCall(method, callOptions, next)
        )
      }

      new SimpleForwardingClientCall[ReqT, RespT](nextCall) {

        override def start(
            responseListener: ClientCall.Listener[RespT],
            headers: Metadata,
        ): Unit = {

          W3CTraceContext.injectIntoGrpcMetadata(traceContext, headers)

          super.start(responseListener, headers)
        }
      }
    }
  }

  private class TraceContextServerInterceptor extends ServerInterceptor {
    override def interceptCall[ReqT, RespT](
        call: ServerCall[ReqT, RespT],
        headers: Metadata,
        next: ServerCallHandler[ReqT, RespT],
    ): ServerCall.Listener[ReqT] = {
      val traceContext = W3CTraceContext.fromGrpcMetadata(headers)
      val context = GrpcContext
        .current()
        .withValue(TraceContextThreadLocalKey, traceContext)
      Contexts.interceptCall(context, call, headers, next)
    }
  }
}
