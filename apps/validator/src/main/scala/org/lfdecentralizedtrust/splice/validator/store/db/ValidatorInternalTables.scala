// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import io.circe.*
import scala.util.control.NonFatal

object ValidatorInternalTables {

  case class ValidatorInternalConfig(
      key: String,
      values: Map[String, String],
  ) {
    def toJson: io.circe.Json = {
      Json.obj(
        "key" -> Json.fromString(key),
        "values" -> Json.obj(values.map { case (k, v) => k -> Json.fromString(v) }.toSeq*),
      )
    }
  }

  object ValidatorInternalConfig {

    implicit val decoder: Decoder[ValidatorInternalConfig] = (c: HCursor) =>
      for {
        key <- c.downField("key").as[String]
        values <- c.downField("values").as[Map[String, String]]
      } yield {
        ValidatorInternalConfig(key, values)
      }

    def fromJson(payload: Json): Either[Throwable, ValidatorInternalConfig] = {
      try {
        payload
          .as[ValidatorInternalConfig](decoder)
          .left
          .map(df => new Exception(s"Json decoding error: ${df.message}"))
      } catch {
        case NonFatal(e) => Left(e)
      }
    }
  }
}
