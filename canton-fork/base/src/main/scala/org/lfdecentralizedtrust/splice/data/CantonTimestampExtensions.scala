// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.data

import com.digitalasset.canton.data.CantonTimestamp
import com.google.protobuf.timestamp.Timestamp as ProtoTimestamp

import java.time.Instant

object CantonTimestampExtensions {
  implicit class `CantonTimestamp extensions`(private val self: CantonTimestamp.type)
      extends AnyVal {
    def tryFromProtoTimestamp(ts: ProtoTimestamp): CantonTimestamp =
      self
        .fromProtoTimestamp(ts)
        .fold(
          err => throw new IllegalArgumentException(err.message),
          identity,
        )

    def tryFromInstant(time: Instant): CantonTimestamp =
      self
        .fromInstant(time)
        .fold(
          err =>
            throw new IllegalArgumentException(
              s"cannot parse the instant $time for the topology snapshot: $err"
            ),
          identity,
        )
  }
}
