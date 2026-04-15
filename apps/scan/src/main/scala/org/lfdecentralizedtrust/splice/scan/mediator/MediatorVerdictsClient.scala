// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.mediator

import com.daml.grpc.adapter.client.pekko.ClientAdapter
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.HasRunOnClosing
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.networking.grpc.{ClientChannelBuilder, GrpcManagedChannel}
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.admin.api.client.{
  GrpcClientMetrics,
  GrpcMetricsClientInterceptor,
}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

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
  )(implicit
      tc: TraceContext
  ): Source[v30.Verdict, Future[Option[v30.VerdictsResponse.Complete]]] = {
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

    val completePromise = Promise[Option[v30.VerdictsResponse.Complete]]()

    ClientAdapter
      .serverStreaming(
        req,
        stub.verdicts,
      )
      .takeWhile(
        response => {
          response.payload match {
            case v30.VerdictsResponse.Payload.Complete(complete) =>
              logger.info(s"Received Complete message from mediator verdicts stream: $complete")
              completePromise.trySuccess(Some(complete)).discard
              false
            case _ => true
          }
        },
        inclusive = false,
      )
      .collect { case v30.VerdictsResponse(v30.VerdictsResponse.Payload.Verdict(verdict)) =>
        verdict
      }
      .watchTermination() { (_, done) =>
        done.onComplete {
          case Success(_) =>
            if (completePromise.trySuccess(None)) {
              logger.info(
                "Mediator verdicts stream completed without a Complete message"
              )
            }
          case Failure(ex) =>
            logger.info(
              s"Mediator verdicts stream terminated with an error",
              ex,
            )
            completePromise.tryFailure(ex)
        }
        completePromise.future
      }
  }
}
