// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api.client

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.{ApiLoggingConfig, FullClientConfig}
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc, W3CTraceContext}
import io.grpc.*
import io.grpc.health.v1.{HealthCheckRequest, HealthGrpc}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import org.scalatest.wordspec.AnyWordSpec

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class ApiClientRequestLoggerTest extends AnyWordSpec with BaseTest {

  "ApiClientRequestLogger" should {
    "sets a new trace context per request" in {
      val receivedTraceIds = new ConcurrentLinkedQueue[Option[String]]()
      val server = NettyServerBuilder
        .forAddress(new InetSocketAddress("127.0.0.1", 0))
        .addService(
          ServerInterceptors.intercept(
            new HealthStatusManager().getHealthService,
            new ServerInterceptor {
              override def interceptCall[ReqT, RespT](
                  call: ServerCall[ReqT, RespT],
                  headers: Metadata,
                  next: ServerCallHandler[ReqT, RespT],
              ): ServerCall.Listener[ReqT] = {
                receivedTraceIds.add(W3CTraceContext.fromGrpcMetadata(headers).traceId)
                next.startCall(call, headers)
              }
            },
          )
        )
        .build()
        .start()
      // Like AppConnection, use the channel builder that adds a TraceContextGrpc.clientInterceptor at the channel level
      val channel = ClientChannelBuilder
        .createChannelBuilderToTrustedServer(
          FullClientConfig(port = Port.tryCreate(server.getPort))
        )(directExecutionContext.execute(_))
        .build()
      try {
        val stub = HealthGrpc
          .newBlockingStub(channel)
          .withInterceptors(new ApiClientRequestLogger(loggerFactory, ApiLoggingConfig()))
        val callerTraceContext = TraceContext.createNew("caller")
        (1 to 2).foreach { _ =>
          TraceContextGrpc.withGrpcContext(callerTraceContext)(
            stub.check(HealthCheckRequest.getDefaultInstance)
          )
        }
        val traceIds = receivedTraceIds.asScala.toSeq
        traceIds should have size 2
        forAll(traceIds) { traceId =>
          traceId shouldBe defined
          traceId should not be callerTraceContext.traceId
        }
        traceIds.distinct should have size 2
      } finally {
        channel.shutdownNow().discard
        server.shutdownNow().discard
      }
    }
  }
}
