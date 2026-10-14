// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig

/** Uploads every chunk of a bulk storage dump once per [[ScanStorageConfig.Encoding]].
  *
  * Each upstream chunk is broadcast to one branch per encoding, where it's encoded, uploaded via
  * the given [[uploadFlow]] (an [[S3ZstdObjects]] flow), and counted in the metrics. The emitted
  * object keys of all branches are merged into a single downstream output.
  *
  * Note that the `Merge` is not eager, so the resulting flow only completes once every branch has
  * uploaded all of its objects. Callers can therefore rely on total completion before advancing a
  * progress marker.
  */
object MultiEncodingBulkStorageFlow {
  private lazy val encodings = ScanStorageConfig.Encoding.all.toList
  private lazy val numEncodings = encodings.length

  def apply[A](
      encode: (A, ScanStorageConfig.Encoding) => String,
      uploadFlow: ScanStorageConfig.Encoding => Flow[String, String, ?],
      incObjects: ScanStorageConfig.Encoding => Unit,
  ): Flow[A, String, NotUsed] = {
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val broadcast = b.add(Broadcast[A](numEncodings))
      val merge = b.add(Merge[String](numEncodings))

      encodings.zipWithIndex.foreach { case (encoding, i) =>
        val branch = Flow[A]
          .map(encode(_, encoding))
          .via(uploadFlow(encoding))
          .wireTap(_ => incObjects(encoding))

        broadcast.out(i) ~> branch ~> merge.in(i)
      }

      FlowShape(broadcast.in, merge.out)
    })
  }
}
