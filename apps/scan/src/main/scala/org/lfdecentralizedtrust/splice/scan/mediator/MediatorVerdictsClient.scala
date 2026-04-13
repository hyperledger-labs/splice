// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.mediator

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.{ClientChannelBuilder, GrpcManagedChannel}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.HasRunOnClosing
import org.lfdecentralizedtrust.splice.admin.api.client.{
  GrpcClientMetrics,
  GrpcMetricsClientInterceptor,
}
import com.digitalasset.canton.tracing.TraceContextGrpc
import com.daml.grpc.adapter.client.pekko.ClientAdapter
import com.daml.grpc.adapter.ExecutionSequencerFactory

import scala.concurrent.ExecutionContext

final class MediatorVerdictsClient(
    mediatorAdminClientConfig: com.digitalasset.canton.config.FullClientConfig,
    hasRunOnClosing: HasRunOnClosing,
    grpcClientMetrics: GrpcClientMetrics,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, esf: ExecutionSequencerFactory)
    extends AutoCloseable
    with NamedLogging {

  override def close(): Unit = managedChannel.close()

  private val managedChannel: GrpcManagedChannel = GrpcManagedChannel(
    "mediator-verdicts-client",
    ClientChannelBuilder
      .createChannelBuilderToTrustedServer(mediatorAdminClientConfig)(ec.execute(_))
      .build(),
    hasRunOnClosing,
    logger,
  )

  def streamVerdicts(
      resumeFromTs: Option[CantonTimestamp]
  )(implicit tc: TraceContext): Source[v30.Verdict, NotUsed] = {
    val req = v30.VerdictsRequest(
      mostRecentlyReceivedRecordTime = resumeFromTs.map(_.toProtoTimestamp)
    )

    val stub = TraceContextGrpc.addTraceContextToCallOptions(
      v30.MediatorInspectionServiceGrpc
        .stub(managedChannel.channel)
        .withInterceptors(
          TraceContextGrpc.clientInterceptor(None),
          new GrpcMetricsClientInterceptor(grpcClientMetrics),
        )
    )

    ClientAdapter
      .serverStreaming(
        req,
        stub.verdicts,
      )
      .mapConcat(_.verdict)
      .mapMaterializedValue(_ => NotUsed)
  }
}
