// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import com.google.protobuf.timestamp.Timestamp as ProtoTimestamp

object CantonTimestampUtil {

  /** Parse a proto timestamp, throwing on failure.
    * Analogous to UniqueIdentifier.tryFromProtoPrimitive.
    */
  def tryFromProtoTimestamp(ts: ProtoTimestamp): CantonTimestamp =
    CantonTimestamp
      .fromProtoTimestamp(ts)
      .fold(
        err => throw new IllegalArgumentException(s"Invalid proto timestamp: ${err.message}"),
        identity,
      )
}
