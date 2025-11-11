// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import scala.concurrent.{ExecutionContext, Future}

trait ValidatorInternalStore {

  def logger: TracedLogger

  def setConfig(key: String, value: Json)(implicit tc: TraceContext): Future[Unit]

  def getConfig(key: String)(implicit tc: TraceContext): Future[Option[Json]]

  private val scanInternalConfigKey = "validator_scan_internal_config_key"

  implicit val scanUrlEncoder: Encoder[ScanUrlInternalConfig] =
    Encoder.forProduct2("svName", "url")(s => (s.svName, s.url))
  implicit val scanUrlDecoder: Decoder[ScanUrlInternalConfig] =
    Decoder.forProduct2("svName", "url")(ScanUrlInternalConfig.apply)

  def setScanUrlInternalConfig(
      value: Seq[ScanUrlInternalConfig]
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(s"saving scan internal config in the database $value")
    setConfig(scanInternalConfigKey, value.asJson)
  }

  def getScanUrlInternalConfig(
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[Seq[ScanUrlInternalConfig]]] = {

    getConfig(scanInternalConfigKey).map {
      case Some(json) =>
        if (json.isNull || json.asObject.exists(_.isEmpty)) {
          None
        } else {
          json.as[Seq[ScanUrlInternalConfig]].toOption
        }
      case None =>
        None
    }
  }
}

final case class ScanUrlInternalConfig(
    svName: String,
    url: String,
)
