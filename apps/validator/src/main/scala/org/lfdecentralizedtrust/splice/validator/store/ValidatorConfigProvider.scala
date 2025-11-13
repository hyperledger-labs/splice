// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Codec
import scala.concurrent.Future
import io.circe.generic.semiauto.*

final case class ScanUrlInternalConfig(
    svName: String,
    url: String,
)

object ScanUrlInternalConfig {
  implicit val scanUrlCodec: Codec[ScanUrlInternalConfig] = deriveCodec[ScanUrlInternalConfig]
}

class ValidatorConfigProvider(config: ValidatorInternalStore) {

  private val scanInternalConfigKey = "validator_scan_internal_config_key"

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
