// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client

import com.daml.metrics.api.MetricHandle.Timer.TimerHandle
import com.daml.metrics.api.MetricHandle.{Histogram, Meter, Timer}
import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.MetricsContext.{withExtraMetricLabels, withMetricLabels}
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcMetricsClientInterceptor.{
  MetricsGrpcClientType,
  MetricsGrpcMethodName,
  MetricsGrpcResponseCode,
  MetricsGrpcServerType,
  MetricsGrpcServiceName,
  MetricsRequestTypeStreaming,
  MetricsRequestTypeUnary,
}
import com.google.protobuf.{GeneratedMessage as JavaGeneratedMessage}
import io.grpc.{
  CallOptions,
  Channel,
  ClientCall,
  Metadata,
  MethodDescriptor,
  ClientInterceptor,
  Status,
}
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import scalapb.{GeneratedMessage as ScalapbGeneratedMessage}

/** Adjusted from com.daml.metrics.grpc.GrpcMetricsServerInterceptor
  * to measure client instead of server requests.
  */
class GrpcMetricsClientInterceptor(metrics: GrpcClientMetrics) extends ClientInterceptor {

  override def interceptCall[ReqT, RespT](
      methodDescriptor: MethodDescriptor[ReqT, RespT],
      callOptions: CallOptions,
      channel: Channel,
  ): ClientCall[ReqT, RespT] = {
    val clientType =
      if (methodDescriptor.getType.clientSendsOneMessage()) MetricsRequestTypeUnary
      else MetricsRequestTypeStreaming
    val serverType =
      if (methodDescriptor.getType.serverSendsOneMessage()) MetricsRequestTypeUnary
      else MetricsRequestTypeStreaming
    withMetricLabels(
      MetricsGrpcServiceName -> methodDescriptor.getServiceName,
      MetricsGrpcMethodName -> methodDescriptor.getBareMethodName,
      MetricsGrpcClientType -> clientType,
      MetricsGrpcServerType -> serverType,
    ) { implicit mc =>
      val timerHandle = metrics.callTimer.startAsync()
      metrics.callsStarted.mark()
      val clientCall = channel.newCall(methodDescriptor, callOptions)
      new MetricsCall(
        clientCall,
        timerHandle,
        metrics.messagesSent,
        metrics.messagesSentSize,
        metrics.messagesReceived,
        metrics.messagesReceivedSize,
        metrics.callsCompleted,
      )
    }
  }

  private final class MonitoringClientCallListener[T](
      delegate: ClientCall.Listener[T],
      messagesReceived: Meter,
      messagesReceivedSize: Histogram,
      timer: TimerHandle,
      callsClosed: Meter,
  )(implicit
      metricsContext: MetricsContext
  ) extends SimpleForwardingClientCallListener[T](delegate) {

    override def onMessage(message: T): Unit = {
      delegate.onMessage(message)
      updateHistogramWithSerializedSize(messagesReceivedSize, message)
      messagesReceived.mark()
    }

    override def onClose(status: Status, trailers: Metadata): Unit = {
      delegate.onClose(status, trailers)
      withExtraMetricLabels(MetricsGrpcResponseCode -> status.getCode.toString) {
        implicit metricsContext =>
          callsClosed.mark()
          timer.stop()
      }
    }
  }

  private final class MetricsCall[ReqT, RespT](
      delegate: ClientCall[ReqT, RespT],
      timer: TimerHandle,
      messagesSent: Meter,
      messagesSentSize: Histogram,
      messagesReceived: Meter,
      messagesReceivedSize: Histogram,
      callsClosed: Meter,
  )(implicit metricsContext: MetricsContext)
      extends SimpleForwardingClientCall[ReqT, RespT](delegate) {

    override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
      val loggingListener =
        new MonitoringClientCallListener(
          responseListener,
          messagesReceived,
          messagesReceivedSize,
          timer,
          callsClosed,
        )

      delegate.start(loggingListener, headers)
    }

    override def sendMessage(message: ReqT): Unit = {
      super.sendMessage(message)
      updateHistogramWithSerializedSize(messagesSentSize, message)
      messagesSent.mark()
    }

    /*
     */
  }

  private def updateHistogramWithSerializedSize[T](
      histogram: Histogram,
      message: T,
  )(implicit metricsContext: MetricsContext): Unit = {
    message match {
      case generatedMessage: JavaGeneratedMessage =>
        histogram.update(generatedMessage.getSerializedSize)
      case generatedMessage: ScalapbGeneratedMessage =>
        histogram.update(generatedMessage.serializedSize)
      case _ =>
    }
  }
}
object GrpcMetricsClientInterceptor {

  val MetricsGrpcServiceName = "grpc_service_name"
  val MetricsGrpcMethodName = "grpc_method_name"
  val MetricsGrpcClientType = "grpc_client_type"
  val MetricsGrpcServerType = "grpc_server_type"
  val MetricsRequestTypeUnary = "unary"
  val MetricsRequestTypeStreaming = "streaming"
  val MetricsGrpcResponseCode = "grpc_code"

}

trait GrpcClientMetrics {
  val callTimer: Timer
  val messagesSent: Meter
  val messagesSentSize: Histogram
  val messagesReceived: Meter
  val messagesReceivedSize: Histogram
  val callsStarted: Meter
  val callsCompleted: Meter
}
