// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.ByteString


class S3ZstdWriter() {
  val flow: Flow[ByteString, String, NotUsed] = {
    Flow[ByteString]
      .groupedWeighted()
  }
}
