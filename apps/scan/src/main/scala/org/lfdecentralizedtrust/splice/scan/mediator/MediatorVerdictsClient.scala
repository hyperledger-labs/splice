// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.mediator

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.discard.Implicits.*
import com.digitalasset.canton.networking.grpc.{ClientChannelBuilder, ForwardingStreamObserver}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.admin.api.client.commands.MediatorInspectionCommands
import io.grpc.Context
import io.grpc.stub.StreamObserver

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executor

final class MediatorVerdictsClient(
    mediatorAdminClientConfig: com.digitalasset.canton.config.FullClientConfig,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AutoCloseable
    with NamedLogging {

  private implicit val executor: Executor = (command: Runnable) => ec.execute(command)

  private val channel = com.digitalasset.canton.lifecycle.LifeCycle.toCloseableChannel(
    ClientChannelBuilder.createChannelBuilderToTrustedServer(mediatorAdminClientConfig).build(),
    logger,
    "MediatorVerdictsClient channel",
  )

  override def close(): Unit = com.digitalasset.canton.lifecycle.LifeCycle.close(channel)(logger)

  def streamVerdicts(
      resumeFromTs: Option[CantonTimestamp]
  )(implicit tc: TraceContext): Source[v30.Verdict, NotUsed] = {

    val srcWithFutureMat = Source.fromMaterializer { (mat, _) =>
      implicit val ec: ExecutionContext = mat.executionContext

      val (queue, src) = org.apache.pekko.stream.scaladsl.Source
        .queue[v30.Verdict](bufferSize = 128)
        .preMaterialize()(mat)

      val verdictObserver = new StreamObserver[v30.Verdict] {
        override def onNext(value: v30.Verdict): Unit = {
          queue.offer(value).discard[org.apache.pekko.stream.QueueOfferResult]
        }
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

      val svc = mediatorVerdicts.createService(channel.channel)
      val ctx = Context.current().withCancellation()
      mediatorVerdicts.createRequestInternal() match {
        case Right(req) => ctx.run(() => mediatorVerdicts.doRequest(svc, req, rawObserver))
        case Left(err) => queue.fail(new IllegalArgumentException(err))
      }

      src.watchTermination() { (_, done) =>
        done.onComplete(_ => ctx.cancel(io.grpc.Status.CANCELLED.asException()))(ec)
        NotUsed
      }
    }
    srcWithFutureMat.mapMaterializedValue(_ => NotUsed)
  }
}
