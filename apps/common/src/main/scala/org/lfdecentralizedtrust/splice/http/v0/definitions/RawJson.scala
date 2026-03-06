// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http.v0.definitions

import io.circe.{Decoder, Encoder}

/** A wrapper for pre-serialized JSON strings.
  * Used for contract payloads, choice arguments, and exercise results
  * to avoid unnecessary String -> io.circe.Json -> String roundtrips.
  */
final case class RawJson(value: String)

object RawJson {

  /** Encoder that wraps the raw JSON string as a circe JSON string value.
    * No parsing occurs — the string is emitted as-is.
    */
  implicit val encoder: Encoder[RawJson] =
    Encoder.instance[RawJson](rawJson => io.circe.Json.fromString(rawJson.value))

  /** Decoder that captures any JSON value as a raw string. */
  implicit val decoder: Decoder[RawJson] =
    Decoder.instance[RawJson] { cursor =>
      Right(RawJson(cursor.value.noSpaces))
    }
}
