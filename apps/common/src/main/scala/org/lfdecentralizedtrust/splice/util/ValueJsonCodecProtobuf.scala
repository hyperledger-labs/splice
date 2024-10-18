// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data as JavaApi
import com.google.protobuf.util.JsonFormat

/** A codec for converting [[com.daml.ledger.javaapi.data.Value]] to/from JSON strings.
  *
  * This codec is fully lossless:
  * - The encoded values contain type information
  * - The encoded values contain field labels (if present in the source value)
  * - Numerical values are serialized as strings
  *
  * The codec serializes values using the canonical JSON encoding of the com.daml.ledger.api.v2 protobuf format.
  * See https://protobuf.dev/programming-guides/proto3/#json for details on the JSON encoding.
  *
  * Note: This codec must be forever stable, as it's used to persist data in the UpdateHistory database.
  */
object ValueJsonCodecProtobuf {
  def serializeValue(x: JavaApi.Value): String = {
    val proto = x.toProto
    JsonFormat.printer.print(proto)
  }

  def deserializeValue(x: String): JavaApi.Value = {
    val builder = com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder()
    JsonFormat.parser().merge(x, builder)
    JavaApi.Value.fromProto(builder.build())
  }
}
