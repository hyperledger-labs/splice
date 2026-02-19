// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.sequencer

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.{ClientChannelBuilder, GrpcManagedChannel}
import com.digitalasset.canton.sequencer.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.HasRunOnClosing
import org.lfdecentralizedtrust.splice.admin.api.client.{
  GrpcClientMetrics,
  GrpcMetricsClientInterceptor,
}
import com.digitalasset.canton.tracing.TraceContextGrpc

import scala.concurrent.{ExecutionContext, Future}

final class SequencerTrafficClient(
    sequencerAdminClientConfig: com.digitalasset.canton.config.FullClientConfig,
    hasRunOnClosing: HasRunOnClosing,
    grpcClientMetrics: GrpcClientMetrics,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AutoCloseable
    with NamedLogging {

  override def close(): Unit = managedChannel.close()

  private val managedChannel: GrpcManagedChannel = GrpcManagedChannel(
    "sequencer-traffic-client",
    ClientChannelBuilder
      .createChannelBuilderToTrustedServer(sequencerAdminClientConfig)(ec.execute(_))
      .build(),
    hasRunOnClosing,
    logger,
  )

  /** Fetch traffic summaries for the given sequencing times.
    *
    * @param sequencingTimes The sequencing times to query
    * @return Traffic summaries for the requested times (may be fewer if some times have no data)
    */
  def getTrafficSummaries(
      sequencingTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Seq[v30.TrafficSummary]] = {
    val req = v30.GetTrafficSummariesRequest(
      sequencingTimestamps = sequencingTimes.map(_.toProtoTimestamp)
    )

    val stub = TraceContextGrpc.addTraceContextToCallOptions(
      v30.SequencerTrafficInspectionServiceGrpc
        .stub(managedChannel.channel)
        .withInterceptors(
          TraceContextGrpc.clientInterceptor(None),
          new GrpcMetricsClientInterceptor(grpcClientMetrics),
        )
    )

    stub.getTrafficSummaries(req).map(_.summary)
  }
}
