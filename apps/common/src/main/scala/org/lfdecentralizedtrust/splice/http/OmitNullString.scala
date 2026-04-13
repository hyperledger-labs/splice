// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import io.circe.{Decoder, Encoder}

/** An opaque wrapper around [[String]] intended for OpenAPI fields that are
  * conditionally omitted from JSON responses when their value is absent.
  *
  * Using a dedicated type instead of a bare `String` makes it explicit
  * in both the OpenAPI spec and the generated Scala code which fields
  * are omitted when null.
  */
final case class OmitNullString(value: String) extends AnyVal

object OmitNullString {
  implicit val encoder: Encoder[OmitNullString] =
    Encoder.encodeString.contramap(_.value)

  implicit val decoder: Decoder[OmitNullString] =
    Decoder.decodeString.map(OmitNullString(_))
}
