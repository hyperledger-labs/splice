// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.mediator

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.QueueOfferResult
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.discard.Implicits.*
import com.digitalasset.canton.networking.grpc.{
  ClientChannelBuilder,
  ForwardingStreamObserver,
  GrpcManagedChannel,
}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.admin.api.client.commands.MediatorInspectionCommands
import io.grpc.Context
import io.grpc.stub.StreamObserver
import com.digitalasset.canton.lifecycle.HasRunOnClosing

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executor

final class MediatorVerdictsClient(
    mediatorAdminClientConfig: com.digitalasset.canton.config.FullClientConfig,
    hasRunOnClosing: HasRunOnClosing,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AutoCloseable
    with NamedLogging {

  private implicit val executor: Executor = (command: Runnable) => ec.execute(command)

  private val managedChannel: GrpcManagedChannel = GrpcManagedChannel(
    "mediator-verdicts-client",
    ClientChannelBuilder.createChannelBuilderToTrustedServer(mediatorAdminClientConfig).build(),
    hasRunOnClosing,
    logger,
  )

  override def close(): Unit = managedChannel.close()

  def streamVerdicts(
      resumeFromTs: Option[CantonTimestamp]
  )(implicit tc: TraceContext): Source[v30.Verdict, NotUsed] = {
    Source
      .queue[v30.Verdict](bufferSize = 128)
      .mapMaterializedValue { queue =>
        val verdictObserver = new StreamObserver[v30.Verdict] {
          override def onNext(value: v30.Verdict): Unit =
            queue.offer(value).discard[QueueOfferResult]
          override def onError(t: Throwable): Unit = queue.fail(t)
          override def onCompleted(): Unit = queue.complete()
        }

        val mediatorVerdicts = MediatorInspectionCommands.MediatorVerdicts(
          mostRecentlyReceivedRecordTimeOfRequest = resumeFromTs,
          observer = verdictObserver,
        )

        val rawObserver = new ForwardingStreamObserver[v30.VerdictsResponse, v30.Verdict](
          verdictObserver,
          mediatorVerdicts.extractResults,
        )

        val svc = mediatorVerdicts.createService(managedChannel.channel)
        val ctx = Context.current().withCancellation()
        mediatorVerdicts.createRequestInternal() match {
          case Right(req) => ctx.run(() => mediatorVerdicts.doRequest(svc, req, rawObserver))
          case Left(err) => queue.fail(new IllegalArgumentException(err))
        }
        ctx
      }
      .watchTermination() { (ctx, done) =>
        done.onComplete(_ => ctx.cancel(io.grpc.Status.CANCELLED.asException()))(ec)
        NotUsed
      }
      .mapMaterializedValue(_ => NotUsed)
  }
}
