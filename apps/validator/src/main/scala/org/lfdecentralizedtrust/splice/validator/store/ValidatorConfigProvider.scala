// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Decoder, Encoder}
import scala.concurrent.Future

final case class ScanUrlInternalConfig(
    svName: String,
    url: String,
)

class ValidatorConfigProvider(config: ValidatorInternalStore) {

  private val scanInternalConfigKey = "validator_scan_internal_config_key"

  implicit val scanUrlEncoder: Encoder[ScanUrlInternalConfig] =
    Encoder.forProduct2("svName", "url")(s => (s.svName, s.url))
  implicit val scanUrlDecoder: Decoder[ScanUrlInternalConfig] =
    Decoder.forProduct2("svName", "url")(ScanUrlInternalConfig.apply)

  final def setScanUrlInternalConfig(
      value: Seq[ScanUrlInternalConfig]
  )(implicit tc: TraceContext): Future[Unit] = {
    config.setConfig[Seq[ScanUrlInternalConfig]](scanInternalConfigKey, value)
  }

  final def getScanUrlInternalConfig(
  )(implicit
      tc: TraceContext
  ): OptionT[Future, Seq[ScanUrlInternalConfig]] = {
    config.getConfig[Seq[ScanUrlInternalConfig]](scanInternalConfigKey)
  }
}
