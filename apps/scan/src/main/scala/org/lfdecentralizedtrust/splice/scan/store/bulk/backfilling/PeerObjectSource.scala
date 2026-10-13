// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContext, Future}

trait PeerObjectSource {
  def open(peer: Uri, key: String)(implicit tc: TraceContext): Future[Source[ByteString, Any]]
}

class ScanPeerObjectSource(peerConnection: PeerBftScanConnection)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends PeerObjectSource {

  private def openConnections(implicit tc: TraceContext) =
    peerConnection.connection.map(_.scanList.scanConnections.open)

  override def open(peer: Uri, key: String)(implicit
      tc: TraceContext
  ): Future[Source[ByteString, Any]] =
    openConnections.flatMap { connections =>
      connections.find(_.config.adminApi.url == peer) match {
        case Some(connection) =>
          connection.runHttpCmd(peer, HttpScanAppClient.BulkStorageDownload(key))
        case None =>
          Future.failed(new IllegalStateException(s"Peer $peer is no longer connected"))
      }
    }
}
